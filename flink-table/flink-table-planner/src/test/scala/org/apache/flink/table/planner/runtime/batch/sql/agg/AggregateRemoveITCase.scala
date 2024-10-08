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
package org.apache.flink.table.planner.runtime.batch.sql.agg

import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.table.legacy.api.Types
import org.apache.flink.table.planner.plan.stats.FlinkStatistic
import org.apache.flink.table.planner.runtime.utils.BatchTestBase
import org.apache.flink.table.planner.runtime.utils.BatchTestBase.row
import org.apache.flink.table.planner.runtime.utils.TestData._

import org.junit.jupiter.api.{BeforeEach, Test}

import scala.collection.JavaConverters._

class AggregateRemoveITCase extends BatchTestBase {

  @BeforeEach
  override def before(): Unit = {
    super.before()
    registerCollection(
      "T1",
      Seq(
        row(2, 1, "A", null),
        row(3, 2, "A", "Hi"),
        row(5, 2, "B", "Hello"),
        row(6, 3, "C", "Hello world")),
      new RowTypeInfo(Types.INT, Types.INT, Types.STRING, Types.STRING),
      "a, b, c, d",
      Array(true, true, true, true),
      FlinkStatistic.builder().uniqueKeys(Set(Set("a").asJava).asJava).build()
    )

    registerCollection(
      "T2",
      smallData3,
      type3,
      "a, b, c",
      nullablesOfSmallData3,
      FlinkStatistic.builder().uniqueKeys(Set(Set("a").asJava).asJava).build())
    registerCollection(
      "T3",
      smallData5,
      type5,
      "a, b, c, d, e",
      nullablesOfSmallData5,
      FlinkStatistic.builder().uniqueKeys(Set(Set("b").asJava).asJava).build())
  }

  @Test
  def testSimple(): Unit = {
    checkResult("SELECT a, b FROM T3 GROUP BY a, b", Seq(row(1, 1), row(2, 2), row(2, 3)))

    checkResult(
      "SELECT a, b + 1, c, s FROM (" +
        "SELECT a, MIN(b) AS b, SUM(b) AS s, MAX(c) AS c FROM T3 GROUP BY a)",
      Seq(row(1, 2, 0, 1), row(2, 3, 2, 5)))

    checkResult("SELECT a, SUM(b) AS s FROM T3 GROUP BY a", Seq(row(1, 1), row(2, 5)))

    checkResult(
      "SELECT MAX(a), SUM(b), MIN(c) FROM (VALUES (1, 2, 3)) T(a, b, c)",
      Seq(row(1, 2, 3)))

    checkResult(
      "SELECT a, b + 1, c, s FROM (" +
        "SELECT a, MIN(b) AS b, SUM(b) AS s, MAX(c) AS c FROM T2 GROUP BY a)",
      Seq(
        row(1, 2L, "Hi", 1L),
        row(2, 3L, "Hello", 2L),
        row(3, 3L, "Hello world", 2L)
      )
    )

    checkResult(
      "SELECT MAX(a), SUM(b), MIN(c) FROM (VALUES (1, 2, 3)) T(a, b, c)",
      Seq(row(1, 2, 3))
    )

    checkResult(
      "SELECT * FROM T2 WHERE EXISTS (SELECT SUM(a) FROM T3 WHERE 1=2)",
      Seq(row(1, 1, "Hi"), row(2, 2, "Hello"), row(3, 2, "Hello world"))
    )

    checkResult(
      """
        |SELECT a, SUM(b), MAX(b) FROM
        | (SELECT a, MAX(b) AS b FROM
        | (VALUES (cast(null as BIGINT), cast(null as BIGINT))) T(a, b) GROUP BY a) t
        | GROUP BY a
      """.stripMargin,
      Seq(row(null, null, null))
    )
  }

  @Test
  def testWithGroupingSets(): Unit = {
    checkResult(
      "SELECT a, b, c, COUNT(d) FROM T1 " +
        "GROUP BY GROUPING SETS ((a, b), (a, c))",
      Seq(
        row(2, 1, null, 0),
        row(2, null, "A", 0),
        row(3, 2, null, 1),
        row(3, null, "A", 1),
        row(5, 2, null, 1),
        row(5, null, "B", 1),
        row(6, 3, null, 1),
        row(6, null, "C", 1))
    )

    checkResult(
      "SELECT a, c, COUNT(d) FROM T1 " +
        "GROUP BY GROUPING SETS ((a, c), (a), ())",
      Seq(
        row(2, "A", 0),
        row(2, null, 0),
        row(3, "A", 1),
        row(3, null, 1),
        row(5, "B", 1),
        row(5, null, 1),
        row(6, "C", 1),
        row(6, null, 1),
        row(null, null, 3))
    )

    checkResult(
      "SELECT a, b, c, COUNT(d) FROM T1 " +
        "GROUP BY GROUPING SETS ((a, b, c), (a, b, d))",
      Seq(
        row(2, 1, "A", 0),
        row(2, 1, null, 0),
        row(3, 2, "A", 1),
        row(3, 2, null, 1),
        row(5, 2, "B", 1),
        row(5, 2, null, 1),
        row(6, 3, "C", 1),
        row(6, 3, null, 1))
    )
  }

  @Test
  def testWithRollup(): Unit = {
    checkResult(
      "SELECT a, b, c, COUNT(d) FROM T1 GROUP BY ROLLUP (a, b, c)",
      Seq(
        row(2, 1, "A", 0),
        row(2, 1, null, 0),
        row(2, null, null, 0),
        row(3, 2, "A", 1),
        row(3, 2, null, 1),
        row(3, null, null, 1),
        row(5, 2, "B", 1),
        row(5, 2, null, 1),
        row(5, null, null, 1),
        row(6, 3, "C", 1),
        row(6, 3, null, 1),
        row(6, null, null, 1),
        row(null, null, null, 3)
      )
    )
  }

  @Test
  def testWithCube(): Unit = {
    checkResult(
      "SELECT a, b, c, COUNT(d) FROM T1 GROUP BY CUBE (a, b, c)",
      Seq(
        row(2, 1, "A", 0),
        row(2, 1, null, 0),
        row(2, null, "A", 0),
        row(2, null, null, 0),
        row(3, 2, "A", 1),
        row(3, 2, null, 1),
        row(3, null, "A", 1),
        row(3, null, null, 1),
        row(5, 2, "B", 1),
        row(5, 2, null, 1),
        row(5, null, "B", 1),
        row(5, null, null, 1),
        row(6, 3, "C", 1),
        row(6, 3, null, 1),
        row(6, null, "C", 1),
        row(6, null, null, 1),
        row(null, 1, "A", 0),
        row(null, 1, null, 0),
        row(null, 2, "A", 1),
        row(null, 2, "B", 1),
        row(null, 2, null, 2),
        row(null, 3, "C", 1),
        row(null, 3, null, 1),
        row(null, null, "A", 1),
        row(null, null, "B", 1),
        row(null, null, "C", 1),
        row(null, null, null, 3)
      )
    )

    checkResult(
      "SELECT b, c, e, SUM(a), MAX(d) FROM T3 GROUP BY CUBE (b, c, e)",
      Seq(
        row(null, null, null, 5, "Hallo Welt wie"),
        row(null, null, 1, 3, "Hallo Welt wie"),
        row(null, null, 2, 2, "Hallo Welt"),
        row(null, 0, null, 1, "Hallo"),
        row(null, 0, 1, 1, "Hallo"),
        row(null, 1, null, 2, "Hallo Welt"),
        row(null, 1, 2, 2, "Hallo Welt"),
        row(null, 2, null, 2, "Hallo Welt wie"),
        row(null, 2, 1, 2, "Hallo Welt wie"),
        row(1, null, null, 1, "Hallo"),
        row(1, null, 1, 1, "Hallo"),
        row(1, 0, null, 1, "Hallo"),
        row(1, 0, 1, 1, "Hallo"),
        row(2, null, null, 2, "Hallo Welt"),
        row(2, null, 2, 2, "Hallo Welt"),
        row(2, 1, null, 2, "Hallo Welt"),
        row(2, 1, 2, 2, "Hallo Welt"),
        row(3, null, null, 2, "Hallo Welt wie"),
        row(3, null, 1, 2, "Hallo Welt wie"),
        row(3, 2, null, 2, "Hallo Welt wie"),
        row(3, 2, 1, 2, "Hallo Welt wie")
      )
    )
  }

  @Test
  def testSingleDistinctAgg(): Unit = {
    checkResult(
      "SELECT a, COUNT(DISTINCT c) FROM T1 GROUP BY a",
      Seq(row(2, 1), row(3, 1), row(5, 1), row(6, 1)))

    checkResult(
      "SELECT a, b, COUNT(DISTINCT c) FROM T1 GROUP BY a, b",
      Seq(row(2, 1, 1), row(3, 2, 1), row(5, 2, 1), row(6, 3, 1)))

    checkResult(
      "SELECT a, b, COUNT(DISTINCT c), COUNT(DISTINCT d) FROM T1 GROUP BY a, b",
      Seq(row(2, 1, 1, 0), row(3, 2, 1, 1), row(5, 2, 1, 1), row(6, 3, 1, 1)))
  }

  @Test
  def testSingleDistinctAgg_WithNonDistinctAgg(): Unit = {
    checkResult(
      "SELECT a, COUNT(DISTINCT c), SUM(b) FROM T1 GROUP BY a",
      Seq(row(2, 1, 1), row(3, 1, 2), row(5, 1, 2), row(6, 1, 3)))

    checkResult(
      "SELECT a, c, COUNT(DISTINCT c), SUM(b) FROM T1 GROUP BY a, c",
      Seq(row(2, "A", 1, 1), row(3, "A", 1, 2), row(5, "B", 1, 2), row(6, "C", 1, 3)))

    checkResult(
      "SELECT a, COUNT(DISTINCT c), SUM(b) FROM T1 GROUP BY a",
      Seq(row(2, 1, 1), row(3, 1, 2), row(5, 1, 2), row(6, 1, 3)))

    checkResult(
      "SELECT a, d, COUNT(DISTINCT c), SUM(b) FROM T1 GROUP BY a, d",
      Seq(
        row(2, null, 1, 1),
        row(3, "Hi", 1, 2),
        row(5, "Hello", 1, 2),
        row(6, "Hello world", 1, 3)))
  }

  @Test
  def testMultiDistinctAggs(): Unit = {
    checkResult(
      "SELECT a, COUNT(DISTINCT b), SUM(DISTINCT b) FROM T1 GROUP BY a",
      Seq(row(2, 1, 1), row(3, 1, 2), row(5, 1, 2), row(6, 1, 3)))

    checkResult(
      "SELECT a, d, COUNT(DISTINCT c), SUM(DISTINCT b) FROM T1 GROUP BY a, d",
      Seq(
        row(2, null, 1, 1),
        row(3, "Hi", 1, 2),
        row(5, "Hello", 1, 2),
        row(6, "Hello world", 1, 3))
    )

    checkResult(
      "SELECT a, SUM(DISTINCT b), MAX(DISTINCT b), MIN(DISTINCT c) FROM T1 GROUP BY a",
      Seq(row(2, 1, 1, "A"), row(3, 2, 2, "A"), row(5, 2, 2, "B"), row(6, 3, 3, "C")))

    checkResult(
      "SELECT a, d, COUNT(DISTINCT c), MAX(DISTINCT b), SUM(b) FROM T1 GROUP BY a, d",
      Seq(
        row(2, null, 1, 1, 1),
        row(3, "Hi", 1, 2, 2),
        row(5, "Hello", 1, 2, 2),
        row(6, "Hello world", 1, 3, 3))
    )
  }

}
