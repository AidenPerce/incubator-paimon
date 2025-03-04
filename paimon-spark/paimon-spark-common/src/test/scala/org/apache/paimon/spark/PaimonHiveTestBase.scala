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
package org.apache.paimon.spark

import org.apache.paimon.hive.TestHiveMetastore
import org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions

import org.apache.spark.SparkConf

class PaimonHiveTestBase extends PaimonSparkTestBase {

  protected lazy val testHiveMetastore: TestHiveMetastore = new TestHiveMetastore

  protected val hiveDbName: String = "test_hive"

  override protected def sparkConf: SparkConf = {
    super.sparkConf
      .set("spark.sql.warehouse.dir", tempDBDir.getCanonicalPath)
      .set("spark.sql.catalogImplementation", "hive")
      .set("spark.sql.catalog.spark_catalog", classOf[SparkGenericCatalog[_]].getName)
      .set("spark.sql.extensions", classOf[PaimonSparkSessionExtensions].getName)
  }

  override protected def beforeAll(): Unit = {
    testHiveMetastore.start()
    super.beforeAll()
    spark.sql(s"USE spark_catalog")
    spark.sql(s"CREATE DATABASE IF NOT EXISTS $hiveDbName")
    spark.sql(s"USE $hiveDbName")
  }

  override protected def afterAll(): Unit = {
    try {
      spark.sql(s"USE spark_catalog")
      spark.sql("USE default")
      spark.sql(s"DROP DATABASE $hiveDbName CASCADE")
    } finally {
      super.afterAll()
      testHiveMetastore.stop()
    }
  }

  override protected def beforeEach(): Unit = {
    spark.sql(s"USE spark_catalog")
  }

}
