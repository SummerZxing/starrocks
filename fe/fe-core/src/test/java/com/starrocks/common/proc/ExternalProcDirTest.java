// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

package com.starrocks.common.proc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.FeConstants;
import com.starrocks.server.MetadataMgr;
import mockit.Mock;
import mockit.MockUp;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ExternalProcDirTest {

    @Before
    public void setUp() throws Exception {
        new MockUp<MetadataMgr>() {
            @Mock
            public List<String> listDbNames(String catalogName) throws DdlException {
                ImmutableSet.Builder<String> dbNames = ImmutableSet.builder();
                dbNames.add("hive_test").add("temp_db").add("ods");
                return ImmutableList.copyOf(dbNames.build());
            }

            @Mock
            public Database getDb(String catalogName, String dbName) {
                return new Database();
            }

            @Mock
            public List<String> listTableNames(String catalogName, String dbName) throws DdlException {
                ImmutableSet.Builder<String> tableNames = ImmutableSet.builder();
                tableNames.add("test1");
                tableNames.add("sr");
                tableNames.add("starrocks");
                return ImmutableList.copyOf(tableNames.build());
            }
            @Mock
            public Table getTable(String catalogName, String dbName, String tblName) {
                return new HiveTable();
            }
        };

        new MockUp<HiveTable>() {
            public List<Column> getFullSchema() {
                List<Column> fullSchema = new ArrayList<>();
                Column columnId = new Column("id", Type.INT);
                Column columnName = new Column("name", Type.VARCHAR);
                fullSchema.add(columnId);
                fullSchema.add(columnName);
                return fullSchema;
            }
        };

    }

    @Test
    public void testExternalDbsFetchResultNormal() {
        ExternalDbsProcDir dir = new ExternalDbsProcDir("hive_catalog");
        ProcResult result = dir.fetchResult();
        Assert.assertNotNull(result);
        Assert.assertTrue(result instanceof BaseProcResult);

        Assert.assertEquals(Lists.newArrayList("DbName"),
                result.getColumnNames());
        List<List<String>> rows = Lists.newArrayList();
        rows.add(Arrays.asList("hive_test"));
        rows.add(Arrays.asList("ods"));
        rows.add(Arrays.asList("temp_db"));
        Assert.assertEquals(rows, result.getRows());
    }

    @Test
    public void testExternalTablesFetchResultNormal() throws AnalysisException {
        ExternalTablesProcDir dir = new ExternalTablesProcDir("hive_catalog", "temp_db");
        ProcResult result = dir.fetchResult();
        Assert.assertNotNull(result);
        Assert.assertTrue(result instanceof BaseProcResult);
        Assert.assertEquals(Lists.newArrayList("TableName"),
                result.getColumnNames());
        Assert.assertEquals(3, result.getRows().size());
        List<List<String>> rows = Lists.newArrayList();
        rows.add(Arrays.asList("sr"));
        rows.add(Arrays.asList("starrocks"));
        rows.add(Arrays.asList("test1"));
        Assert.assertEquals(rows, result.getRows());
    }

    @Test
    public void testExternalTableFetchResultNormal() throws AnalysisException {
        Table table = new HiveTable();
        ExternalTableProcDir dir = new ExternalTableProcDir(table);
        ProcResult result = dir.fetchResult();
        Assert.assertNotNull(result);
        Assert.assertTrue(result instanceof BaseProcResult);
        Assert.assertEquals(Lists.newArrayList("Nodes"), result.getColumnNames());
        Assert.assertEquals(2, result.getRows().size());
        List<List<String>> rows = Lists.newArrayList();
        rows.add(Arrays.asList("partitions"));
        rows.add(Arrays.asList("schema"));
        Assert.assertEquals(rows, result.getRows());
    }

    @Test
    public void testExternalScemaFetchResultNormal() throws AnalysisException {
        Table table = new HiveTable();
        List<Column> fullSchema = new ArrayList<>();
        Column columnId = new Column("id", Type.INT);
        Column columnName = new Column("name", Type.VARCHAR);
        fullSchema.add(columnId);
        fullSchema.add(columnName);
        table.setNewFullSchema(fullSchema);
        ExternalSchemaProcNode dir = new ExternalSchemaProcNode(table);
        ProcResult result = dir.fetchResult();
        Assert.assertNotNull(result);
        Assert.assertTrue(result instanceof BaseProcResult);
        Assert.assertEquals(Lists.newArrayList("Field", "Type", "Null", "Key", "Default", "Extra"), result.getColumnNames());
        Assert.assertEquals(2, result.getRows().size());
        List<List<String>> rows = Lists.newArrayList();
        rows.add(Arrays.asList("id", "INT", "No", "false", FeConstants.null_string, ""));
        rows.add(Arrays.asList("name", "VARCHAR", "No", "false", FeConstants.null_string, ""));
        Assert.assertEquals(rows, result.getRows());
    }
}
