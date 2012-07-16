package com.linkedin.helix.controller.restlet;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.I0Itec.zkclient.DataUpdater;
import org.apache.log4j.Logger;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.data.Protocol;

import com.linkedin.helix.BaseDataAccessor;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.manager.zk.ZNRecordSerializer;
import com.linkedin.helix.manager.zk.ZkBaseDataAccessor;
import com.linkedin.helix.manager.zk.ZkClient;
/**
 * Controller side restlet server that receives ZNRecordUpdate requests from
 * clients, and batch the ZNRecordUpdate and apply them to zookeeper. This is 
 * to optimize the concurrency level of zookeeper access for ZNRecord updates 
 * that does not require real-time, like message handling status updates and 
 * healthcheck reports. 
 * 
 * As one server will be used by multiple helix controllers that runs on the same machine,
 * This class is designed as a singleton. Application is responsible to call init() 
 * and shutdown() on the getInstance().
 * */
public class ZKPropertyTransferServer
{
  public static final String PORT = "port";
  public static final String SERVER = "ZKPropertyTransferServer";
  public static int PERIOD = 10 * 1000;
  public static String RESTRESOURCENAME = "ZNRecordUpdates";
  // If the buffered ZNRecord updates exceed the limit, do a zookeeper batch update.
  public static int MAX_UPDATE_LIMIT = 1000;
  private static Logger LOG = Logger.getLogger(ZKPropertyTransferServer.class);
  
  int _localWebservicePort;
  String _webserviceUrl;
  ZkBaseDataAccessor<ZNRecord> _accessor;
  String _zkAddress;
  
  AtomicReference<ConcurrentHashMap<String, ZNRecordUpdate>> _dataBufferRef
    = new AtomicReference<ConcurrentHashMap<String, ZNRecordUpdate>>();
  
  boolean _initialized = false;
  boolean _shutdownFlag = false;
  Component _component = null;
  Timer _timer = null;
  
  /**
   * Timertask for zookeeper batched writes
   * */
  class ZKPropertyTransferTask extends TimerTask
  {
    @Override
    public void run()
    {
      try
      {
        sendData();
      }
      catch(Throwable t)
      {
        LOG.error("", t);
      }
    
    }
  }
  
  void sendData()
  {
    ConcurrentHashMap<String, ZNRecordUpdate> updateCache  = null;
    
    synchronized(_dataBufferRef)
    {
      updateCache = _dataBufferRef.getAndSet(new ConcurrentHashMap<String, ZNRecordUpdate>());
    }
    
    if(updateCache != null)
    {
      List<String> paths = new ArrayList<String>();
      List<DataUpdater<ZNRecord>> updaters = new ArrayList<DataUpdater<ZNRecord>>();
      List<ZNRecord> vals = new ArrayList<ZNRecord>();
      for(ZNRecordUpdate holder : updateCache.values())
      {
        paths.add(holder.getPath());
        updaters.add(holder.getZNRecordUpdater());
        vals.add(holder.getRecord());
      }
      // Batch write the accumulated updates into zookeeper
      if(paths.size() > 0)
      {
        _accessor.updateChildren(paths, updaters, BaseDataAccessor.Option.PERSISTENT);
      }
      LOG.info("Updating " + vals.size() + " records");
    }
    else
    {
      LOG.warn("null _dataQueueRef. Should be in the beginning only");
    }
  }
  
  static ZKPropertyTransferServer _instance = new ZKPropertyTransferServer();
  
  private ZKPropertyTransferServer()
  {
    _dataBufferRef.getAndSet(new ConcurrentHashMap<String, ZNRecordUpdate>());
  }
  
  public static ZKPropertyTransferServer getInstance()
  {
    return _instance;
  }
  
  public boolean isInitialized()
  {
    return _initialized;
  }
  
  public void init(int localWebservicePort, String zkAddress)
  {
    if(!_initialized && !_shutdownFlag)
    {
      LOG.error("Initializing with port " + _localWebservicePort + " zkAddress: " + zkAddress);
      _localWebservicePort = localWebservicePort;
      ZkClient zkClient = new ZkClient(zkAddress);
      zkClient.setZkSerializer(new ZNRecordSerializer());
      _accessor = new ZkBaseDataAccessor<ZNRecord>(zkClient);
      _zkAddress = zkAddress;
      startServer();
    }
    else
    {
      LOG.error("Already initialized with port " + _localWebservicePort + " shutdownFlag: " + _shutdownFlag);
    }
  }
  
  public String getWebserviceUrl()
  {
    if(!_initialized || _shutdownFlag)
    {
      LOG.debug("inited:" + _initialized + " shutdownFlag:"+_shutdownFlag+" , return");
      return null;
    }
    return _webserviceUrl;
  }
  
  /** Add an ZNRecordUpdate to the change queue. 
   *  Called by the webservice front-end.
   *
   */
  void enqueueData(ZNRecordUpdate e)
  {
    if(!_initialized || _shutdownFlag)
    {
      LOG.error("zkDataTransferServer inited:" + _initialized 
          + " shutdownFlag:"+_shutdownFlag+" , return");
      return;
    }
    // Do local merge if receive multiple update on the same path
    synchronized(_dataBufferRef)
    {
      e.getRecord().setSimpleField(SERVER, _webserviceUrl);
      if(_dataBufferRef.get().containsKey(e.getPath()))
      {
        ZNRecord oldVal = _dataBufferRef.get().get(e.getPath()).getRecord();
        oldVal = e.getZNRecordUpdater().update(oldVal);
        _dataBufferRef.get().get(e.getPath())._record = oldVal;
      }
      else
      {
        _dataBufferRef.get().put(e.getPath(), e);
      }
    }
    if(_dataBufferRef.get().size() > MAX_UPDATE_LIMIT)
    {
      sendData();
    }
  }
  
  void startServer()
  {
    LOG.info("zkDataTransferServer starting on Port " + _localWebservicePort + " zkAddress " + _zkAddress);
    
    _component = new Component();
    
    _component.getServers().add(Protocol.HTTP, _localWebservicePort);
    Context applicationContext = _component.getContext().createChildContext();
    applicationContext.getAttributes().put(SERVER, this);
    applicationContext.getAttributes().put(PORT, "" + _localWebservicePort);
    ZkPropertyTransferApplication application = new ZkPropertyTransferApplication(
        applicationContext);
    // Attach the application to the component and start it
    _component.getDefaultHost().attach(application);
    _timer = new Timer();
    _timer.schedule(new ZKPropertyTransferTask(), PERIOD , PERIOD);
    
    try
    {
      _webserviceUrl 
        = "http://" + InetAddress.getLocalHost().getCanonicalHostName() + ":" + _localWebservicePort 
              + "/" + RESTRESOURCENAME;
      _component.start();
      _initialized = true;
    }
    catch (Exception e)
    {
      LOG.error("", e);
    }
    LOG.info("zkDataTransferServer started on Port " + _localWebservicePort + " zkAddress " + _zkAddress);
  }
  
  public void shutdown()
  {
    if(_shutdownFlag)
    {
      LOG.error("ZKPropertyTransferServer already has been shutdown...");
      return;
    }
    LOG.info("zkDataTransferServer shuting down on Port " + _localWebservicePort + " zkAddress " + _zkAddress);
    if(_timer != null)
    {
      _timer.cancel();
    }
    if(_component != null)
    {
      try
      {
        _component.stop();
      }
      catch (Exception e)
      {
        LOG.error("", e);
      }
    }
    _shutdownFlag = true;
  }

  public void reset()
  {
    if(_shutdownFlag == true)
    {
      _shutdownFlag = false;
      _initialized = false;
      _component = null;
      _timer = null;
      _dataBufferRef.getAndSet(new ConcurrentHashMap<String, ZNRecordUpdate>());
    }
  }
}
