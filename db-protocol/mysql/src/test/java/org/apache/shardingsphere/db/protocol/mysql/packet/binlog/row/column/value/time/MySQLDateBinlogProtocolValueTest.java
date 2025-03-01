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

import io.netty.buffer.ByteBuf;
import org.apache.shardingsphere.db.protocol.mysql.packet.binlog.row.column.MySQLBinlogColumnDef;
import org.apache.shardingsphere.db.protocol.mysql.payload.MySQLPacketPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.time.LocalDate;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public final class MySQLDateBinlogProtocolValueTest {
    
    @Mock
    private MySQLPacketPayload payload;
    
    @Mock
    private ByteBuf byteBuf;
    
    @Mock
    private MySQLBinlogColumnDef columnDef;
    
    @Test
    public void assertRead() {
        when(payload.getByteBuf()).thenReturn(byteBuf);
        when(byteBuf.readUnsignedMediumLE()).thenReturn(1901 * 16 * 32 + 32 + 1);
        Date expected = Date.valueOf(LocalDate.of(1901, 1, 1));
        assertThat(new MySQLDateBinlogProtocolValue().read(columnDef, payload), is(expected));
    }
    
    @Test
    public void assertReadNullDate() {
        when(payload.getByteBuf()).thenReturn(byteBuf);
        when(byteBuf.readUnsignedMediumLE()).thenReturn(0);
        assertThat(new MySQLDateBinlogProtocolValue().read(columnDef, payload), is(MySQLTimeValueUtils.ZERO_OF_DATE));
    }
}
