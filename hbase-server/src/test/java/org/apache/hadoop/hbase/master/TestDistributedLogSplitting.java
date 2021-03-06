/**
 *
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
package org.apache.hadoop.hbase.master;

import static org.apache.hadoop.hbase.SplitLogCounters.tot_mgr_wait_for_zk_delete;
import static org.apache.hadoop.hbase.SplitLogCounters.tot_wkr_final_transition_failed;
import static org.apache.hadoop.hbase.SplitLogCounters.tot_wkr_preempt_task;
import static org.apache.hadoop.hbase.SplitLogCounters.tot_wkr_task_acquired;
import static org.apache.hadoop.hbase.SplitLogCounters.tot_wkr_task_done;
import static org.apache.hadoop.hbase.SplitLogCounters.tot_wkr_task_err;
import static org.apache.hadoop.hbase.SplitLogCounters.tot_wkr_task_resigned;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.SplitLogCounters;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.Waiter;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.coordination.ZKSplitLogManagerCoordination;
import org.apache.hadoop.hbase.ipc.ServerNotRunningYetException;
import org.apache.hadoop.hbase.master.SplitLogManager.TaskBatch;
import org.apache.hadoop.hbase.master.assignment.RegionStates;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.MultiVersionConcurrencyControl;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.testclassification.MasterTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.JVMClusterUtil.MasterThread;
import org.apache.hadoop.hbase.util.JVMClusterUtil.RegionServerThread;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.wal.AbstractFSWALProvider;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.wal.WALEdit;
import org.apache.hadoop.hbase.wal.WALFactory;
import org.apache.hadoop.hbase.wal.WALKeyImpl;
import org.apache.hadoop.hbase.wal.WALSplitter;
import org.apache.hadoop.hbase.zookeeper.MiniZooKeeperCluster;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZKWatcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;

import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;

@Category({MasterTests.class, LargeTests.class})
@SuppressWarnings("deprecation")
public class TestDistributedLogSplitting {
  private static final Log LOG = LogFactory.getLog(TestSplitLogManager.class);
  static {
    // Uncomment the following line if more verbosity is needed for
    // debugging (see HBASE-12285 for details).
    //Logger.getLogger("org.apache.hadoop.hbase").setLevel(Level.DEBUG);

    // test ThreeRSAbort fails under hadoop2 (2.0.2-alpha) if shortcircuit-read (scr) is on. this
    // turns it off for this test.  TODO: Figure out why scr breaks recovery.
    System.setProperty("hbase.tests.use.shortcircuit.reads", "false");

  }

  @Rule
  public TestName testName = new TestName();
  TableName tableName;

  // Start a cluster with 2 masters and 6 regionservers
  static final int NUM_MASTERS = 2;
  static final int NUM_RS = 5;
  static byte[] COLUMN_FAMILY = Bytes.toBytes("family");

  MiniHBaseCluster cluster;
  HMaster master;
  Configuration conf;
  static Configuration originalConf;
  static HBaseTestingUtility TEST_UTIL;
  static MiniZooKeeperCluster zkCluster;

  @Rule
  public TestName name = new TestName();

  @BeforeClass
  public static void setup() throws Exception {
    TEST_UTIL = new HBaseTestingUtility(HBaseConfiguration.create());
    zkCluster = TEST_UTIL.startMiniZKCluster();
    originalConf = TEST_UTIL.getConfiguration();
  }

  @AfterClass
  public static void tearDown() throws IOException {
    TEST_UTIL.shutdownMiniZKCluster();
    TEST_UTIL.shutdownMiniDFSCluster();
    TEST_UTIL.shutdownMiniHBaseCluster();
  }

  private void startCluster(int num_rs) throws Exception {
    SplitLogCounters.resetCounters();
    LOG.info("Starting cluster");
    conf.getLong("hbase.splitlog.max.resubmit", 0);
    // Make the failure test faster
    conf.setInt("zookeeper.recovery.retry", 0);
    conf.setInt(HConstants.REGIONSERVER_INFO_PORT, -1);
    conf.setFloat(HConstants.LOAD_BALANCER_SLOP_KEY, (float) 100.0); // no load balancing
    conf.setInt("hbase.regionserver.wal.max.splitters", 3);
    conf.setInt(HConstants.REGION_SERVER_HIGH_PRIORITY_HANDLER_COUNT, 10);
    TEST_UTIL.shutdownMiniHBaseCluster();
    TEST_UTIL = new HBaseTestingUtility(conf);
    TEST_UTIL.setZkCluster(zkCluster);
    TEST_UTIL.startMiniHBaseCluster(NUM_MASTERS, num_rs);
    cluster = TEST_UTIL.getHBaseCluster();
    LOG.info("Waiting for active/ready master");
    cluster.waitForActiveAndReadyMaster();
    master = cluster.getMaster();
    while (cluster.getLiveRegionServerThreads().size() < num_rs) {
      Threads.sleep(10);
    }
  }

  @Before
  public void before() throws Exception {
    // refresh configuration
    conf = HBaseConfiguration.create(originalConf);
    tableName = TableName.valueOf(testName.getMethodName());
  }

  @After
  public void after() throws Exception {
    try {
      if (TEST_UTIL.getHBaseCluster() != null) {
        for (MasterThread mt : TEST_UTIL.getHBaseCluster().getLiveMasterThreads()) {
          mt.getMaster().abort("closing...", null);
        }
      }
      TEST_UTIL.shutdownMiniHBaseCluster();
    } finally {
      TEST_UTIL.getTestFileSystem().delete(FSUtils.getRootDir(TEST_UTIL.getConfiguration()), true);
      ZKUtil.deleteNodeRecursively(TEST_UTIL.getZooKeeperWatcher(), "/hbase");
    }
  }

  @Test (timeout=300000)
  public void testRecoveredEdits() throws Exception {
    conf.setLong("hbase.regionserver.hlog.blocksize", 30 * 1024); // create more than one wal
    startCluster(NUM_RS);

    final int NUM_LOG_LINES = 10000;
    final SplitLogManager slm = master.getMasterWalManager().getSplitLogManager();
    // turn off load balancing to prevent regions from moving around otherwise
    // they will consume recovered.edits
    master.balanceSwitch(false);
    FileSystem fs = master.getMasterFileSystem().getFileSystem();

    List<RegionServerThread> rsts = cluster.getLiveRegionServerThreads();

    Path rootdir = FSUtils.getRootDir(conf);

    int numRegions = 50;
    Table t = installTable(new ZKWatcher(conf, "table-creation", null), numRegions);
    try {
      TableName table = t.getName();
      List<RegionInfo> regions = null;
      HRegionServer hrs = null;
      for (int i = 0; i < NUM_RS; i++) {
        hrs = rsts.get(i).getRegionServer();
        regions = ProtobufUtil.getOnlineRegions(hrs.getRSRpcServices());
        // At least one RS will have >= to average number of regions.
        if (regions.size() >= numRegions/NUM_RS) break;
      }
      final Path logDir = new Path(rootdir, AbstractFSWALProvider.getWALDirectoryName(hrs
          .getServerName().toString()));

      LOG.info("#regions = " + regions.size());
      Iterator<RegionInfo> it = regions.iterator();
      while (it.hasNext()) {
        RegionInfo region = it.next();
        if (region.getTable().getNamespaceAsString()
            .equals(NamespaceDescriptor.SYSTEM_NAMESPACE_NAME_STR)) {
          it.remove();
        }
      }

      makeWAL(hrs, regions, NUM_LOG_LINES, 100);

      slm.splitLogDistributed(logDir);

      int count = 0;
      for (RegionInfo hri : regions) {
        Path tdir = FSUtils.getTableDir(rootdir, table);
        Path editsdir = WALSplitter.getRegionDirRecoveredEditsDir(
            HRegion.getRegionDir(tdir, hri.getEncodedName()));
        LOG.debug("checking edits dir " + editsdir);
        FileStatus[] files = fs.listStatus(editsdir, new PathFilter() {
          @Override
          public boolean accept(Path p) {
            if (WALSplitter.isSequenceIdFile(p)) {
              return false;
            }
            return true;
          }
        });
        assertTrue(
            "edits dir should have more than a single file in it. instead has " + files.length,
            files.length > 1);
        for (int i = 0; i < files.length; i++) {
          int c = countWAL(files[i].getPath(), fs, conf);
          count += c;
        }
        LOG.info(count + " edits in " + files.length + " recovered edits files.");
      }

      // check that the log file is moved
      assertFalse(fs.exists(logDir));
      assertEquals(NUM_LOG_LINES, count);
    } finally {
      if (t != null) t.close();
    }
  }

  @Test(timeout = 300000)
  public void testMasterStartsUpWithLogSplittingWork() throws Exception {
    conf.setInt(ServerManager.WAIT_ON_REGIONSERVERS_MINTOSTART, NUM_RS - 1);
    startCluster(NUM_RS);

    final int NUM_REGIONS_TO_CREATE = 40;
    final int NUM_LOG_LINES = 1000;
    // turn off load balancing to prevent regions from moving around otherwise
    // they will consume recovered.edits
    master.balanceSwitch(false);

    final ZKWatcher zkw = new ZKWatcher(conf, "table-creation", null);
    Table ht = installTable(zkw, NUM_REGIONS_TO_CREATE);
    try {
      HRegionServer hrs = findRSToKill(false);
      List<RegionInfo> regions = ProtobufUtil.getOnlineRegions(hrs.getRSRpcServices());
      makeWAL(hrs, regions, NUM_LOG_LINES, 100);

      // abort master
      abortMaster(cluster);

      // abort RS
      LOG.info("Aborting region server: " + hrs.getServerName());
      hrs.abort("testing");

      // wait for abort completes
      TEST_UTIL.waitFor(120000, 200, new Waiter.Predicate<Exception>() {
        @Override
        public boolean evaluate() throws Exception {
          return (cluster.getLiveRegionServerThreads().size() <= (NUM_RS - 1));
        }
      });

      Thread.sleep(2000);
      LOG.info("Current Open Regions:"
          + HBaseTestingUtility.getAllOnlineRegions(cluster).size());

      // wait for abort completes
      TEST_UTIL.waitFor(120000, 200, new Waiter.Predicate<Exception>() {
        @Override
        public boolean evaluate() throws Exception {
          return (HBaseTestingUtility.getAllOnlineRegions(cluster).size()
              >= (NUM_REGIONS_TO_CREATE + 1));
        }
      });

      LOG.info("Current Open Regions After Master Node Starts Up:"
          + HBaseTestingUtility.getAllOnlineRegions(cluster).size());

      assertEquals(NUM_LOG_LINES, TEST_UTIL.countRows(ht));
    } finally {
      if (ht != null) ht.close();
      if (zkw != null) zkw.close();
    }
  }

  /**
   * The original intention of this test was to force an abort of a region
   * server and to make sure that the failure path in the region servers is
   * properly evaluated. But it is difficult to ensure that the region server
   * doesn't finish the log splitting before it aborts. Also now, there is
   * this code path where the master will preempt the region server when master
   * detects that the region server has aborted.
   * @throws Exception
   */
  // Was marked flaky before Distributed Log Replay cleanup.
  @Test (timeout=300000)
  public void testWorkerAbort() throws Exception {
    LOG.info("testWorkerAbort");
    startCluster(3);
    final int NUM_LOG_LINES = 10000;
    final SplitLogManager slm = master.getMasterWalManager().getSplitLogManager();
    FileSystem fs = master.getMasterFileSystem().getFileSystem();

    final List<RegionServerThread> rsts = cluster.getLiveRegionServerThreads();
    HRegionServer hrs = findRSToKill(false);
    Path rootdir = FSUtils.getRootDir(conf);
    final Path logDir = new Path(rootdir,
      AbstractFSWALProvider.getWALDirectoryName(hrs.getServerName().toString()));

    Table t = installTable(new ZKWatcher(conf, "table-creation", null), 40);
    try {
      makeWAL(hrs, ProtobufUtil.getOnlineRegions(hrs.getRSRpcServices()), NUM_LOG_LINES, 100);

      new Thread() {
        @Override
        public void run() {
          waitForCounter(tot_wkr_task_acquired, 0, 1, 1000);
          for (RegionServerThread rst : rsts) {
            rst.getRegionServer().abort("testing");
            break;
          }
        }
      }.start();
      // slm.splitLogDistributed(logDir);
      FileStatus[] logfiles = fs.listStatus(logDir);
      TaskBatch batch = new TaskBatch();
      slm.enqueueSplitTask(logfiles[0].getPath().toString(), batch);
      //waitForCounter but for one of the 2 counters
      long curt = System.currentTimeMillis();
      long waitTime = 80000;
      long endt = curt + waitTime;
      while (curt < endt) {
        if ((tot_wkr_task_resigned.sum() + tot_wkr_task_err.sum() +
            tot_wkr_final_transition_failed.sum() + tot_wkr_task_done.sum() +
            tot_wkr_preempt_task.sum()) == 0) {
          Thread.yield();
          curt = System.currentTimeMillis();
        } else {
          assertTrue(1 <= (tot_wkr_task_resigned.sum() + tot_wkr_task_err.sum() +
              tot_wkr_final_transition_failed.sum() + tot_wkr_task_done.sum() +
              tot_wkr_preempt_task.sum()));
          return;
        }
      }
      fail("none of the following counters went up in " + waitTime +
          " milliseconds - " +
          "tot_wkr_task_resigned, tot_wkr_task_err, " +
          "tot_wkr_final_transition_failed, tot_wkr_task_done, " +
          "tot_wkr_preempt_task");
    } finally {
      if (t != null) t.close();
    }
  }

  @Test (timeout=300000)
  public void testThreeRSAbort() throws Exception {
    LOG.info("testThreeRSAbort");
    final int NUM_REGIONS_TO_CREATE = 40;
    final int NUM_ROWS_PER_REGION = 100;

    startCluster(NUM_RS); // NUM_RS=6.

    final ZKWatcher zkw = new ZKWatcher(conf, "distributed log splitting test", null);

    Table table = installTable(zkw, NUM_REGIONS_TO_CREATE);
    try {
      populateDataInTable(NUM_ROWS_PER_REGION);

      List<RegionServerThread> rsts = cluster.getLiveRegionServerThreads();
      assertEquals(NUM_RS, rsts.size());
      cluster.killRegionServer(rsts.get(0).getRegionServer().getServerName());
      cluster.killRegionServer(rsts.get(1).getRegionServer().getServerName());
      cluster.killRegionServer(rsts.get(2).getRegionServer().getServerName());

      long start = EnvironmentEdgeManager.currentTime();
      while (cluster.getLiveRegionServerThreads().size() > (NUM_RS - 3)) {
        if (EnvironmentEdgeManager.currentTime() - start > 60000) {
          fail("Timed out waiting for server aborts.");
        }
        Thread.sleep(200);
      }
      TEST_UTIL.waitUntilAllRegionsAssigned(tableName);
      assertEquals(NUM_REGIONS_TO_CREATE * NUM_ROWS_PER_REGION, TEST_UTIL.countRows(table));
    } finally {
      if (table != null) table.close();
      if (zkw != null) zkw.close();
    }
  }

  @Test(timeout=30000)
  public void testDelayedDeleteOnFailure() throws Exception {
    LOG.info("testDelayedDeleteOnFailure");
    startCluster(1);
    final SplitLogManager slm = master.getMasterWalManager().getSplitLogManager();
    final FileSystem fs = master.getMasterFileSystem().getFileSystem();
    final Path logDir = new Path(new Path(FSUtils.getRootDir(conf), HConstants.HREGION_LOGDIR_NAME),
        ServerName.valueOf("x", 1, 1).toString());
    fs.mkdirs(logDir);
    ExecutorService executor = null;
    try {
      final Path corruptedLogFile = new Path(logDir, "x");
      FSDataOutputStream out;
      out = fs.create(corruptedLogFile);
      out.write(0);
      out.write(Bytes.toBytes("corrupted bytes"));
      out.close();
      ZKSplitLogManagerCoordination coordination =
          (ZKSplitLogManagerCoordination) (master.getCoordinatedStateManager())
              .getSplitLogManagerCoordination();
      coordination.setIgnoreDeleteForTesting(true);
      executor = Executors.newSingleThreadExecutor();
      Runnable runnable = new Runnable() {
        @Override
        public void run() {
          try {
            // since the logDir is a fake, corrupted one, so the split log worker
            // will finish it quickly with error, and this call will fail and throw
            // an IOException.
            slm.splitLogDistributed(logDir);
          } catch (IOException ioe) {
            try {
              assertTrue(fs.exists(corruptedLogFile));
              // this call will block waiting for the task to be removed from the
              // tasks map which is not going to happen since ignoreZKDeleteForTesting
              // is set to true, until it is interrupted.
              slm.splitLogDistributed(logDir);
            } catch (IOException e) {
              assertTrue(Thread.currentThread().isInterrupted());
              return;
            }
            fail("did not get the expected IOException from the 2nd call");
          }
          fail("did not get the expected IOException from the 1st call");
        }
      };
      Future<?> result = executor.submit(runnable);
      try {
        result.get(2000, TimeUnit.MILLISECONDS);
      } catch (TimeoutException te) {
        // it is ok, expected.
      }
      waitForCounter(tot_mgr_wait_for_zk_delete, 0, 1, 10000);
      executor.shutdownNow();
      executor = null;

      // make sure the runnable is finished with no exception thrown.
      result.get();
    } finally {
      if (executor != null) {
        // interrupt the thread in case the test fails in the middle.
        // it has no effect if the thread is already terminated.
        executor.shutdownNow();
      }
      fs.delete(logDir, true);
    }
  }

  @Test(timeout = 300000)
  public void testReadWriteSeqIdFiles() throws Exception {
    LOG.info("testReadWriteSeqIdFiles");
    startCluster(2);
    final ZKWatcher zkw = new ZKWatcher(conf, "table-creation", null);
    Table ht = installTable(zkw, 10);
    try {
      FileSystem fs = master.getMasterFileSystem().getFileSystem();
      Path tableDir = FSUtils.getTableDir(FSUtils.getRootDir(conf), TableName.valueOf(name.getMethodName()));
      List<Path> regionDirs = FSUtils.getRegionDirs(fs, tableDir);
      long newSeqId = WALSplitter.writeRegionSequenceIdFile(fs, regionDirs.get(0), 1L, 1000L);
      WALSplitter.writeRegionSequenceIdFile(fs, regionDirs.get(0) , 1L, 1000L);
      assertEquals(newSeqId + 2000,
          WALSplitter.writeRegionSequenceIdFile(fs, regionDirs.get(0), 3L, 1000L));

      Path editsdir = WALSplitter.getRegionDirRecoveredEditsDir(regionDirs.get(0));
      FileStatus[] files = FSUtils.listStatus(fs, editsdir, new PathFilter() {
        @Override
        public boolean accept(Path p) {
          return WALSplitter.isSequenceIdFile(p);
        }
      });
      // only one seqid file should exist
      assertEquals(1, files.length);

      // verify all seqId files aren't treated as recovered.edits files
      NavigableSet<Path> recoveredEdits =
          WALSplitter.getSplitEditFilesSorted(fs, regionDirs.get(0));
      assertEquals(0, recoveredEdits.size());
    } finally {
      if (ht != null) ht.close();
      if (zkw != null) zkw.close();
    }
  }

  Table installTable(ZKWatcher zkw, int nrs) throws Exception {
    return installTable(zkw, nrs, 0);
  }

  Table installTable(ZKWatcher zkw, int nrs, int existingRegions) throws Exception {
    // Create a table with regions
    byte [] family = Bytes.toBytes("family");
    LOG.info("Creating table with " + nrs + " regions");
    Table table = TEST_UTIL.createMultiRegionTable(tableName, family, nrs);
    int numRegions = -1;
    try (RegionLocator r = TEST_UTIL.getConnection().getRegionLocator(tableName)) {
      numRegions = r.getStartKeys().length;
    }
    assertEquals(nrs, numRegions);
    LOG.info("Waiting for no more RIT\n");
    blockUntilNoRIT(zkw, master);
    // disable-enable cycle to get rid of table's dead regions left behind
    // by createMultiRegions
    LOG.debug("Disabling table\n");
    TEST_UTIL.getAdmin().disableTable(tableName);
    LOG.debug("Waiting for no more RIT\n");
    blockUntilNoRIT(zkw, master);
    NavigableSet<String> regions = HBaseTestingUtility.getAllOnlineRegions(cluster);
    LOG.debug("Verifying only catalog and namespace regions are assigned\n");
    if (regions.size() != 2) {
      for (String oregion : regions)
        LOG.debug("Region still online: " + oregion);
    }
    assertEquals(2 + existingRegions, regions.size());
    LOG.debug("Enabling table\n");
    TEST_UTIL.getAdmin().enableTable(tableName);
    LOG.debug("Waiting for no more RIT\n");
    blockUntilNoRIT(zkw, master);
    LOG.debug("Verifying there are " + numRegions + " assigned on cluster\n");
    regions = HBaseTestingUtility.getAllOnlineRegions(cluster);
    assertEquals(numRegions + 2 + existingRegions, regions.size());
    return table;
  }

  void populateDataInTable(int nrows) throws Exception {
    List<RegionServerThread> rsts = cluster.getLiveRegionServerThreads();
    assertEquals(NUM_RS, rsts.size());

    for (RegionServerThread rst : rsts) {
      HRegionServer hrs = rst.getRegionServer();
      List<RegionInfo> hris = ProtobufUtil.getOnlineRegions(hrs.getRSRpcServices());
      for (RegionInfo hri : hris) {
        if (hri.getTable().isSystemTable()) {
          continue;
        }
        LOG.debug("adding data to rs = " + rst.getName() +
            " region = "+ hri.getRegionNameAsString());
        Region region = hrs.getOnlineRegion(hri.getRegionName());
        assertTrue(region != null);
        putData(region, hri.getStartKey(), nrows, Bytes.toBytes("q"), COLUMN_FAMILY);
      }
    }

    for (MasterThread mt : cluster.getLiveMasterThreads()) {
      HRegionServer hrs = mt.getMaster();
      List<RegionInfo> hris;
      try {
        hris = ProtobufUtil.getOnlineRegions(hrs.getRSRpcServices());
      } catch (ServerNotRunningYetException e) {
        // It's ok: this master may be a backup. Ignored.
        continue;
      }
      for (RegionInfo hri : hris) {
        if (hri.getTable().isSystemTable()) {
          continue;
        }
        LOG.debug("adding data to rs = " + mt.getName() +
            " region = "+ hri.getRegionNameAsString());
        Region region = hrs.getOnlineRegion(hri.getRegionName());
        assertTrue(region != null);
        putData(region, hri.getStartKey(), nrows, Bytes.toBytes("q"), COLUMN_FAMILY);
      }
    }
  }

  public void makeWAL(HRegionServer hrs, List<RegionInfo> regions, int num_edits, int edit_size)
      throws IOException {
    makeWAL(hrs, regions, num_edits, edit_size, true);
  }

  public void makeWAL(HRegionServer hrs, List<RegionInfo> regions,
      int num_edits, int edit_size, boolean cleanShutdown) throws IOException {
    // remove root and meta region
    regions.remove(RegionInfoBuilder.FIRST_META_REGIONINFO);

    for(Iterator<RegionInfo> iter = regions.iterator(); iter.hasNext(); ) {
      RegionInfo regionInfo = iter.next();
      if(regionInfo.getTable().isSystemTable()) {
        iter.remove();
      }
    }
    HTableDescriptor htd = new HTableDescriptor(tableName);
    htd.addFamily(new HColumnDescriptor(COLUMN_FAMILY));
    byte[] value = new byte[edit_size];

    List<RegionInfo> hris = new ArrayList<>();
    for (RegionInfo region : regions) {
      if (region.getTable() != tableName) {
        continue;
      }
      hris.add(region);
    }
    LOG.info("Creating wal edits across " + hris.size() + " regions.");
    for (int i = 0; i < edit_size; i++) {
      value[i] = (byte) ('a' + (i % 26));
    }
    int n = hris.size();
    int[] counts = new int[n];
    // sync every ~30k to line up with desired wal rolls
    final int syncEvery = 30 * 1024 / edit_size;
    MultiVersionConcurrencyControl mvcc = new MultiVersionConcurrencyControl();
    if (n > 0) {
      for (int i = 0; i < num_edits; i += 1) {
        WALEdit e = new WALEdit();
        RegionInfo curRegionInfo = hris.get(i % n);
        final WAL log = hrs.getWAL(curRegionInfo);
        byte[] startRow = curRegionInfo.getStartKey();
        if (startRow == null || startRow.length == 0) {
          startRow = new byte[] { 0, 0, 0, 0, 1 };
        }
        byte[] row = Bytes.incrementBytes(startRow, counts[i % n]);
        row = Arrays.copyOfRange(row, 3, 8); // use last 5 bytes because
        // HBaseTestingUtility.createMultiRegions use 5 bytes key
        byte[] qualifier = Bytes.toBytes("c" + Integer.toString(i));
        e.add(new KeyValue(row, COLUMN_FAMILY, qualifier, System.currentTimeMillis(), value));
        log.append(curRegionInfo,
            new WALKeyImpl(curRegionInfo.getEncodedNameAsBytes(), tableName,
                System.currentTimeMillis(), mvcc), e, true);
        if (0 == i % syncEvery) {
          log.sync();
        }
        counts[i % n] += 1;
      }
    }
    // done as two passes because the regions might share logs. shutdown is idempotent, but sync
    // will cause errors if done after.
    for (RegionInfo info : hris) {
      final WAL log = hrs.getWAL(info);
      log.sync();
    }
    if (cleanShutdown) {
      for (RegionInfo info : hris) {
        final WAL log = hrs.getWAL(info);
        log.shutdown();
      }
    }
    for (int i = 0; i < n; i++) {
      LOG.info("region " + hris.get(i).getRegionNameAsString() + " has " + counts[i] + " edits");
    }
    return;
  }

  private int countWAL(Path log, FileSystem fs, Configuration conf)
      throws IOException {
    int count = 0;
    WAL.Reader in = WALFactory.createReader(fs, log, conf);
    try {
      WAL.Entry e;
      while ((e = in.next()) != null) {
        if (!WALEdit.isMetaEditFamily(e.getEdit().getCells().get(0))) {
          count++;
        }
      }
    } finally {
      try {
        in.close();
      } catch (IOException exception) {
        LOG.warn("Problem closing wal: " + exception.getMessage());
        LOG.debug("exception details.", exception);
      }
    }
    return count;
  }

  private void blockUntilNoRIT(ZKWatcher zkw, HMaster master) throws Exception {
    TEST_UTIL.waitUntilNoRegionsInTransition(60000);
  }

  private void putData(Region region, byte[] startRow, int numRows, byte [] qf,
      byte [] ...families)
      throws IOException {
    for(int i = 0; i < numRows; i++) {
      Put put = new Put(Bytes.add(startRow, Bytes.toBytes(i)));
      for(byte [] family : families) {
        put.addColumn(family, qf, null);
      }
      region.put(put);
    }
  }

  private void waitForCounter(LongAdder ctr, long oldval, long newval,
      long timems) {
    long curt = System.currentTimeMillis();
    long endt = curt + timems;
    while (curt < endt) {
      if (ctr.sum() == oldval) {
        Thread.yield();
        curt = System.currentTimeMillis();
      } else {
        assertEquals(newval, ctr.sum());
        return;
      }
    }
    assertTrue(false);
  }

  private void abortMaster(MiniHBaseCluster cluster) throws InterruptedException {
    for (MasterThread mt : cluster.getLiveMasterThreads()) {
      if (mt.getMaster().isActiveMaster()) {
        mt.getMaster().abort("Aborting for tests", new Exception("Trace info"));
        mt.join();
        break;
      }
    }
    LOG.debug("Master is aborted");
  }

  /**
   * Find a RS that has regions of a table.
   * @param hasMetaRegion when true, the returned RS has hbase:meta region as well
   */
  private HRegionServer findRSToKill(boolean hasMetaRegion) throws Exception {
    List<RegionServerThread> rsts = cluster.getLiveRegionServerThreads();
    List<RegionInfo> regions = null;
    HRegionServer hrs = null;

    for (RegionServerThread rst: rsts) {
      hrs = rst.getRegionServer();
      while (rst.isAlive() && !hrs.isOnline()) {
        Thread.sleep(100);
      }
      if (!rst.isAlive()) {
        continue;
      }
      boolean isCarryingMeta = false;
      boolean foundTableRegion = false;
      regions = ProtobufUtil.getOnlineRegions(hrs.getRSRpcServices());
      for (RegionInfo region : regions) {
        if (region.isMetaRegion()) {
          isCarryingMeta = true;
        }
        if (region.getTable() == tableName) {
          foundTableRegion = true;
        }
        if (foundTableRegion && (isCarryingMeta || !hasMetaRegion)) {
          break;
        }
      }
      if (isCarryingMeta && hasMetaRegion) {
        // clients ask for a RS with META
        if (!foundTableRegion) {
          final HRegionServer destRS = hrs;
          // the RS doesn't have regions of the specified table so we need move one to this RS
          List<RegionInfo> tableRegions = TEST_UTIL.getAdmin().getRegions(tableName);
          final RegionInfo hri = tableRegions.get(0);
          TEST_UTIL.getAdmin().move(hri.getEncodedNameAsBytes(),
              Bytes.toBytes(destRS.getServerName().getServerName()));
          // wait for region move completes
          final RegionStates regionStates =
              TEST_UTIL.getHBaseCluster().getMaster().getAssignmentManager().getRegionStates();
          TEST_UTIL.waitFor(45000, 200, new Waiter.Predicate<Exception>() {
            @Override
            public boolean evaluate() throws Exception {
              ServerName sn = regionStates.getRegionServerOfRegion(hri);
              return (sn != null && sn.equals(destRS.getServerName()));
            }
          });
        }
        return hrs;
      } else if (hasMetaRegion || isCarryingMeta) {
        continue;
      }
      if (foundTableRegion) break;
    }

    return hrs;
  }
}
