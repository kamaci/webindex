/*
 * Copyright 2015 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.webindex.data.spark;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.SortedSet;
import java.util.TreeSet;

import com.google.common.base.Preconditions;
import io.fluo.api.config.FluoConfiguration;
import io.fluo.api.data.Bytes;
import io.fluo.api.data.RowColumn;
import io.fluo.core.util.AccumuloUtil;
import io.fluo.recipes.accumulo.export.TableInfo;
import io.fluo.webindex.core.DataConfig;
import io.fluo.webindex.core.models.Page;
import io.fluo.webindex.data.FluoApp;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexEnv {

  private static final Logger log = LoggerFactory.getLogger(IndexEnv.class);

  private Connector conn;
  final private String accumuloTable;
  private FluoConfiguration fluoConfig;
  private FileSystem hdfs;
  private Path failuresDir;
  private Path hadoopTempDir;
  private Path accumuloTempDir;
  private Path fluoTempDir;

  public IndexEnv(DataConfig dataConfig) {
    this(getFluoConfig(dataConfig), dataConfig.accumuloIndexTable, getHadoopConfigFromEnv(),
        dataConfig.hdfsTempDir);
  }

  public IndexEnv(DataConfig dataConfig, Configuration hadoopConfig) {
    this(getFluoConfig(dataConfig), dataConfig.accumuloIndexTable, hadoopConfig,
        dataConfig.hdfsTempDir);
  }

  public IndexEnv(FluoConfiguration fluoConfig, String accumuloTable, Configuration hadoopConfig,
      String hdfsTempDir) {
    this.fluoConfig = fluoConfig;
    this.accumuloTable = accumuloTable;
    conn = AccumuloUtil.getConnector(fluoConfig);
    try {
      hdfs = FileSystem.get(hadoopConfig);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to get HDFS client from hadoop config", e);
    }
    hadoopTempDir = new Path(hdfsTempDir + "/hadoop");
    fluoTempDir = new Path(hdfsTempDir + "/fluo");
    failuresDir = new Path(hdfsTempDir + "/failures");
    accumuloTempDir = new Path(hdfsTempDir + "/accumulo");
  }

  private static Configuration getHadoopConfigFromEnv() {
    String hadoopConfDir = System.getenv("HADOOP_CONF_DIR");
    if (hadoopConfDir == null) {
      log.error("HADOOP_CONF_DIR must be set in environment!");
      System.exit(1);
    }
    if (!(new File(hadoopConfDir).exists())) {
      log.error("Directory set by HADOOP_CONF_DIR={} does not exist", hadoopConfDir);
      System.exit(1);
    }
    Configuration config = new Configuration();
    config.addResource(hadoopConfDir);
    return config;
  }

  private static FluoConfiguration getFluoConfig(DataConfig dataConfig) {
    Preconditions.checkArgument(new File(dataConfig.getFluoPropsPath()).exists(),
        "fluoPropsPath must be set in data.yml and exist");
    return new FluoConfiguration(new File(dataConfig.getFluoPropsPath()));
  }

  private static SortedSet<Text> getSplits(String filename) {
    SortedSet<Text> splits = new TreeSet<>();
    InputStream is = IndexEnv.class.getClassLoader().getResourceAsStream("splits/" + filename);
    try {
      try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
        String line;
        while ((line = br.readLine()) != null) {
          splits.add(new Text(line));
        }
      }
    } catch (IOException e) {
      log.error("Failed to read splits/accumulo-default.txt resource", e);
      System.exit(-1);
    }
    return splits;
  }

  public static SortedSet<Text> getAccumuloDefaultSplits() {
    return getSplits("accumulo-default.txt");
  }

  public static SortedSet<Text> getFluoDefaultSplits() {
    return getSplits("fluo-default.txt");
  }

  public void initAccumuloIndexTable() {
    if (conn.tableOperations().exists(accumuloTable)) {
      try {
        conn.tableOperations().delete(accumuloTable);
      } catch (TableNotFoundException | AccumuloSecurityException | AccumuloException e) {
        throw new IllegalStateException("Failed to delete Accumulo table " + accumuloTable, e);
      }
    }
    try {
      conn.tableOperations().create(accumuloTable);
    } catch (AccumuloException | AccumuloSecurityException | TableExistsException e) {
      throw new IllegalStateException("Failed to create Accumulo table " + accumuloTable, e);
    }

    try {
      conn.tableOperations().addSplits(accumuloTable, IndexEnv.getAccumuloDefaultSplits());
    } catch (AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
      throw new IllegalStateException("Failed to add splits to Accumulo table " + accumuloTable, e);
    }
  }

  public void setFluoTableSplits() {
    final String table = fluoConfig.getAccumuloTable();
    try {
      conn.tableOperations().addSplits(table, IndexEnv.getAccumuloDefaultSplits());
    } catch (AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
      throw new IllegalStateException("Failed to add splits to Fluo's Accumulo table " + table, e);
    }
  }

  public void configureApplication(FluoConfiguration appConfig) {
    FluoApp.configureApplication(appConfig,
        new TableInfo(fluoConfig.getAccumuloInstance(), fluoConfig.getAccumuloZookeepers(),
            fluoConfig.getAccumuloUser(), fluoConfig.getAccumuloPassword(), accumuloTable),
        FluoApp.NUM_BUCKETS);
  }

  public void initializeIndexes(JavaSparkContext ctx, JavaRDD<Page> pages, IndexStats stats)
      throws Exception {
    // Create the Accumulo index from pages RDD
    JavaPairRDD<RowColumn, Bytes> accumuloIndex = IndexUtil.createAccumuloIndex(stats, pages);

    // Create a Fluo index by filtering a subset of data from Accumulo index
    JavaPairRDD<RowColumn, Bytes> fluoIndex =
        IndexUtil.createFluoIndex(accumuloIndex, FluoApp.NUM_BUCKETS);

    // Load the indexes into Fluo and Accumulo
    saveRowColBytesToFluo(ctx, fluoIndex);
    saveRowColBytesToAccumulo(ctx, accumuloIndex);
  }

  public void saveRowColBytesToFluo(JavaSparkContext ctx, JavaPairRDD<RowColumn, Bytes> data)
      throws Exception {
    IndexUtil.saveRowColBytesToFluo(data, ctx, conn, fluoConfig, fluoTempDir, failuresDir);
  }

  public void saveKeyValueToFluo(JavaSparkContext ctx, JavaPairRDD<Key, Value> data)
      throws Exception {
    IndexUtil.saveKeyValueToFluo(data, ctx, conn, fluoConfig, fluoTempDir, failuresDir);
  }

  public void saveRowColBytesToAccumulo(JavaSparkContext ctx, JavaPairRDD<RowColumn, Bytes> data)
      throws Exception {
    IndexUtil.saveRowColBytesToAccumulo(data, ctx, conn, accumuloTempDir, failuresDir,
        accumuloTable);
  }

  public void saveKeyValueToAccumulo(JavaSparkContext ctx, JavaPairRDD<Key, Value> data)
      throws Exception {
    IndexUtil.saveKeyValueToAccumulo(data, ctx, conn, accumuloTempDir, failuresDir, accumuloTable);
  }

  public FileSystem getHdfs() {
    return hdfs;
  }

  public Path getFluoTempDir() {
    return fluoTempDir;
  }

  public Path getAccumuloTempDir() {
    return accumuloTempDir;
  }

  public Path getHadoopTempDir() {
    return hadoopTempDir;
  }

  public Path getFailuresDir() {
    return failuresDir;
  }

  public Connector getAccumuloConnector() {
    return conn;
  }

  public FluoConfiguration getFluoConfig() {
    return fluoConfig;
  }
}