/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.snappydata.hydra

import java.io.PrintWriter

import com.typesafe.config.Config
import org.apache.spark.sql.SnappySQLJob
import org.apache.spark.streaming.dstream.DStream
import spark.jobserver.{SparkJobValid, SparkJobValidation}


object SnappyOlapQueries {
  val Q1: String = "SELECT ol_number, " +
    "            sum(ol_quantity) AS sum_qty, " +
    "            sum(ol_amount) AS sum_amount, " +
    "            avg(ol_quantity) AS avg_qty, " +
    "            avg(ol_amount) AS avg_amount, " +
    "            count(*) AS count_order " +
    "            FROM order_line" +
    "            WHERE ol_delivery_d > '2007-01-02 00:00:00.000000' " +
    "            GROUP BY ol_number " + "            ORDER BY ol_number"

  val Q2: String = "SELECT su_suppkey, " +
    "            su_name, " +
    "            n_name, " +
    "            i_id, " +
    "            i_name, " +
    "            su_address, " +
    "            su_phone, " +
    "            su_comment " +
    "            FROM item, supplier, stock, nation, region, " +
    "            (SELECT s_i_id AS m_i_id, MIN(s_quantity) AS m_s_quantity " +
    "            FROM stock, " + "            supplier, " +
    "            nation, " + "            region " +
    "            WHERE PMOD((s_w_id*s_i_id), 10000)=su_suppkey " +
    "            AND su_nationkey=n_nationkey " +
    "            AND n_regionkey=r_regionkey " +
    "            AND r_name LIKE 'Europ%' " +
    "            GROUP BY s_i_id) m " +
    "            WHERE i_id = s_i_id " +
    "            AND PMOD((s_w_id * s_i_id), 10000) = su_suppkey " +
    "            AND su_nationkey = n_nationkey " +
    "            AND n_regionkey = r_regionkey " +
    "            AND i_data LIKE '%b' " +
    "            AND r_name LIKE 'Europ%' " +
    "            AND i_id=m_i_id " +
    "            AND s_quantity = m_s_quantity " +
    "            ORDER BY n_name, " +
    "            su_name, " +
    "            i_id"

  val Q3: String = "SELECT ol_o_id, " +
    "   ol_w_id, " +
    "   ol_d_id, " +
    "   sum(ol_amount) AS revenue, " +
    "   o_entry_d " +
    "             FROM " +
    " customer, " +
    " oorder_col, " +
    " order_line_col," +
    " new_order" +
    "             WHERE c_state LIKE 'A%' " +
    "            AND c_id = o_c_id " +
    "            AND c_w_id = o_w_id " +
    "            AND c_d_id = o_d_id " +
    "            AND no_w_id = o_w_id " +
    "            AND no_d_id = o_d_id " +
    "            AND no_o_id = o_id " +
    "            AND ol_w_id = o_w_id " +
    "            AND ol_d_id = o_d_id " +
    "            AND ol_o_id = o_id " +
    "            AND o_entry_d > '2007-01-02 00:00:00.000000' " +
    "             GROUP BY ol_o_id, " +
    "     ol_w_id, " +
    "     ol_d_id, " +
    "     o_entry_d " +
    "             ORDER BY revenue DESC , o_entry_d"

  val Q4: String = "SELECT o_ol_cnt, " +
    "count(*) AS order_count " +
    "FROM oorder_col " + "WHERE exists " +
    "(SELECT * " + "FROM order_line_col " +
    "WHERE o_id = ol_o_id " +
    "AND o_w_id = ol_w_id " +
    "AND o_d_id = ol_d_id " +
    "AND ol_delivery_d >= o_entry_d) " +
    "GROUP BY o_ol_cnt " +
    "ORDER BY o_ol_cnt"

 val Q5: String = "SELECT n_name, " +
   "sum(ol_amount) AS revenue " +
   "FROM customer, " +
   "oorder_col, " +
   "order_line_col, " +
   "stock, " +
   "supplier, " +
   "nation, " +
   "region " +
   "WHERE c_id = o_c_id " +
   "AND c_w_id = o_w_id " +
   "AND c_d_id = o_d_id " +
   "AND ol_o_id = o_id " +
   "AND ol_w_id = o_w_id " +
   "AND ol_d_id=o_d_id " +
   "AND ol_w_id = s_w_id " +
   "AND ol_i_id = s_i_id " +
   "AND pMOD((s_w_id * s_i_id), 10000) = su_suppkey " +
   "AND ascii(substr(c_state, 1, 1)) = su_nationkey " +
   "AND su_nationkey = n_nationkey " +
   "AND n_regionkey = r_regionkey " +
   "AND r_name = 'Europe' " +
   "AND o_entry_d >= '2007-01-02 00:00:00.000000' " +
   "GROUP BY n_name " +
   "ORDER BY revenue DESC"

  val Q6: String = "SELECT sum(ol_amount) AS revenue " +
    "FROM order_line_col " +
    "WHERE ol_delivery_d >= '1999-01-01 00:00:00.000000' " +
    "AND ol_delivery_d < '2020-01-01 00:00:00.000000' " +
    "AND ol_quantity BETWEEN 1 AND 100000"

  // Select, GroupBY and OrderClause of Q7 has been MOdified
  val Q7: String = "SELECT su_nationkey AS supp_nation, " +
    "n2.n_nationkey AS cust_nation, " +
    "YEAR(o_entry_d) AS l_year, " +
    "sum(ol_amount) AS revenue " +
    "FROM supplier, " +
    "stock, " +
    "order_line_col, " +
    "oorder_col, " +
    "customer, " +
    "nation n1, " +
    "nation n2 " +
    "WHERE ol_supply_w_id = s_w_id " +
    "AND ol_i_id = s_i_id " +
    "AND pMOD ((s_w_id * s_i_id), 10000) = su_suppkey " +
    "AND ol_w_id = o_w_id " +
    "AND ol_d_id = o_d_id " +
    "AND ol_o_id = o_id " +
    "AND c_id = o_c_id " +
    "AND c_w_id = o_w_id " +
    "AND c_d_id = o_d_id " +
    "AND su_nationkey = n1.n_nationkey " +
    "AND ascii(substr(c_state,1, 1)) = n2.n_nationkey " +
    "AND ((n1.n_name = 'Germany' " +
    "AND n2.n_name = 'Cambodia') " +
    "OR (n1.n_name = 'Cambodia' " +
    "AND n2.n_name = 'Germany')) " +
    "GROUP BY su_nationkey, " +
    "n2.n_nationkey, " +
    "YEAR(o_entry_d) " +
    "ORDER BY su_nationkey, " +
    "n2.n_nationkey, " +
    "YEAR(o_entry_d)"

  // Modified the group by and order by clauses
  val Q8 = "SELECT YEAR (o_entry_d) AS l_year, " +
    "sum(CASE WHEN n2.n_name = 'Germany' THEN ol_amount ELSE 0 END) / " +
    "sum(ol_amount) AS mkt_share " +
    "FROM item, " +
    "supplier, " +
    "stock, " +
    "order_line_col, " +
    "oorder_col, " +
    "customer, " +
    "nation n1, " +
    "nation n2, " +
    "region " +
    "WHERE i_id = s_i_id " +
    "AND ol_i_id = s_i_id  " +
    "AND ol_supply_w_id = s_w_id " +
    "AND pMOD ((s_w_id * s_i_id), 10000) = su_suppkey " +
    "AND ol_w_id = o_w_id " +
    "AND ol_d_id = o_d_id " +
    "AND ol_o_id = o_id " +
    "AND c_id = o_c_id " +
    "AND c_w_id = o_w_id " +
    "AND c_d_id = o_d_id " +
    "AND n1.n_nationkey = ascii(substr(c_state, 1, 1)) " +
    "AND n1.n_regionkey = r_regionkey " +
    "AND ol_i_id < 1000 " +
    "AND r_name = 'Europe' " +
    "AND su_nationkey = n2.n_nationkey " +
    "AND i_data LIKE '%b' " +
    "AND i_id = ol_i_id " +
    "GROUP BY YEAR(o_entry_d) " +
    " ORDER BY YEAR(o_entry_d)"

  // Modified the group by and order by clauses
  val Q9 = "SELECT n_name, YEAR(o_entry_d) AS l_year, " +
    "sum(ol_amount) AS sum_profit " +
    "FROM item, stock, supplier, " +
    "order_line_col, " +
    "oorder_col, " +
    "nation " +
    "WHERE ol_i_id = s_i_id " +
    "AND ol_supply_w_id = s_w_id " +
    "AND pMOD ((s_w_id * s_i_id), 10000) = su_suppkey " +
    "AND ol_w_id = o_w_id " +
    "AND ol_d_id = o_d_id " +
    "AND ol_o_id = o_id " +
    "AND ol_i_id = i_id " +
    "AND su_nationkey = n_nationkey " +
    "AND i_data LIKE '%bb' " +
    "GROUP BY n_name, " +
    "YEAR(o_entry_d) " +
    "ORDER BY n_name, " +
    "YEAR(o_entry_d) DESC"

  val Q10 = "SELECT c_id, " +
    "c_last, " +
    "sum(ol_amount) AS revenue, " +
    "c_city, " +
    "c_phone, " +
    "n_name " +
    "FROM customer, " +
    "oorder_col, " +
    "order_line_col, " +
    "nation " +
    "WHERE c_id = o_c_id " +
    "AND c_w_id = o_w_id " +
    "AND c_d_id = o_d_id " +
    "AND ol_w_id = o_w_id " +
    "AND ol_d_id = o_d_id " +
    "AND ol_o_id = o_id " +
    "AND o_entry_d >= '2007-01-02 00:00:00.000000' " +
    "AND o_entry_d <= ol_delivery_d " +
    "AND n_nationkey = ascii(substr(c_state, 1, 1)) " +
    "GROUP BY c_id, " +
    "c_last, " +
    "c_city, " +
    "c_phone, " +
    "n_name " +
    "ORDER BY revenue DESC"

  val Q12 = "SELECT o_ol_cnt, " +
    "sum(CASE WHEN o_carrier_id = 1 " +
    "OR o_carrier_id = 2 THEN 1 ELSE 0 END) AS high_line_count, " +
    "sum(CASE WHEN o_carrier_id <> 1 " +
    "AND o_carrier_id <> 2 THEN 1 ELSE 0 END) AS low_line_count " +
    "FROM oorder_col, " +
    "order_line_col " +
    "WHERE ol_w_id = o_w_id " +
    "AND ol_d_id = o_d_id " +
    "AND ol_o_id = o_id " +
    "AND o_entry_d <= ol_delivery_d " +
    "AND ol_delivery_d < '2020-01-01 00:00:00.000000' " +
    "GROUP BY o_ol_cnt " +
    "ORDER BY o_ol_cnt"

  val Q13 = "SELECT c_count, " +
    "count(*) AS custdist " +
    "FROM " +
    "(SELECT c_id, " +
    "count(o_id) AS c_count " +
    "FROM customer " +
    "LEFT OUTER JOIN oorder_col ON (c_w_id = o_w_id " +
    "AND c_d_id = o_d_id " +
    "AND c_id = o_c_id " +
    "AND o_carrier_id > 8) " +
    "GROUP BY c_id) AS c_orders " +
    "GROUP BY c_count " +
    "ORDER BY custdist DESC, c_count DESC"

  val Q14 = " SELECT (100.00 * sum(CASE WHEN i_data LIKE 'PR%' THEN ol_amount ELSE 0 END) / " +
    "(1  + sum(ol_amount))) AS promo_revenue " +
    "FROM order_line_col, " +
    "item " +
    "WHERE ol_i_id = i_id " +
    "AND ol_delivery_d >= '2007-01-02 00:00:00.000000' " +
    "AND ol_delivery_d < '2020-01-02 00:00:00.000000'"

  val Q16 = "SELECT i_name, " +
    "substr(i_data,  1, 3) AS brand, " +
    "i_price, " +
    "count(DISTINCT (pmod((s_w_id * s_i_id),10000))) AS supplier_cnt " +
    "FROM stock, " +
    "item " +
    "WHERE i_id = s_i_id " +
    "AND i_data NOT LIKE 'zz%' " +
    "AND (pmod((s_w_id * s_i_id),10000) NOT IN " +
    "(SELECT su_suppkey " +
    "FROM supplier " +
    "WHERE su_comment LIKE '%bad%')) " +
    "" +
    "GROUP BY i_name, " +
    "substr(i_data,  1, 3), " +
    "i_price " +
    "ORDER BY supplier_cnt DESC"

  val Q17 = "SELECT SUM(ol_amount) / 2.0 AS avg_yearly " +
    "FROM order_line_col, " +
    "(SELECT i_id, AVG (ol_quantity) AS a " +
    "FROM item, " +
    "order_line_col " +
    "WHERE i_data LIKE '%b' " +
    "AND ol_i_id = i_id " +
    "GROUP BY i_id) t " +
    "WHERE ol_i_id = t.i_id " +
    "AND ol_quantity < t.a"

  val Q18 = "              SELECT c_last, " +
    "c_id, " +
    "o_id, " +
    "o_entry_d, " +
    "o_ol_cnt, " +
    "sum(ol_amount) AS amount_sum " +
    "FROM customer, " +
    "oorder_col, " +
    "order_line_col " +
    "WHERE c_id = o_c_id " +
    "AND c_w_id = o_w_id " +
    "AND c_d_id = o_d_id " +
    "AND ol_w_id = o_w_id " +
    "AND ol_d_id = o_d_id " +
    "AND ol_o_id = o_id " +
    "GROUP BY o_id, " +
    "o_w_id, " +
    "o_d_id, " +
    "c_id, " +
    "c_last, " +
    "o_entry_d, " +
    "o_ol_cnt HAVING sum(ol_amount) > 200 " +
    "ORDER BY amount_sum DESC, o_entry_d"

  val Q19 = " SELECT sum(ol_amount) AS revenue " +
    "FROM order_line_col, " +
    "item " +
    "WHERE (ol_i_id = i_id " +
    "AND i_data LIKE '%a' " +
    "AND ol_quantity >= 1 " +
    "AND ol_quantity <= 10 " +
    "AND i_price BETWEEN 1 AND 400000 " +
    "AND ol_w_id IN (1, 2, 3)) " +
    "OR (ol_i_id = i_id " +
    "AND i_data LIKE '%b' " +
    "AND ol_quantity >= 1 " +
    "AND ol_quantity <= 10 " +
    "AND i_price BETWEEN 1 AND 400000 " +
    "AND ol_w_id IN (1, 2, 4))  OR (ol_i_id = i_id  AND i_data LIKE '%c' AND ol_quantity >= 1 " +
    "AND ol_quantity <= 10 " +
    "AND i_price BETWEEN 1 AND 400000 " +
    "AND ol_w_id IN (1, 5, 3))"

  val Q20 = "SELECT su_name, su_address " +
    "FROM supplier, " +
    "nation " +
    "WHERE su_suppkey IN " +
    "(SELECT pmod(s_i_id * s_w_id, 10000) " +
    "FROM stock " +
    "INNER JOIN item ON i_id = s_i_id " +
    "INNER JOIN order_line_col ON ol_i_id = s_i_id " +
    "WHERE ol_delivery_d > '2010-05-23 12:00:00' " +
    "AND i_data LIKE 'co%' " +
    "GROUP BY s_i_id, " +
    "s_w_id, " +
    "s_quantity HAVING 2*s_quantity > sum(ol_quantity)) " +
    "AND su_nationkey = n_nationkey " +
    "AND n_name = 'Germany' " +
    "ORDER BY su_name"
  val queries = Map("Q1" -> Q1 ,
  "Q2" -> Q2,
  "Q3" -> Q3,
  "Q5" -> Q5,
  "Q6" -> Q6,
    "Q7" -> Q7,
    "Q8" -> Q8,
    "Q9" -> Q9,
  "Q10" -> Q10,
  "Q12" -> Q12,
  "Q13" -> Q13,
  "Q14" -> Q14,
  "Q16" -> Q16,
  "Q17" -> Q17,
  "Q18" -> Q18,
  "Q19" -> Q19,
  "Q20" -> Q20
  )
}
object OLAPQueries extends SnappySQLJob {

  override def runJob(snsc: C, jobConfig: Config): Any = {

    def getCurrentDirectory = new java.io.File(".").getCanonicalPath

    // scalastyle:off println
    var stream: DStream[_] = null
    snsc.sql("set spark.sql.shuffle.partitions=20")
      var i: Int = 0
      while (i < 4) {
        val outFileName = s"OLAPQueries-${i}.out"

        val pw = new PrintWriter(outFileName)

        i = i + 1
        for (q <- SnappyOlapQueries.queries) {
          val start: Long = System.currentTimeMillis
          try {
            snsc.sql(q._2).collect()
          } catch {
            case e => pw.println(s"Exception for query ${q._1}:  " + e)
          }
          val end: Long = System.currentTimeMillis - start
          pw.println(s"${new java.util.Date(System.currentTimeMillis())} " +
            s"Time taken by ${q._1} is $end")
        }
        pw.close()
      }


      // Return the output file name
      s"See ${getCurrentDirectory}"

  }

  override def validate(snsc: C, config: Config): SparkJobValidation = {
    SparkJobValid
  }
}
