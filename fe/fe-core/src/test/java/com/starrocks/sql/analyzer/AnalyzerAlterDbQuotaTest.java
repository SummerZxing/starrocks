// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

package com.starrocks.sql.analyzer;

import com.starrocks.sql.ast.AlterDatabaseQuotaStmt;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import static com.starrocks.sql.analyzer.AnalyzeTestUtil.analyzeSuccess;

public class AnalyzerAlterDbQuotaTest {

    @BeforeClass
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        AnalyzeTestUtil.init();
    }

    private void testAlterDatabaseDataQuotaStmt(String dbName, String quotaQuantity, long quotaSize) {
        String sql = "ALTER DATABASE " + dbName + " SET DATA QUOTA " + quotaQuantity;
        AlterDatabaseQuotaStmt stmt = (AlterDatabaseQuotaStmt)analyzeSuccess(sql);
        Assert.assertEquals(quotaSize, stmt.getQuota());
    }

    @Test
    public void testNormalAlterDatabaseDataQuotaStmt() {
        // byte
        testAlterDatabaseDataQuotaStmt("testDb", "102400b", 102400L);

        // kb
        testAlterDatabaseDataQuotaStmt("testDb", "100kb", 100L * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100Kb", 100L * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100KB", 100L * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100K", 100L * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100k", 100L * 1024);

        // mb
        testAlterDatabaseDataQuotaStmt("testDb", "100mb", 100L * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100Mb", 100L * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100MB", 100L * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100M", 100L * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100m", 100L * 1024 * 1024);

        // gb
        testAlterDatabaseDataQuotaStmt("testDb", "100gb", 100L * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100Gb", 100L * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100GB", 100L * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100G", 100L * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100g", 100L * 1024 * 1024 * 1024);

        // tb
        testAlterDatabaseDataQuotaStmt("testDb", "100tb", 100L * 1024 * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100Tb", 100L * 1024 * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100TB", 100L * 1024 * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100T", 100L * 1024 * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100t", 100L * 1024 * 1024 * 1024 * 1024);

        // tb
        testAlterDatabaseDataQuotaStmt("testDb", "100pb", 100L * 1024 * 1024 * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100Pb", 100L * 1024 * 1024 * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100PB", 100L * 1024 * 1024 * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100P", 100L * 1024 * 1024 * 1024 * 1024 * 1024);
        testAlterDatabaseDataQuotaStmt("testDb", "100p", 100L * 1024 * 1024 * 1024 * 1024 * 1024);
    }

}
