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

package org.apache.shardingsphere.dbdiscovery.rule;

import org.apache.shardingsphere.dbdiscovery.api.config.rule.DatabaseDiscoveryDataSourceRuleConfiguration;
import org.apache.shardingsphere.dbdiscovery.exception.MissingRequiredDataSourceNamesConfigurationException;
import org.apache.shardingsphere.dbdiscovery.mysql.type.MGRMySQLDatabaseDiscoveryProvider;
import org.apache.shardingsphere.infra.datasource.mapper.DataSourceRole;
import org.apache.shardingsphere.infra.datasource.mapper.DataSourceRoleInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class DatabaseDiscoveryDataSourceRuleTest {
    
    private final DatabaseDiscoveryDataSourceRule databaseDiscoveryDataSourceRule = new DatabaseDiscoveryDataSourceRule(
            new DatabaseDiscoveryDataSourceRuleConfiguration("test_pr", Arrays.asList("ds_0", "ds_1"), "ha_heartbeat", "discoveryTypeName"), new Properties(),
            new MGRMySQLDatabaseDiscoveryProvider());
    
    @Test
    public void assertNewHADataSourceRuleWithoutName() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseDiscoveryDataSourceRule(new DatabaseDiscoveryDataSourceRuleConfiguration("", Arrays.asList("ds_0", "ds_1"), "ha_heartbeat", "discoveryTypeName"),
                        new Properties(), new MGRMySQLDatabaseDiscoveryProvider()));
    }
    
    @Test
    public void assertNewHADataSourceRuleWithNullDataSourceName() {
        assertThrows(MissingRequiredDataSourceNamesConfigurationException.class,
                () -> new DatabaseDiscoveryDataSourceRule(new DatabaseDiscoveryDataSourceRuleConfiguration(
                        "ds", null, "ha_heartbeat", "discoveryTypeName"), new Properties(), new MGRMySQLDatabaseDiscoveryProvider()));
    }
    
    @Test
    public void assertNewHADataSourceRuleWithEmptyDataSourceName() {
        assertThrows(MissingRequiredDataSourceNamesConfigurationException.class,
                () -> new DatabaseDiscoveryDataSourceRule(new DatabaseDiscoveryDataSourceRuleConfiguration("ds", Collections.emptyList(), "ha_heartbeat", "discoveryTypeName"),
                        new Properties(), new MGRMySQLDatabaseDiscoveryProvider()));
    }
    
    @Test
    public void assertGetDataSourceNamesWithoutDisabledDataSourceNames() {
        assertThat(databaseDiscoveryDataSourceRule.getDataSourceNames(), is(Arrays.asList("ds_0", "ds_1")));
    }
    
    @Test
    public void assertGetDataSourceMapper() {
        databaseDiscoveryDataSourceRule.changePrimaryDataSourceName("ds_1");
        assertThat(databaseDiscoveryDataSourceRule.getDataSourceMapper(),
                is(Collections.singletonMap("test_pr",
                        new LinkedHashSet<>(Arrays.asList(new DataSourceRoleInfo("ds_1", DataSourceRole.PRIMARY), new DataSourceRoleInfo("ds_0", DataSourceRole.MEMBER))))));
    }
}
