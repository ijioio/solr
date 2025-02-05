/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.metrics.SolrMetricsContext;
import org.apache.solr.util.stats.MetricUtils;
import org.junit.BeforeClass;
import org.junit.Test;

/** Test for {@link ThinCache}. */
public class TestThinCache extends SolrTestCaseJ4 {

  SolrMetricManager metricManager = new SolrMetricManager();
  String registry = TestUtil.randomSimpleString(random(), 2, 10);
  String scope = TestUtil.randomSimpleString(random(), 2, 10);

  @Test
  public void testSimple() {
    Object cacheScope = new Object();
    ThinCache.NodeLevelCache<Object, Integer, String> backing = new ThinCache.NodeLevelCache<>();
    ThinCache<Object, Integer, String> lfuCache = new ThinCache<>();
    lfuCache.setBacking(cacheScope, backing);
    SolrMetricsContext solrMetricsContext = new SolrMetricsContext(metricManager, registry, "foo");
    lfuCache.initializeMetrics(solrMetricsContext, scope + "-1");

    Object cacheScope2 = new Object();
    ThinCache<Object, Integer, String> newLFUCache = new ThinCache<>();
    newLFUCache.setBacking(cacheScope2, backing);
    newLFUCache.initializeMetrics(solrMetricsContext, scope + "-2");

    Map<String, String> params = new HashMap<>();
    params.put("size", "100");
    params.put("initialSize", "10");

    NoOpRegenerator regenerator = new NoOpRegenerator();
    backing.init(params, null, null);
    Object initObj =
        lfuCache.init(Collections.singletonMap("autowarmCount", "25"), null, regenerator);
    lfuCache.setState(SolrCache.State.LIVE);
    for (int i = 0; i < 101; i++) {
      lfuCache.put(i + 1, Integer.toString(i + 1));
    }
    assertEquals("15", lfuCache.get(15));
    assertEquals("75", lfuCache.get(75));
    assertNull(lfuCache.get(110));
    Map<String, Object> nl = lfuCache.getMetricsMap().getValue();
    assertEquals(3L, nl.get("lookups"));
    assertEquals(2L, nl.get("hits"));
    assertEquals(101L, nl.get("inserts"));

    assertNull(lfuCache.get(1)); // first item put in should be the first out

    // Test autowarming
    newLFUCache.init(Collections.singletonMap("autowarmCount", "25"), initObj, regenerator);
    newLFUCache.warm(null, lfuCache);
    newLFUCache.setState(SolrCache.State.LIVE);

    newLFUCache.put(103, "103");
    assertEquals("15", newLFUCache.get(15));
    assertEquals("75", newLFUCache.get(75));
    assertNull(newLFUCache.get(50));
    nl = newLFUCache.getMetricsMap().getValue();
    assertEquals(3L, nl.get("lookups"));
    assertEquals(2L, nl.get("hits"));
    assertEquals(1L, nl.get("inserts"));
    assertEquals(0L, nl.get("evictions"));

    assertEquals(7L, nl.get("cumulative_lookups"));
    assertEquals(4L, nl.get("cumulative_hits"));
    assertEquals(102L, nl.get("cumulative_inserts"));
  }

  @Test
  public void testInitCore() throws Exception {
    for (int i = 0; i < 20; i++) {
      assertU(adoc("id", Integer.toString(i)));
    }
    assertU(commit());
    assertQ(req("q", "*:*", "fq", "id:0"));
    assertQ(req("q", "*:*", "fq", "id:0"));
    assertQ(req("q", "*:*", "fq", "id:1"));
    Map<String, Object> nodeMetricsSnapshot =
        MetricUtils.convertMetrics(
            h.getCoreContainer().getMetricManager().registry("solr.node"),
            List.of(
                "CACHE.nodeLevelCache/myNodeLevelCacheThin",
                "CACHE.nodeLevelCache/myNodeLevelCache"));
    Map<String, Object> coreMetricsSnapshot =
        MetricUtils.convertMetrics(
            h.getCore().getCoreMetricManager().getRegistry(),
            List.of("CACHE.searcher.filterCache"));

    // check that metrics are accessible, and the core cache writes through to the node-level cache
    Map<String, Number> assertions = Map.of("lookups", 3L, "hits", 1L, "inserts", 2L, "size", 2);
    for (Map.Entry<String, Number> e : assertions.entrySet()) {
      String key = e.getKey();
      Number val = e.getValue();
      assertEquals(
          val, nodeMetricsSnapshot.get("CACHE.nodeLevelCache/myNodeLevelCacheThin.".concat(key)));
      assertEquals(val, coreMetricsSnapshot.get("CACHE.searcher.filterCache.".concat(key)));
    }

    // for the other node-level cache, simply check that metrics are accessible
    assertEquals(0, nodeMetricsSnapshot.get("CACHE.nodeLevelCache/myNodeLevelCache.size"));
  }

  @BeforeClass
  public static void setupSolrHome() throws Exception {
    // make a solr home underneath the test's TEMP_DIR, else we don't have write access to copy in
    // `solr.xml`
    Path tmpFile = createTempDir();

    // make data and conf dirs
    Files.createDirectories(tmpFile.resolve("data"));
    Path confDir = tmpFile.resolve("collection1").resolve("conf");
    Files.createDirectories(confDir);

    // copy over configuration files
    copyXmlToHome(
        tmpFile.toFile(),
        TEST_PATH().resolve("solr-nodelevelcaches.xml").toAbsolutePath().toString());
    Files.copy(
        getFile("solr/collection1/conf/solrconfig-nodelevelcaches.xml").toPath(),
        confDir.resolve("solrconfig.xml"));
    Files.copy(
        getFile("solr/collection1/conf/solrconfig.snippet.randomindexconfig.xml").toPath(),
        confDir.resolve("solrconfig.snippet.randomindexconfig.xml"));
    Files.copy(
        getFile("solr/collection1/conf/schema-minimal.xml").toPath(),
        confDir.resolve("schema.xml"));

    // we want the actual `solr.xml` file to be read, instead of the normal operation, which creates
    // a synthetic "test NodeConfig"
    System.setProperty("solr.tests.loadSolrXml", "true");
    initCore("solrconfig.xml", "schema.xml", tmpFile.toAbsolutePath().toString());
  }
}
