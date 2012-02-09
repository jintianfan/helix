package com.linkedin.helix.integration;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.MBeanServerNotification;
import javax.management.MalformedObjectNameException;

import org.apache.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.helix.ClusterDataAccessor;
import com.linkedin.helix.ClusterManager;
import com.linkedin.helix.NotificationContext;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.TestHelper;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.agent.zk.ZKDataAccessor;
import com.linkedin.helix.agent.zk.ZNRecordSerializer;
import com.linkedin.helix.agent.zk.ZkClient;
import com.linkedin.helix.alerts.AlertValueAndStatus;
import com.linkedin.helix.controller.ClusterManagerMain;
import com.linkedin.helix.healthcheck.ParticipantHealthReportCollectorImpl;
import com.linkedin.helix.healthcheck.PerformanceHealthReportProvider;
import com.linkedin.helix.mock.storage.MockEspressoHealthReportProvider;
import com.linkedin.helix.mock.storage.MockParticipant;
import com.linkedin.helix.mock.storage.MockTransitionIntf;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.monitoring.mbeans.ClusterAlertMBeanCollection;
import com.linkedin.helix.monitoring.mbeans.ClusterMBeanObserver;
import com.linkedin.helix.tools.ClusterSetup;

public class TestWildcardAlert extends ZkIntegrationTestBase 
{
  class TestClusterMBeanObserver extends ClusterMBeanObserver
  {
    Map<String, Map<String, Object>> _beanValueMap = new HashMap<String, Map<String, Object>>();
    
    public TestClusterMBeanObserver(String domain)
        throws InstanceNotFoundException, IOException,
        MalformedObjectNameException, NullPointerException
    {
      super(domain);
    }

    @Override
    public void onMBeanRegistered(MBeanServerConnection server,
        MBeanServerNotification mbsNotification)
    {
      try
      {
        MBeanInfo info = _server.getMBeanInfo(mbsNotification.getMBeanName());
        MBeanAttributeInfo[] infos = info.getAttributes();
        _beanValueMap.put(mbsNotification.getMBeanName().toString(), new HashMap<String, Object>());
        for(MBeanAttributeInfo infoItem : infos)
        {
          Object val = _server.getAttribute(mbsNotification.getMBeanName(), infoItem.getName());
          System.out.println("         " + infoItem.getName() + " : " + _server.getAttribute(mbsNotification.getMBeanName(), infoItem.getName()) + " type : " + infoItem.getType());
          _beanValueMap.get(mbsNotification.getMBeanName().toString()).put(infoItem.getName(), val);
        }
      } 
      catch (Exception e)
      {
        _logger.error("Error getting bean info, domain="+_domain, e);
      } 
    }

    @Override
    public void onMBeanUnRegistered(MBeanServerConnection server,
        MBeanServerNotification mbsNotification)
    {
      
    }
    
  }

  private static final Logger _logger = Logger.getLogger(TestWildcardAlert.class);
  ZkClient _zkClient;
  protected ClusterSetup _setupTool = null;
  protected final String _alertStr = "EXP(accumulate()(localhost_*.RestQueryStats@DBName=TestDB0.latency)|EXPAND|SUMEACH)CMP(GREATER)CON(10)";  
  protected final String _alertStatusStr = _alertStr; //+" : (*)";
  protected final String _dbName = "TestDB0";

  @BeforeClass ()
  public void beforeClass() throws Exception
  {
    _zkClient = new ZkClient(ZK_ADDR);
    _zkClient.setZkSerializer(new ZNRecordSerializer());

    _setupTool = new ClusterSetup(ZK_ADDR);
  }

  @AfterClass
  public void afterClass()
  {
    _zkClient.close();
  }

  public class WildcardAlertTransition implements MockTransitionIntf
  {
    @Override
    public void doTrasition(Message message, NotificationContext context)
    {
      ClusterManager manager = context.getManager();
      ClusterDataAccessor accessor = manager.getDataAccessor();
      String fromState = message.getFromState();
      String toState = message.getToState();
      String instance = message.getTgtName();
      String partition = message.getStateUnitKey();

      if (fromState.equalsIgnoreCase("SLAVE")
          && toState.equalsIgnoreCase("MASTER"))
      {

    	//add a stat and report to ZK
    	//perhaps should keep reporter per instance...
    	ParticipantHealthReportCollectorImpl reporter =
    			new ParticipantHealthReportCollectorImpl(manager, instance);
    	MockEspressoHealthReportProvider provider = new
    			MockEspressoHealthReportProvider();
    	reporter.addHealthReportProvider(provider);
    	String statName = "latency";
    	provider.setStat(_dbName, statName,"15");
     	reporter.transmitHealthReports();

    	/*
        for (int i = 0; i < 5; i++)
        {
          accessor.setProperty(PropertyType.HEALTHREPORT,
                               new ZNRecord("mockAlerts" + i),
                               instance,
                               "mockAlerts");
          try
          {
            Thread.sleep(1000);
          }
          catch (InterruptedException e)
          {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
        }
        */
      }
    }
  }

  @Test()
  public void testWildcardAlert() throws Exception
  {
    String clusterName = getShortClassName();
    MockParticipant[] participants = new MockParticipant[5];

    System.out.println("START TestWildcardAlert at " + new Date(System.currentTimeMillis()));

    TestHelper.setupCluster(clusterName,
                            ZK_ADDR,
                            12918,        // participant start port
                            "localhost",  // participant name prefix
                            "TestDB",     // resource group name prefix
                            1,            // resource groups
                            10,           // partitions per resource group
                            5,            // number of nodes //change back to 5!!!
                            3,            // replicas //change back to 3!!!
                            "MasterSlave",
                            true);        // do rebalance

    _setupTool.getClusterManagementTool().addAlert(clusterName, _alertStr);
    //_setupTool.getClusterManagementTool().addAlert(clusterName, _alertStr2);

    TestHelper.startController(clusterName,
                               "controller_0",
                               ZK_ADDR,
                               ClusterManagerMain.STANDALONE);
    // start participants
    for (int i = 0; i < 5; i++) //!!!change back to 5
    {
      String instanceName = "localhost_" + (12918 + i);

      participants[i] = new MockParticipant(clusterName,
                                            instanceName,
                                            ZK_ADDR,
                                            new WildcardAlertTransition());
      new Thread(participants[i]).start();
    }

    TestClusterMBeanObserver jmxMBeanObserver = new TestClusterMBeanObserver(ClusterAlertMBeanCollection.DOMAIN_ALERT);
    
    TestHelper.verifyWithTimeout("verifyBestPossAndExtViewExtended",
                                 15000,  // timeout in millisecond //was 15000
                                 ZK_ADDR,
                                 TestHelper.<String>setOf(clusterName),
                                 TestHelper.<String>setOf(_dbName),
                                 null,
                                 null,
                                 null);// other verifications go here
    ZKDataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    //for (int i = 0; i < 1; i++) //change 1 back to 5
    //{
    //String instance = "localhost_" + (12918 + i);
    //String instance = "localhost_12918";
    ZNRecord record = accessor.getProperty(PropertyType.ALERT_STATUS);
    Map<String, Map<String,String>> recMap = record.getMapFields();
    Set<String> keySet = recMap.keySet();
    Map<String,String> alertStatusMap = recMap.get(_alertStatusStr);
    String val = alertStatusMap.get(AlertValueAndStatus.VALUE_NAME);
    boolean fired = Boolean.parseBoolean(alertStatusMap.get(AlertValueAndStatus.FIRED_NAME));
    Assert.assertEquals(Double.parseDouble(val), Double.parseDouble("75.0"));
    Assert.assertTrue(fired);
    
    // Make sure that the jmxObserver has received all the jmx bean value that is corresponding to the alerts.
    Assert.assertTrue(jmxMBeanObserver._beanValueMap.size() == 1);
    String beanName = "HelixAlerts:alert=EXP(accumulate()(localhost_%.RestQueryStats@DBName#TestDB0.latency)|EXPAND|SUMEACH)CMP(GREATER)CON(10)--(%)";
    Assert.assertTrue(jmxMBeanObserver._beanValueMap.containsKey(beanName));
    
    Map<String, Object> beanValueMap = jmxMBeanObserver._beanValueMap.get(beanName);
    Assert.assertEquals(beanValueMap.size(),3);
    Assert.assertEquals((Integer)(beanValueMap.get("AlertFired")), new Integer(1));
    Assert.assertEquals((Double)(beanValueMap.get("AlertValue")), new Double(75.0));
    Assert.assertEquals((String)(beanValueMap.get("SensorName")), "EXP(accumulate()(localhost_%.RestQueryStats@DBName#TestDB0.latency)|EXPAND|SUMEACH)CMP(GREATER)CON(10)--(%)");
  //}
    
    System.out.println("END TestWildcardAlert at " + new Date(System.currentTimeMillis()));
  }
}