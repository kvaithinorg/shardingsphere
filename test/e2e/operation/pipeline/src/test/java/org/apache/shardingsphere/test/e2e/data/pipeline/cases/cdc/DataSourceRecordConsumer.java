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

package org.apache.shardingsphere.test.e2e.data.pipeline.cases.cdc;

import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.api.datasource.PipelineDataSourceWrapper;
import org.apache.shardingsphere.data.pipeline.api.metadata.model.PipelineColumnMetaData;
import org.apache.shardingsphere.data.pipeline.api.metadata.model.PipelineTableMetaData;
import org.apache.shardingsphere.data.pipeline.cdc.client.util.ProtobufAnyValueConverter;
import org.apache.shardingsphere.data.pipeline.cdc.protocol.response.DataRecordResult.Record;
import org.apache.shardingsphere.data.pipeline.cdc.protocol.response.DataRecordResult.Record.DataChangeType;
import org.apache.shardingsphere.data.pipeline.cdc.protocol.response.DataRecordResult.Record.MetaData;
import org.apache.shardingsphere.data.pipeline.cdc.protocol.response.TableColumn;
import org.apache.shardingsphere.data.pipeline.core.metadata.loader.StandardPipelineTableMetaDataLoader;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.test.e2e.data.pipeline.util.SQLBuilderUtil;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public final class DataSourceRecordConsumer implements Consumer<List<Record>> {
    
    private final DataSource dataSource;
    
    private final Map<String, PipelineTableMetaData> tableMetaDataMap;
    
    private final StandardPipelineTableMetaDataLoader loader;
    
    public DataSourceRecordConsumer(final DataSource dataSource, final DatabaseType databaseType) {
        this.dataSource = dataSource;
        tableMetaDataMap = new ConcurrentHashMap<>();
        loader = new StandardPipelineTableMetaDataLoader(new PipelineDataSourceWrapper(dataSource, databaseType));
    }
    
    @Override
    public void accept(final List<Record> records) {
        long insertCount = records.stream().filter(each -> DataChangeType.INSERT == each.getDataChangeType()).count();
        if (insertCount == records.size()) {
            batchInsertRecords(records);
            return;
        }
        for (Record record : records) {
            write(record);
        }
    }
    
    private void batchInsertRecords(final List<Record> records) {
        Record firstRecord = records.get(0);
        MetaData metaData = firstRecord.getMetaData();
        PipelineTableMetaData tableMetaData = loadTableMetaData(metaData.getSchema(), metaData.getTable());
        List<String> columnNames = firstRecord.getAfterList().stream().map(TableColumn::getName).collect(Collectors.toList());
        String tableName = buildTableNameWithSchema(metaData.getSchema(), metaData.getTable());
        String insertSQL = SQLBuilderUtil.buildInsertSQL(columnNames, tableName);
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement(insertSQL)) {
            for (Record each : records) {
                List<TableColumn> tableColumns = each.getAfterList();
                for (int i = 0; i < tableColumns.size(); i++) {
                    preparedStatement.setObject(i + 1, convertValueFromAny(tableMetaData, tableColumns.get(i)));
                }
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        } catch (final SQLException ex) {
            throw new RuntimeException(ex);
        }
    }
    
    private void write(final Record record) {
        String sql = buildSQL(record);
        MetaData metaData = record.getMetaData();
        PipelineTableMetaData tableMetaData = loadTableMetaData(metaData.getSchema(), metaData.getTable());
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            Map<String, TableColumn> afterMap = new LinkedHashMap<>(record.getBeforeList().size(), 1);
            record.getAfterList().forEach(each -> afterMap.put(each.getName(), each));
            switch (record.getDataChangeType()) {
                case INSERT:
                    for (int i = 0; i < record.getAfterCount(); i++) {
                        TableColumn tableColumn = record.getAfterList().get(i);
                        preparedStatement.setObject(i + 1, convertValueFromAny(tableMetaData, tableColumn));
                    }
                    break;
                case UPDATE:
                    for (int i = 0; i < record.getAfterCount(); i++) {
                        TableColumn tableColumn = record.getAfterList().get(i);
                        preparedStatement.setObject(i + 1, convertValueFromAny(tableMetaData, tableColumn));
                    }
                    preparedStatement.setObject(record.getAfterCount() + 1, convertValueFromAny(tableMetaData, afterMap.get("order_id")));
                    int updateCount = preparedStatement.executeUpdate();
                    if (1 != updateCount) {
                        log.warn("executeUpdate failed, updateCount={}, updateSql={}, updatedColumns={}", updateCount, sql, afterMap.keySet());
                    }
                    break;
                case DELETE:
                    Object orderId = convertValueFromAny(tableMetaData, afterMap.get("order_id"));
                    preparedStatement.setObject(1, orderId);
                    preparedStatement.execute();
                    break;
                default:
            }
            preparedStatement.execute();
        } catch (final SQLException ex) {
            throw new RuntimeException(ex);
        }
    }
    
    private PipelineTableMetaData loadTableMetaData(final String schemaName, final String tableName) {
        PipelineTableMetaData result = tableMetaDataMap.get(buildTableNameWithSchema(schemaName, tableName));
        if (null != result) {
            return result;
        }
        result = loader.getTableMetaData(Strings.emptyToNull(schemaName), tableName);
        tableMetaDataMap.put(buildTableNameWithSchema(schemaName, tableName), result);
        return result;
    }
    
    private String buildTableNameWithSchema(final String schema, final String tableName) {
        return schema.isEmpty() ? tableName : String.join(".", schema, tableName);
    }
    
    private String buildSQL(final Record record) {
        List<String> columnNames = record.getAfterList().stream().map(TableColumn::getName).collect(Collectors.toList());
        MetaData metaData = record.getMetaData();
        String tableName = buildTableNameWithSchema(metaData.getSchema(), metaData.getTable());
        switch (record.getDataChangeType()) {
            case INSERT:
                return SQLBuilderUtil.buildInsertSQL(columnNames, tableName);
            case UPDATE:
                return SQLBuilderUtil.buildUpdateSQL(columnNames, tableName, "?");
            case DELETE:
                return SQLBuilderUtil.buildDeleteSQL(tableName, "order_id");
            default:
                throw new UnsupportedOperationException();
        }
    }
    
    private Object convertValueFromAny(final PipelineTableMetaData tableMetaData, final TableColumn tableColumn) {
        PipelineColumnMetaData columnMetaData = tableMetaData.getColumnMetaData(tableColumn.getName());
        Object result;
        try {
            result = ProtobufAnyValueConverter.convertToObject(tableColumn.getValue());
        } catch (final InvalidProtocolBufferException ex) {
            log.error("invalid protocol message value: {}", tableColumn.getValue());
            throw new RuntimeException(ex);
        }
        if (null == result) {
            return null;
        }
        switch (columnMetaData.getDataType()) {
            case Types.TIME:
                if ("TIME".equalsIgnoreCase(columnMetaData.getDataTypeName())) {
                    // Time.valueOf() will lose nanos
                    return LocalTime.ofNanoOfDay((Long) result);
                }
                return result;
            case Types.DATE:
                if ("DATE".equalsIgnoreCase(columnMetaData.getDataTypeName())) {
                    LocalDate localDate = LocalDate.ofEpochDay((Long) result);
                    return Date.valueOf(localDate);
                }
                return result;
            default:
                return result;
        }
    }
}
