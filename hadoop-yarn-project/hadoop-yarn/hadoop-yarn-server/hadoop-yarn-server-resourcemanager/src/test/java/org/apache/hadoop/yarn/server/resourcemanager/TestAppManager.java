/**
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

package org.apache.hadoop.yarn.server.resourcemanager;

import org.apache.hadoop.util.Lists;
import org.apache.hadoop.util.Sets;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.service.Service;
import org.apache.hadoop.yarn.MockApps;
import org.apache.hadoop.yarn.api.protocolrecords.SubmitApplicationRequest;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ExecutionType;
import org.apache.hadoop.yarn.api.records.ExecutionTypeRequest;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceInformation;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.AsyncDispatcher;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.exceptions.InvalidResourceRequestException;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.server.resourcemanager.ahs.RMApplicationHistoryWriter;
import org.apache.hadoop.yarn.server.resourcemanager.metrics.SystemMetricsPublisher;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.placement.ApplicationPlacementContext;
import org.apache.hadoop.yarn.server.resourcemanager.placement.PlacementManager;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.MockRMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppMetrics;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppState;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.AMLivelinessMonitor;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.ContainerAllocationExpirer;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacityScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacitySchedulerConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.ManagedParentQueue;
import org.apache.hadoop.yarn.server.resourcemanager.security.ClientToAMTokenSecretManagerInRM;
import org.apache.hadoop.yarn.server.resourcemanager.timelineservice.RMTimelineCollectorManager;
import org.apache.hadoop.yarn.server.security.ApplicationACLsManager;
import org.apache.hadoop.yarn.util.Records;
import org.apache.hadoop.yarn.util.resource.ResourceCalculator;
import org.apache.hadoop.yarn.util.resource.Resources;

import org.apache.hadoop.thirdparty.com.google.common.collect.Maps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentMap;

import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacitySchedulerConfiguration.PREFIX;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testing applications being retired from RM.
 *
 */

public class TestAppManager extends AppManagerTestBase{
  @Rule
  public UseCapacitySchedulerRule shouldUseCs = new UseCapacitySchedulerRule();

  private static final Logger LOG =
      LoggerFactory.getLogger(TestAppManager.class);
  private static RMAppEventType appEventType = RMAppEventType.KILL;

  private static String USER = "user_";
  private static String USER0 = USER + 0;
  private ResourceScheduler scheduler;

  private static final String USER_ID_PREFIX = "userid=";

  public synchronized RMAppEventType getAppEventType() {
    return appEventType;
  } 
  public synchronized void setAppEventType(RMAppEventType newType) {
    appEventType = newType;
  } 


  private static List<RMApp> newRMApps(int n, long time, RMAppState state) {
    List<RMApp> list = Lists.newArrayList();
    for (int i = 0; i < n; ++i) {
      list.add(new MockRMApp(i, time, state));
    }
    return list;
  }

  public RMContext mockRMContext(int n, long time) {
    final List<RMApp> apps = newRMApps(n, time, RMAppState.FINISHED);
    final ConcurrentMap<ApplicationId, RMApp> map = Maps.newConcurrentMap();
    for (RMApp app : apps) {
      map.put(app.getApplicationId(), app);
    }
    Dispatcher rmDispatcher = new AsyncDispatcher();
    ContainerAllocationExpirer containerAllocationExpirer = new ContainerAllocationExpirer(
            rmDispatcher);
    AMLivelinessMonitor amLivelinessMonitor = new AMLivelinessMonitor(
            rmDispatcher);
    AMLivelinessMonitor amFinishingMonitor = new AMLivelinessMonitor(
            rmDispatcher);
    RMApplicationHistoryWriter writer = mock(RMApplicationHistoryWriter.class);
    RMContext context = new RMContextImpl(rmDispatcher,
            containerAllocationExpirer, amLivelinessMonitor, amFinishingMonitor,
            null, null, null, null, null) {
      @Override
      public ConcurrentMap<ApplicationId, RMApp> getRMApps() {
        return map;
      }
    };
    ((RMContextImpl)context).setStateStore(mock(RMStateStore.class));
    metricsPublisher = mock(SystemMetricsPublisher.class);
    context.setSystemMetricsPublisher(metricsPublisher);
    context.setRMApplicationHistoryWriter(writer);
    ((RMContextImpl)context).setYarnConfiguration(new YarnConfiguration());
    return context;
  }

  public class TestAppManagerDispatcher implements
      EventHandler<RMAppManagerEvent> {


    public TestAppManagerDispatcher() {
    }

    @Override
    public void handle(RMAppManagerEvent event) {
       // do nothing
    }   
  }   

  public class TestDispatcher implements
      EventHandler<RMAppEvent> {

    public TestDispatcher() {
    }

    @Override
    public void handle(RMAppEvent event) {
      //RMApp rmApp = this.rmContext.getRMApps().get(appID);
      setAppEventType(event.getType());
      System.out.println("in handle routine " + getAppEventType().toString());
    }   
  }

  protected void addToCompletedApps(TestRMAppManager appMonitor, RMContext rmContext) {
    for (RMApp app : rmContext.getRMApps().values()) {
      if (app.getState() == RMAppState.FINISHED
          || app.getState() == RMAppState.KILLED
          || app.getState() == RMAppState.FAILED) {
        appMonitor.finishApplication(app.getApplicationId());
      }
    }
  }

  private RMContext rmContext;
  private SystemMetricsPublisher metricsPublisher;
  private TestRMAppManager appMonitor;
  private ApplicationSubmissionContext asContext;
  private ApplicationId appId;
  private QueueInfo mockDefaultQueueInfo;

  @SuppressWarnings("deprecation")
  @Before
  public void setUp() throws IOException {
    long now = System.currentTimeMillis();

    rmContext = mockRMContext(1, now - 10);
    rmContext
        .setRMTimelineCollectorManager(mock(RMTimelineCollectorManager.class));

    if (shouldUseCs.useCapacityScheduler()) {
      scheduler = mockResourceScheduler(CapacityScheduler.class);
    } else {
      scheduler = mockResourceScheduler();
    }

    ((RMContextImpl)rmContext).setScheduler(scheduler);

    Configuration conf = new Configuration();
    conf.setBoolean(YarnConfiguration.NODE_LABELS_ENABLED, true);
    ((RMContextImpl) rmContext).setYarnConfiguration(conf);
    ApplicationMasterService masterService =
        new ApplicationMasterService(rmContext, scheduler);
    appMonitor = new TestRMAppManager(rmContext,
        new ClientToAMTokenSecretManagerInRM(), scheduler, masterService,
        new ApplicationACLsManager(conf), conf);

    appId = MockApps.newAppID(1);
    RecordFactory recordFactory = RecordFactoryProvider.getRecordFactory(null);
    asContext =
        recordFactory.newRecordInstance(ApplicationSubmissionContext.class);
    asContext.setApplicationId(appId);
    asContext.setAMContainerSpec(mockContainerLaunchContext(recordFactory));
    asContext.setResource(mockResource());
    asContext.setPriority(Priority.newInstance(0));
    asContext.setQueue("default");
    mockDefaultQueueInfo = mock(QueueInfo.class);
    when(scheduler.getQueueInfo("default", false, false))
        .thenReturn(mockDefaultQueueInfo);

    setupDispatcher(rmContext, conf);
  }

  private static PlacementManager createMockPlacementManager(
      String userRegex, String placementQueue, String placementParentQueue
  ) throws YarnException {
    PlacementManager placementMgr = mock(PlacementManager.class);
    doAnswer(new Answer<ApplicationPlacementContext>() {

      @Override
      public ApplicationPlacementContext answer(InvocationOnMock invocation)
          throws Throwable {
        return new ApplicationPlacementContext(placementQueue, placementParentQueue);
      }

    }).when(placementMgr).placeApplication(
        any(ApplicationSubmissionContext.class),
        matches(userRegex),
        any(Boolean.class));

    return placementMgr;
  }

  private TestRMAppManager createAppManager(RMContext context, Configuration configuration) {
    ApplicationMasterService masterService = new ApplicationMasterService(context,
        context.getScheduler());

    return new TestRMAppManager(context,
        new ClientToAMTokenSecretManagerInRM(),
        context.getScheduler(), masterService,
        new ApplicationACLsManager(configuration), configuration);
  }

  @Test
  public void testQueueSubmitWithACLsEnabledWithQueueMapping()
      throws YarnException {
    YarnConfiguration conf = new YarnConfiguration(new Configuration(false));
    conf.set(YarnConfiguration.YARN_ACL_ENABLE, "true");
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
        ResourceScheduler.class);

    CapacitySchedulerConfiguration csConf = new
        CapacitySchedulerConfiguration(conf, false);
    csConf.set(PREFIX + "root.queues", "default,test");

    csConf.setCapacity("root.default", 50.0f);
    csConf.setMaximumCapacity("root.default", 100.0f);

    csConf.setCapacity("root.test", 50.0f);
    csConf.setMaximumCapacity("root.test", 100.0f);

    csConf.set(PREFIX + "root.acl_submit_applications", " ");
    csConf.set(PREFIX + "root.acl_administer_queue", " ");

    csConf.set(PREFIX + "root.default.acl_submit_applications", " ");
    csConf.set(PREFIX + "root.default.acl_administer_queue", " ");

    csConf.set(PREFIX + "root.test.acl_submit_applications", "test");
    csConf.set(PREFIX + "root.test.acl_administer_queue", "test");

    asContext.setQueue("oldQueue");

    MockRM newMockRM = new MockRM(csConf);
    RMContext newMockRMContext = newMockRM.getRMContext();
    newMockRMContext.setQueuePlacementManager(createMockPlacementManager("test", "test", null));
    TestRMAppManager newAppMonitor = createAppManager(newMockRMContext, conf);

    newAppMonitor.submitApplication(asContext, "test");
    RMApp app = newMockRMContext.getRMApps().get(appId);
    Assert.assertNotNull("app should not be null", app);
    Assert.assertEquals("the queue should be placed on 'test' queue", "test", app.getQueue());

    try {
      asContext.setApplicationId(appId = MockApps.newAppID(2));
      newAppMonitor.submitApplication(asContext, "test1");
      Assert.fail("should fail since test1 does not have permission to submit to queue");
    } catch(YarnException e) {
      assertTrue(e.getCause() instanceof AccessControlException);
    }
  }

  @Test
  public void testQueueSubmitWithACLsEnabledWithQueueMappingForAutoCreatedQueue()
      throws IOException, YarnException {
    YarnConfiguration conf = new YarnConfiguration();
    conf.set(YarnConfiguration.YARN_ACL_ENABLE, "true");
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
        ResourceScheduler.class);

    CapacitySchedulerConfiguration csConf = new CapacitySchedulerConfiguration(
        conf, false);
    csConf.set(PREFIX + "root.queues", "default,managedparent");

    csConf.setCapacity("root.default", 50.0f);
    csConf.setMaximumCapacity("root.default", 100.0f);

    csConf.setCapacity("root.managedparent", 50.0f);
    csConf.setMaximumCapacity("root.managedparent", 100.0f);

    csConf.set(PREFIX + "root.acl_submit_applications", " ");
    csConf.set(PREFIX + "root.acl_administer_queue", " ");

    csConf.set(PREFIX + "root.default.acl_submit_applications", " ");
    csConf.set(PREFIX + "root.default.acl_administer_queue", " ");

    csConf.set(PREFIX + "root.managedparent.acl_administer_queue", "admin");
    csConf.set(PREFIX + "root.managedparent.acl_submit_applications", "user1");

    csConf.setAutoCreateChildQueueEnabled("root.managedparent", true);
    csConf.setAutoCreatedLeafQueueConfigCapacity("root.managedparent", 30f);
    csConf.setAutoCreatedLeafQueueConfigMaxCapacity("root.managedparent", 100f);

    asContext.setQueue("oldQueue");

    MockRM newMockRM = new MockRM(csConf);
    CapacityScheduler cs =
        ((CapacityScheduler) newMockRM.getResourceScheduler());
    ManagedParentQueue managedParentQueue = new ManagedParentQueue(cs.getQueueContext(),
        "managedparent", cs.getQueue("root"), null);
    cs.getCapacitySchedulerQueueManager().addQueue("managedparent",
        managedParentQueue);

    RMContext newMockRMContext = newMockRM.getRMContext();
    newMockRMContext.setQueuePlacementManager(createMockPlacementManager(
        "user1|user2", "user1", "managedparent"));
    TestRMAppManager newAppMonitor = createAppManager(newMockRMContext, conf);

    newAppMonitor.submitApplication(asContext, "user1");
    RMApp app = newMockRMContext.getRMApps().get(appId);
    Assert.assertNotNull("app should not be null", app);
    Assert.assertEquals("the queue should be placed on 'managedparent.user1' queue",
        "managedparent.user1",
        app.getQueue());

    try {
      asContext.setApplicationId(appId = MockApps.newAppID(2));
      newAppMonitor.submitApplication(asContext, "user2");
      Assert.fail("should fail since user2 does not have permission to submit to queue");
    } catch (YarnException e) {
      assertTrue(e.getCause() instanceof AccessControlException);
    }
  }

  @After
  public void tearDown() {
    setAppEventType(RMAppEventType.KILL);
    ((Service)rmContext.getDispatcher()).stop();
    UserGroupInformation.reset();
  }

  @Test
  public void testRMAppRetireNone() throws Exception {
    long now = System.currentTimeMillis();

    // Create such that none of the applications will retire since
    // haven't hit max #
    RMContext rmContext = mockRMContext(10, now - 10);
    Configuration conf = new YarnConfiguration();
    conf.setInt(YarnConfiguration.RM_MAX_COMPLETED_APPLICATIONS, 10);
    TestRMAppManager appMonitor = new TestRMAppManager(rmContext,conf);

    Assert.assertEquals("Number of apps incorrect before checkAppTimeLimit",
        10, rmContext.getRMApps().size());

    // add them to completed apps list
    addToCompletedApps(appMonitor, rmContext);

    // shouldn't  have to many apps
    appMonitor.checkAppNumCompletedLimit();
    Assert.assertEquals("Number of apps incorrect after # completed check", 10,
        rmContext.getRMApps().size());
    Assert.assertEquals("Number of completed apps incorrect after check", 10,
        appMonitor.getCompletedAppsListSize());
    verify(rmContext.getStateStore(), never()).removeApplication(
      isA(RMApp.class));
  }

  @Test
  public void testQueueSubmitWithNoPermission() throws IOException {
    YarnConfiguration conf = new YarnConfiguration();
    conf.set(PREFIX + "root.acl_submit_applications", " ");
    conf.set(PREFIX + "root.acl_administer_queue", " ");

    conf.set(PREFIX + "root.default.acl_submit_applications", " ");
    conf.set(PREFIX + "root.default.acl_administer_queue", " ");
    conf.set(YarnConfiguration.YARN_ACL_ENABLE, "true");
    MockRM mockRM = new MockRM(conf);
    ClientRMService rmService = mockRM.getClientRMService();
    SubmitApplicationRequest req =
        Records.newRecord(SubmitApplicationRequest.class);
    ApplicationSubmissionContext sub =
        Records.newRecord(ApplicationSubmissionContext.class);
    sub.setApplicationId(appId);
    ResourceRequest resReg =
        ResourceRequest.newInstance(Priority.newInstance(0),
            ResourceRequest.ANY, Resource.newInstance(1024, 1), 1);
    sub.setAMContainerResourceRequests(Collections.singletonList(resReg));
    req.setApplicationSubmissionContext(sub);
    sub.setAMContainerSpec(mock(ContainerLaunchContext.class));
    try {
      rmService.submitApplication(req);
    } catch (Exception e) {
      e.printStackTrace();
      if (e instanceof YarnException) {
        assertTrue(e.getCause() instanceof AccessControlException);
      } else {
        Assert.fail("Yarn exception is expected : " + e.getMessage());
      }
    } finally {
      mockRM.close();
    }
  }

  @Test
  public void testRMAppRetireSome() throws Exception {
    long now = System.currentTimeMillis();

    RMContext rmContext = mockRMContext(10, now - 20000);
    Configuration conf = new YarnConfiguration();
    conf.setInt(YarnConfiguration.RM_STATE_STORE_MAX_COMPLETED_APPLICATIONS, 3); 
    conf.setInt(YarnConfiguration.RM_MAX_COMPLETED_APPLICATIONS, 3);
    TestRMAppManager appMonitor = new TestRMAppManager(rmContext, conf);

    Assert.assertEquals("Number of apps incorrect before", 10, rmContext
        .getRMApps().size());

    // add them to completed apps list
    addToCompletedApps(appMonitor, rmContext);

    // shouldn't  have to many apps
    appMonitor.checkAppNumCompletedLimit();
    Assert.assertEquals("Number of apps incorrect after # completed check", 3,
        rmContext.getRMApps().size());
    Assert.assertEquals("Number of completed apps incorrect after check", 3,
        appMonitor.getCompletedAppsListSize());
    verify(rmContext.getStateStore(), times(7)).removeApplication(
      isA(RMApp.class));
  }

  @Test
  public void testRMAppRetireSomeDifferentStates() throws Exception {
    long now = System.currentTimeMillis();

    // these parameters don't matter, override applications below
    RMContext rmContext = mockRMContext(10, now - 20000);
    Configuration conf = new YarnConfiguration();
    conf.setInt(YarnConfiguration.RM_STATE_STORE_MAX_COMPLETED_APPLICATIONS, 2);
    conf.setInt(YarnConfiguration.RM_MAX_COMPLETED_APPLICATIONS, 2);

    TestRMAppManager appMonitor = new TestRMAppManager(rmContext, conf);

    // clear out applications map
    rmContext.getRMApps().clear();
    Assert.assertEquals("map isn't empty", 0, rmContext.getRMApps().size());

    // 6 applications are in final state, 4 are not in final state.
    // / set with various finished states
    RMApp app = new MockRMApp(0, now - 20000, RMAppState.KILLED);
    rmContext.getRMApps().put(app.getApplicationId(), app);
    app = new MockRMApp(1, now - 200000, RMAppState.FAILED);
    rmContext.getRMApps().put(app.getApplicationId(), app);
    app = new MockRMApp(2, now - 30000, RMAppState.FINISHED);
    rmContext.getRMApps().put(app.getApplicationId(), app);
    app = new MockRMApp(3, now - 20000, RMAppState.RUNNING);
    rmContext.getRMApps().put(app.getApplicationId(), app);
    app = new MockRMApp(4, now - 20000, RMAppState.NEW);
    rmContext.getRMApps().put(app.getApplicationId(), app);

    // make sure it doesn't expire these since still running
    app = new MockRMApp(5, now - 10001, RMAppState.KILLED);
    rmContext.getRMApps().put(app.getApplicationId(), app);
    app = new MockRMApp(6, now - 30000, RMAppState.ACCEPTED);
    rmContext.getRMApps().put(app.getApplicationId(), app);
    app = new MockRMApp(7, now - 20000, RMAppState.SUBMITTED);
    rmContext.getRMApps().put(app.getApplicationId(), app);
    app = new MockRMApp(8, now - 10001, RMAppState.FAILED);
    rmContext.getRMApps().put(app.getApplicationId(), app);
    app = new MockRMApp(9, now - 20000, RMAppState.FAILED);
    rmContext.getRMApps().put(app.getApplicationId(), app);

    Assert.assertEquals("Number of apps incorrect before", 10, rmContext
        .getRMApps().size());

    // add them to completed apps list
    addToCompletedApps(appMonitor, rmContext);

    // shouldn't  have to many apps
    appMonitor.checkAppNumCompletedLimit();
    Assert.assertEquals("Number of apps incorrect after # completed check", 6,
        rmContext.getRMApps().size());
    Assert.assertEquals("Number of completed apps incorrect after check", 2,
        appMonitor.getCompletedAppsListSize());
    // 6 applications in final state, 4 of them are removed
    verify(rmContext.getStateStore(), times(4)).removeApplication(
      isA(RMApp.class));
  }

  @Test
  public void testRMAppRetireNullApp() throws Exception {
    long now = System.currentTimeMillis();

    RMContext rmContext = mockRMContext(10, now - 20000);
    TestRMAppManager appMonitor = new TestRMAppManager(rmContext, new Configuration());

    Assert.assertEquals("Number of apps incorrect before", 10, rmContext
        .getRMApps().size());

    appMonitor.finishApplication(null);

    Assert.assertEquals("Number of completed apps incorrect after check", 0,
        appMonitor.getCompletedAppsListSize());
  }

  @Test
  public void testRMAppRetireZeroSetting() throws Exception {
    long now = System.currentTimeMillis();

    RMContext rmContext = mockRMContext(10, now - 20000);
    Configuration conf = new YarnConfiguration();
    conf.setInt(YarnConfiguration.RM_STATE_STORE_MAX_COMPLETED_APPLICATIONS, 0);
    conf.setInt(YarnConfiguration.RM_MAX_COMPLETED_APPLICATIONS, 0);
    TestRMAppManager appMonitor = new TestRMAppManager(rmContext, conf);
    Assert.assertEquals("Number of apps incorrect before", 10, rmContext
        .getRMApps().size());

    addToCompletedApps(appMonitor, rmContext);
    Assert.assertEquals("Number of completed apps incorrect", 10,
        appMonitor.getCompletedAppsListSize());

    appMonitor.checkAppNumCompletedLimit();

    Assert.assertEquals("Number of apps incorrect after # completed check", 0,
        rmContext.getRMApps().size());
    Assert.assertEquals("Number of completed apps incorrect after check", 0,
        appMonitor.getCompletedAppsListSize());
    verify(rmContext.getStateStore(), times(10)).removeApplication(
      isA(RMApp.class));
  }

  @Test
  public void testStateStoreAppLimitLessThanMemoryAppLimit() {
    long now = System.currentTimeMillis();
    final int allApps = 10;
    RMContext rmContext = mockRMContext(allApps, now - 20000);
    Configuration conf = new YarnConfiguration();
    int maxAppsInMemory = 8;
    int maxAppsInStateStore = 4;
    conf.setInt(YarnConfiguration.RM_MAX_COMPLETED_APPLICATIONS, maxAppsInMemory);
    conf.setInt(YarnConfiguration.RM_STATE_STORE_MAX_COMPLETED_APPLICATIONS,
      maxAppsInStateStore);
    TestRMAppManager appMonitor = new TestRMAppManager(rmContext, conf);

    addToCompletedApps(appMonitor, rmContext);
    Assert.assertEquals("Number of completed apps incorrect", allApps,
        appMonitor.getCompletedAppsListSize());
    appMonitor.checkAppNumCompletedLimit();

    Assert.assertEquals("Number of apps incorrect after # completed check",
      maxAppsInMemory, rmContext.getRMApps().size());
    Assert.assertEquals("Number of completed apps incorrect after check",
      maxAppsInMemory, appMonitor.getCompletedAppsListSize());

    int numRemoveAppsFromStateStore = 10 - maxAppsInStateStore;
    verify(rmContext.getStateStore(), times(numRemoveAppsFromStateStore))
      .removeApplication(isA(RMApp.class));
    Assert.assertEquals(maxAppsInStateStore,
      appMonitor.getNumberOfCompletedAppsInStateStore());
  }

  @Test
  public void testStateStoreAppLimitGreaterThanMemoryAppLimit() {
    long now = System.currentTimeMillis();
    final int allApps = 10;
    RMContext rmContext = mockRMContext(allApps, now - 20000);
    Configuration conf = new YarnConfiguration();
    int maxAppsInMemory = 8;
    conf.setInt(YarnConfiguration.RM_MAX_COMPLETED_APPLICATIONS, maxAppsInMemory);
    // greater than maxCompletedAppsInMemory, reset to RM_MAX_COMPLETED_APPLICATIONS.
    conf.setInt(YarnConfiguration.RM_STATE_STORE_MAX_COMPLETED_APPLICATIONS, 1000);
    TestRMAppManager appMonitor = new TestRMAppManager(rmContext, conf);

    addToCompletedApps(appMonitor, rmContext);
    Assert.assertEquals("Number of completed apps incorrect", allApps,
        appMonitor.getCompletedAppsListSize());
    appMonitor.checkAppNumCompletedLimit();

    int numRemoveApps = allApps - maxAppsInMemory;
    Assert.assertEquals("Number of apps incorrect after # completed check",
      maxAppsInMemory, rmContext.getRMApps().size());
    Assert.assertEquals("Number of completed apps incorrect after check",
      maxAppsInMemory, appMonitor.getCompletedAppsListSize());
    verify(rmContext.getStateStore(), times(numRemoveApps)).removeApplication(
      isA(RMApp.class));
    Assert.assertEquals(maxAppsInMemory,
      appMonitor.getNumberOfCompletedAppsInStateStore());
  }

  protected void setupDispatcher(RMContext rmContext, Configuration conf) {
    TestDispatcher testDispatcher = new TestDispatcher();
    TestAppManagerDispatcher testAppManagerDispatcher = 
        new TestAppManagerDispatcher();
    rmContext.getDispatcher().register(RMAppEventType.class, testDispatcher);
    rmContext.getDispatcher().register(RMAppManagerEventType.class, testAppManagerDispatcher);
    ((Service)rmContext.getDispatcher()).init(conf);
    ((Service)rmContext.getDispatcher()).start();
    Assert.assertEquals("app event type is wrong before", RMAppEventType.KILL, appEventType);
  }

  @SuppressWarnings("deprecation")
  @Test
  public void testRMAppSubmitAMContainerResourceRequests() throws Exception {
    asContext.setResource(Resources.createResource(1024));
    asContext.setAMContainerResourceRequest(
        ResourceRequest.newInstance(Priority.newInstance(0),
            ResourceRequest.ANY, Resources.createResource(1024), 1, true));
    List<ResourceRequest> reqs = new ArrayList<>();
    reqs.add(ResourceRequest.newInstance(Priority.newInstance(0),
        ResourceRequest.ANY, Resources.createResource(1025), 1, false));
    reqs.add(ResourceRequest.newInstance(Priority.newInstance(0),
        "/rack", Resources.createResource(1025), 1, false));
    reqs.add(ResourceRequest.newInstance(Priority.newInstance(0),
        "/rack/node", Resources.createResource(1025), 1, true));
    asContext.setAMContainerResourceRequests(cloneResourceRequests(reqs));
    // getAMContainerResourceRequest uses the first entry of
    // getAMContainerResourceRequests
    Assert.assertEquals(reqs.get(0), asContext.getAMContainerResourceRequest());
    Assert.assertEquals(reqs, asContext.getAMContainerResourceRequests());
    RMApp app = testRMAppSubmit();
    for (ResourceRequest req : reqs) {
      req.setNodeLabelExpression(RMNodeLabelsManager.NO_LABEL);
    }

    // setAMContainerResourceRequests has priority over
    // setAMContainerResourceRequest and setResource
    Assert.assertEquals(reqs, app.getAMResourceRequests());
  }

  @SuppressWarnings("deprecation")
  @Test
  public void testRMAppSubmitAMContainerResourceRequest() throws Exception {
    asContext.setResource(Resources.createResource(1024));
    asContext.setAMContainerResourceRequests(null);
    ResourceRequest req =
        ResourceRequest.newInstance(Priority.newInstance(0),
            ResourceRequest.ANY, Resources.createResource(1025), 1, true);
    req.setNodeLabelExpression(RMNodeLabelsManager.NO_LABEL);
    asContext.setAMContainerResourceRequest(ResourceRequest.clone(req));
    // getAMContainerResourceRequests uses a singleton list of
    // getAMContainerResourceRequest
    Assert.assertEquals(req, asContext.getAMContainerResourceRequest());
    Assert.assertEquals(req, asContext.getAMContainerResourceRequests().get(0));
    Assert.assertEquals(1, asContext.getAMContainerResourceRequests().size());
    RMApp app = testRMAppSubmit();
    // setAMContainerResourceRequest has priority over setResource
    Assert.assertEquals(Collections.singletonList(req),
        app.getAMResourceRequests());
  }

  @Test
  public void testRMAppSubmitAMContainerWithNoLabelByRMDefaultAMNodeLabel() throws Exception {
    List<ResourceRequest> reqs = new ArrayList<>();
    ResourceRequest anyReq = ResourceRequest.newInstance(
        Priority.newInstance(1),
        ResourceRequest.ANY, Resources.createResource(1024), 1, false, null,
        ExecutionTypeRequest.newInstance(ExecutionType.GUARANTEED));
    reqs.add(anyReq);
    asContext.setAMContainerResourceRequests(cloneResourceRequests(reqs));
    asContext.setNodeLabelExpression("fixed");

    Configuration conf = new Configuration(false);
    String defaultAMNodeLabel = "core";
    conf.set(YarnConfiguration.AM_DEFAULT_NODE_LABEL, defaultAMNodeLabel);

    when(mockDefaultQueueInfo.getAccessibleNodeLabels()).thenReturn(
        new HashSet<String>() {{ add("core"); }});

    TestRMAppManager newAppMonitor = createAppManager(rmContext, conf);
    newAppMonitor.submitApplication(asContext, "test");

    RMApp app = rmContext.getRMApps().get(appId);
    waitUntilEventProcessed();
    Assert.assertEquals(defaultAMNodeLabel,
        app.getAMResourceRequests().get(0).getNodeLabelExpression());
  }

  @Test
  public void testRMAppSubmitResource() throws Exception {
    asContext.setResource(Resources.createResource(1024));
    asContext.setAMContainerResourceRequests(null);
    RMApp app = testRMAppSubmit();

    // setResource
    Assert.assertEquals(Collections.singletonList(
        ResourceRequest.newInstance(RMAppAttemptImpl.AM_CONTAINER_PRIORITY,
        ResourceRequest.ANY, Resources.createResource(1024), 1, true,
            "")),
        app.getAMResourceRequests());
  }

  @Test
  public void testRMAppSubmitNoResourceRequests() throws Exception {
    asContext.setResource(null);
    asContext.setAMContainerResourceRequests(null);
    try {
      testRMAppSubmit();
      Assert.fail("Should have failed due to no ResourceRequest");
    } catch (InvalidResourceRequestException e) {
      Assert.assertEquals(
          "Invalid resource request, no resources requested",
          e.getMessage());
    }
  }

  @Test
  public void testRMAppSubmitAMContainerResourceRequestsDisagree()
      throws Exception {
    asContext.setResource(null);
    List<ResourceRequest> reqs = new ArrayList<>();
    when(mockDefaultQueueInfo.getAccessibleNodeLabels()).thenReturn
        (new HashSet<String>() {{ add("label1"); add(""); }});
    ResourceRequest anyReq = ResourceRequest.newInstance(
        Priority.newInstance(1),
        ResourceRequest.ANY, Resources.createResource(1024), 1, false, "label1",
        ExecutionTypeRequest.newInstance(ExecutionType.GUARANTEED));
    reqs.add(anyReq);
    reqs.add(ResourceRequest.newInstance(Priority.newInstance(2),
        "/rack", Resources.createResource(1025), 2, false, "",
        ExecutionTypeRequest.newInstance(ExecutionType.OPPORTUNISTIC)));
    reqs.add(ResourceRequest.newInstance(Priority.newInstance(3),
        "/rack/node", Resources.createResource(1026), 3, true, "",
        ExecutionTypeRequest.newInstance(ExecutionType.OPPORTUNISTIC)));
    asContext.setAMContainerResourceRequests(cloneResourceRequests(reqs));
    RMApp app = testRMAppSubmit();
    // It should force the requests to all agree on these points
    for (ResourceRequest req : reqs) {
      req.setCapability(anyReq.getCapability());
      req.setExecutionTypeRequest(
          ExecutionTypeRequest.newInstance(ExecutionType.GUARANTEED));
      req.setNumContainers(1);
      req.setPriority(Priority.newInstance(0));
    }
    Assert.assertEquals(reqs, app.getAMResourceRequests());
  }

  @Test
  public void testRMAppSubmitAMContainerResourceRequestsNoAny()
      throws Exception {
    asContext.setResource(null);
    List<ResourceRequest> reqs = new ArrayList<>();
    reqs.add(ResourceRequest.newInstance(Priority.newInstance(1),
        "/rack", Resources.createResource(1025), 1, false));
    reqs.add(ResourceRequest.newInstance(Priority.newInstance(1),
        "/rack/node", Resources.createResource(1025), 1, true));
    asContext.setAMContainerResourceRequests(cloneResourceRequests(reqs));
    // getAMContainerResourceRequest uses the first entry of
    // getAMContainerResourceRequests
    Assert.assertEquals(reqs, asContext.getAMContainerResourceRequests());
    try {
      testRMAppSubmit();
      Assert.fail("Should have failed due to missing ANY ResourceRequest");
    } catch (InvalidResourceRequestException e) {
      Assert.assertEquals(
          "Invalid resource request, no resource request specified with *",
          e.getMessage());
    }
  }

  @Test
  public void testRMAppSubmitAMContainerResourceRequestsTwoManyAny()
      throws Exception {
    asContext.setResource(null);
    List<ResourceRequest> reqs = new ArrayList<>();
    reqs.add(ResourceRequest.newInstance(Priority.newInstance(1),
        ResourceRequest.ANY, Resources.createResource(1025), 1, false));
    reqs.add(ResourceRequest.newInstance(Priority.newInstance(1),
        ResourceRequest.ANY, Resources.createResource(1025), 1, false));
    asContext.setAMContainerResourceRequests(cloneResourceRequests(reqs));
    // getAMContainerResourceRequest uses the first entry of
    // getAMContainerResourceRequests
    Assert.assertEquals(reqs, asContext.getAMContainerResourceRequests());
    try {
      testRMAppSubmit();
      Assert.fail("Should have failed due to too many ANY ResourceRequests");
    } catch (InvalidResourceRequestException e) {
      Assert.assertEquals(
          "Invalid resource request, only one resource request with * is " +
              "allowed", e.getMessage());
    }
  }

  private RMApp testRMAppSubmit() throws Exception {
    appMonitor.submitApplication(asContext, "test");
    return waitUntilEventProcessed();
  }

  private RMApp waitUntilEventProcessed() throws InterruptedException {
    RMApp app = rmContext.getRMApps().get(appId);
    Assert.assertNotNull("app is null", app);
    Assert.assertEquals("app id doesn't match", appId, app.getApplicationId());
    Assert.assertEquals("app state doesn't match", RMAppState.NEW, app.getState());
    // wait for event to be processed
    int timeoutSecs = 0;
    while ((getAppEventType() == RMAppEventType.KILL) &&
        timeoutSecs++ < 20) {
      Thread.sleep(1000);
    }
    Assert.assertEquals("app event type sent is wrong", RMAppEventType.START,
        getAppEventType());
    return app;
  }

  @Test
  public void testRMAppSubmitWithInvalidTokens() throws Exception {
    // Setup invalid security tokens
    DataOutputBuffer dob = new DataOutputBuffer();
    ByteBuffer securityTokens = ByteBuffer.wrap(dob.getData(), 0,
        dob.getLength());
    Configuration conf = new Configuration();
    conf.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION,
        "kerberos");
    UserGroupInformation.setConfiguration(conf);
    asContext.getAMContainerSpec().setTokens(securityTokens);
    try {
      appMonitor.submitApplication(asContext, "test");
      Assert.fail("Application submission should fail because" +
          " Tokens are invalid.");
    } catch (YarnException e) {
      // Exception is expected
      assertTrue("The thrown exception is not" +
          " java.io.EOFException",
          e.getMessage().contains("java.io.EOFException"));
    }
    int timeoutSecs = 0;
    while ((getAppEventType() == RMAppEventType.KILL) &&
        timeoutSecs++ < 20) {
      Thread.sleep(1000);
    }
    Assert.assertEquals("app event type sent is wrong",
        RMAppEventType.APP_REJECTED, getAppEventType());
    asContext.getAMContainerSpec().setTokens(null);
  }

  @Test (timeout = 30000)
  public void testRMAppSubmitMaxAppAttempts() throws Exception {
    int[] globalMaxAppAttempts = new int[] { 10, 1 };
    int[] rmAmMaxAttempts = new int[] { 8, 1 };
    int[][] individualMaxAppAttempts = new int[][]{
        new int[]{ 9, 10, 11, 0 },
        new int[]{ 1, 10, 0, -1 }};
    int[][] expectedNums = new int[][]{
        new int[]{ 9, 10, 10, 8 },
        new int[]{ 1, 1, 1, 1 }};
    for (int i = 0; i < globalMaxAppAttempts.length; ++i) {
      for (int j = 0; j < individualMaxAppAttempts.length; ++j) {
        scheduler = mockResourceScheduler();
        Configuration conf = new Configuration();
        conf.setInt(YarnConfiguration.GLOBAL_RM_AM_MAX_ATTEMPTS,
            globalMaxAppAttempts[i]);
        conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, rmAmMaxAttempts[i]);
        ApplicationMasterService masterService =
            new ApplicationMasterService(rmContext, scheduler);
        TestRMAppManager appMonitor = new TestRMAppManager(rmContext,
            new ClientToAMTokenSecretManagerInRM(), scheduler, masterService,
            new ApplicationACLsManager(conf), conf);

        ApplicationId appID = MockApps.newAppID(i * 4 + j + 1);
        asContext.setApplicationId(appID);
        if (individualMaxAppAttempts[i][j] != 0) {
          asContext.setMaxAppAttempts(individualMaxAppAttempts[i][j]);
        }
        appMonitor.submitApplication(asContext, "test");
        RMApp app = rmContext.getRMApps().get(appID);
        Assert.assertEquals("max application attempts doesn't match",
            expectedNums[i][j], app.getMaxAppAttempts());

        // wait for event to be processed
        int timeoutSecs = 0;
        while ((getAppEventType() == RMAppEventType.KILL) &&
            timeoutSecs++ < 20) {
          Thread.sleep(1000);
        }
        setAppEventType(RMAppEventType.KILL);
      }
    }
  }

  @Test (timeout = 30000)
  public void testRMAppSubmitDuplicateApplicationId() throws Exception {
    ApplicationId appId = MockApps.newAppID(0);
    asContext.setApplicationId(appId);
    RMApp appOrig = rmContext.getRMApps().get(appId);
    assertTrue("app name matches "
        + "but shouldn't", "testApp1" != appOrig.getName());

    // our testApp1 should be rejected and original app with same id should be left in place
    try {
      appMonitor.submitApplication(asContext, "test");
      Assert.fail("Exception is expected when applicationId is duplicate.");
    } catch (YarnException e) {
      assertTrue("The thrown exception is not the expectd one.",
          e.getMessage().contains("Cannot add a duplicate!"));
    }

    // make sure original app didn't get removed
    RMApp app = rmContext.getRMApps().get(appId);
    Assert.assertNotNull("app is null", app);
    Assert.assertEquals("app id doesn't match",
        appId, app.getApplicationId());
    Assert.assertEquals("app state doesn't match",
        RMAppState.FINISHED, app.getState());
  }

  @SuppressWarnings("deprecation")
  @Test (timeout = 30000)
  public void testRMAppSubmitInvalidResourceRequest() throws Exception {
    asContext.setResource(Resources.createResource(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB + 1));

    // submit an app
    try {
      appMonitor.submitApplication(asContext, "test");
      Assert.fail("Application submission should fail because resource" +
          " request is invalid.");
    } catch (YarnException e) {
      // Exception is expected
      // TODO Change this to assert the expected exception type - post YARN-142
      // sub-task related to specialized exceptions.
      assertTrue("The thrown exception is not" +
          " InvalidResourceRequestException",
          e.getMessage().contains("Invalid resource request"));
    }
  }

  @Test (timeout = 30000)
  public void testEscapeApplicationSummary() {
    RMApp app = mock(RMAppImpl.class);
    ApplicationSubmissionContext asc = mock(ApplicationSubmissionContext.class);
    when(asc.getNodeLabelExpression()).thenReturn("test");
    when(app.getApplicationSubmissionContext()).thenReturn(asc);
    when(app.getApplicationId()).thenReturn(
        ApplicationId.newInstance(100L, 1));
    when(app.getName()).thenReturn("Multiline\n\n\r\rAppName");
    when(app.getUser()).thenReturn("Multiline\n\n\r\rUserName");
    when(app.getQueue()).thenReturn("Multiline\n\n\r\rQueueName");
    when(app.getState()).thenReturn(RMAppState.RUNNING);
    when(app.getApplicationType()).thenReturn("MAPREDUCE");
    when(app.getSubmitTime()).thenReturn(1000L);
    when(app.getLaunchTime()).thenReturn(2000L);
    when(app.getApplicationTags()).thenReturn(Sets.newHashSet("tag2", "tag1"));

    RMAppAttempt mockRMAppAttempt = mock(RMAppAttempt.class);
    Container mockContainer = mock(Container.class);
    NodeId mockNodeId = mock(NodeId.class);
    String host = "127.0.0.1";

    when(mockNodeId.getHost()).thenReturn(host);
    when(mockContainer.getNodeId()).thenReturn(mockNodeId);
    when(mockRMAppAttempt.getMasterContainer()).thenReturn(mockContainer);
    when(app.getCurrentAppAttempt()).thenReturn(mockRMAppAttempt);

    Map<String, Long> resourceSecondsMap = new HashMap<>();
    resourceSecondsMap.put(ResourceInformation.MEMORY_MB.getName(), 16384L);
    resourceSecondsMap.put(ResourceInformation.VCORES.getName(), 64L);
    RMAppMetrics metrics =
        new RMAppMetrics(Resource.newInstance(1234, 56),
            10, 1, resourceSecondsMap, new HashMap<>(), 1234);
    when(app.getRMAppMetrics()).thenReturn(metrics);
    when(app.getDiagnostics()).thenReturn(new StringBuilder(
        "Multiline\n\n\r\rDiagnostics=Diagn,ostic"));

    RMAppManager.ApplicationSummary.SummaryBuilder summary =
        new RMAppManager.ApplicationSummary().createAppSummary(app);
    String msg = summary.toString();
    LOG.info("summary: " + msg);
    Assert.assertFalse(msg.contains("\n"));
    Assert.assertFalse(msg.contains("\r"));

    String escaped = "\\n\\n\\r\\r";
    assertTrue(msg.contains("Multiline" + escaped +"AppName"));
    assertTrue(msg.contains("Multiline" + escaped +"UserName"));
    assertTrue(msg.contains("Multiline" + escaped +"QueueName"));
    assertTrue(msg.contains("appMasterHost=" + host));
    assertTrue(msg.contains("submitTime=1000"));
    assertTrue(msg.contains("launchTime=2000"));
    assertTrue(msg.contains("memorySeconds=16384"));
    assertTrue(msg.contains("vcoreSeconds=64"));
    assertTrue(msg.contains("preemptedAMContainers=1"));
    assertTrue(msg.contains("preemptedNonAMContainers=10"));
    assertTrue(msg.contains("preemptedResources=<memory:1234\\, vCores:56>"));
    assertTrue(msg.contains("applicationType=MAPREDUCE"));
    assertTrue(msg.contains("applicationTags=tag1\\,tag2"));
    assertTrue(msg.contains("applicationNodeLabel=test"));
    assertTrue(msg.contains("diagnostics=Multiline" + escaped
        + "Diagnostics\\=Diagn\\,ostic"));
    assertTrue(msg.contains("totalAllocatedContainers=1234"));
  }

  @Test
  public void testRMAppSubmitWithQueueChanged() throws Exception {
    // Setup a PlacementManager returns a new queue
    PlacementManager placementMgr = mock(PlacementManager.class);
    doAnswer(new Answer<ApplicationPlacementContext>() {

      @Override
      public ApplicationPlacementContext answer(InvocationOnMock invocation)
          throws Throwable {
        return new ApplicationPlacementContext("newQueue");
      }

    }).when(placementMgr).placeApplication(
        any(ApplicationSubmissionContext.class),
        any(String.class),
        any(Boolean.class));
    rmContext.setQueuePlacementManager(placementMgr);

    asContext.setQueue("oldQueue");
    appMonitor.submitApplication(asContext, "test");

    RMApp app = rmContext.getRMApps().get(appId);
    RMAppEvent event = new RMAppEvent(appId, RMAppEventType.START);
    rmContext.getRMApps().get(appId).handle(event);
    event = new RMAppEvent(appId, RMAppEventType.APP_NEW_SAVED);
    rmContext.getRMApps().get(appId).handle(event);

    Assert.assertNotNull("app is null", app);
    Assert.assertEquals("newQueue", asContext.getQueue());

    // wait for event to be processed
    int timeoutSecs = 0;
    while ((getAppEventType() == RMAppEventType.KILL) && timeoutSecs++ < 20) {
      Thread.sleep(1000);
    }
    Assert.assertEquals("app event type sent is wrong", RMAppEventType.START,
        getAppEventType());
  }

  private static ResourceScheduler mockResourceScheduler() {
    return mockResourceScheduler(ResourceScheduler.class);
  }

  private static <T extends ResourceScheduler> ResourceScheduler
      mockResourceScheduler(Class<T> schedulerClass) {
    ResourceScheduler scheduler = mock(schedulerClass);
    when(scheduler.getMinimumResourceCapability()).thenReturn(
        Resources.createResource(
            YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_MB));
    when(scheduler.getMaximumResourceCapability()).thenReturn(
        Resources.createResource(
            YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB));
    when(scheduler.getMaximumResourceCapability(any(String.class))).thenReturn(
        Resources.createResource(
            YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB));

    when(scheduler.getMaximumResourceCapability(anyString())).thenReturn(
        Resources.createResource(
            YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB));

    ResourceCalculator rs = mock(ResourceCalculator.class);
    when(scheduler.getResourceCalculator()).thenReturn(rs);

    when(scheduler.getNormalizedResource(any(), any()))
        .thenAnswer(new Answer<Resource>() {
          @Override
          public Resource answer(InvocationOnMock invocationOnMock)
              throws Throwable {
            return (Resource) invocationOnMock.getArguments()[0];
          }
        });

    return scheduler;
  }

  private static ContainerLaunchContext mockContainerLaunchContext(
      RecordFactory recordFactory) {
    ContainerLaunchContext amContainer = recordFactory.newRecordInstance(
        ContainerLaunchContext.class);
    amContainer.setApplicationACLs(new HashMap<ApplicationAccessType, String>());;
    return amContainer;
  }

  private static Resource mockResource() {
    return Resources.createResource(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_MB);
  }

  private static List<ResourceRequest> cloneResourceRequests(
      List<ResourceRequest> reqs) {
    List<ResourceRequest> cloneReqs = new ArrayList<>();
    for (ResourceRequest req : reqs) {
      cloneReqs.add(ResourceRequest.clone(req));
    }
    return cloneReqs;
  }

  @Test
  public void testGetUserNameForPlacementTagBasedPlacementDisabled()
      throws YarnException {
    String user = "user1";
    String expectedQueue = "user1Queue";
    String userNameFromAppTag = "user2";
    String userIdTag = USER_ID_PREFIX + userNameFromAppTag;
    setApplicationTags("tag1", userIdTag, "tag2");
    verifyPlacementUsername(expectedQueue, user, userNameFromAppTag, user);
  }

  /**
   * Test for the case when the application tag based placement is enabled and
   * the submitting user 'user1' is whitelisted and the user from the
   * application tag has access to queue.
   * Expected behaviour: the placement is done for user from the tag 'user2'
   */
  @Test
  public void testGetUserNameForPlacementTagBasedPlacementEnabled()
          throws YarnException {
    String user = "user1";
    String usernameFromAppTag = "user2";
    String expectedQueue = "user1Queue";
    String expectedUser = usernameFromAppTag;
    String userIdTag = USER_ID_PREFIX + usernameFromAppTag;
    setApplicationTags("tag1", userIdTag, "tag2");
    enableApplicationTagPlacement(true, user);
    verifyPlacementUsername(expectedQueue, user, usernameFromAppTag,
            expectedUser);
  }

  /**
   * Test for the case when the application tag based placement is enabled.
   * And submitting user 'user1' is whitelisted and  there are multiple valid
   * username tags passed
   * Expected behaviour: the placement is done for the first valid username
   * from the tag 'user2'
   */
  @Test
  public void testGetUserNameForPlacementTagBasedPlacementMultipleUserIds()
          throws YarnException {
    String user = "user1";
    String expectedQueue = "user1Queue";
    String userNameFromAppTag = "user2";
    String expectedUser = userNameFromAppTag;
    String userIdTag = USER_ID_PREFIX + expectedUser;
    String userIdTag2 = USER_ID_PREFIX + "user3";
    setApplicationTags("tag1", userIdTag, "tag2", userIdTag2);
    enableApplicationTagPlacement(true, user);
    verifyPlacementUsername(expectedQueue, user, userNameFromAppTag,
            expectedUser);
  }

  /**
   * Test for the case when the application tag based placement is enabled.
   * And no username is set in the application tag
   * Expected behaviour: the placement is done for the submitting user 'user1'
   */
  @Test
  public void testGetUserNameForPlacementTagBasedPlacementNoUserId()
          throws YarnException {
    String user = "user1";
    String expectedQueue = "user1Queue";
    String userNameFromAppTag = null;
    setApplicationTags("tag1", "tag2");
    enableApplicationTagPlacement(true, user);
    verifyPlacementUsername(expectedQueue, user, userNameFromAppTag, user);
  }

  /**
   * Test for the case when the application tag based placement is enabled but
   * the user from the application tag 'user2' does not have access to the
   * queue.
   * Expected behaviour: the placement is done for the submitting user 'user1'
   */
  @Test
  public void testGetUserNameForPlacementUserWithoutAccessToQueue()
          throws YarnException {
    String user = "user1";
    String expectedQueue = "user1Queue";
    String userNameFromAppTag = "user2";
    String userIdTag = USER_ID_PREFIX + userNameFromAppTag;
    setApplicationTags("tag1", userIdTag, "tag2");
    enableApplicationTagPlacement(false, user);
    verifyPlacementUsername(expectedQueue, user, userNameFromAppTag, user);
  }

  /**
   * Test for the case when the application tag based placement is enabled but
   * the submitting user 'user1' is not whitelisted and there is a valid
   * username tag passed.
   * Expected behaviour: the placement is done for the submitting user 'user1'
   */
  @Test
  public void testGetUserNameForPlacementNotWhitelistedUser()
          throws YarnException {
    String user = "user1";
    String expectedQueue = "user1Queue";
    String userNameFromAppTag = "user2";
    String userIdTag = USER_ID_PREFIX + userNameFromAppTag;
    setApplicationTags("tag1", userIdTag, "tag2");
    enableApplicationTagPlacement(true, "someUser");
    verifyPlacementUsername(expectedQueue, user, userNameFromAppTag, user);
  }

  /**
   * Test for the case when the application tag based placement is enabled but
   * there is no whitelisted user.
   * Expected behaviour: the placement is done for the submitting user 'user1'
   */
  @Test
  public void testGetUserNameForPlacementEmptyWhiteList()
          throws YarnException {
    String user = "user1";
    String expectedQueue = "user1Queue";
    String userNameFromAppTag = "user2";
    String userIdTag = USER_ID_PREFIX + userNameFromAppTag;
    setApplicationTags("tag1", userIdTag, "tag2");
    enableApplicationTagPlacement(false);
    verifyPlacementUsername(expectedQueue, user, userNameFromAppTag, user);
  }


  /**
   * Test for the case when the application tag based placement is enabled and
   * there is one wrongly qualified user
   * 'userid=' and a valid user 'userid=user2' passed
   * with application tag.
   * Expected behaviour: the placement is done for the first valid username
   * from the tag 'user2'
   */
  @Test
  public void testGetUserNameForPlacementWronglyQualifiedFirstUserNameInTag()
          throws YarnException {
    String user = "user1";
    String expectedQueue = "user1Queue";
    String userNameFromAppTag = "user2";
    String expectedUser = userNameFromAppTag;
    String userIdTag = USER_ID_PREFIX + userNameFromAppTag;
    String wrongUserIdTag = USER_ID_PREFIX;
    setApplicationTags("tag1", wrongUserIdTag, userIdTag, "tag2");
    enableApplicationTagPlacement(true, user);
    verifyPlacementUsername(expectedQueue, user, userNameFromAppTag,
            expectedUser);
  }

  /**
   * Test for the case when the application tag based placement is enabled and
   * there is only one wrongly qualified user 'userid=' passed
   * with application tag.
   * Expected behaviour: the placement is done for the submitting user 'user1'
   */
  @Test
  public void testGetUserNameForPlacementWronglyQualifiedUserNameInTag()
          throws YarnException {
    String user = "user1";
    String expectedQueue = "user1Queue";
    String userNameFromAppTag = "";
    String wrongUserIdTag = USER_ID_PREFIX;
    setApplicationTags("tag1", wrongUserIdTag, "tag2");
    enableApplicationTagPlacement(true, user);
    verifyPlacementUsername(expectedQueue, user, userNameFromAppTag, user);
  }

  /**
   * Test for the case when the application tag based placement is enabled.
   * And there is no placement rule defined for the user from the application tag
   * Expected behaviour: the placement is done for the submitting user 'user1'
   */
  @Test
  public void testGetUserNameForPlacementNoRuleDefined()
          throws YarnException {
    String user = "user1";
    String expectedUser = user;
    String userNameFromAppTag = "user2";
    String wrongUserIdTag = USER_ID_PREFIX + userNameFromAppTag;
    setApplicationTags("tag1", wrongUserIdTag, "tag2");
    enableApplicationTagPlacement(true, user);
    PlacementManager placementMgr = mock(PlacementManager.class);
    when(placementMgr.placeApplication(asContext, userNameFromAppTag))
            .thenReturn(null);
    String userNameForPlacement = appMonitor
            .getUserNameForPlacement(user, asContext, placementMgr);
    Assert.assertEquals(expectedUser, userNameForPlacement);
  }

  @Test
  @UseMockCapacityScheduler
  public void testCheckAccessFullPathWithCapacityScheduler()
      throws YarnException {
    // make sure we only combine "parent + queue" if CS is selected
    testCheckAccess("root.users", "hadoop");
  }

  @Test
  @UseMockCapacityScheduler
  public void testCheckAccessLeafQueueOnlyWithCapacityScheduler()
      throws YarnException {
    // make sure we that NPE is avoided if there's no parent defined
    testCheckAccess(null, "hadoop");
  }

  private void testCheckAccess(String parent, String queue)
      throws YarnException {
    enableApplicationTagPlacement(true, "hadoop");
    String userIdTag = USER_ID_PREFIX + "hadoop";
    setApplicationTags("tag1", userIdTag, "tag2");
    PlacementManager placementMgr = mock(PlacementManager.class);
    ApplicationPlacementContext appContext;
    String expectedQueue;
    if (parent == null) {
      appContext = new ApplicationPlacementContext(queue);
      expectedQueue = queue;
    } else {
      appContext = new ApplicationPlacementContext(queue, parent);
      expectedQueue = parent + "." + queue;
    }

    when(placementMgr.placeApplication(asContext, "hadoop"))
            .thenReturn(appContext);
    appMonitor.getUserNameForPlacement("hadoop", asContext, placementMgr);

    ArgumentCaptor<String> queueNameCaptor =
        ArgumentCaptor.forClass(String.class);
    verify(scheduler).checkAccess(any(UserGroupInformation.class),
        any(QueueACL.class), queueNameCaptor.capture());

    assertEquals("Expected access check for queue",
        expectedQueue, queueNameCaptor.getValue());
  }

  private void enableApplicationTagPlacement(boolean userHasAccessToQueue,
                                             String... whiteListedUsers) {
    Configuration conf = new Configuration();
    conf.setBoolean(YarnConfiguration
            .APPLICATION_TAG_BASED_PLACEMENT_ENABLED, true);
    conf.setStrings(YarnConfiguration
            .APPLICATION_TAG_BASED_PLACEMENT_USER_WHITELIST, whiteListedUsers);
    ((RMContextImpl) rmContext).setYarnConfiguration(conf);
    when(scheduler.checkAccess(any(UserGroupInformation.class),
            eq(QueueACL.SUBMIT_APPLICATIONS), any(String.class)))
            .thenReturn(userHasAccessToQueue);
    ApplicationMasterService masterService =
            new ApplicationMasterService(rmContext, scheduler);
    appMonitor = new TestRMAppManager(rmContext,
            new ClientToAMTokenSecretManagerInRM(),
            scheduler, masterService,
            new ApplicationACLsManager(conf), conf);
  }

  private void verifyPlacementUsername(final String queue,
          final String submittingUser, final String userNameFRomAppTag,
          final String expectedUser)
          throws YarnException {
    PlacementManager placementMgr = mock(PlacementManager.class);
    ApplicationPlacementContext appContext
            = new ApplicationPlacementContext(queue);
    when(placementMgr.placeApplication(asContext, userNameFRomAppTag))
            .thenReturn(appContext);
    String userNameForPlacement = appMonitor
            .getUserNameForPlacement(submittingUser, asContext, placementMgr);
    Assert.assertEquals(expectedUser, userNameForPlacement);
  }

  private void setApplicationTags(String... tags) {
    Set<String> applicationTags = new TreeSet<>();
    Collections.addAll(applicationTags, tags);
    asContext.setApplicationTags(applicationTags);
  }

  private class UseCapacitySchedulerRule extends TestWatcher {
    private boolean useCapacityScheduler;

    @Override
    protected void starting(Description d) {
      useCapacityScheduler =
          d.getAnnotation(UseMockCapacityScheduler.class) != null;
    }

    public boolean useCapacityScheduler() {
      return useCapacityScheduler;
    }
  }

  @Retention(RetentionPolicy.RUNTIME)
  public @interface UseMockCapacityScheduler {
    // mark test cases with this which require
    // the scheduler type to be CapacityScheduler
  }
}
