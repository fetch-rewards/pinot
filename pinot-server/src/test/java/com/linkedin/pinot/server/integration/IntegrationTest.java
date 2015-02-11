package com.linkedin.pinot.server.integration;

import com.linkedin.pinot.util.TestUtils;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.linkedin.pinot.common.query.QueryExecutor;
import com.linkedin.pinot.common.request.AggregationInfo;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.request.FilterQuery;
import com.linkedin.pinot.common.request.InstanceRequest;
import com.linkedin.pinot.common.request.QuerySource;
import com.linkedin.pinot.common.segment.ReadMode;
import com.linkedin.pinot.common.utils.DataTable;
import com.linkedin.pinot.core.data.manager.FileBasedInstanceDataManager;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.indexsegment.columnar.ColumnarSegmentLoader;
import com.linkedin.pinot.core.indexsegment.generator.SegmentGeneratorConfig;
import com.linkedin.pinot.core.segment.creator.SegmentIndexCreationDriver;
import com.linkedin.pinot.core.segment.creator.impl.SegmentCreationDriverFactory;
import com.linkedin.pinot.segments.v1.creator.SegmentTestUtils;
import com.linkedin.pinot.server.conf.ServerConf;
import com.linkedin.pinot.server.starter.ServerInstance;


public class IntegrationTest {

  private final String SMALL_AVRO_DATA = "data/simpleData200001.avro";
  private static File INDEXES_DIR = new File("/tmp/IntegrationTestList-" + System.currentTimeMillis());

  private List<IndexSegment> _indexSegmentList = new ArrayList<IndexSegment>();

  private static Logger LOGGER = LoggerFactory.getLogger(IntegrationTest.class);

  public static final String PINOT_PROPERTIES = "pinot.properties";

  private static ServerConf _serverConf;
  private static ServerInstance _serverInstance;
  private static QueryExecutor _queryExecutor;

  @BeforeTest
  public void setUp() throws Exception {
    //Process Command Line to get config and port
    FileUtils.deleteDirectory(new File("/tmp/pinot/test1"));
    setupSegmentList();
    File confFile = new File(TestUtils.getFileFromResourceUrl(InstanceServerStarter.class.getClassLoader().getResource("conf/" + PINOT_PROPERTIES)));
    // build _serverConf
    PropertiesConfiguration serverConf = new PropertiesConfiguration();
    serverConf.setDelimiterParsingDisabled(false);
    serverConf.load(confFile);
    _serverConf = new ServerConf(serverConf);

    LOGGER.info("Trying to create a new ServerInstance!");
    _serverInstance = new ServerInstance();
    LOGGER.info("Trying to initial ServerInstance!");
    _serverInstance.init(_serverConf);
    LOGGER.info("Trying to start ServerInstance!");
    _serverInstance.start();
    _queryExecutor = _serverInstance.getQueryExecutor();

    FileBasedInstanceDataManager instanceDataManager = (FileBasedInstanceDataManager) _serverInstance.getInstanceDataManager();
    for (int i = 0; i < 2; ++i) {
      instanceDataManager.getResourceDataManager("midas");
      instanceDataManager.getResourceDataManager("midas").addSegment(_indexSegmentList.get(i));
    }
  }

  @AfterTest
  public static void Shutdown() {
    _serverInstance.shutDown();
    if (INDEXES_DIR.exists()) {
      FileUtils.deleteQuietly(INDEXES_DIR);
    }
  }

  private void setupSegmentList() throws Exception {
    final URL resource = getClass().getClassLoader().getResource(SMALL_AVRO_DATA);
    final String filePath = TestUtils.getFileFromResourceUrl(resource);
    _indexSegmentList.clear();
    if (INDEXES_DIR.exists()) {
      FileUtils.deleteQuietly(INDEXES_DIR);
    }
    INDEXES_DIR.mkdir();

    for (int i = 0; i < 2; ++i) {
      final File segmentDir = new File(INDEXES_DIR, "segment_" + i);

      final SegmentGeneratorConfig config =
          SegmentTestUtils.getSegmentGenSpecWithSchemAndProjectedColumns(new File(filePath), segmentDir, "dim" + i,
              TimeUnit.DAYS, "midas", "testTable");

      final SegmentIndexCreationDriver driver = SegmentCreationDriverFactory.get(null);
      driver.init(config);
      driver.build();

      System.out.println("built at : " + segmentDir.getAbsolutePath());
    }
    _indexSegmentList.add(ColumnarSegmentLoader.load(new File(new File(INDEXES_DIR, "segment_0"), "midas_testTable_0_9_"), ReadMode.mmap));
    _indexSegmentList.add(ColumnarSegmentLoader.load(new File(new File(INDEXES_DIR, "segment_1"), "midas_testTable_0_99_"), ReadMode.mmap));
  }

  @Test
  public void testWvmpQuery() {

    BrokerRequest brokerRequest = getCountQuery();
    brokerRequest.setFilterQuery(null);
    brokerRequest.setFilterQueryIsSet(false);
    QuerySource querySource = new QuerySource();
    querySource.setResourceName("wvmp");
    querySource.setTableName("testWvmpTable1");
    brokerRequest.setQuerySource(querySource);
    InstanceRequest instanceRequest = new InstanceRequest(0, brokerRequest);

    try {
      DataTable instanceResponse = _queryExecutor.processQuery(instanceRequest);
      System.out.println(instanceResponse.getLong(0, 0));
      System.out.println(instanceResponse.getMetadata().get("timeUsedMs"));
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testCountQuery() {

    BrokerRequest brokerRequest = getCountQuery();

    QuerySource querySource = new QuerySource();
    querySource.setResourceName("midas");
    querySource.setTableName("testTable");
    brokerRequest.setQuerySource(querySource);
    InstanceRequest instanceRequest = new InstanceRequest(0, brokerRequest);
    addMidasSearchSegmentsToInstanceRequest(instanceRequest);
    try {
      DataTable instanceResponse = _queryExecutor.processQuery(instanceRequest);
      System.out.println(instanceResponse.getLong(0, 0));
      System.out.println(instanceResponse.getMetadata().get("timeUsedMs"));
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  private void addMidasSearchSegmentsToInstanceRequest(InstanceRequest instanceRequest) {
    instanceRequest.addToSearchSegments("midas_testTable_0_9_");
    instanceRequest.addToSearchSegments("midas_testTable_0_99_");
  }

  @Test
  public void testSumQuery() {
    BrokerRequest brokerRequest = getSumQuery();

    QuerySource querySource = new QuerySource();
    querySource.setResourceName("midas");
    querySource.setTableName("testTable");
    brokerRequest.setQuerySource(querySource);
    InstanceRequest instanceRequest = new InstanceRequest(0, brokerRequest);
    addMidasSearchSegmentsToInstanceRequest(instanceRequest);
    try {
      DataTable instanceResponse = _queryExecutor.processQuery(instanceRequest);
      System.out.println(instanceResponse.getDouble(0, 0));
      System.out.println(instanceResponse.getMetadata().get("timeUsedMs"));
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }

  }

  @Test
  public void testMaxQuery() {

    BrokerRequest brokerRequest = getMaxQuery();

    QuerySource querySource = new QuerySource();
    querySource.setResourceName("midas");
    querySource.setTableName("testTable");
    brokerRequest.setQuerySource(querySource);
    InstanceRequest instanceRequest = new InstanceRequest(0, brokerRequest);
    addMidasSearchSegmentsToInstanceRequest(instanceRequest);
    try {
      DataTable instanceResponse = _queryExecutor.processQuery(instanceRequest);
      System.out.println(instanceResponse.getDouble(0, 0));
      System.out.println(instanceResponse.getMetadata().get("timeUsedMs"));
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testMinQuery() {
    BrokerRequest brokerRequest = getMinQuery();

    QuerySource querySource = new QuerySource();
    querySource.setResourceName("midas");
    querySource.setTableName("testTable");
    brokerRequest.setQuerySource(querySource);
    InstanceRequest instanceRequest = new InstanceRequest(0, brokerRequest);
    addMidasSearchSegmentsToInstanceRequest(instanceRequest);
    try {
      DataTable instanceResponse = _queryExecutor.processQuery(instanceRequest);
      System.out.println(instanceResponse.getDouble(0, 0));
      System.out.println(instanceResponse.getMetadata().get("timeUsedMs"));
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }

  }

  private BrokerRequest getCountQuery() {
    BrokerRequest query = new BrokerRequest();
    AggregationInfo aggregationInfo = getCountAggregationInfo();
    List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(aggregationInfo);
    query.setAggregationsInfo(aggregationsInfo);
    FilterQuery filterQuery = getFilterQuery();
    query.setFilterQuery(filterQuery);
    return query;
  }

  private BrokerRequest getSumQuery() {
    BrokerRequest query = new BrokerRequest();
    AggregationInfo aggregationInfo = getSumAggregationInfo();
    List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(aggregationInfo);
    query.setAggregationsInfo(aggregationsInfo);
    FilterQuery filterQuery = getFilterQuery();
    query.setFilterQuery(filterQuery);
    return query;
  }

  private BrokerRequest getMaxQuery() {
    BrokerRequest query = new BrokerRequest();
    AggregationInfo aggregationInfo = getMaxAggregationInfo();
    List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(aggregationInfo);
    query.setAggregationsInfo(aggregationsInfo);
    FilterQuery filterQuery = getFilterQuery();
    query.setFilterQuery(filterQuery);
    return query;
  }

  private BrokerRequest getMinQuery() {
    BrokerRequest query = new BrokerRequest();
    AggregationInfo aggregationInfo = getMinAggregationInfo();
    List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(aggregationInfo);
    query.setAggregationsInfo(aggregationsInfo);
    FilterQuery filterQuery = getFilterQuery();
    query.setFilterQuery(filterQuery);
    return query;
  }

  private FilterQuery getFilterQuery() {
    return new FilterQuery();
  }

  private AggregationInfo getCountAggregationInfo() {
    String type = "count";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met");

    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private AggregationInfo getSumAggregationInfo() {
    String type = "sum";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met");

    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private AggregationInfo getMaxAggregationInfo() {
    String type = "max";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met");

    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private AggregationInfo getMinAggregationInfo() {
    String type = "min";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met");

    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }
}
