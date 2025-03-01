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

package org.apache.shardingsphere.db.protocol.mysql.packet.binlog.row.column.value.time;

import org.apache.shardingsphere.db.protocol.mysql.packet.binlog.row.column.MySQLBinlogColumnDef;
import org.apache.shardingsphere.db.protocol.mysql.payload.MySQLPacketPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public final class MySQLDatetimeBinlogProtocolValueTest {
    
    @Mock
    private MySQLPacketPayload payload;
    
    @Mock
    private MySQLBinlogColumnDef columnDef;
    
    @Test
    public void assertRead() {
        when(payload.readInt8()).thenReturn(99991231235959L);
        LocalDateTime expected = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        assertThat(new MySQLDatetimeBinlogProtocolValue().read(columnDef, payload), is(Timestamp.valueOf(expected)));
    }
    
    @Test
    public void assertReadNullTime() {
        when(payload.readInt8()).thenReturn(0L);
        assertThat(new MySQLDatetimeBinlogProtocolValue().read(columnDef, payload), is(MySQLTimeValueUtils.DATETIME_OF_ZERO));
    }
}
