/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm.sql.parser;

import org.apache.calcite.sql.SqlNode;
import org.apache.storm.sql.parser.impl.ParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestSqlParser {
    private static SqlNode parse(String sql) throws Exception {
        StormParser parser = new StormParser(sql);
        return parser.impl().parseSqlStmtEof();
    }

    @Test
    public void testCreateTable() throws Exception {
        String sql = "CREATE EXTERNAL TABLE foo (bar INT) LOCATION 'kafka:///foo'";
        parse(sql);
    }

    @Test
    public void testCreateTableWithPrimaryKey() throws Exception {
        String sql = "CREATE EXTERNAL TABLE foo (bar INT PRIMARY KEY ASC) LOCATION 'kafka:///foo'";
        parse(sql);
    }

    @Test
    public void testCreateTableWithoutLocation() throws Exception {
        String sql = "CREATE EXTERNAL TABLE foo (bar INT)";
        assertThrows(ParseException.class, () -> parse(sql));
    }

    @Test
    public void testCreateFunction() throws Exception {
        String sql = "CREATE FUNCTION foo AS 'org.apache.storm.sql.MyUDF'";
        parse(sql);
    }
}
