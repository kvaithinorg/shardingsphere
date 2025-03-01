/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.data.pipeline.cdc.core.connector;

import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.api.importer.ImporterType;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.DataRecord;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.FinishedRecord;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.Record;
import org.apache.shardingsphere.data.pipeline.cdc.constant.CDCSinkType;
import org.apache.shardingsphere.data.pipeline.cdc.core.ack.CDCAckHolder;
import org.apache.shardingsphere.data.pipeline.cdc.core.ack.CDCAckPosition;
import org.apache.shardingsphere.data.pipeline.cdc.core.importer.SocketSinkImporter;
import org.apache.shardingsphere.data.pipeline.cdc.generator.CDCResponseGenerator;
import org.apache.shardingsphere.data.pipeline.cdc.protocol.response.DataRecordResult;
import org.apache.shardingsphere.data.pipeline.cdc.util.CDCDataRecordUtil;
import org.apache.shardingsphere.data.pipeline.cdc.util.DataRecordResultConvertUtil;
import org.apache.shardingsphere.data.pipeline.core.record.RecordUtil;
import org.apache.shardingsphere.data.pipeline.spi.importer.connector.ImporterConnector;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Socket sink importer connector.
 */
@Slf4j
public final class SocketSinkImporterConnector implements ImporterConnector, AutoCloseable {
    
    private static final long DEFAULT_TIMEOUT_MILLISECONDS = 200L;
    
    private final Lock lock = new ReentrantLock();
    
    private final Condition condition = lock.newCondition();
    
    @Setter
    private volatile boolean incrementalTaskRunning = true;
    
    private final ShardingSphereDatabase database;
    
    private final Channel channel;
    
    private final int jobShardingCount;
    
    private final Comparator<DataRecord> dataRecordComparator;
    
    private final Map<String, String> tableNameSchemaMap = new HashMap<>();
    
    private final Map<SocketSinkImporter, BlockingQueue<Record>> incrementalRecordMap = new ConcurrentHashMap<>();
    
    private final AtomicInteger runningIncrementalTaskCount = new AtomicInteger(0);
    
    private Thread incrementalImporterTask;
    
    public SocketSinkImporterConnector(final Channel channel, final ShardingSphereDatabase database, final int jobShardingCount, final Collection<String> schemaTableNames,
                                       final Comparator<DataRecord> dataRecordComparator) {
        this.channel = channel;
        this.database = database;
        this.jobShardingCount = jobShardingCount;
        schemaTableNames.stream().filter(each -> each.contains(".")).forEach(each -> {
            String[] split = each.split("\\.");
            tableNameSchemaMap.put(split[1], split[0]);
        });
        this.dataRecordComparator = dataRecordComparator;
    }
    
    @Override
    public Object getConnector() {
        return channel;
    }
    
    /**
     * Write data record into channel.
     *
     * @param recordList data records
     * @param socketSinkImporter cdc importer
     * @param importerType importer type
     */
    public void write(final List<Record> recordList, final SocketSinkImporter socketSinkImporter, final ImporterType importerType) {
        if (recordList.isEmpty()) {
            return;
        }
        if (ImporterType.INVENTORY == importerType || null == dataRecordComparator) {
            Map<SocketSinkImporter, CDCAckPosition> importerDataRecordMap = new HashMap<>();
            int dataRecordCount = (int) recordList.stream().filter(each -> each instanceof DataRecord).count();
            Record lastRecord = recordList.get(recordList.size() - 1);
            if (lastRecord instanceof FinishedRecord && 0 == dataRecordCount) {
                socketSinkImporter.ackWithLastDataRecord(new CDCAckPosition(lastRecord, 0));
                return;
            }
            importerDataRecordMap.put(socketSinkImporter, new CDCAckPosition(RecordUtil.getLastNormalRecord(recordList), dataRecordCount));
            writeImmediately(recordList, importerDataRecordMap);
        } else if (ImporterType.INCREMENTAL == importerType) {
            writeIntoQueue(recordList, socketSinkImporter);
        }
    }
    
    private void writeImmediately(final List<? extends Record> recordList, final Map<SocketSinkImporter, CDCAckPosition> importerDataRecordMap) {
        while (!channel.isWritable() && channel.isActive()) {
            doAwait();
        }
        if (!channel.isActive()) {
            return;
        }
        List<DataRecordResult.Record> records = new LinkedList<>();
        for (Record each : recordList) {
            if (!(each instanceof DataRecord)) {
                continue;
            }
            DataRecord dataRecord = (DataRecord) each;
            records.add(DataRecordResultConvertUtil.convertDataRecordToRecord(database.getName(), tableNameSchemaMap.get(dataRecord.getTableName()), dataRecord));
        }
        String ackId = CDCAckHolder.getInstance().bindAckIdWithPosition(importerDataRecordMap);
        DataRecordResult dataRecordResult = DataRecordResult.newBuilder().addAllRecord(records).setAckId(ackId).build();
        channel.writeAndFlush(CDCResponseGenerator.succeedBuilder("").setDataRecordResult(dataRecordResult).build());
    }
    
    private void doAwait() {
        lock.lock();
        try {
            condition.await(DEFAULT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException ignored) {
        } finally {
            lock.unlock();
        }
    }
    
    @SneakyThrows(InterruptedException.class)
    private void writeIntoQueue(final List<Record> dataRecords, final SocketSinkImporter socketSinkImporter) {
        BlockingQueue<Record> blockingQueue = incrementalRecordMap.get(socketSinkImporter);
        if (null == blockingQueue) {
            log.warn("not find the queue to write");
            return;
        }
        for (Record each : dataRecords) {
            blockingQueue.put(each);
        }
    }
    
    /**
     * Send incremental start event.
     *
     * @param socketSinkImporter socket sink importer
     * @param batchSize batch size
     */
    public void sendIncrementalStartEvent(final SocketSinkImporter socketSinkImporter, final int batchSize) {
        incrementalRecordMap.computeIfAbsent(socketSinkImporter, ignored -> new ArrayBlockingQueue<>(batchSize));
        int count = runningIncrementalTaskCount.incrementAndGet();
        if (count < jobShardingCount || null == dataRecordComparator) {
            return;
        }
        log.debug("start CDC incremental importer");
        if (null == incrementalImporterTask) {
            incrementalImporterTask = new Thread(new CDCIncrementalImporterTask(batchSize));
            incrementalImporterTask.start();
        }
    }
    
    /**
     * Clean socket sink importer connector.
     *
     * @param socketSinkImporter CDC importer
     */
    public void clean(final SocketSinkImporter socketSinkImporter) {
        incrementalRecordMap.remove(socketSinkImporter);
        if (ImporterType.INCREMENTAL == socketSinkImporter.getImporterType()) {
            incrementalTaskRunning = false;
        }
    }
    
    @Override
    public String getType() {
        return CDCSinkType.SOCKET.name();
    }
    
    @Override
    public void close() throws Exception {
        channel.close();
    }
    
    @RequiredArgsConstructor
    private final class CDCIncrementalImporterTask implements Runnable {
        
        private final int batchSize;
        
        @SneakyThrows(InterruptedException.class)
        @Override
        public void run() {
            while (incrementalTaskRunning) {
                Map<SocketSinkImporter, CDCAckPosition> cdcAckPositionMap = new HashMap<>();
                List<DataRecord> dataRecords = new LinkedList<>();
                for (int i = 0; i < batchSize; i++) {
                    DataRecord minimumDataRecord = CDCDataRecordUtil.findMinimumDataRecordAndSavePosition(incrementalRecordMap, dataRecordComparator, cdcAckPositionMap);
                    if (null == minimumDataRecord) {
                        break;
                    }
                    dataRecords.add(minimumDataRecord);
                }
                if (dataRecords.isEmpty()) {
                    Thread.sleep(200L);
                } else {
                    writeImmediately(dataRecords, cdcAckPositionMap);
                }
            }
        }
    }
}
