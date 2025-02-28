package com.starrocks.sql.plan;

import com.starrocks.common.DdlException;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class HiveTPCHPlanTest extends HivePlanTestBase {
    @BeforeClass
    public static void beforeClass() throws Exception {
        HivePlanTestBase.beforeClass();
        UtFrameUtils.addMockBackend(10002);
        UtFrameUtils.addMockBackend(10003);
    }

    @AfterClass
    public static void afterClass() {
        try {
            UtFrameUtils.dropMockBackend(10002);
            UtFrameUtils.dropMockBackend(10003);
        } catch (DdlException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testTPCH1() {
        runFileUnitTest("external/hive/tpch/q1");
    }

    @Test
    public void testTPCH2() {
        runFileUnitTest("external/hive/tpch/q2");
    }

    @Test
    public void testTPCH3() {
        runFileUnitTest("external/hive/tpch/q3");
    }

    @Test
    public void testTPCH4() {
        runFileUnitTest("external/hive/tpch/q4");
    }

    @Test
    public void testTPCH5() {
        runFileUnitTest("external/hive/tpch/q5");
    }

    @Test
    public void testTPCH6() {
        runFileUnitTest("external/hive/tpch/q6");
    }

    @Test
    public void testTPCH7() {
        runFileUnitTest("external/hive/tpch/q7");
    }

    @Test
    public void testTPCH8() {
        runFileUnitTest("external/hive/tpch/q8");
    }

    @Test
    public void testTPCH9() {
        runFileUnitTest("external/hive/tpch/q9");
    }

    @Test
    public void testTPCH10() {
        runFileUnitTest("external/hive/tpch/q10");
    }

    @Test
    public void testTPCH11() {
        runFileUnitTest("external/hive/tpch/q11");
    }

    @Test
    public void testTPCH12() {
        runFileUnitTest("external/hive/tpch/q12");
    }

    @Test
    public void testTPCH13() {
        runFileUnitTest("external/hive/tpch/q13");
    }

    @Test
    public void testTPCH14() {
        runFileUnitTest("external/hive/tpch/q14");
    }

    @Test
    public void testTPCH15() {
        runFileUnitTest("external/hive/tpch/q15");
    }

    @Test
    public void testTPCH16() {
        runFileUnitTest("external/hive/tpch/q16");
    }

    @Test
    public void testTPCH17() {
        runFileUnitTest("external/hive/tpch/q17");
    }

    @Test
    public void testTPCH18() {
        runFileUnitTest("external/hive/tpch/q18");
    }

    @Test
    public void testTPCH19() {
        runFileUnitTest("external/hive/tpch/q19");
    }

    @Test
    public void testTPCH20() {
        runFileUnitTest("external/hive/tpch/q20");
    }

    @Test
    public void testTPCH21() {
        runFileUnitTest("external/hive/tpch/q21");
    }

    @Test
    public void testTPCH22() {
        runFileUnitTest("external/hive/tpch/q22");
    }
}
