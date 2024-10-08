/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.hashtable;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.disk.iomanager.IOManagerAsync;
import org.apache.flink.runtime.memory.MemoryAllocationException;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.runtime.memory.MemoryManagerBuilder;
import org.apache.flink.runtime.operators.testutils.UnionIterator;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.runtime.typeutils.BinaryRowDataSerializer;
import org.apache.flink.table.runtime.util.ConstantsKeyValuePairsIterator;
import org.apache.flink.table.runtime.util.RowIterator;
import org.apache.flink.table.runtime.util.UniformBinaryRowGenerator;
import org.apache.flink.testutils.junit.extensions.parameterized.ParameterizedTestExtension;
import org.apache.flink.testutils.junit.extensions.parameterized.Parameters;
import org.apache.flink.util.MutableObjectIterator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.table.api.config.ExecutionConfigOptions.TABLE_EXEC_SPILL_COMPRESSION_BLOCK_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;

/** Test for {@link LongHashPartition}. */
@ExtendWith(ParameterizedTestExtension.class)
class LongHashTableTest {

    private static final int PAGE_SIZE = 32 * 1024;
    private IOManager ioManager;
    private BinaryRowDataSerializer buildSideSerializer;
    private BinaryRowDataSerializer probeSideSerializer;
    private MemoryManager memManager =
            MemoryManagerBuilder.newBuilder().setMemorySize(896 * PAGE_SIZE).build();

    private boolean useCompress;

    public LongHashTableTest(boolean useCompress) {
        this.useCompress = useCompress;
    }

    @Parameters(name = "useCompress-{0}")
    private static List<Boolean> getVarSeg() {
        return Arrays.asList(true, false);
    }

    @BeforeEach
    void init() {
        TypeInformation[] types = new TypeInformation[] {Types.INT, Types.INT};
        this.buildSideSerializer = new BinaryRowDataSerializer(types.length);
        this.probeSideSerializer = new BinaryRowDataSerializer(types.length);
        this.ioManager = new IOManagerAsync();
    }

    private class MyHashTable extends LongHybridHashTable {

        public MyHashTable(long memorySize) {
            super(
                    LongHashTableTest.this,
                    useCompress,
                    (int) TABLE_EXEC_SPILL_COMPRESSION_BLOCK_SIZE.defaultValue().getBytes(),
                    buildSideSerializer,
                    probeSideSerializer,
                    memManager,
                    memorySize,
                    LongHashTableTest.this.ioManager,
                    24,
                    200000);
        }

        @Override
        public long getBuildLongKey(RowData row) {
            return row.getInt(0);
        }

        @Override
        public long getProbeLongKey(RowData row) {
            return row.getInt(0);
        }

        @Override
        public BinaryRowData probeToBinary(RowData row) {
            return (BinaryRowData) row;
        }
    }

    @TestTemplate
    void testInMemory() throws IOException {
        final int numKeys = 100000;
        final int buildValsPerKey = 3;
        final int probeValsPerKey = 10;

        // create a build input that gives 3 million pairs with 3 values sharing the same key
        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys, buildValsPerKey, false);

        // create a probe input that gives 10 million pairs with 10 values sharing a key
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys, probeValsPerKey, true);

        final MyHashTable table = new MyHashTable(500 * PAGE_SIZE);

        int numRecordsInJoinResult = join(table, buildInput, probeInput);

        assertThat(numRecordsInJoinResult)
                .as("Wrong number of records in join result.")
                .isEqualTo(numKeys * buildValsPerKey * probeValsPerKey);

        table.close();

        table.free();
    }

    @TestTemplate
    void testSpillingHashJoinOneRecursion() throws IOException {
        final int numKeys = 100000;
        final int buildValsPerKey = 3;
        final int probeValsPerKey = 10;

        // create a build input that gives 3 million pairs with 3 values sharing the same key
        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys, buildValsPerKey, false);

        // create a probe input that gives 10 million pairs with 10 values sharing a key
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys, probeValsPerKey, true);

        final MyHashTable table = new MyHashTable(300 * PAGE_SIZE);

        int numRecordsInJoinResult = join(table, buildInput, probeInput);

        assertThat(numRecordsInJoinResult)
                .as("Wrong number of records in join result.")
                .isEqualTo(numKeys * buildValsPerKey * probeValsPerKey);

        table.close();

        // ----------------------------------------------------------------------------------------

        table.free();
    }

    /** Non partition in memory in level 0. */
    @TestTemplate
    void testSpillingHashJoinOneRecursionPerformance() throws IOException {
        final int numKeys = 1000000;
        final int buildValsPerKey = 3;
        final int probeValsPerKey = 10;

        // create a build input that gives 3 million pairs with 3 values sharing the same key
        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys, buildValsPerKey, false);

        // create a probe input that gives 10 million pairs with 10 values sharing a key
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys, probeValsPerKey, true);

        final MyHashTable table = new MyHashTable(100 * PAGE_SIZE);

        int numRecordsInJoinResult = join(table, buildInput, probeInput);

        assertThat(numRecordsInJoinResult)
                .as("Wrong number of records in join result.")
                .isEqualTo(numKeys * buildValsPerKey * probeValsPerKey);

        table.close();

        // ----------------------------------------------------------------------------------------

        table.free();
    }

    @TestTemplate
    void testSpillingHashJoinOneRecursionValidity() throws IOException {
        final int numKeys = 1000000;
        final int buildValsPerKey = 3;
        final int probeValsPerKey = 10;

        // create a build input that gives 3 million pairs with 3 values sharing the same key
        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys, buildValsPerKey, false);

        // create a probe input that gives 10 million pairs with 10 values sharing a key
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys, probeValsPerKey, true);

        // create the map for validating the results
        HashMap<Integer, Long> map = new HashMap<>(numKeys);

        // ----------------------------------------------------------------------------------------
        final MyHashTable table = new MyHashTable(100 * PAGE_SIZE);

        BinaryRowData buildRow = buildSideSerializer.createInstance();
        while ((buildRow = buildInput.next(buildRow)) != null) {
            table.putBuildRow(buildRow);
        }
        table.endBuild();

        BinaryRowData probeRow = probeSideSerializer.createInstance();
        while ((probeRow = probeInput.next(probeRow)) != null) {
            if (table.tryProbe(probeRow)) {
                testJoin(table, map);
            }
        }

        while (table.nextMatching()) {
            testJoin(table, map);
        }

        table.close();

        assertThat(map).as("Wrong number of keys").hasSize(numKeys);
        for (Map.Entry<Integer, Long> entry : map.entrySet()) {
            long val = entry.getValue();
            int key = entry.getKey();

            assertThat(val)
                    .as("Wrong number of values in per-key cross product for key " + key)
                    .isEqualTo(probeValsPerKey * buildValsPerKey);
        }

        // ----------------------------------------------------------------------------------------

        table.free();
    }

    @TestTemplate
    void testSpillingHashJoinWithMassiveCollisions() throws IOException {
        // the following two values are known to have a hash-code collision on the initial level.
        // we use them to make sure one partition grows over-proportionally large
        final int repeatedValue1 = 40559;
        final int repeatedValue2 = 92882;
        final int repeatedValueCountBuild = 200000;
        final int repeatedValueCountProbe = 5;

        final int numKeys = 1000000;
        final int buildValsPerKey = 3;
        final int probeValsPerKey = 10;

        // create a build input that gives 3 million pairs with 3 values sharing the same key, plus
        // 400k pairs with two colliding keys
        MutableObjectIterator<BinaryRowData> build1 =
                new UniformBinaryRowGenerator(numKeys, buildValsPerKey, false);
        MutableObjectIterator<BinaryRowData> build2 =
                new ConstantsKeyValuePairsIterator(repeatedValue1, 17, repeatedValueCountBuild);
        MutableObjectIterator<BinaryRowData> build3 =
                new ConstantsKeyValuePairsIterator(repeatedValue2, 23, repeatedValueCountBuild);
        List<MutableObjectIterator<BinaryRowData>> builds = new ArrayList<>();
        builds.add(build1);
        builds.add(build2);
        builds.add(build3);
        MutableObjectIterator<BinaryRowData> buildInput = new UnionIterator<>(builds);

        // create a probe input that gives 10 million pairs with 10 values sharing a key
        MutableObjectIterator<BinaryRowData> probe1 =
                new UniformBinaryRowGenerator(numKeys, probeValsPerKey, true);
        MutableObjectIterator<BinaryRowData> probe2 =
                new ConstantsKeyValuePairsIterator(repeatedValue1, 17, 5);
        MutableObjectIterator<BinaryRowData> probe3 =
                new ConstantsKeyValuePairsIterator(repeatedValue2, 23, 5);
        List<MutableObjectIterator<BinaryRowData>> probes = new ArrayList<>();
        probes.add(probe1);
        probes.add(probe2);
        probes.add(probe3);
        MutableObjectIterator<BinaryRowData> probeInput = new UnionIterator<>(probes);

        // create the map for validating the results
        HashMap<Integer, Long> map = new HashMap<>(numKeys);

        final MyHashTable table = new MyHashTable(896 * PAGE_SIZE);

        BinaryRowData buildRow = buildSideSerializer.createInstance();
        while ((buildRow = buildInput.next(buildRow)) != null) {
            table.putBuildRow(buildRow);
        }
        table.endBuild();

        BinaryRowData probeRow = probeSideSerializer.createInstance();
        while ((probeRow = probeInput.next(probeRow)) != null) {
            if (table.tryProbe(probeRow)) {
                testJoin(table, map);
            }
        }

        while (table.nextMatching()) {
            testJoin(table, map);
        }

        table.close();

        assertThat(map).as("Wrong number of keys").hasSize(numKeys);
        for (Map.Entry<Integer, Long> entry : map.entrySet()) {
            long val = entry.getValue();
            int key = entry.getKey();

            assertThat(val)
                    .as("Wrong number of values in per-key cross product for key " + key)
                    .isEqualTo(
                            (key == repeatedValue1 || key == repeatedValue2)
                                    ? (probeValsPerKey + repeatedValueCountProbe)
                                            * (buildValsPerKey + repeatedValueCountBuild)
                                    : probeValsPerKey * buildValsPerKey);
        }

        // ----------------------------------------------------------------------------------------

        table.free();
    }

    @TestTemplate
    void testSpillingHashJoinWithTwoRecursions() throws IOException {
        // the following two values are known to have a hash-code collision on the first recursion
        // level.
        // we use them to make sure one partition grows over-proportionally large
        final int repeatedValue1 = 40559;
        final int repeatedValue2 = 92882;
        final int repeatedValueCountBuild = 200000;
        final int repeatedValueCountProbe = 5;

        final int numKeys = 1000000;
        final int buildValsPerKey = 3;
        final int probeValsPerKey = 10;

        // create a build input that gives 3 million pairs with 3 values sharing the same key, plus
        // 400k pairs with two colliding keys
        MutableObjectIterator<BinaryRowData> build1 =
                new UniformBinaryRowGenerator(numKeys, buildValsPerKey, false);
        MutableObjectIterator<BinaryRowData> build2 =
                new ConstantsKeyValuePairsIterator(repeatedValue1, 17, repeatedValueCountBuild);
        MutableObjectIterator<BinaryRowData> build3 =
                new ConstantsKeyValuePairsIterator(repeatedValue2, 23, repeatedValueCountBuild);
        List<MutableObjectIterator<BinaryRowData>> builds = new ArrayList<>();
        builds.add(build1);
        builds.add(build2);
        builds.add(build3);
        MutableObjectIterator<BinaryRowData> buildInput = new UnionIterator<>(builds);

        // create a probe input that gives 10 million pairs with 10 values sharing a key
        MutableObjectIterator<BinaryRowData> probe1 =
                new UniformBinaryRowGenerator(numKeys, probeValsPerKey, true);
        MutableObjectIterator<BinaryRowData> probe2 =
                new ConstantsKeyValuePairsIterator(repeatedValue1, 17, 5);
        MutableObjectIterator<BinaryRowData> probe3 =
                new ConstantsKeyValuePairsIterator(repeatedValue2, 23, 5);
        List<MutableObjectIterator<BinaryRowData>> probes = new ArrayList<>();
        probes.add(probe1);
        probes.add(probe2);
        probes.add(probe3);
        MutableObjectIterator<BinaryRowData> probeInput = new UnionIterator<>(probes);

        // create the map for validating the results
        HashMap<Integer, Long> map = new HashMap<>(numKeys);

        final MyHashTable table = new MyHashTable(896 * PAGE_SIZE);

        BinaryRowData buildRow = buildSideSerializer.createInstance();
        while ((buildRow = buildInput.next(buildRow)) != null) {
            table.putBuildRow(buildRow);
        }
        table.endBuild();

        BinaryRowData probeRow = probeSideSerializer.createInstance();
        while ((probeRow = probeInput.next(probeRow)) != null) {
            if (table.tryProbe(probeRow)) {
                testJoin(table, map);
            }
        }

        while (table.nextMatching()) {
            testJoin(table, map);
        }

        table.close();

        assertThat(map).as("Wrong number of keys").hasSize(numKeys);
        for (Map.Entry<Integer, Long> entry : map.entrySet()) {
            long val = entry.getValue();
            int key = entry.getKey();

            assertThat(val)
                    .as("Wrong number of values in per-key cross product for key " + key)
                    .isEqualTo(
                            (key == repeatedValue1 || key == repeatedValue2)
                                    ? (probeValsPerKey + repeatedValueCountProbe)
                                            * (buildValsPerKey + repeatedValueCountBuild)
                                    : probeValsPerKey * buildValsPerKey);
        }

        // ----------------------------------------------------------------------------------------

        table.free();
    }

    /*
     * This test is basically identical to the "testSpillingHashJoinWithMassiveCollisions" test, only that the number
     * of repeated values (causing bucket collisions) are large enough to make sure that their target partition no longer
     * fits into memory by itself and needs to be repartitioned in the recursion again.
     */
    @TestTemplate
    void testSpillingHashJoinWithTooManyRecursions() throws IOException {
        // the following two values are known to have a hash-code collision on the first recursion
        // level.
        // we use them to make sure one partition grows over-proportionally large
        final int repeatedValue1 = 40559;
        final int repeatedValue2 = 92882;
        final int repeatedValueCount = 3000000;

        final int numKeys = 1000000;
        final int buildValsPerKey = 3;
        final int probeValsPerKey = 10;

        // create a build input that gives 3 million pairs with 3 values sharing the same key, plus
        // 400k pairs with two colliding keys
        MutableObjectIterator<BinaryRowData> build1 =
                new UniformBinaryRowGenerator(numKeys, buildValsPerKey, false);
        MutableObjectIterator<BinaryRowData> build2 =
                new ConstantsKeyValuePairsIterator(repeatedValue1, 17, repeatedValueCount);
        MutableObjectIterator<BinaryRowData> build3 =
                new ConstantsKeyValuePairsIterator(repeatedValue2, 23, repeatedValueCount);
        List<MutableObjectIterator<BinaryRowData>> builds = new ArrayList<>();
        builds.add(build1);
        builds.add(build2);
        builds.add(build3);
        MutableObjectIterator<BinaryRowData> buildInput = new UnionIterator<>(builds);

        // create a probe input that gives 10 million pairs with 10 values sharing a key
        MutableObjectIterator<BinaryRowData> probe1 =
                new UniformBinaryRowGenerator(numKeys, probeValsPerKey, true);
        MutableObjectIterator<BinaryRowData> probe2 =
                new ConstantsKeyValuePairsIterator(repeatedValue1, 17, repeatedValueCount);
        MutableObjectIterator<BinaryRowData> probe3 =
                new ConstantsKeyValuePairsIterator(repeatedValue2, 23, repeatedValueCount);
        List<MutableObjectIterator<BinaryRowData>> probes = new ArrayList<>();
        probes.add(probe1);
        probes.add(probe2);
        probes.add(probe3);
        MutableObjectIterator<BinaryRowData> probeInput = new UnionIterator<>(probes);
        final MyHashTable table = new MyHashTable(896 * PAGE_SIZE);

        // create the map for validating the results
        HashMap<Integer, Long> map = new HashMap<>(numKeys);

        BinaryRowData buildRow = buildSideSerializer.createInstance();
        while ((buildRow = buildInput.next(buildRow)) != null) {
            table.putBuildRow(buildRow);
        }
        table.endBuild();

        BinaryRowData probeRow = probeSideSerializer.createInstance();
        while ((probeRow = probeInput.next(probeRow)) != null) {
            if (table.tryProbe(probeRow)) {
                testJoin(table, map);
            }
        }

        while (table.nextMatching()) {
            testJoin(table, map);
        }

        // The partition which spill to disk more than 3 can't be joined
        assertThat(map.size()).as("Wrong number of records in join result.").isLessThan(numKeys);

        // Here exists two partition which spill to disk more than 3
        assertThat(table.getPartitionsPendingForSMJ().size())
                .as("Wrong number of spilled partition.")
                .isEqualTo(2);

        Map<Integer, Integer> spilledPartitionBuildSideKeys = new HashMap<>();
        Map<Integer, Integer> spilledPartitionProbeSideKeys = new HashMap<>();
        for (LongHashPartition p : table.getPartitionsPendingForSMJ()) {
            RowIterator<BinaryRowData> buildIter = table.getSpilledPartitionBuildSideIter(p);
            while (buildIter.advanceNext()) {
                Integer key = buildIter.getRow().getInt(0);
                spilledPartitionBuildSideKeys.put(
                        key, spilledPartitionBuildSideKeys.getOrDefault(key, 0) + 1);
            }

            ProbeIterator probeIter = table.getSpilledPartitionProbeSideIter(p);
            BinaryRowData rowData;
            while ((rowData = probeIter.next()) != null) {
                Integer key = rowData.getInt(0);
                spilledPartitionProbeSideKeys.put(
                        key, spilledPartitionProbeSideKeys.getOrDefault(key, 0) + 1);
            }
        }

        // assert spilled partition contains key repeatedValue1 and repeatedValue2
        Integer buildKeyCnt = repeatedValueCount + buildValsPerKey;
        assertThat(spilledPartitionBuildSideKeys).containsEntry(repeatedValue1, buildKeyCnt);
        assertThat(spilledPartitionBuildSideKeys).containsEntry(repeatedValue2, buildKeyCnt);

        Integer probeKeyCnt = repeatedValueCount + probeValsPerKey;
        assertThat(spilledPartitionProbeSideKeys).containsEntry(repeatedValue1, probeKeyCnt);
        assertThat(spilledPartitionProbeSideKeys).containsEntry(repeatedValue2, probeKeyCnt);

        table.close();

        // ----------------------------------------------------------------------------------------

        table.free();
    }

    @TestTemplate
    void testSparseProbeSpilling() throws IOException, MemoryAllocationException {
        final int numBuildKeys = 1000000;
        final int numBuildVals = 1;
        final int numProbeKeys = 20;
        final int numProbeVals = 1;

        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numBuildKeys, numBuildVals, false);
        final MyHashTable table = new MyHashTable(100 * PAGE_SIZE);

        int expectedNumResults =
                (Math.min(numProbeKeys, numBuildKeys) * numBuildVals) * numProbeVals;

        int numRecordsInJoinResult =
                join(
                        table,
                        buildInput,
                        new UniformBinaryRowGenerator(numProbeKeys, numProbeVals, true));

        assertThat(numRecordsInJoinResult)
                .as("Wrong number of records in join result.")
                .isEqualTo(expectedNumResults);

        table.close();

        table.free();
    }

    @TestTemplate
    void validateSpillingDuringInsertion() throws IOException, MemoryAllocationException {
        final int numBuildKeys = 500000;
        final int numBuildVals = 1;
        final int numProbeKeys = 10;
        final int numProbeVals = 1;

        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numBuildKeys, numBuildVals, false);
        final MyHashTable table = new MyHashTable(85 * PAGE_SIZE);

        int expectedNumResults =
                (Math.min(numProbeKeys, numBuildKeys) * numBuildVals) * numProbeVals;

        int numRecordsInJoinResult =
                join(
                        table,
                        buildInput,
                        new UniformBinaryRowGenerator(numProbeKeys, numProbeVals, true));

        assertThat(numRecordsInJoinResult)
                .as("Wrong number of records in join result.")
                .isEqualTo(expectedNumResults);

        table.close();

        table.free();
    }

    @TestTemplate
    void testBucketsNotFulfillSegment() throws Exception {
        final int numKeys = 10000;
        final int buildValsPerKey = 3;
        final int probeValsPerKey = 10;

        // create a build input that gives 30000 pairs with 3 values sharing the same key
        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys, buildValsPerKey, false);

        // create a probe input that gives 100000 pairs with 10 values sharing a key
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys, probeValsPerKey, true);

        // ----------------------------------------------------------------------------------------

        final MyHashTable table = new MyHashTable(35 * PAGE_SIZE);

        int numRecordsInJoinResult = join(table, buildInput, probeInput);

        assertThat(numRecordsInJoinResult)
                .as("Wrong number of records in join result.")
                .isEqualTo(numKeys * buildValsPerKey * probeValsPerKey);

        table.close();
        table.free();
    }

    private void testJoin(MyHashTable table, HashMap<Integer, Long> map) throws IOException {
        BinaryRowData record;
        int numBuildValues = 0;

        final RowData probeRec = table.getCurrentProbeRow();
        int key = probeRec.getInt(0);

        RowIterator<BinaryRowData> buildSide = table.getBuildSideIterator();
        if (buildSide.advanceNext()) {
            numBuildValues = 1;
            record = buildSide.getRow();
            assertThat(record.getInt(0))
                    .as("Probe-side key was different than build-side key.")
                    .isEqualTo(key);
        } else {
            fail("No build side values found for a probe key.");
        }
        while (buildSide.advanceNext()) {
            numBuildValues++;
            record = buildSide.getRow();
            assertThat(record.getInt(0))
                    .as("Probe-side key was different than build-side key.")
                    .isEqualTo(key);
        }

        Long contained = map.get(key);
        if (contained == null) {
            contained = (long) numBuildValues;
        } else {
            contained = contained + numBuildValues;
        }

        map.put(key, contained);
    }

    private int join(
            MyHashTable table,
            MutableObjectIterator<BinaryRowData> buildInput,
            MutableObjectIterator<BinaryRowData> probeInput)
            throws IOException {
        int count = 0;

        BinaryRowData reuseBuildSizeRow = buildSideSerializer.createInstance();
        BinaryRowData buildRow;
        while ((buildRow = buildInput.next(reuseBuildSizeRow)) != null) {
            table.putBuildRow(buildRow);
        }
        table.endBuild();

        BinaryRowData probeRow = probeSideSerializer.createInstance();
        while ((probeRow = probeInput.next(probeRow)) != null) {
            if (table.tryProbe(probeRow)) {
                count += joinWithNextKey(table);
            }
        }

        while (table.nextMatching()) {
            count += joinWithNextKey(table);
        }
        return count;
    }

    private int joinWithNextKey(MyHashTable table) throws IOException {
        int count = 0;
        final RowIterator<BinaryRowData> buildIterator = table.getBuildSideIterator();
        final RowData probeRow = table.getCurrentProbeRow();
        BinaryRowData buildRow;

        buildRow = buildIterator.advanceNext() ? buildIterator.getRow() : null;
        // get the first build side value
        if (probeRow != null && buildRow != null) {
            count++;
            while (buildIterator.advanceNext()) {
                count++;
            }
        }
        return count;
    }
}
