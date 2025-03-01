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

package org.apache.shardingsphere.proxy.backend.hbase.checker;

import org.apache.shardingsphere.proxy.backend.hbase.result.HBaseSupportedSQLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class HeterogeneousUpdateStatementCheckerTest {
    
    @Test
    public void assertExecuteUpdateStatement() {
        SQLStatement sqlStatement = HBaseSupportedSQLStatement.parseSQLStatement(HBaseSupportedSQLStatement.getUpdateStatement());
        HBaseCheckerFactory.newInstance(sqlStatement).execute();
    }
    
    @Test
    public void assertUpdateWithFunction() {
        String sql = "update /*+ hbase */ t_test_order set age = 10, name = 'bob', time = now() where rowKey = 1";
        SQLStatement sqlStatement = HBaseSupportedSQLStatement.parseSQLStatement(sql);
        Exception ex = assertThrows(IllegalArgumentException.class, () -> HBaseCheckerFactory.newInstance(sqlStatement).execute());
        assertThat(ex.getMessage(), is("Assigment must is literal or parameter marker"));
    }
    
    @Test
    public void assertOperatorIsNotEqual() {
        String sql = "update /*+ hbase */ t_test_order set age = 10 where rowKey > 1";
        SQLStatement sqlStatement = HBaseSupportedSQLStatement.parseSQLStatement(sql);
        Exception ex = assertThrows(IllegalArgumentException.class, () -> HBaseCheckerFactory.newInstance(sqlStatement).execute());
        assertThat(ex.getMessage(), is("Only Supported `=` operator"));
    }
    
    @Test
    public void assertColumnIsNotRowKey() {
        String sql = "update /*+ hbase */ t_test_order set age = 10 where age = 1";
        SQLStatement sqlStatement = HBaseSupportedSQLStatement.parseSQLStatement(sql);
        Exception ex = assertThrows(IllegalArgumentException.class, () -> HBaseCheckerFactory.newInstance(sqlStatement).execute());
        assertThat(ex.getMessage(), is("age is not a allowed key"));
    }
    
    @Test
    public void assertLeftIsNotColumn() {
        String sql = "update /*+ hbase */ t_test_order set age = 10 where 1 = 1";
        SQLStatement sqlStatement = HBaseSupportedSQLStatement.parseSQLStatement(sql);
        Exception ex = assertThrows(IllegalArgumentException.class, () -> HBaseCheckerFactory.newInstance(sqlStatement).execute());
        assertThat(ex.getMessage(), is("left segment must is ColumnSegment"));
    }
    
    @Test
    public void assertMultiExpression() {
        String sql = "update /*+ hbase */ t_test_order set age = 10 WHERE order_id = ? AND user_id = ? AND status=?";
        SQLStatement sqlStatement = HBaseSupportedSQLStatement.parseSQLStatement(sql);
        Exception ex = assertThrows(IllegalArgumentException.class, () -> HBaseCheckerFactory.newInstance(sqlStatement).execute());
        assertThat(ex.getMessage(), is("Do not supported Multiple expressions"));
    }
    
    @Test
    public void assertNotWhereSegment() {
        String sql = "update /*+ hbase */ t_test_order set age = 10 ";
        SQLStatement sqlStatement = HBaseSupportedSQLStatement.parseSQLStatement(sql);
        Exception ex = assertThrows(IllegalArgumentException.class, () -> HBaseCheckerFactory.newInstance(sqlStatement).execute());
        assertThat(ex.getMessage(), is("Must Have Where Segment"));
    }
    
    @Test
    public void assertUpdateRowKey() {
        String sql = "update /*+ hbase */ t_test_order set rowKey = 10 where rowKey = 'kid'";
        SQLStatement sqlStatement = HBaseSupportedSQLStatement.parseSQLStatement(sql);
        Exception ex = assertThrows(IllegalArgumentException.class, () -> HBaseCheckerFactory.newInstance(sqlStatement).execute());
        assertThat(ex.getMessage(), is("Do not allow update rowKey"));
    }
}
