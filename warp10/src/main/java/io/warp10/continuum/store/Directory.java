//
//   Copyright 2016  Cityzen Data
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.continuum.store;

import io.warp10.continuum.DirectoryUtil;
import io.warp10.continuum.JettyUtil;
import io.warp10.continuum.KafkaOffsetCounters;
import io.warp10.continuum.gts.GTSHelper;
import io.warp10.continuum.sensision.SensisionConstants;
import io.warp10.continuum.store.thrift.data.DirectoryFindRequest;
import io.warp10.continuum.store.thrift.data.DirectoryFindResponse;
import io.warp10.continuum.store.thrift.data.DirectoryGetRequest;
import io.warp10.continuum.store.thrift.data.DirectoryGetResponse;
import io.warp10.continuum.store.thrift.data.DirectoryStatsRequest;
import io.warp10.continuum.store.thrift.data.DirectoryStatsResponse;
import io.warp10.continuum.store.thrift.data.Metadata;
import io.warp10.continuum.store.thrift.service.DirectoryService;
import io.warp10.crypto.CryptoUtils;
import io.warp10.crypto.KeyStore;
import io.warp10.crypto.OrderPreservingBase64;
import io.warp10.crypto.SipHashInline;
import io.warp10.script.WarpScriptException;
import io.warp10.script.HyperLogLogPlus;
import io.warp10.script.functions.PARSESELECTOR;
import io.warp10.sensision.Sensision;
import io.warp10.warp.sdk.DirectoryPlugin;
import io.warp10.warp.sdk.DirectoryPlugin.GTS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESWrapEngine;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.encoders.Hex;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.MapMaker;
import com.google.common.primitives.Longs;
import com.netflix.curator.framework.CuratorFramework;
import com.netflix.curator.framework.CuratorFrameworkFactory;
import com.netflix.curator.retry.RetryNTimes;
import com.netflix.curator.x.discovery.ServiceDiscovery;
import com.netflix.curator.x.discovery.ServiceDiscoveryBuilder;
import com.netflix.curator.x.discovery.ServiceInstance;
import com.netflix.curator.x.discovery.ServiceInstanceBuilder;
import com.netflix.curator.x.discovery.ServiceType;

/**
 * Manages Metadata for a subset of known GTS.
 * Listens to Kafka to get updates of and new Metadatas 
 */
public class Directory extends AbstractHandler implements DirectoryService.Iface, Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(Directory.class);

  /**
   * Comparator which sorts the IDs in their lexicographical order suitable for scanning HBase keys
   */
  private static final Comparator<Long> ID_COMPARATOR = new Comparator<Long>() {
    @Override
    public int compare(Long o1, Long o2) {
      if (Long.signum(o1) == Long.signum(o2)) {
        return o1.compareTo(o2);
      } else {
        //
        // 0 is the first key
        //
        if (0 == o1) {
          return -1;
        } else if (0 == o2) {
          return 1;
        } else {
          //
          // If the two numbers have differents signs, then the positive values MUST appear before the negative ones
          //
          if (o1 > 0) {
            return -1;
          } else {
            return 1;
          }          
        }
      }
    }
  };

  public static final String PAYLOAD_MODULUS_KEY = "modulus";
  public static final String PAYLOAD_REMAINDER_KEY = "remainder";
  public static final String PAYLOAD_THRIFT_PROTOCOL_KEY = "thrift.protocol";
  public static final String PAYLOAD_THRIFT_TRANSPORT_KEY = "thrift.transport";
  public static final String PAYLOAD_THRIFT_MAXFRAMELEN_KEY = "thrift.maxframelen";
  public static final String PAYLOAD_STREAMING_PORT_KEY = "streaming.port";

  /**
   * Values of P and P' for the HyperLogLogPlus estimators
   */
  public static final int ESTIMATOR_P = 14;
  private static final int ESTIMATOR_PPRIME = 25;

  /**
   * Allow individual tracking of 100 class names
   */
  private long LIMIT_CLASS_CARDINALITY = 100;
  
  /**
   * Allow tracking of 100 label names
   */
  private long LIMIT_LABELS_CARDINALITY = 100;
  
  private final KeyStore keystore;

  /**
   * Name under which the directory service is registered in ZK
   */
  public static final String DIRECTORY_SERVICE = "com.cityzendata.continuum.directory";
  
  private static final String DIRECTORY_INIT_NTHREADS_DEFAULT = "4";
  
  /**
   * row key prefix for metadata
   */
  public static final byte[] HBASE_METADATA_KEY_PREFIX = "M".getBytes(Charsets.UTF_8);
  
  private final int modulus;
  private final int remainder;
  private String host;
  private int port;
  private int streamingport;
  private int streamingselectors;
  private int streamingacceptors;
    
  /**
   * Set of required parameters, those MUST be set
   */
  private static final String[] REQUIRED_PROPERTIES = new String[] {
    io.warp10.continuum.Configuration.DIRECTORY_ZK_QUORUM,
    io.warp10.continuum.Configuration.DIRECTORY_ZK_ZNODE,
    io.warp10.continuum.Configuration.DIRECTORY_SERVICE_NTHREADS,
    io.warp10.continuum.Configuration.DIRECTORY_KAFKA_NTHREADS,
    io.warp10.continuum.Configuration.DIRECTORY_PARTITION,
    io.warp10.continuum.Configuration.DIRECTORY_HOST,
    io.warp10.continuum.Configuration.DIRECTORY_PORT,
    io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_ZKCONNECT,
    io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_TOPIC,
    io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_GROUPID,
    io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_COMMITPERIOD,
    io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_MAXPENDINGPUTSSIZE,
    io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_ZKCONNECT,
    io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_TABLE,
    io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_COLFAM,
    io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_ZNODE,
    io.warp10.continuum.Configuration.DIRECTORY_PSK,
    io.warp10.continuum.Configuration.DIRECTORY_MAXAGE,
    io.warp10.continuum.Configuration.DIRECTORY_STREAMING_PORT,
    io.warp10.continuum.Configuration.DIRECTORY_STREAMING_SELECTORS,
    io.warp10.continuum.Configuration.DIRECTORY_STREAMING_ACCEPTORS,
    io.warp10.continuum.Configuration.DIRECTORY_STREAMING_IDLE_TIMEOUT,
    io.warp10.continuum.Configuration.DIRECTORY_STREAMING_THREADPOOL,
    io.warp10.continuum.Configuration.DIRECTORY_FIND_MAXRESULTS_HARD,
    io.warp10.continuum.Configuration.DIRECTORY_REGISTER,
    io.warp10.continuum.Configuration.DIRECTORY_INIT,
    io.warp10.continuum.Configuration.DIRECTORY_STORE,
    io.warp10.continuum.Configuration.DIRECTORY_DELETE,
  };

  /**
   * Name of HBase table where metadata should be written
   */
  private final TableName hbaseTable;
  
  /**
   * Name of column family where metadata should be written
   */
  private final byte[] colfam;
  
  /**
   * How often to commit Kafka offsets
   */
  private final long commitPeriod;

  /**
   * How big do we allow the Put list to grow
   */
  private final long maxPendingPutsSize;
  
  /**
   * Instance of HBase connection to create Table instances
   */
  private final Connection conn;
  
  /**
   * CyclicBarrier instance to synchronize consuming threads prior to committing offsets
   */
  private CyclicBarrier barrier;
  
  /**
   * Flag for signaling abortion of consuming process
   */
  private final AtomicBoolean abort = new AtomicBoolean(false);
  
  /**
   * Maps of class name to labelsId to metadata
   */
  private final Map<String,Map<Long,Metadata>> metadatas = new MapMaker().concurrencyLevel(64).makeMap();
  
  /**
   * Map of classId to class names
   */
  //private final Map<Long,String> classNames = new MapMaker().concurrencyLevel(64).makeMap();
  private final Map<Long,String> classNames = new ConcurrentSkipListMap<Long, String>(ID_COMPARATOR);
  
  private final Map<String,Set<String>> classesPerProducer = new MapMaker().concurrencyLevel(64).makeMap();
  
  /**
   * Number of threads for servicing requests
   */
  private final int serviceNThreads;
  
  private final AtomicBoolean cachePopulated = new AtomicBoolean(false);
  
  private final Properties properties;

  private final ServiceDiscovery<Map> sd;
  
  private final long[] SIPHASH_CLASS_LONGS;
  private final long[] SIPHASH_LABELS_LONGS;
  private final long[] SIPHASH_PSK_LONGS;
  
  /**
   * Maximum age of a Find request
   */
  private final long maxage;
  
  private final int maxThriftFrameLength;
  
  private final int maxFindResults;
  
  private final int maxHardFindResults;
  
  private final int initNThreads;
  
  private final long idleTimeout;
  
  /**
   * Should we register our service in ZK
   */
  private final boolean register;
  
  /**
   * Should we initialize Directory upon startup by reading from HBase
   */
  private final boolean init;
  
  /**
   * Should we store in HBase metadata we receive via Kafka
   */
  private final boolean store;

  /**
   * Should we delete in HBase
   */
  private final boolean delete;

  /**
   * Directory plugin to use
   */
  private final DirectoryPlugin plugin;
  
  public Directory(KeyStore keystore, final Properties props) throws IOException {
    this.keystore = keystore;

    SIPHASH_CLASS_LONGS = SipHashInline.getKey(this.keystore.getKey(KeyStore.SIPHASH_CLASS));
    SIPHASH_LABELS_LONGS = SipHashInline.getKey(this.keystore.getKey(KeyStore.SIPHASH_LABELS));
    
    this.properties = (Properties) props.clone();
        
    //
    // Check mandatory parameters
    //
    
    for (String required: REQUIRED_PROPERTIES) {
      Preconditions.checkNotNull(properties.getProperty(required), "Missing configuration parameter '%s'.", required);          
    }

    maxThriftFrameLength = Integer.parseInt(this.properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_FRAME_MAXLEN, "0"));

    maxFindResults = Integer.parseInt(this.properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_FIND_MAXRESULTS, "100000"));
  
    maxHardFindResults = Integer.parseInt(this.properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_FIND_MAXRESULTS_HARD));

    this.register = "true".equals(this.properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_REGISTER));
    this.init = "true".equals(this.properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_INIT));
    this.store = "true".equals(this.properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_STORE));
    this.delete = "true".equals(this.properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_DELETE));

    //
    // Extract parameters
    //
    
    idleTimeout = Long.parseLong(this.properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_STREAMING_IDLE_TIMEOUT));

    if (properties.containsKey(io.warp10.continuum.Configuration.DIRECTORY_STATS_CLASS_MAXCARDINALITY)) {
      this.LIMIT_CLASS_CARDINALITY = Long.parseLong(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_STATS_CLASS_MAXCARDINALITY));
    }

    if (properties.containsKey(io.warp10.continuum.Configuration.DIRECTORY_STATS_LABELS_MAXCARDINALITY)) {
      this.LIMIT_LABELS_CARDINALITY = Long.parseLong(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_STATS_LABELS_MAXCARDINALITY));
    }

    this.initNThreads = Integer.parseInt(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_INIT_NTHREADS, DIRECTORY_INIT_NTHREADS_DEFAULT));

    String partition = properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_PARTITION);
    String[] tokens = partition.split(":");
    this.modulus = Integer.parseInt(tokens[0]);
    this.remainder = Integer.parseInt(tokens[1]);
    
    this.maxage = Long.parseLong(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_MAXAGE));
    
    final String topic = properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_TOPIC);
    final int nthreads = Integer.valueOf(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_KAFKA_NTHREADS));
    
    Configuration conf = new Configuration();
    conf.set("hbase.zookeeper.quorum", properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_ZKCONNECT));
    if (!"".equals(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_ZNODE))) {
      conf.set("zookeeper.znode.parent", properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_ZNODE));
    }
    
    this.conn = ConnectionFactory.createConnection(conf);

    this.hbaseTable = TableName.valueOf(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_TABLE));
    this.colfam = properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_COLFAM).getBytes(Charsets.UTF_8);
    
    this.serviceNThreads = Integer.valueOf(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_SERVICE_NTHREADS));
    
    //
    // Extract keys
    //
    
    extractKeys(properties);

    SIPHASH_PSK_LONGS = SipHashInline.getKey(this.keystore.getKey(KeyStore.SIPHASH_DIRECTORY_PSK));

    //
    // Load Directory plugin
    //
    
    if (this.properties.containsKey(io.warp10.continuum.Configuration.DIRECTORY_PLUGIN_CLASS)) {
      try {
        // Create new classloader with filtering so caller cannot access the warp10 classes, except those needed
        ClassLoader filteringCL = new ClassLoader(this.getClass().getClassLoader()) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {                
            if (name.startsWith("io.warp10") && !name.startsWith("io.warp10.warp.sdk.")) {
              throw new ClassNotFoundException();
            } else {
              return this.getParent().loadClass(name);
            }
          }
        };

        Class pluginClass = Class.forName((String)properties.get(io.warp10.continuum.Configuration.DIRECTORY_PLUGIN_CLASS), true, filteringCL);
        this.plugin = (DirectoryPlugin) pluginClass.newInstance();
        
        //
        // Now call the 'init' method of the plugin
        //
        
        this.plugin.init(new Properties(properties));
      } catch (Exception e) {
        throw new RuntimeException("Unable to instantiate plugin class", e);
      }
    } else {
      this.plugin = null;
    }
    
    //
    // Create Curator framework and service discovery
    //
    
    CuratorFramework curatorFramework = CuratorFrameworkFactory.builder()
        .connectionTimeoutMs(1000)
        .retryPolicy(new RetryNTimes(10, 500))
        .connectString(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_ZK_QUORUM))
        .build();
    curatorFramework.start();

    this.sd = ServiceDiscoveryBuilder.builder(Map.class)
        .basePath(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_ZK_ZNODE))
        .client(curatorFramework)
        .build();
    
    //
    // Launch a Thread which will populate the metadata cache
    // We don't do that in the constructor otherwise it might take too long to return
    //

    final Directory self = this;

    if (this.init) {

      Thread[] initThreads = new Thread[this.initNThreads];
      final AtomicBoolean[] stopMarkers = new AtomicBoolean[this.initNThreads];
      
      final LinkedBlockingQueue<Result> resultQ = new LinkedBlockingQueue<Result>(initThreads.length * 8192);
      
      for (int i = 0; i < initThreads.length; i++) {
        stopMarkers[i] = new AtomicBoolean(false);
        final AtomicBoolean stopMe = stopMarkers[i];
        initThreads[i] = new Thread(new Runnable() {
          @Override
          public void run() {
            AESWrapEngine engine = new AESWrapEngine();
            CipherParameters params = new KeyParameter(self.keystore.getKey(KeyStore.AES_HBASE_METADATA));
            engine.init(false, params);

            PKCS7Padding padding = new PKCS7Padding();

            TDeserializer deserializer = new TDeserializer(new TCompactProtocol.Factory());

            while (!stopMe.get()) {
              try {
                
                Result result = resultQ.poll(100, TimeUnit.MILLISECONDS);
                
                if (null == result) {
                  continue;
                }
                
                byte[] value = result.getValue(self.colfam, Constants.EMPTY_COLQ);
                
                //
                // Unwrap
                //
                
                byte[] unwrapped = engine.unwrap(value, 0, value.length);
                
                //
                // Unpad
                //
                
                int padcount = padding.padCount(unwrapped);
                byte[] unpadded = Arrays.copyOf(unwrapped, unwrapped.length - padcount);
                
                //
                // Deserialize
                //

                Metadata metadata = new Metadata();
                deserializer.deserialize(metadata, unpadded);

                //
                // Compute classId/labelsId and compare it to the values in the row key
                //
                
                long classId = GTSHelper.classId(self.SIPHASH_CLASS_LONGS, metadata.getName());
                long labelsId = GTSHelper.labelsId(self.SIPHASH_LABELS_LONGS, metadata.getLabels());
                
                //
                // Recheck labelsid so we don't retain GTS with invalid labelsid in the row key (which may have happened due
                // to bugs)
                //
                
                int rem = ((int) ((labelsId >>> 56) & 0xffL)) % self.modulus;
                
                if (self.remainder != rem) {
                  continue;
                }
                
                ByteBuffer bb = ByteBuffer.wrap(result.getRow()).order(ByteOrder.BIG_ENDIAN);
                bb.position(1);
                long hbClassId = bb.getLong();
                long hbLabelsId = bb.getLong();
                
                // If classId/labelsId are incoherent, skip metadata
                if (classId != hbClassId || labelsId != hbLabelsId) {
                  LOG.warn("Incoherent class/labels Id for " + metadata);
                  continue;
                }
          
                metadata.setClassId(classId);
                metadata.setLabelsId(labelsId);
                
                if (!metadata.isSetAttributes()) {
                  metadata.setAttributes(new HashMap<String,String>());
                }
                
                //
                // Internalize Strings
                //
                
                GTSHelper.internalizeStrings(metadata);
                
                //
                // Let the DirectoryPlugin handle the Metadata
                //
                
                if (null != plugin) {
                  
                  long nano = 0;
                  
                  try {
                    GTS gts = new GTS(
                        new UUID(metadata.getClassId(), metadata.getLabelsId()),
                        metadata.getName(),
                        metadata.getLabels(),
                        metadata.getAttributes());
                    
                    nano = System.nanoTime();
                    
                    if (!plugin.store(null, gts)) {
                      throw new RuntimeException("Error storing GTS " + gts + " using external plugin.");
                    }                    
                  } finally {
                    nano = System.nanoTime() - nano;
                    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_STORE_CALLS, Sensision.EMPTY_LABELS, 1);
                    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_STORE_TIME_NANOS, Sensision.EMPTY_LABELS, nano);                                      
                  }
                  continue;
                }
                
                synchronized(metadatas) {
                  if (!metadatas.containsKey(metadata.getName())) {
                    //metadatas.put(metadata.getName(), new ConcurrentHashMap<Long, Metadata>());
                    metadatas.put(metadata.getName(), new ConcurrentSkipListMap<Long, Metadata>(ID_COMPARATOR));
                    classNames.put(classId, metadata.getName());
                  }                
                }
                
                //
                // Store per producer class name. We use the name since it has been internalized,
                // therefore we conly consume the HashNode and the HashSet overhead
                //
                
                String producer = metadata.getLabels().get(Constants.PRODUCER_LABEL);
                
                synchronized(classesPerProducer) {
                  Set<String> classes = classesPerProducer.get(producer);
                  
                  if (null == classes) {
                    classes = new HashSet<String>();
                    classesPerProducer.put(producer, classes);
                  }
                  
                  classes.add(metadata.getName());
                }

                Sensision.set(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PRODUCERS, Sensision.EMPTY_LABELS, classesPerProducer.size());

                synchronized(metadatas.get(metadata.getName())) {
                  if (!metadatas.get(metadata.getName()).containsKey(labelsId)) {
                    metadatas.get(metadata.getName()).put(labelsId, metadata);
                    continue;
                  } else if (!metadatas.get(metadata.getName()).get(labelsId).getLabels().equals(metadata.getLabels())) {
                    LOG.warn("LabelsId collision under class '" + metadata.getName() + "' " + metadata.getLabels() + " and " + metadatas.get(metadata.getName()).get(labelsId).getLabels());
                    Sensision.update(SensisionConstants.CLASS_WARP_DIRECTORY_LABELS_COLLISIONS, Sensision.EMPTY_LABELS, 1);                    
                  }
                }
                
                continue;
              } catch (InvalidCipherTextException icte) {
                throw new RuntimeException(icte);
              } catch (TException te) {
                throw new RuntimeException(te);
              } catch (InterruptedException ie) {              
              }
            }
          }
        });
        
        initThreads[i].setDaemon(true);
        initThreads[i].setName("[Directory initializer #" + i + "]");
        initThreads[i].start();
      }
      
      Thread populator = new Thread(new Runnable() {
        
        @Override
        public void run() {
          
          long nano = System.nanoTime();
          
          Table htable = null;
          
          long count = 0L;
          
          boolean done = false;
          
          byte[] lastrow = HBASE_METADATA_KEY_PREFIX;
          
          while(!done) {
            try {
              //
              // Populate the metadata cache with initial data from HBase
              //
                       
              htable = self.conn.getTable(self.hbaseTable);

              Scan scan = new Scan();
              scan.setStartRow(lastrow);
              // FIXME(hbs): we know the prefix is 'M', so we use 'N' as the stoprow
              scan.setStopRow("N".getBytes(Charsets.UTF_8));
              scan.addFamily(self.colfam);
              scan.setCaching(10000);
              scan.setBatch(10000);
              scan.setMaxResultSize(1000000L);

              ResultScanner scanner = htable.getScanner(scan);
                        
              do {
                Result result = scanner.next();

                if (null == result) {
                  done = true;
                  break;
                }
                
                //
                // FIXME(hbs): this could be done in a filter on the RS side
                //
                
                int r = (((int) result.getRow()[HBASE_METADATA_KEY_PREFIX.length + 8]) & 0xff) % self.modulus;
                
                //byte r = (byte) (result.getRow()[HBASE_METADATA_KEY_PREFIX.length + 8] % self.modulus);
                
                // Skip metadata if its modulus is not the one we expect
                if (self.remainder != r) {
                  continue;
                }
                
                //
                // Store the current row so we can restart from there if an exception occurs
                //
                
                lastrow = result.getRow();
                
                boolean interrupted = true;
                
                while(interrupted) {
                  interrupted = false;
                  try {
                    resultQ.put(result);
                    count++;
                    if (0 == count % 1000) {
                      Sensision.set(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_GTS, Sensision.EMPTY_LABELS, count);
                    }
                  } catch (InterruptedException ie) {
                    interrupted = true;
                  }
                }
                
              } while (true);
              
            } catch (IOException ioe) {
              LOG.error("Caught exception in scanning loop, will attempt to continue where we stopped", ioe);
            } finally {
              if (null != htable) { try { htable.close(); } catch (Exception e) {} }
              Sensision.set(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_GTS, Sensision.EMPTY_LABELS, count);
            }                    
          }
          
          //
          // Wait until resultQ is empty
          //
          
          while(!resultQ.isEmpty()) {
            try { Thread.sleep(100L); } catch (InterruptedException ie) {}
          }
          
          //
          // Notify the init threads to stop
          //
          
          for (int i = 0; i < initNThreads; i++) {
            stopMarkers[i].set(true);
          }
          
          self.cachePopulated.set(true);
          
          nano = System.nanoTime() - nano;
          
          LOG.info("Loaded " + count + " GTS in " + (nano / 1000000.0D) + " ms");
        }            
      });
      
      populator.setName("Warp Directory Populator");
      populator.setDaemon(true);
      populator.start();      
    } else {
      LOG.info("Skipped initialization");
      this.cachePopulated.set(true);
    }
    
    this.commitPeriod = Long.valueOf(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_COMMITPERIOD));
    
    this.maxPendingPutsSize = Long.parseLong(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_MAXPENDINGPUTSSIZE));
    
    this.host = properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_HOST);
    this.port = Integer.parseInt(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_PORT));
    this.streamingport = Integer.parseInt(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_STREAMING_PORT));
    this.streamingacceptors = Integer.parseInt(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_STREAMING_ACCEPTORS));
    this.streamingselectors = Integer.parseInt(properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_STREAMING_SELECTORS));
    
    int streamingMaxThreads = Integer.parseInt(props.getProperty(io.warp10.continuum.Configuration.DIRECTORY_STREAMING_THREADPOOL));
    
    final String groupid = properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_GROUPID);

    final KafkaOffsetCounters counters = new KafkaOffsetCounters(topic, groupid, this.commitPeriod * 2);

    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        
        //
        // Wait until cache has been populated
        //
        
        while(!self.cachePopulated.get()) {
          try { Thread.sleep(1000L); } catch (InterruptedException ie) {};
        }

        Sensision.set(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_CLASSES, Sensision.EMPTY_LABELS, classNames.size());
        
        //
        // Enter an endless loop which will spawn 'nthreads' threads
        // each time the Kafka consumer is shut down (which will happen if an error
        // happens while talking to HBase, to get a chance to re-read data from the
        // previous snapshot).
        //
        
        while (true) {
          try {
            Map<String,Integer> topicCountMap = new HashMap<String, Integer>();
            
            topicCountMap.put(topic, nthreads);
                        
            Properties props = new Properties();
            props.setProperty("zookeeper.connect", properties.getProperty(io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_ZKCONNECT));
            props.setProperty("group.id", groupid);
            props.setProperty("auto.commit.enable", "false");    
            
            ConsumerConfig config = new ConsumerConfig(props);
            ConsumerConnector connector = Consumer.createJavaConsumerConnector(config);

            Map<String,List<KafkaStream<byte[], byte[]>>> consumerMap = connector.createMessageStreams(topicCountMap);
            
            List<KafkaStream<byte[], byte[]>> streams = consumerMap.get(topic);
            
            self.barrier = new CyclicBarrier(streams.size() + 1);

            ExecutorService executor = Executors.newFixedThreadPool(nthreads);
            
            //
            // now create runnables which will consume messages
            //
            
            // Reset counters
            counters.reset();
            
            for (final KafkaStream<byte[],byte[]> stream : streams) {
              executor.submit(new DirectoryConsumer(self, stream, counters));
            }      
            
            while(!abort.get()) {
              if (streams.size() == barrier.getNumberWaiting()) {
                //
                // Check if we should abort, which could happen when
                // an exception was thrown when flushing the commits just before
                // entering the barrier
                //
                
                if (abort.get()) {
                  break;
                }
                  
                //
                // All processing threads are waiting on the barrier, this means we can flush the offsets because
                // they have all processed data successfully for the given activity period
                //
                
                // Commit offsets
                connector.commitOffsets();
                counters.sensisionPublish();
                
                Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_KAFKA_COMMITS, Sensision.EMPTY_LABELS, 1);
                
                // Release the waiting threads
                try {
                  barrier.await();
                } catch (Exception e) {
                  break;
                }
              }
              try {
                Thread.sleep(100L);          
              } catch (InterruptedException ie) {          
              }
            }

            //
            // We exited the loop, this means one of the threads triggered an abort,
            // we will shut down the executor and shut down the connector to start over.
            //
            
            executor.shutdownNow();
            connector.shutdown();
            abort.set(false);
          } catch (Throwable t) {
            LOG.error("", t);
          } finally {
            try { Thread.sleep(1000L); } catch (InterruptedException ie) {}
          }
        }          
      }
    });
    
    t.setName("Warp Directory Spawner");
    t.setDaemon(true);
    t.start();
    
    t = new Thread(this);
    t.setName("Warp Directory");
    t.setDaemon(true);
    t.start();
    
    //
    // Start Jetty for the streaming service
    //
    
    //
    // Start Jetty server for the streaming service
    //
    
    BlockingArrayQueue<Runnable> queue = null;
    
    if (props.containsKey(io.warp10.continuum.Configuration.DIRECTORY_STREAMING_MAXQUEUESIZE)) {
      int queuesize = Integer.parseInt(props.getProperty(io.warp10.continuum.Configuration.DIRECTORY_STREAMING_MAXQUEUESIZE));
      queue = new BlockingArrayQueue<Runnable>(queuesize);
    }

    Server server = new Server(new QueuedThreadPool(streamingMaxThreads,8, (int) idleTimeout, queue));
    ServerConnector connector = new ServerConnector(server, this.streamingacceptors, this.streamingselectors);
    connector.setIdleTimeout(idleTimeout);
    connector.setPort(this.streamingport);
    connector.setHost(host);
    connector.setName("Directory Streaming Service");
    
    server.setConnectors(new Connector[] { connector });

    server.setHandler(this);
    
    JettyUtil.setSendServerVersion(server, false);
    
    try {
      server.start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  @Override
  public void run() {
    
    //
    // Wait until cache has been populated
    //
    
    while(!this.cachePopulated.get()) {
      Sensision.set(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_JVM_FREEMEMORY, Sensision.EMPTY_LABELS, Runtime.getRuntime().freeMemory());
      try { Thread.sleep(1000L); } catch (InterruptedException ie) {}
    }

    Sensision.set(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_JVM_FREEMEMORY, Sensision.EMPTY_LABELS, Runtime.getRuntime().freeMemory());

    //
    // Start the Thrift Service
    //
        
    DirectoryService.Processor processor = new DirectoryService.Processor(this);
    
    ServiceInstance<Map> instance = null;

    try {
      InetSocketAddress bindAddress = new InetSocketAddress(this.host, this.port);
      TServerTransport transport = new TServerSocket(bindAddress);
      TThreadPoolServer.Args args = new TThreadPoolServer.Args(transport);
      args.processor(processor);
      //
      // FIXME(dmn): Set the min/max threads in the config file ?
      args.maxWorkerThreads(this.serviceNThreads);
      args.minWorkerThreads(this.serviceNThreads);
      if (0 != maxThriftFrameLength) {
        args.inputTransportFactory(new io.warp10.thrift.TFramedTransport.Factory(maxThriftFrameLength));
        args.outputTransportFactory(new io.warp10.thrift.TFramedTransport.Factory(maxThriftFrameLength));        
      } else {
        args.inputTransportFactory(new io.warp10.thrift.TFramedTransport.Factory());
        args.outputTransportFactory(new io.warp10.thrift.TFramedTransport.Factory());
      }
      args.inputProtocolFactory(new TCompactProtocol.Factory());
      args.outputProtocolFactory(new TCompactProtocol.Factory());
      TServer server = new TThreadPoolServer(args);
      
      //
      // TODO(hbs): Check that the number of registered services does not go over the licensed number
      //

      ServiceInstanceBuilder<Map> builder = ServiceInstance.builder();
      builder.port(((TServerSocket) transport).getServerSocket().getLocalPort());
      builder.address(((TServerSocket) transport).getServerSocket().getInetAddress().getHostAddress());
      builder.id(UUID.randomUUID().toString());
      builder.name(DIRECTORY_SERVICE);
      builder.serviceType(ServiceType.DYNAMIC);
      Map<String,String> payload = new HashMap<String,String>();
      
      payload.put(PAYLOAD_MODULUS_KEY, Integer.toString(modulus));
      payload.put(PAYLOAD_REMAINDER_KEY, Integer.toString(remainder));
      payload.put(PAYLOAD_THRIFT_PROTOCOL_KEY, "org.apache.thrift.protocol.TCompactProtocol");
      payload.put(PAYLOAD_THRIFT_TRANSPORT_KEY, "org.apache.thrift.transport.TFramedTransport");
      payload.put(PAYLOAD_STREAMING_PORT_KEY, Integer.toString(this.streamingport));
      if (0 != maxThriftFrameLength) {
        payload.put(PAYLOAD_THRIFT_MAXFRAMELEN_KEY, Integer.toString(maxThriftFrameLength));
      }
      builder.payload(payload);

      instance = builder.build();

      if (this.register) {
        sd.start();
        sd.registerService(instance);
      }
      
      server.serve();

    } catch (TTransportException tte) {
      LOG.error("",tte);
    } catch (Exception e) {
      LOG.error("", e);
    } finally {
      if (null != instance) {
        try {
          sd.unregisterService(instance);
        } catch (Exception e) {
        }
      }
    }
  }
  
  /**
   * Extract Directory related keys and populate the KeyStore with them.
   * 
   * @param props Properties from which to extract the key specs
   */
  private void extractKeys(Properties props) {
    String keyspec = props.getProperty(io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_MAC);
    
    if (null != keyspec) {
      byte[] key = this.keystore.decodeKey(keyspec);
      Preconditions.checkArgument(16 == key.length, "Key " + io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_MAC + " MUST be 128 bits long.");
      this.keystore.setKey(KeyStore.SIPHASH_KAFKA_METADATA, key);
    }

    keyspec = props.getProperty(io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_AES);
    
    if (null != keyspec) {
      byte[] key = this.keystore.decodeKey(keyspec);
      Preconditions.checkArgument(16 == key.length || 24 == key.length || 32 == key.length, "Key " + io.warp10.continuum.Configuration.DIRECTORY_KAFKA_METADATA_AES + " MUST be 128, 192 or 256 bits long.");
      this.keystore.setKey(KeyStore.AES_KAFKA_METADATA, key);
    }
    
    keyspec = props.getProperty(io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_AES);
    
    if (null != keyspec) {
      byte[] key = this.keystore.decodeKey(keyspec);
      Preconditions.checkArgument(16 == key.length || 24 == key.length || 32 == key.length, "Key " + io.warp10.continuum.Configuration.DIRECTORY_HBASE_METADATA_AES + " MUST be 128, 192 or 256 bits long.");
      this.keystore.setKey(KeyStore.AES_HBASE_METADATA, key);
    }
    
    keyspec = props.getProperty(io.warp10.continuum.Configuration.DIRECTORY_PSK);
    
    if (null != keyspec) {
      byte[] key = this.keystore.decodeKey(keyspec);
      Preconditions.checkArgument(16 == key.length, "Key " + io.warp10.continuum.Configuration.DIRECTORY_PSK + " MUST be 128 bits long.");      
      this.keystore.setKey(KeyStore.SIPHASH_DIRECTORY_PSK, key);      
    }
    
    this.keystore.forget();
  }

  private static class DirectoryConsumer implements Runnable {

    private final Directory directory;
    private final KafkaStream<byte[],byte[]> stream;
        
    private final KafkaOffsetCounters counters;
    
    private final AtomicBoolean localabort = new AtomicBoolean(false);
    
    public DirectoryConsumer(Directory directory, KafkaStream<byte[], byte[]> stream, KafkaOffsetCounters counters) {
      this.directory = directory;
      this.stream = stream;
      this.counters = counters;
    }
    
    @Override
    public void run() {
      Table htable = null;

      try {
        ConsumerIterator<byte[],byte[]> iter = this.stream.iterator();

        byte[] siphashKey = directory.keystore.getKey(KeyStore.SIPHASH_KAFKA_METADATA);
        byte[] aesKey = directory.keystore.getKey(KeyStore.AES_KAFKA_METADATA);
            
        htable = directory.conn.getTable(directory.hbaseTable);

        final Table ht = htable;

        //
        // AtomicLong with the timestamp of the last Put or 0 if
        // none were added since the last flush
        //
        
        final AtomicLong lastPut = new AtomicLong(0L);
        
        final List<Put> puts = new ArrayList<Put>();

        final AtomicLong putSize = new AtomicLong(0L);
        
        //
        // Start the synchronization Thread
        //
        
        Thread synchronizer = new Thread(new Runnable() {
          @Override
          public void run() {
            long lastsync = System.currentTimeMillis();
            long lastflush = lastsync;
            
            //
            // Check for how long we've been storing readings, if we've reached the commitperiod,
            // flush any pending commits and synchronize with the other threads so offsets can be committed
            //

            while(!localabort.get()) { 
              long now = System.currentTimeMillis();
              
              if (now - lastsync > directory.commitPeriod) {
                //
                // We synchronize on 'puts' so the main Thread does not add Puts to it
                //
                
                synchronized (puts) {
                  //
                  // Attempt to flush
                  //
                  
                  try {
                    Object[] results = new Object[puts.size()];
                    
                    if (directory.store) {
                      ht.batch(puts, results);

                      // Check results for nulls
                      for (Object o: results) {
                        if (null == o) {
                          throw new IOException("At least one Put failed.");
                        }
                      }
                      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_HBASE_COMMITS, Sensision.EMPTY_LABELS, 1);
                    }
                    
                    puts.clear();
                    putSize.set(0L);
                    // Reset lastPut to 0
                    lastPut.set(0L);                    
                  } catch (IOException ioe) {
                    // Clear list of Puts
                    puts.clear();
                    putSize.set(0L);
                    // If an exception is thrown, abort
                    directory.abort.set(true);
                    return;
                  } catch (InterruptedException ie) {
                    // Clear list of Puts
                    puts.clear();
                    putSize.set(0L);
                    // If an exception is thrown, abort
                    directory.abort.set(true);
                    return;                    
                  }
                  //
                  // Now join the cyclic barrier which will trigger the
                  // commit of offsets
                  //
                  try {
                    directory.barrier.await();
                    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_BARRIER_SYNCS, Sensision.EMPTY_LABELS, 1);
                  } catch (Exception e) {
                    directory.abort.set(true);
                    return;
                  } finally {
                    lastsync = System.currentTimeMillis();
                  }
                }
              } else if (0 != lastPut.get() && (now - lastPut.get() > 500) || putSize.get() > directory.maxPendingPutsSize) {
                //
                // If the last Put was added to 'ht' more than 500ms ago, force a flush
                //
                
                synchronized(puts) {
                  try {
                    Object[] results = new Object[puts.size()];
                    
                    if (directory.store) {
                      ht.batch(puts, results);
                      
                      // Check results for nulls
                      for (Object o: results) {
                        if (null == o) {
                          throw new IOException("At least one Put failed.");
                        }
                      }
                      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_HBASE_COMMITS, Sensision.EMPTY_LABELS, 1);
                    }
                    
                    puts.clear();
                    putSize.set(0L);
                    // Reset lastPut to 0
                    lastPut.set(0L);
                  } catch (IOException ioe) {
                    // Clear list of Puts
                    puts.clear();
                    putSize.set(0L);
                    directory.abort.set(true);
                    return;
                  } catch(InterruptedException ie) {                  
                    // Clear list of Puts
                    puts.clear();
                    putSize.set(0L);
                    directory.abort.set(true);
                    return;
                  }                  
                }
              }
 
              try {
                Thread.sleep(100L);
              } catch (InterruptedException ie) {                
              }
            }
          }
        });
        
        synchronizer.setName("Warp Directory Synchronizer");
        synchronizer.setDaemon(true);
        synchronizer.start();
        
        // TODO(hbs): allow setting of writeBufferSize

        while (iter.hasNext()) {
          Sensision.set(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_JVM_FREEMEMORY, Sensision.EMPTY_LABELS, Runtime.getRuntime().freeMemory());

          //
          // Since the call to 'next' may block, we need to first
          // check that there is a message available, otherwise we
          // will miss the synchronization point with the other
          // threads.
          //
          
          boolean nonEmpty = iter.nonEmpty();
          
          if (nonEmpty) {
            MessageAndMetadata<byte[], byte[]> msg = iter.next();
            counters.count(msg.partition(), msg.offset());
            
            //
            // We do an early selection check based on the Kafka key.
            // Since 20151104 we now corretly push the Kafka key (cf Ingress bug in pushMetadataMessage(k,v))
            //
                        
            int r = (((int) msg.key()[8]) & 0xff) % directory.modulus;
            
            if (directory.remainder != r) {
              continue;
            }

            //
            // We do not rely on the Kafka key for selection as it might have been incorrectly set.
            // We therefore unwrap all messages and decide later.
            //
            
            byte[] data = msg.message();
            
            if (null != siphashKey) {
              data = CryptoUtils.removeMAC(siphashKey, data);
            }
            
            // Skip data whose MAC was not verified successfully
            if (null == data) {
              Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_KAFKA_FAILEDMACS, Sensision.EMPTY_LABELS, 1);
              continue;
            }
            
            // Unwrap data if need be
            if (null != aesKey) {
              data = CryptoUtils.unwrap(aesKey, data);
            }
            
            // Skip data that was not unwrapped successfuly
            if (null == data) {
              Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_KAFKA_FAILEDDECRYPTS, Sensision.EMPTY_LABELS, 1);
              continue;
            }
            
            //
            // TODO(hbs): We could check that metadata class/labels Id match those of the key, but
            // since it was wrapped/authenticated, we suppose it's ok.
            //
                        
            //byte[] labelsBytes = Arrays.copyOfRange(data, 8, 16);
            //long labelsId = Longs.fromByteArray(labelsBytes);
            
            byte[] metadataBytes = Arrays.copyOfRange(data, 16, data.length);
            TDeserializer deserializer = new TDeserializer(new TCompactProtocol.Factory());
            Metadata metadata = new Metadata();
            deserializer.deserialize(metadata, metadataBytes);
            
            //
            // Force Attributes
            //
            
            if (!metadata.isSetAttributes()) {
              metadata.setAttributes(new HashMap<String,String>());
            }
            
            //
            // Recompute labelsid and classid
            //
            
            long classId = GTSHelper.classId(directory.SIPHASH_CLASS_LONGS, metadata.getName());
            long labelsId = GTSHelper.labelsId(directory.SIPHASH_LABELS_LONGS, metadata.getLabels());

            metadata.setLabelsId(labelsId);
            metadata.setClassId(classId);

            //
            // Recheck labelsid so we don't retain GTS with invalid labelsid in the row key (which may have happened due
            // to bugs)
            //
            
            int rem = ((int) ((labelsId >>> 56) & 0xffL)) % directory.modulus;
            
            if (directory.remainder != rem) {
              continue;
            }
            
            
            //
            // Check the source of the metadata
            //

            //
            // If Metadata is from Delete, remove it from the cache AND from HBase
            //
            
            if (io.warp10.continuum.Configuration.INGRESS_METADATA_DELETE_SOURCE.equals(metadata.getSource())) {
              
              //
              // Call external plugin
              //
              
              if (null != directory.plugin) {
                
                long nano = 0;
                
                try {
                  GTS gts = new GTS(
                      new UUID(metadata.getClassId(), metadata.getLabelsId()),
                      metadata.getName(),
                      metadata.getLabels(),
                      metadata.getAttributes());
                  
                  nano = System.nanoTime();
                      
                  if (!directory.plugin.delete(gts)) {
                    break;
                  }                  
                } finally {
                  nano = System.nanoTime() - nano;
                  Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_DELETE_CALLS, Sensision.EMPTY_LABELS, 1);
                  Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_DELETE_TIME_NANOS, Sensision.EMPTY_LABELS, nano);                  
                }

              } else {
                if (!directory.metadatas.containsKey(metadata.getName())) {
                  continue;
                }

                // Remove cache entry
                directory.metadatas.get(metadata.getName()).remove(labelsId);
                Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_GTS, Sensision.EMPTY_LABELS, -1);              
              }
              
              if (!directory.delete) {
                continue;
              }

              // Remove HBase entry
              
              // Prefix + classId + labelsId
              byte[] rowkey = new byte[HBASE_METADATA_KEY_PREFIX.length + 8 + 8];

              ByteBuffer bb = ByteBuffer.wrap(rowkey).order(ByteOrder.BIG_ENDIAN);
              bb.put(HBASE_METADATA_KEY_PREFIX);
              bb.putLong(classId);
              bb.putLong(labelsId);
              
              //System.arraycopy(HBASE_METADATA_KEY_PREFIX, 0, rowkey, 0, HBASE_METADATA_KEY_PREFIX.length);
              // Copy classId/labelsId
              //System.arraycopy(data, 0, rowkey, HBASE_METADATA_KEY_PREFIX.length, 16);
              
              Delete delete = new Delete(rowkey);              
              try {
                synchronized (ht) {
                  htable.delete(delete);
                  lastPut.set(System.currentTimeMillis());
                }                                            
              } catch (IOException ioe) {
                // Clear current list of puts
                puts.clear();
                putSize.set(0L);
                directory.abort.set(true);
                return;
              }
              
              continue;
            }                        

            //
            // Call external plugin
            //
            
            if (null != directory.plugin) {
              long nano = 0;
              
              try {
                GTS gts = new GTS(
                    new UUID(metadata.getClassId(), metadata.getLabelsId()),
                    metadata.getName(),
                    metadata.getLabels(),
                    metadata.getAttributes());
                
                nano = System.nanoTime();
                
                if (!directory.plugin.store(metadata.getSource(), gts)) {
                  break;
                }                
              } finally {
                nano = System.nanoTime() - nano;
                Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_STORE_CALLS, Sensision.EMPTY_LABELS, 1);
                Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_STORE_TIME_NANOS, Sensision.EMPTY_LABELS, nano);                  
              }
            } else {
              //
              // If Metadata comes from Ingress and it is already in the cache, do
              // nothing.
              //
              
              if (io.warp10.continuum.Configuration.INGRESS_METADATA_SOURCE.equals(metadata.getSource())
                  && directory.metadatas.containsKey(metadata.getName())
                  && directory.metadatas.get(metadata.getName()).containsKey(labelsId)) {
                continue;
              }              
            }
            
            //
            // Write Metadata to HBase as it is either new or an updated version\
            // WARNING(hbs): in case of an updated version, we might erase a newer version of
            // the metadata (in case we updated it already but the Kafka offsets were not committed prior to
            // a failure of this Directory process). This will eventually be corrected when the newer version is
            // later re-read from Kafka.
            //
            
            // Prefix + classId + labelsId
            byte[] rowkey = new byte[HBASE_METADATA_KEY_PREFIX.length + 8 + 8];

            ByteBuffer bb = ByteBuffer.wrap(rowkey).order(ByteOrder.BIG_ENDIAN);
            bb.put(HBASE_METADATA_KEY_PREFIX);
            bb.putLong(classId);
            bb.putLong(labelsId);

            //System.arraycopy(HBASE_METADATA_KEY_PREFIX, 0, rowkey, 0, HBASE_METADATA_KEY_PREFIX.length);
            // Copy classId/labelsId
            //System.arraycopy(data, 0, rowkey, HBASE_METADATA_KEY_PREFIX.length, 16);

            //
            // Encrypt content
            //
                        
            Put put = null;
            byte[] encrypted = null;
            
            if (directory.store) {
              put = new Put(rowkey);
              encrypted = CryptoUtils.wrap(directory.keystore.getKey(KeyStore.AES_HBASE_METADATA), metadataBytes);
              put.addColumn(directory.colfam, new byte[0], encrypted);              
            }
              
            synchronized (puts) {
              if (directory.store) {
                puts.add(put);
                putSize.addAndGet(encrypted.length);
                lastPut.set(System.currentTimeMillis());
                Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_HBASE_PUTS, Sensision.EMPTY_LABELS, 1);                
              }

              if (null != directory.plugin) {
                continue;
              }

              //
              // Internalize Strings
              //
              
              GTSHelper.internalizeStrings(metadata);

              //
              // Store it in the cache (we dot that in the synchronized section)
              //

              //byte[] classBytes = Arrays.copyOf(data, 8);            
              //long classId = Longs.fromByteArray(classBytes);
              
              if (!directory.metadatas.containsKey(metadata.getName())) {
                //directory.metadatas.put(metadata.getName(), new ConcurrentHashMap<Long,Metadata>());
                directory.metadatas.put(metadata.getName(), new ConcurrentSkipListMap<Long,Metadata>(ID_COMPARATOR));
                directory.classNames.put(classId, metadata.getName());
                Sensision.set(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_CLASSES, Sensision.EMPTY_LABELS, directory.classNames.size());
              }
              
              //
              // Store per producer class
              //

              String producer = metadata.getLabels().get(Constants.PRODUCER_LABEL);
              
              synchronized(directory.classesPerProducer) {
                Set<String> classes = directory.classesPerProducer.get(producer);
                
                if (null == classes) {
                  classes = new HashSet<String>();
                  directory.classesPerProducer.put(producer, classes);
                }
                
                classes.add(metadata.getName());
              }
              
              Sensision.set(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PRODUCERS, Sensision.EMPTY_LABELS, directory.classesPerProducer.size());
              
              //
              // Force classId/labelsId in Metadata, we will need them!
              //
              
              metadata.setClassId(classId);
              metadata.setLabelsId(labelsId);
              
              directory.metadatas.get(metadata.getName()).put(labelsId, metadata);
              Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_GTS, Sensision.EMPTY_LABELS, 1);
            }                                            
          } else {
            // Sleep a tiny while
            try {
              Thread.sleep(2L);
            } catch (InterruptedException ie) {             
            }
          }          
        }        
      } catch (Throwable t) {
        LOG.error("", t);
      } finally {
        // Set abort to true in case we exit the 'run' method
        directory.abort.set(true);
        this.localabort.set(true);
        if (null != htable) {
          try { htable.close(); } catch (IOException ioe) {}
        }
      }
    }
  }

  @Override
  public DirectoryFindResponse find(DirectoryFindRequest request) throws TException {
    
    DirectoryFindResponse response = new DirectoryFindResponse();

    //
    // Check request age
    //
    
    long now = System.currentTimeMillis();
    
    if (now - request.getTimestamp() > this.maxage) {
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_FIND_EXPIRED, Sensision.EMPTY_LABELS, 1);
      response.setError("Request has expired.");
      return response;
    }
    
    //
    // Compute request hash
    //
    
    long hash = DirectoryUtil.computeHash(SIPHASH_PSK_LONGS[0], SIPHASH_PSK_LONGS[1], request);
    
    // Check hash against value in the request
    
    if (hash != request.getHash()) {
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_FIND_INVALID, Sensision.EMPTY_LABELS, 1);
      response.setError("Invalid request.");
      return response;
    }
    
    //
    // Build patterns from expressions
    //
    
    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_FIND_REQUESTS, Sensision.EMPTY_LABELS, 1);

    Matcher classPattern;
    
    Collection<Metadata> metas;
    
    //
    // Allocate a set if there is more than one class selector as we may have
    // duplicate results
    //
    
    if (request.getClassSelectorSize() > 1) {
      metas = new HashSet<Metadata>();
    } else {
      metas = new ArrayList<Metadata>();
    }
    
    long count = 0;

    for (int i = 0; i < request.getClassSelectorSize(); i++) {
      
      //
      // Call external plugin if it is defined
      //
      
      if (null != this.plugin) {
        
        long time = 0;
        long precount = 0;
        long nano = System.nanoTime();
        
        try (DirectoryPlugin.GTSIterator iter = this.plugin.find(this.remainder, request.getClassSelector().get(i), request.getLabelsSelectors().get(i))) {
          
          while(iter.hasNext()) {
            
            GTS gts = iter.next();
            nano = System.nanoTime() - nano;
            time += nano;
            
            count++;
            
            if (count >= maxHardFindResults) {
              Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_FIND_LIMITED, Sensision.EMPTY_LABELS, 1);
              response.setError("Find request would return more than " + maxHardFindResults + " results, aborting.");
              return response;
            }

            Metadata meta = new Metadata();
            meta.setName(gts.getName());
            //meta.setLabels(ImmutableMap.copyOf(metadata.getLabels()));
            meta.setLabels(new HashMap<String, String>(gts.getLabels()));
            //meta.setAttributes(ImmutableMap.copyOf(metadata.getAttributes()));
            meta.setAttributes(new HashMap<String,String>(gts.getAttributes()));
            meta.setClassId(GTSHelper.classId(SIPHASH_CLASS_LONGS, meta.getName()));
            meta.setLabelsId(GTSHelper.labelsId(SIPHASH_LABELS_LONGS, meta.getLabels()));
            
            metas.add(meta);
            nano = System.nanoTime();
          }                  
        } catch (Exception e) {          
        } finally {
          Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_FIND_CALLS, Sensision.EMPTY_LABELS, 1);
          Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_FIND_RESULTS, Sensision.EMPTY_LABELS, count - precount);
          Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_FIND_TIME_NANOS, Sensision.EMPTY_LABELS, time);                  
        }
      } else { 
        String exactClassName = null;
        

        if (request.getClassSelector().get(i).startsWith("=") || !request.getClassSelector().get(i).startsWith("~")) {
          exactClassName = request.getClassSelector().get(i).startsWith("=") ? request.getClassSelector().get(i).substring(1) : request.getClassSelector().get(i);
          classPattern = Pattern.compile(Pattern.quote(exactClassName)).matcher("");
        } else {
          classPattern = Pattern.compile(request.getClassSelector().get(i).substring(1)).matcher("");
        }
        
        Map<String,Matcher> labelPatterns = new HashMap<String,Matcher>();
        
        if (request.getLabelsSelectors().get(i).size() > 0) {
          for (Entry<String,String> entry: request.getLabelsSelectors().get(i).entrySet()) {
            String label = entry.getKey();
            String expr = entry.getValue();
            Pattern pattern;
            
            if (expr.startsWith("=") || !expr.startsWith("~")) {
              pattern = Pattern.compile(Pattern.quote(expr.startsWith("=") ? expr.substring(1) : expr));
            } else {
              pattern = Pattern.compile(expr.substring(1));
            }
            
            labelPatterns.put(label,  pattern.matcher(""));
          }      
        }
              
        //
        // Loop over the class names to find matches
        //

        // Copy the class names as 'this.classNames' might be updated while in the for loop
        Collection<String> classNames = new ArrayList<String>();
        classNames.addAll(this.classNames.values());
        
        if (null != exactClassName) {
          // If the class name is an exact match, check if it is known, if not, skip to the next selector
          if(!this.metadatas.containsKey(exactClassName)) {
            continue;
          }
          classNames = new ArrayList<String>();
          classNames.add(exactClassName);
        } else {
          //
          // Extract per producer classes if producer selector exists
          //
          
          if (request.getLabelsSelectors().get(i).size() > 0) {
            String producersel = request.getLabelsSelectors().get(i).get(Constants.PRODUCER_LABEL);
            
            if (null != producersel && producersel.startsWith("=")) {
              classNames = new ArrayList<String>();
              classNames.addAll(classesPerProducer.get(producersel.substring(1)));
            }
          }
        }

        for (String className: classNames) {
          
          //
          // If class matches, check all labels for matches
          //
          
          if (classPattern.reset(className).matches()) {
            for (Metadata metadata: this.metadatas.get(className).values()) {
              boolean exclude = false;
              
              for (String labelName: labelPatterns.keySet()) {
                //
                // Immediately exclude metadata which do not contain one of the
                // labels for which we have patterns either in labels or in attributes
                //

                if (!metadata.getLabels().containsKey(labelName) && !metadata.getAttributes().containsKey(labelName)) {
                  exclude = true;
                  break;
                }
                
                //
                // Check if the label value matches, if not, exclude the GTS
                //
                
                if ((metadata.getLabels().containsKey(labelName) && !labelPatterns.get(labelName).reset(metadata.getLabels().get(labelName)).matches())
                    || (metadata.getAttributes().containsKey(labelName) && !labelPatterns.get(labelName).reset(metadata.getAttributes().get(labelName)).matches())) {
                  exclude = true;
                  break;
                }
              }
              
              if (exclude) {
                continue;
              }

              //
              // We have a match, rebuild metadata
              //

              if (count >= maxHardFindResults) {
                Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_FIND_LIMITED, Sensision.EMPTY_LABELS, 1);
                response.setError("Find request would return more than " + maxHardFindResults + " results, aborting.");
                return response;
              }

              Metadata meta = new Metadata();
              meta.setName(className);
              //meta.setLabels(ImmutableMap.copyOf(metadata.getLabels()));
              meta.setLabels(new HashMap<String, String>(metadata.getLabels()));
              //meta.setAttributes(ImmutableMap.copyOf(metadata.getAttributes()));
              meta.setAttributes(new HashMap<String,String>(metadata.getAttributes()));
              meta.setClassId(GTSHelper.classId(SIPHASH_CLASS_LONGS, meta.getName()));
              meta.setLabelsId(GTSHelper.labelsId(SIPHASH_LABELS_LONGS, meta.getLabels()));
              
              metas.add(meta);
              
              count++;
            }
          }
        }      
      }        
    }

    if (request.getClassSelectorSize() > 1) {
      // We create a list because 'metas' is a set
      response.setMetadatas(new ArrayList<Metadata>());
      response.getMetadatas().addAll(metas);
    } else {
      response.setMetadatas((List<Metadata>) metas);
    }

    //
    // Optimize the result when the number of matching Metadata exceeds maxFindResults.
    // We extract common labels and attempt to compress the result
    //
    
    count = response.getMetadatasSize();
    
    if (count >= this.maxFindResults) {
      
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_FIND_COMPACTED, Sensision.EMPTY_LABELS, 1);
      
      //
      // Extract common labels
      //
      
      Map<String,String> commonLabels = null;
      Set<String> remove = new HashSet<String>();
      
      for (Metadata metadata: response.getMetadatas()) {
        if (null == commonLabels) {
          commonLabels = new HashMap<String, String>(metadata.getLabels());
          continue;
        }
        
        remove.clear();
        
        for (Entry<String,String> entry: commonLabels.entrySet()) {
          if (!metadata.getLabels().containsKey(entry.getKey()) || !entry.getValue().equals(metadata.getLabels().get(entry.getKey()))) {
            remove.add(entry.getKey());
          }
        }
        
        if (!remove.isEmpty()) {
          for (String label: remove) {
            commonLabels.remove(label);
          }
        }
      }
      
      //
      // Remove common labels from all Metadata
      //
      
      long commonLabelsSize = 0;
      
      if (!commonLabels.isEmpty()) {
        for (Metadata metadata: response.getMetadatas()) {
          for (String label: commonLabels.keySet()) {
            metadata.getLabels().remove(label);
          }
        }
        
        //
        // Estimate common labels size
        //
        
        for (Entry<String,String> entry: commonLabels.entrySet()) {
          commonLabelsSize += entry.getKey().length() * 2 + entry.getValue().length() * 2;
        }
        
        response.setCommonLabels(commonLabels);
      }
      
      
      TSerializer serializer = new TSerializer(new TCompactProtocol.Factory());

      byte[] serialized = serializer.serialize(response);
        
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try {
        GZIPOutputStream gzos = new GZIPOutputStream(baos);
        gzos.write(serialized);
        gzos.close();
      } catch (IOException ioe) {
        throw new TException(ioe);
      }
      
      serialized = baos.toByteArray();
        
      if (serialized.length > this.maxThriftFrameLength - commonLabelsSize - 256) {
        response.setError("Find request result would exceed maximum result size (" + this.maxThriftFrameLength + " bytes).");
        response.getMetadatas().clear();
        response.getCommonLabels().clear();
        return response;
      }
      
      response = new DirectoryFindResponse();
      response.setCompressed(serialized);
    }
    
    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_FIND_RESULTS, Sensision.EMPTY_LABELS, count);

    return response;
  }
  
  @Override
  public DirectoryGetResponse get(DirectoryGetRequest request) throws TException {
    DirectoryGetResponse response = new DirectoryGetResponse();
    
    String name = this.classNames.get(request.getClassId());
    
    if (null != name) {
      Metadata metadata = this.metadatas.get(name).get(request.getLabelsId()); 
      if (null != metadata) {
        response.setMetadata(metadata);
      }
    }
    
    return response;
  }
  
  @Override
  public DirectoryStatsResponse stats(DirectoryStatsRequest request) throws TException {
    
    try {
      DirectoryStatsResponse response = new DirectoryStatsResponse();

      //
      // Check request age
      //
      
      long now = System.currentTimeMillis();
      
      if (now - request.getTimestamp() > this.maxage) {
        Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_STATS_EXPIRED, Sensision.EMPTY_LABELS, 1);
        response.setError("Request has expired.");
        return response;
      }
      
      //
      // Compute request hash
      //
      
      long hash = DirectoryUtil.computeHash(SIPHASH_PSK_LONGS[0], SIPHASH_PSK_LONGS[1], request);
      
      // Check hash against value in the request
      
      if (hash != request.getHash()) {
        Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_STATS_INVALID, Sensision.EMPTY_LABELS, 1);
        response.setError("Invalid request.");
        return response;
      }
      
      //
      // Build patterns from expressions
      //
      
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_STATS_REQUESTS, Sensision.EMPTY_LABELS, 1);

      Matcher classPattern;
      
      Collection<Metadata> metas;
      
      //
      // Allocate a set if there is more than one class selector as we may have
      // duplicate results
      //
      
      if (request.getClassSelectorSize() > 1) {
        metas = new HashSet<Metadata>();
      } else {
        metas = new ArrayList<Metadata>();
      }
            
      HyperLogLogPlus gtsCount = new HyperLogLogPlus(ESTIMATOR_P, ESTIMATOR_PPRIME);
      Map<String,HyperLogLogPlus> perClassCardinality = new HashMap<String,HyperLogLogPlus>();
      Map<String,HyperLogLogPlus> perLabelValueCardinality = new HashMap<String,HyperLogLogPlus>();
      HyperLogLogPlus labelNamesCardinality = null;
      HyperLogLogPlus labelValuesCardinality = null;
      HyperLogLogPlus classCardinality = null;
      
      for (int i = 0; i < request.getClassSelectorSize(); i++) {
        String exactClassName = null;
        
        if (request.getClassSelector().get(i).startsWith("=") || !request.getClassSelector().get(i).startsWith("~")) {
          exactClassName = request.getClassSelector().get(i).startsWith("=") ? request.getClassSelector().get(i).substring(1) : request.getClassSelector().get(i);
          classPattern = Pattern.compile(Pattern.quote(exactClassName)).matcher("");
        } else {
          classPattern = Pattern.compile(request.getClassSelector().get(i).substring(1)).matcher("");
        }
        
        Map<String,Object> labelPatterns = new HashMap<String,Object>();
        
        if (null != request.getLabelsSelectors()) {
          for (Entry<String,String> entry: request.getLabelsSelectors().get(i).entrySet()) {
            String label = entry.getKey();
            String expr = entry.getValue();
            Object pattern;
            
            if (expr.startsWith("=") || !expr.startsWith("~")) {
              //pattern = Pattern.compile(Pattern.quote(expr.startsWith("=") ? expr.substring(1) : expr));
              pattern = expr.startsWith("=") ? expr.substring(1) : expr;
            } else {
              pattern = Pattern.compile(expr.substring(1)).matcher("");
            }
            
            //labelPatterns.put(label,  pattern.matcher(""));
            labelPatterns.put(label,  pattern);
          }      
        }
              
        //
        // Loop over the class names to find matches
        //

        //Collection<String> classNames = this.metadatas.keySet();
        Collection<String> classNames = this.classNames.values();
        
        if (null != exactClassName) {
          // If the class name is an exact match, check if it is known, if not, skip to the next selector
          if(!this.metadatas.containsKey(exactClassName)) {
            continue;
          }
          classNames = new ArrayList<String>();
          classNames.add(exactClassName);
        } else {
          //
          // Extract per producer classes if producer selector exists
          //
          
          if (request.getLabelsSelectors().get(i).size() > 0) {
            String producersel = request.getLabelsSelectors().get(i).get(Constants.PRODUCER_LABEL);
            
            if (null != producersel && producersel.startsWith("=")) {
              classNames = new ArrayList<String>();
              classNames.addAll(classesPerProducer.get(producersel.substring(1)));
            }
          }          
        }
        
        for (String className: classNames) {
          
          //
          // If class matches, check all labels for matches
          //
          
          if (classPattern.reset(className).matches()) {
            for (Metadata metadata: this.metadatas.get(className).values()) {
              boolean exclude = false;
              
              for (String labelName: labelPatterns.keySet()) {
                //
                // Immediately exclude metadata which do not contain one of the
                // labels for which we have patterns either in labels or in attributes
                //

                if (!metadata.getLabels().containsKey(labelName) && !metadata.getAttributes().containsKey(labelName)) {
                  exclude = true;
                  break;
                }
                
                //
                // Check if the label value matches, if not, exclude the GTS
                //
                
                Object m = labelPatterns.get(labelName);
                
                //
                // Check if the label value matches, if not, exclude the GTS
                //

                if (m instanceof Matcher) {
                  if ((metadata.getLabels().containsKey(labelName) && !((Matcher) m).reset(metadata.getLabels().get(labelName)).matches())
                      || (metadata.getAttributes().containsKey(labelName) && !((Matcher) m).reset(metadata.getAttributes().get(labelName)).matches())) {
                    exclude = true;
                    break;
                  }                          
                } else if (m instanceof String) {
                  if ((metadata.getLabels().containsKey(labelName) && !((String) m).equals(metadata.getLabels().get(labelName)))
                      || (metadata.getAttributes().containsKey(labelName) && !((String) m).equals(metadata.getAttributes().get(labelName)))) {
                    exclude = true;
                    break;
                  }                                        
                }
              }
              
              if (exclude) {
                continue;
              }

              //
              // We have a match, update estimators
              //

              // Compute classId/labelsId
              long classId = GTSHelper.classId(SIPHASH_CLASS_LONGS, metadata.getName());
              long labelsId = GTSHelper.labelsId(SIPHASH_LABELS_LONGS, metadata.getLabels());
              
              // Compute gtsId, we use the GTS Id String from which we extract the 16 bytes
              byte[] data = GTSHelper.gtsIdToString(classId, labelsId).getBytes(Charsets.UTF_16BE);
              long gtsId = SipHashInline.hash24(SIPHASH_CLASS_LONGS[0], SIPHASH_CLASS_LONGS[1], data, 0, data.length);
              
              gtsCount.aggregate(gtsId);
              
              if (null != perClassCardinality) {              
                HyperLogLogPlus count = perClassCardinality.get(metadata.getName());
                if (null == count) {
                  count = new HyperLogLogPlus(ESTIMATOR_P, ESTIMATOR_PPRIME);
                  perClassCardinality.put(metadata.getName(), count);
                }
                                
                count.aggregate(gtsId);
                
                // If we reached the limit in detailed number of classes, we fallback to a simple estimator
                if (perClassCardinality.size() >= LIMIT_CLASS_CARDINALITY) {
                  classCardinality = new HyperLogLogPlus(ESTIMATOR_P, ESTIMATOR_PPRIME);
                  for (String cls: perClassCardinality.keySet()) {
                    data = cls.getBytes(Charsets.UTF_8);
                    classCardinality.aggregate(SipHashInline.hash24(SIPHASH_CLASS_LONGS[0], SIPHASH_CLASS_LONGS[1], data, 0, data.length, false));
                    perClassCardinality = null;
                  }
                }
              } else {
                data = metadata.getName().getBytes(Charsets.UTF_8);
                classCardinality.aggregate(SipHashInline.hash24(SIPHASH_CLASS_LONGS[0], SIPHASH_CLASS_LONGS[1], data, 0, data.length, false));
              }
              
              if (null != perLabelValueCardinality) {
                if (metadata.getLabelsSize() > 0) {
                  for (Entry<String,String> entry: metadata.getLabels().entrySet()) {
                    HyperLogLogPlus estimator = perLabelValueCardinality.get(entry.getKey());
                    if (null == estimator) {
                      estimator = new HyperLogLogPlus(ESTIMATOR_P, ESTIMATOR_PPRIME);
                      perLabelValueCardinality.put(entry.getKey(), estimator);
                    }
                    data = entry.getValue().getBytes(Charsets.UTF_8);
                    long siphash = SipHashInline.hash24(SIPHASH_LABELS_LONGS[0], SIPHASH_LABELS_LONGS[1], data, 0, data.length, false);
                    estimator.aggregate(siphash);
                  }
                }

                if (metadata.getAttributesSize() > 0) {
                  for (Entry<String,String> entry: metadata.getAttributes().entrySet()) {
                    HyperLogLogPlus estimator = perLabelValueCardinality.get(entry.getKey());
                    if (null == estimator) {
                      estimator = new HyperLogLogPlus(ESTIMATOR_P, ESTIMATOR_PPRIME);
                      perLabelValueCardinality.put(entry.getKey(), estimator);
                    }
                    data = entry.getValue().getBytes(Charsets.UTF_8);
                    estimator.aggregate(SipHashInline.hash24(SIPHASH_LABELS_LONGS[0], SIPHASH_LABELS_LONGS[1], data, 0, data.length, false));
                  }
                }

                if (perLabelValueCardinality.size() >= LIMIT_LABELS_CARDINALITY) {
                  labelNamesCardinality = new HyperLogLogPlus(ESTIMATOR_P, ESTIMATOR_PPRIME);
                  labelValuesCardinality = new HyperLogLogPlus(ESTIMATOR_P, ESTIMATOR_PPRIME);
                  for (Entry<String,HyperLogLogPlus> entry: perLabelValueCardinality.entrySet()) {
                    data = entry.getKey().getBytes(Charsets.UTF_8);
                    labelNamesCardinality.aggregate(SipHashInline.hash24(SIPHASH_LABELS_LONGS[0], SIPHASH_LABELS_LONGS[1], data, 0, data.length, false));
                    labelValuesCardinality.fuse(entry.getValue());
                  }
                  perLabelValueCardinality = null;
                }
              } else {
                if (metadata.getLabelsSize() > 0) {
                  for (Entry<String,String> entry: metadata.getLabels().entrySet()) {
                    data = entry.getKey().getBytes(Charsets.UTF_8);
                    labelValuesCardinality.aggregate(SipHashInline.hash24(SIPHASH_LABELS_LONGS[0], SIPHASH_LABELS_LONGS[1], data, 0, data.length, false));
                    data = entry.getValue().getBytes(Charsets.UTF_8);
                    labelValuesCardinality.aggregate(SipHashInline.hash24(SIPHASH_LABELS_LONGS[0], SIPHASH_LABELS_LONGS[1], data, 0, data.length, false));
                  }
                }
                if (metadata.getAttributesSize() > 0) {
                  for (Entry<String,String> entry: metadata.getAttributes().entrySet()) {
                    data = entry.getKey().getBytes(Charsets.UTF_8);
                    labelValuesCardinality.aggregate(SipHashInline.hash24(SIPHASH_LABELS_LONGS[0], SIPHASH_LABELS_LONGS[1], data, 0, data.length, false));
                    data = entry.getValue().getBytes(Charsets.UTF_8);
                    labelValuesCardinality.aggregate(SipHashInline.hash24(SIPHASH_LABELS_LONGS[0], SIPHASH_LABELS_LONGS[1], data, 0, data.length, false));
                  }
                }
              }            
            }
          }
        }      
      }

      response.setGtsCount(gtsCount.toBytes());
      
      if (null != perClassCardinality) {
        classCardinality = new HyperLogLogPlus(ESTIMATOR_P, ESTIMATOR_PPRIME);
        for (Entry<String,HyperLogLogPlus> entry: perClassCardinality.entrySet()) {
          response.putToPerClassCardinality(entry.getKey(), ByteBuffer.wrap(entry.getValue().toBytes()));
          byte[] data = entry.getKey().getBytes(Charsets.UTF_8);
          classCardinality.aggregate(SipHashInline.hash24(SIPHASH_CLASS_LONGS[0], SIPHASH_CLASS_LONGS[1], data, 0, data.length, false));        
        }
      }
      
      response.setClassCardinality(classCardinality.toBytes());
      
      if (null != perLabelValueCardinality) {
        HyperLogLogPlus estimator = new HyperLogLogPlus(ESTIMATOR_P, ESTIMATOR_PPRIME);
        HyperLogLogPlus nameEstimator = new HyperLogLogPlus(ESTIMATOR_P, ESTIMATOR_PPRIME);
        for (Entry<String,HyperLogLogPlus> entry: perLabelValueCardinality.entrySet()) {
          byte[] data = entry.getKey().getBytes(Charsets.UTF_8);
          nameEstimator.aggregate(SipHashInline.hash24(SIPHASH_LABELS_LONGS[0], SIPHASH_LABELS_LONGS[1], data, 0, data.length, false));
          estimator.fuse(entry.getValue());
          response.putToPerLabelValueCardinality(entry.getKey(), ByteBuffer.wrap(entry.getValue().toBytes()));
        }
        response.setLabelNamesCardinality(nameEstimator.toBytes());
        response.setLabelValuesCardinality(estimator.toBytes());
      } else {
        response.setLabelNamesCardinality(labelNamesCardinality.toBytes());
        response.setLabelValuesCardinality(labelValuesCardinality.toBytes());
      }
      
      return response;   
    } catch (IOException ioe) {
      ioe.printStackTrace();
      throw new TException(ioe);
    } catch (Exception e) {
      e.printStackTrace();
      throw new TException(e);
    }
  }
  
  @Override
  public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    if (!Constants.API_ENDPOINT_DIRECTORY_STREAMING_INTERNAL.equals(target)) {
      return;
    }
    
    long nano = System.nanoTime();
    
    baseRequest.setHandled(true);
    
    //
    // Extract 'selector'
    //
    
    String selector = request.getParameter(Constants.HTTP_PARAM_SELECTOR);
    
    if (null == selector) {
      throw new IOException("Missing parameter '" + Constants.HTTP_PARAM_SELECTOR + "'.");
    }
    
    // Decode selector
    
    selector = new String(OrderPreservingBase64.decode(selector.getBytes(Charsets.US_ASCII)), Charsets.UTF_8);
    
    //
    // Check request signature
    //
    
    String signature = request.getHeader(Constants.getHeader(io.warp10.continuum.Configuration.HTTP_HEADER_DIRECTORY_SIGNATURE));
    
    if (null == signature) {
      throw new IOException("Missing header '" + Constants.getHeader(io.warp10.continuum.Configuration.HTTP_HEADER_DIRECTORY_SIGNATURE) + "'.");
    }
    
    boolean signed = false;
    
    //
    // Signature has the form hex(ts):hex(hash)
    //
    
    String[] subelts = signature.split(":");
    
    if (2 != subelts.length) {
      throw new IOException("Invalid signature.");
    }

    long nowts = System.currentTimeMillis();
    long sigts = new BigInteger(subelts[0], 16).longValue();
    long sighash = new BigInteger(subelts[1], 16).longValue();
        
    if (nowts - sigts > this.maxage) {
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_STREAMING_EXPIRED, Sensision.EMPTY_LABELS, 1);
      throw new IOException("Signature has expired.");
    }
        
    // Recompute hash of ts:selector
        
    String tssel = Long.toString(sigts) + ":" + selector;
        
    byte[] bytes = tssel.getBytes(Charsets.UTF_8);
    long checkedhash = SipHashInline.hash24(SIPHASH_PSK_LONGS[0], SIPHASH_PSK_LONGS[1], bytes, 0, bytes.length);
        
    if (checkedhash != sighash) {
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_STREAMING_INVALID, Sensision.EMPTY_LABELS, 1);
      throw new IOException("Corrupted signature");
    }
    
    //
    // Parse selector  
    //
    
    Object[] tokens = null;

    try {
      tokens = PARSESELECTOR.parse(selector);
    } catch (WarpScriptException ee) {
      throw new IOException(ee);
    }
    
    String classSelector = (String) tokens[0];
    Map<String,String> labelsSelector = (Map<String,String>) tokens[1];
        
    //
    // Loop over the Metadata, outputing the matching ones
    //
    
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/plain");

    //
    // Delegate to the external plugin if it is defined
    //
    
    long count = 0;

    TSerializer serializer = new TSerializer(new TCompactProtocol.Factory());


    if (null != this.plugin) {
      
      long nanofind = System.nanoTime();
      long time = 0;
      
      try (DirectoryPlugin.GTSIterator iter = this.plugin.find(this.remainder, classSelector, labelsSelector)) {
        
        while(iter.hasNext()) {
          GTS gts = iter.next();
          nanofind = System.nanoTime() - nanofind;
          time += nanofind;
          
          Metadata metadata = new Metadata();
          metadata.setName(gts.getName());
          metadata.setLabels(gts.getLabels());
          metadata.setAttributes(gts.getAttributes());
          
          //
          // Recompute class/labels Id
          //
          
          long classId = GTSHelper.classId(this.SIPHASH_CLASS_LONGS, metadata.getName());
          long labelsId = GTSHelper.labelsId(this.SIPHASH_LABELS_LONGS, metadata.getLabels());

          metadata.setClassId(classId);
          metadata.setLabelsId(labelsId);
          
          try {
            response.getOutputStream().write(OrderPreservingBase64.encode(serializer.serialize(metadata)));
            response.getOutputStream().write('\r');
            response.getOutputStream().write('\n');
            count++;
          } catch (TException te) {
          }        
          nanofind = System.nanoTime();
        }
        
      } catch (Exception e) {        
      } finally {
        Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_FIND_CALLS, Sensision.EMPTY_LABELS, 1);
        Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_FIND_RESULTS, Sensision.EMPTY_LABELS, count);
        Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_PLUGIN_FIND_TIME_NANOS, Sensision.EMPTY_LABELS, time);                  
      }
    } else {
      
      String exactClassName = null;
      Matcher classPattern;
      
      if (classSelector.startsWith("=") || !classSelector.startsWith("~")) {
        exactClassName = classSelector.startsWith("=") ? classSelector.substring(1) : classSelector;
        classPattern = Pattern.compile(Pattern.quote(exactClassName)).matcher("");
      } else {
        classPattern = Pattern.compile(classSelector.substring(1)).matcher("");
      }
        
      Map<String,Matcher> labelPatterns = new HashMap<String,Matcher>();
        
      for (Entry<String,String> entry: labelsSelector.entrySet()) {
        String label = entry.getKey();
        String expr = entry.getValue();
        Pattern pattern;
            
        if (expr.startsWith("=") || !expr.startsWith("~")) {
          pattern = Pattern.compile(Pattern.quote(expr.startsWith("=") ? expr.substring(1) : expr));
        } else {
          pattern = Pattern.compile(expr.substring(1));
        }
            
        labelPatterns.put(label,  pattern.matcher(""));
      }      

      //
      // Loop over the class names to find matches
      //

      //Collection<String> classNames = this.metadatas.keySet();
      Collection<String> classNames = this.classNames.values();
      
      if (null != exactClassName) {
        // If the class name is an exact match, check if it is known, if not, return
        if(!this.metadatas.containsKey(exactClassName)) {
          return;
        }
        classNames = new ArrayList<String>();
        classNames.add(exactClassName);
      } else {
        //
        // Extract per producer classes if producer selector exists
        //
        
        if (labelsSelector.size() > 0) {
          String producersel = labelsSelector.get(Constants.PRODUCER_LABEL);
          
          if (null != producersel && producersel.startsWith("=")) {
            classNames = new ArrayList<String>();
            classNames.addAll(classesPerProducer.get(producersel.substring(1)));
          }
        }      
      }
            
      for (String className: classNames) {
        
        //
        // If class matches, check all labels for matches
        //
        
        if (classPattern.reset(className).matches()) {
          for (Metadata metadata: this.metadatas.get(className).values()) {
            boolean exclude = false;
            
            for (String labelName: labelPatterns.keySet()) {
              //
              // Immediately exclude metadata which do not contain one of the
              // labels for which we have patterns either in labels or in attributes
              //

              if (!metadata.getLabels().containsKey(labelName) && !metadata.getAttributes().containsKey(labelName)) {
                exclude = true;
                break;
              }
              
              //
              // Check if the label value matches, if not, exclude the GTS
              //
              
              if ((metadata.getLabels().containsKey(labelName) && !labelPatterns.get(labelName).reset(metadata.getLabels().get(labelName)).matches())
                  || (metadata.getAttributes().containsKey(labelName) && !labelPatterns.get(labelName).reset(metadata.getAttributes().get(labelName)).matches())) {
                exclude = true;
                break;
              }
            }
            
            if (exclude) {
              continue;
            }

            try {
              response.getOutputStream().write(OrderPreservingBase64.encode(serializer.serialize(metadata)));
              response.getOutputStream().write('\r');
              response.getOutputStream().write('\n');
              count++;
            } catch (TException te) {
              continue;
            }
          }
        }
      }           
    }
    
    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_STREAMING_REQUESTS, Sensision.EMPTY_LABELS, 1);
    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_STREAMING_RESULTS, Sensision.EMPTY_LABELS, count);
    nano = System.nanoTime() - nano;
    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_DIRECTORY_STREAMING_TIME_US, Sensision.EMPTY_LABELS, nano / 1000);
  }
}
