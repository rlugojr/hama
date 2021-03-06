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
package org.apache.hama.monitor;

import static org.apache.hama.monitor.Monitor.Destination.ZK;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.util.ZKUtil;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;

/**
 * Monitor daemon performs tasks in order to monitor GroomServer's status.
 */
public final class Monitor extends Thread implements MonitorListener {

  public static final Log LOG = LogFactory.getLog(Monitor.class);
  public static final String MONITOR_ROOT_PATH = "/monitor/";

  private final Map<String, TaskWorker> workers = // <jar path, task worker>
  new ConcurrentHashMap<String, TaskWorker>();
  private final BlockingQueue<Result> results = new LinkedBlockingQueue<Result>();
  private final Publisher publisher;
  private final Collector collector;
  private final Initializer initializer;
  private final Configuration configuration;
  // TODO: may need zookeeper different from GroomServer
  // to alleviate groom server's barrier sync.
  private final ZooKeeper zookeeper;
  private final String groomServerName;

  /**
   * Destination tells Publisher where to publish the result.
   */
  public static enum Destination {
    ZK("zk"), HDFS("hdfs"), JMX("jmx");

    final String dest;

    Destination(String dest) {
      this.dest = dest;
    }

    public String value() {
      return this.dest;
    }
  }

  /**
   * An interface holds the result executed by a task.
   */
  public static interface Result {

    /**
     * Name of the result. This attribute may be used in namespace as path. For
     * instance, when publishing to ZooKeeper, it writes to
     * "/monitor/<groom-server-name>/metrics/jvm" where jvm is the name provided
     * by Result.name() function to identify the metrics record. So ideally name
     * should be given without space e.g. `jvm', instead of `Java virtual
     * machine'.
     * 
     * @return name.
     */
    String name();

    /**
     * Destinations where the result will be handled.
     * 
     * @return Destination in array.
     */
    Destination[] destinations();

    /**
     * Result after a task is executed.
     * 
     * @return result returned.
     */
    Object get();
  }

  /**
   * When executing task goes worng, TaskException is thrown.
   */
  public static class TaskException extends Exception {
    private static final long serialVersionUID = -8982174675196566540L;

    public TaskException() {
      super();
    }

    public TaskException(String message) {
      super(message);
    }

    public TaskException(Throwable t) {
      super(t);
    }
  }

  /**
   * Monitor launchs a worker to run the task.
   */
  static class TaskWorker implements Callable<Object> {

    final Task task;

    TaskWorker(final Task task) {
      this.task = task;
    }

    @Override
    public Object call() throws Exception {
      return task.run();
    }

  }

  /**
   * Monitor class setup task with results and then task worker runs the task.
   */
  public static abstract class Task {

    final String name;
    final AtomicReference<MonitorListener> listener = new AtomicReference<MonitorListener>();

    public Task(String name) {
      this.name = name;
    }

    /**
     * This is only used by Configurator so a task can know which monitor to
     * notify.
     */
    final void setListener(MonitorListener listener) {
      this.listener.set(listener);
    }

    /**
     * Listener that will notify monitor.
     */
    public final MonitorListener getListener() {
      return this.listener.get();
    }

    /**
     * The name of this task.
     */
    public final String getName() {
      return this.name;
    }

    /**
     * Actual flow that tells how a task would be executed. Within run() when an
     * event occurrs, the task should call getListener().notify(Result) passing
     * the result so monitor can react accordingly.
     */
    public abstract Object run() throws TaskException;

  }

  public static final class ZKHandler implements PublisherHandler {
    final ZooKeeper zk;
    final String groomServerName;

    public ZKHandler(ZooKeeper zk, String groomServerName) {
      this.zk = zk;
      this.groomServerName = groomServerName;
    }

    @Override
    public void handle(Result result) {
      Object obj = result.get();
      if (obj instanceof MetricsRecord) {
        String znode = MONITOR_ROOT_PATH + this.groomServerName + "/metrics/"
            + result.name();
        ZKUtil.create(zk, znode); // recursively create znode path
        MetricsRecord record = (MetricsRecord) obj;
        for (Metric<?> metric : record.metrics()) {
          String name = metric.name();
          Number value = (Number) metric.value();
          try {
            // znode must exists so that child (znode/name) can be created.
            if (null != this.zk.exists(znode, false)) {
              String suffix = suffix(value);
              if (LOG.isDebugEnabled()) {
                LOG.debug("Publish name [" + name + "] and value [" + value
                    + "] to zk.");
              }
              final String zpath = znode + ZKUtil.ZK_SEPARATOR + name + suffix;
              if (null == zk.exists(zpath, false)) {
                String p = this.zk.create(zpath, toBytes(value),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                LOG.debug("Publish data to zk with path to `" + p + "'");
              } else {
                // can we just update by increasing 1 version?
                this.zk.setData(zpath, toBytes(value), -1);
                LOG.debug("Update data in znode: " + znode);
              }
            }
          } catch (KeeperException ke) {
            LOG.warn(ke);
          } catch (InterruptedException ie) {
            LOG.warn(ie);
          }
        }
      } else {
        LOG.warn(ZKHandler.class.getSimpleName()
            + " don't know how to handle the result." + obj);
      }
    }

    static String suffix(Number value) {
      if (value instanceof Byte) {
        return "_b";
      } else if (value instanceof Double) {
        return "_d";
      } else if (value instanceof Float) {
        return "_f";
      } else if (value instanceof Integer) {
        return "_i";
      } else if (value instanceof Long) {
        return "_l";
      } else {
        return "_?";
      }
    }

    static byte[] toBytes(Number value) {
      if (value instanceof Byte) {
        return new byte[] { value.byteValue() };
      }

      byte[] bytes = null;
      ByteArrayOutputStream dout = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(dout);
      try {
        if (value instanceof Double) {
          output.writeDouble(value.doubleValue());
        } else if (value instanceof Float) {
          output.writeFloat(value.floatValue());
        } else if (value instanceof Integer) {
          output.writeInt(value.intValue());
        } else if (value instanceof Short) {
          output.writeShort(value.shortValue());
        } else if (value instanceof Long) {
          output.writeLong(value.longValue());
        } else {
          LOG.warn("Unkown data type: " + value);
        }
        bytes = dout.toByteArray();
        if (LOG.isDebugEnabled()) {
          LOG.debug("bytes's length after value (" + value + ") is converted: "
              + ((null != bytes) ? bytes.length : 0));
        }
      } catch (IOException ioe) {
        LOG.warn("Fail writing data to output stream.", ioe);
      } finally {
        try {
          output.close();
        } catch (IOException ioe) {
          LOG.warn("Fail closing output stream.", ioe);
        }
      }
      return bytes;
    }

  }

  static interface PublisherHandler {
    void handle(Result result);
  }

  static final class Publisher extends Thread {

    final ExecutorService pool;
    final Configuration conf;
    final BlockingQueue<Result> results;
    final ConcurrentMap<Destination, PublisherHandler> handlers = new ConcurrentHashMap<Destination, PublisherHandler>();

    Publisher(Configuration conf, BlockingQueue<Result> results) {
      pool = Executors.newCachedThreadPool();
      this.conf = conf;
      this.results = results;
      setName(this.getClass().getSimpleName());
      setDaemon(true);
    }

    void bind(Destination dest, PublisherHandler handler) {
      handlers.putIfAbsent(dest, handler);
    }

    PublisherHandler get(Destination dest) {
      return handlers.get(dest);
    }

    @Override
    public void run() {
      try {
        while (!Thread.interrupted()) {
          final Result result = results.take();
          pool.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
              for (Destination dest : result.destinations()) {
                Publisher.this.get(dest).handle(result);
              }// for
              return null;
            }
          });
          int period = conf.getInt("bsp.monitor.publisher.period", 5);
          Thread.sleep(period * 1000);
        }
      } catch (InterruptedException ie) {
        pool.shutdown();
        LOG.warn("Publisher is interrupted.", ie);
        Thread.currentThread().interrupt();
      }
    }
  }

  static final class Collector extends Thread {

    final ExecutorService pool;
    final Configuration conf;
    final Map<String, TaskWorker> workers;

    Collector(Configuration conf, Map<String, TaskWorker> workers) {
      pool = Executors.newCachedThreadPool();
      this.conf = conf;
      this.workers = workers;
      setName(this.getClass().getSimpleName());
      setDaemon(true);
    }

    @Override
    public void run() {
      try {
        Thread.currentThread();
        while (!Thread.interrupted()) {
          LOG.debug("How many workers will be executed by collector? "
              + workers.size());
          for (TaskWorker worker : workers.values()) {
            pool.submit(worker);
          }
          int period = conf.getInt("bsp.monitor.collector.period", 5);
          Thread.sleep(period * 1000);
        }
      } catch (InterruptedException ie) {
        pool.shutdown();
        LOG.warn(this.getClass().getSimpleName() + " is interrupted.", ie);
        Thread.currentThread().interrupt();
      }
    }
  }

  static final class Initializer extends Thread {

    final Configuration conf;
    final Map<String, TaskWorker> workers;
    final MonitorListener listener;

    Initializer(Map<String, TaskWorker> workers, Configuration conf,
        MonitorListener listener) {
      this.workers = workers;
      this.conf = conf;
      this.listener = listener;
    }

    /**
     * Load jar from plugin directory for executing task.
     */
    @Override
    public void run() {
      try {
        Thread.currentThread();
        while (!Thread.interrupted()) {
          Map<String, Task> tasks = Configurator.configure(
              (HamaConfiguration) this.conf, listener);
          if (null != tasks) {
            for (Map.Entry<String, Task> entry : tasks.entrySet()) {
              String jarPath = entry.getKey();
              Task t = entry.getValue();
              TaskWorker old = ((ConcurrentMap<String, TaskWorker>) this.workers)
                  .putIfAbsent(jarPath, new TaskWorker(t));
              if (null != old) {
                ((ConcurrentMap<String, TaskWorker>) this.workers).replace(
                    jarPath, new TaskWorker(t));
              }
            }
          }
          LOG.debug("Task worker list's size: " + workers.size());
          int period = conf.getInt("bsp.monitor.initializer.period", 5);
          Thread.sleep(period * 1000);
        }// while
      } catch (InterruptedException ie) {
        LOG.warn(this.getClass().getSimpleName() + " is interrupted.", ie);
        Thread.currentThread().interrupt();
      } catch (IOException ioe) {
        LOG.warn(this.getClass().getSimpleName() + " can not load jar file "
            + " from plugin directory.", ioe);
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Constructor
   */
  public Monitor(Configuration configuration, ZooKeeper zookeeper,
      String groomServerName) {
    this.configuration = configuration;
    if (null == this.configuration)
      throw new NullPointerException("No configuration is provided.");
    this.zookeeper = zookeeper;
    if (null == this.zookeeper)
      throw new NullPointerException("ZooKeeper is not provided.");
    this.groomServerName = groomServerName;
    if (null == this.groomServerName)
      throw new NullPointerException("Groom server name is not provided.");
    this.initializer = new Initializer(workers, configuration, this);
    this.collector = new Collector(configuration, workers);
    this.publisher = new Publisher(configuration, results);
    this.publisher
        .bind(ZK, new ZKHandler(this.zookeeper, this.groomServerName));
    setName(this.getClass().getSimpleName());
    setDaemon(true);
  }

  /**
   * Monitor class load jar files and initialize tasks. Dynamic realods if a new
   * task is available.
   */
  public void initialize() {
    initializer.start();
    collector.start();
    publisher.start();
  }

  @Override
  public void notify(Result result) {
    try {
      results.put(result);
      LOG.debug(result.name() + " is put to queue (size is " + results.size()
          + ")");
    } catch (InterruptedException ie) {
      LOG.warn(this.getClass().getSimpleName() + " is interrupted.", ie);
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void run() {
    initialize();
  }

}
