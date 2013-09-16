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

package org.apache.tez.mapreduce.newprocessor;

import java.io.IOException;
import java.net.URI;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKey;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileSystem.Statistics;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.mapred.FileOutputCommitter;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobContext;
import org.apache.hadoop.mapred.MapOutputFile;
import org.apache.hadoop.mapred.RawKeyValueIterator;
import org.apache.hadoop.mapred.TaskAttemptContext;
import org.apache.hadoop.mapred.TaskAttemptID;
import org.apache.hadoop.mapred.TaskID;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.filecache.DistributedCache;
import org.apache.hadoop.mapreduce.lib.reduce.WrappedReducer;
import org.apache.hadoop.mapreduce.security.token.JobTokenSecretManager;
import org.apache.hadoop.mapreduce.task.ReduceContextImpl;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.util.ResourceCalculatorProcessTree;
import org.apache.tez.common.Constants;
import org.apache.tez.common.TezJobConfig;
import org.apache.tez.common.TezTaskStatus.State;
import org.apache.tez.common.TezUtils;
import org.apache.tez.common.counters.TaskCounter;
import org.apache.tez.common.counters.TezCounter;
import org.apache.tez.common.counters.TezCounters;
import org.apache.tez.dag.records.TezDAGID;
import org.apache.tez.engine.common.security.JobTokenIdentifier;
import org.apache.tez.engine.common.security.TokenCache;
import org.apache.tez.engine.common.sort.impl.TezRawKeyValueIterator;
import org.apache.tez.engine.newapi.TezProcessorContext;
import org.apache.tez.engine.records.OutputContext;
import org.apache.tez.mapreduce.hadoop.DeprecatedKeys;
import org.apache.tez.mapreduce.hadoop.IDConverter;
import org.apache.tez.mapreduce.hadoop.MRConfig;
import org.apache.tez.mapreduce.hadoop.MRJobConfig;
import org.apache.tez.mapreduce.hadoop.newmapred.TaskAttemptContextImpl;
import org.apache.tez.mapreduce.hadoop.mapreduce.JobContextImpl;
import org.apache.tez.mapreduce.hadoop.mapreduce.TezNullOutputCommitter;
//import org.apache.tez.mapreduce.partition.MRPartitioner;
import org.apache.tez.mapreduce.task.impl.YarnOutputFiles;

@SuppressWarnings("deprecation")
public abstract class MRTask {

  static final Log LOG = LogFactory.getLog(MRTask.class);

  protected JobConf jobConf;
  protected JobContext jobContext;
  protected TaskAttemptContext taskAttemptContext;
  protected OutputCommitter committer;

  // Current counters
  transient TezCounters counters;
  protected GcTimeUpdater gcUpdater;
  private ResourceCalculatorProcessTree pTree;
  private long initCpuCumulativeTime = 0;
  protected TezProcessorContext tezEngineTaskContext;
  protected TaskAttemptID taskAttemptId;
  protected Progress progress = new Progress();
  protected SecretKey jobTokenSecret;

  boolean isMap;

  /* flag to track whether task is done */
  AtomicBoolean taskDone = new AtomicBoolean(false);

  /** Construct output file names so that, when an output directory listing is
   * sorted lexicographically, positions correspond to output partitions.*/
  private static final NumberFormat NUMBER_FORMAT = NumberFormat.getInstance();
  static {
    NUMBER_FORMAT.setMinimumIntegerDigits(5);
    NUMBER_FORMAT.setGroupingUsed(false);
  }

  protected MRTaskReporter mrReporter;
  protected boolean useNewApi;

  /**
   * A Map where Key-> URIScheme and value->FileSystemStatisticUpdater
   */
  private Map<String, FileSystemStatisticUpdater> statisticUpdaters =
     new HashMap<String, FileSystemStatisticUpdater>();

  public MRTask(boolean isMap) {
    this.isMap = isMap;
  }

  // TODO how to update progress
  public void initialize(TezProcessorContext context) throws IOException,
  InterruptedException {
    
    DeprecatedKeys.init();

    tezEngineTaskContext = context;
    counters = context.getCounters();
    this.taskAttemptId = new TaskAttemptID(
        new TaskID(
            Long.toString(context.getApplicationId().getClusterTimestamp()),
            context.getApplicationId().getId(),
            (isMap ? TaskType.MAP : TaskType.REDUCE),
            context.getTaskIndex()),
          context.getAttemptNumber());
    // TODO TEZAM4 Figure out initialization / run sequence of Input, Process,
    // Output. Phase is MR specific.
    gcUpdater = new GcTimeUpdater(counters);

    byte[] userPayload = context.getUserPayload();
    Configuration conf = TezUtils.createConfFromUserPayload(userPayload);
    if (conf instanceof JobConf) {
      this.jobConf = (JobConf)conf;
    } else {
      this.jobConf = new JobConf(conf);
    }
    jobConf.set(Constants.TEZ_ENGINE_TASK_ATTEMPT_ID,
        taskAttemptId.toString());

    initResourceCalculatorPlugin();

    LOG.info("MRTask.inited: taskAttemptId = " + taskAttemptId.toString());
    
    // TODO Post MRR
    // A single file per vertex will likely be a better solution. Does not
    // require translation - client can take care of this. Will work independent
    // of whether the configuration is for intermediate tasks or not. Has the
    // overhead of localizing multiple files per job - i.e. the client would
    // need to write these files to hdfs, add them as local resources per
    // vertex. A solution like this may be more practical once it's possible to
    // submit configuration parameters to the AM and effectively tasks via RPC.

    jobConf.set(MRJobConfig.VERTEX_NAME, tezEngineTaskContext.getTaskVertexName());

    if (LOG.isDebugEnabled() && userPayload != null) {
      Iterator<Entry<String, String>> iter = jobConf.iterator();
      String taskIdStr = taskAttemptId.getTaskID().toString();
      while (iter.hasNext()) {
        Entry<String, String> confEntry = iter.next();
        LOG.debug("TaskConf Entry"
            + ", taskId=" + taskIdStr
            + ", key=" + confEntry.getKey()
            + ", value=" + confEntry.getValue());
      }
    }

    configureMRTask();
  }
  
  private void configureMRTask()
      throws IOException, InterruptedException {

    Credentials credentials = UserGroupInformation.getCurrentUser()
        .getCredentials();
    jobConf.setCredentials(credentials);
    // TODO Can this be avoided all together. Have the MRTezOutputCommitter use
    // the Tez parameter.
    // TODO This could be fetched from the env if YARN is setting it for all
    // Containers.
    // Set it in conf, so as to be able to be used the the OutputCommitter.
    // TODO should this be DAG Id
    jobConf.setInt(MRJobConfig.APPLICATION_ATTEMPT_ID, tezEngineTaskContext
        .getApplicationId().getId());

    jobConf.setClass(MRConfig.TASK_LOCAL_OUTPUT_CLASS, YarnOutputFiles.class,
        MapOutputFile.class); // MR

    // Not needed. This is probably being set via the source/consumer meta
    Token<JobTokenIdentifier> jobToken = TokenCache.getJobToken(credentials);
    if (jobToken != null) {
      // Will MR ever run without a job token.
      SecretKey sk = JobTokenSecretManager.createSecretKey(jobToken
          .getPassword());
      this.jobTokenSecret = sk;
    } else {
      LOG.warn("No job token set");
    }

    jobConf.set(MRJobConfig.JOB_LOCAL_DIR, jobConf.get(TezJobConfig.JOB_LOCAL_DIR));
    jobConf.set(MRConfig.LOCAL_DIR, jobConf.get(TezJobConfig.LOCAL_DIRS));
    
    if (jobConf.get(TezJobConfig.DAG_CREDENTIALS_BINARY) != null) {
      jobConf.set(MRJobConfig.MAPREDUCE_JOB_CREDENTIALS_BINARY,
          jobConf.get(TezJobConfig.DAG_CREDENTIALS_BINARY));
    }

    // Set up the DistributedCache related configs
    setupDistributedCacheConfig(jobConf);
  }

  /**
   * Set up the DistributedCache related configs to make
   * {@link DistributedCache#getLocalCacheFiles(Configuration)} and
   * {@link DistributedCache#getLocalCacheArchives(Configuration)} working.
   * 
   * @param job
   * @throws IOException
   */
  private static void setupDistributedCacheConfig(final JobConf job)
      throws IOException {

    String localWorkDir = (job.get(TezJobConfig.TASK_LOCAL_RESOURCE_DIR));
    // ^ ^ all symlinks are created in the current work-dir

    // Update the configuration object with localized archives.
    URI[] cacheArchives = DistributedCache.getCacheArchives(job);
    if (cacheArchives != null) {
      List<String> localArchives = new ArrayList<String>();
      for (int i = 0; i < cacheArchives.length; ++i) {
        URI u = cacheArchives[i];
        Path p = new Path(u);
        Path name = new Path((null == u.getFragment()) ? p.getName()
            : u.getFragment());
        String linkName = name.toUri().getPath();
        localArchives.add(new Path(localWorkDir, linkName).toUri().getPath());
      }
      if (!localArchives.isEmpty()) {
        job.set(MRJobConfig.CACHE_LOCALARCHIVES, StringUtils
            .arrayToString(localArchives.toArray(new String[localArchives
                .size()])));
      }
    }

    // Update the configuration object with localized files.
    URI[] cacheFiles = DistributedCache.getCacheFiles(job);
    if (cacheFiles != null) {
      List<String> localFiles = new ArrayList<String>();
      for (int i = 0; i < cacheFiles.length; ++i) {
        URI u = cacheFiles[i];
        Path p = new Path(u);
        Path name = new Path((null == u.getFragment()) ? p.getName()
            : u.getFragment());
        String linkName = name.toUri().getPath();
        localFiles.add(new Path(localWorkDir, linkName).toUri().getPath());
      }
      if (!localFiles.isEmpty()) {
        job.set(MRJobConfig.CACHE_LOCALFILES, StringUtils
            .arrayToString(localFiles.toArray(new String[localFiles.size()])));
      }
    }
  }


  private void initResourceCalculatorPlugin() {
    Class<? extends ResourceCalculatorProcessTree> clazz =
        this.jobConf.getClass(MRConfig.RESOURCE_CALCULATOR_PROCESS_TREE,
            null, ResourceCalculatorProcessTree.class);
    pTree = ResourceCalculatorProcessTree
        .getResourceCalculatorProcessTree(System.getenv().get("JVM_PID"), clazz, this.jobConf);
    LOG.info(" Using ResourceCalculatorProcessTree : " + pTree);
    if (pTree != null) {
      pTree.updateProcessTree();
      initCpuCumulativeTime = pTree.getCumulativeCpuTime();
    }
  }

  public TezProcessorContext getUmbilical() {
    return this.tezEngineTaskContext;
  }

  public void initTask() throws IOException,
                                InterruptedException {
    this.mrReporter = new MRTaskReporter(tezEngineTaskContext);
    this.useNewApi = jobConf.getUseNewMapper();
    TezDAGID dagId = IDConverter.fromMRTaskAttemptId(taskAttemptId).getTaskID()
        .getVertexID().getDAGId();
    
    this.jobContext = new JobContextImpl(jobConf, dagId, mrReporter);
    this.taskAttemptContext =
        new TaskAttemptContextImpl(jobConf, taskAttemptId, mrReporter);

    if (getState() == State.UNASSIGNED) {
      setState(State.RUNNING);
    }

//    combineProcessor = null;
//    boolean useCombiner = false;
//    if (useNewApi) {
//      try {
//        useCombiner = (taskAttemptContext.getCombinerClass() != null);
//      } catch (ClassNotFoundException e) {
//        throw new IOException("Could not find combiner class", e);
//      }
//    } else {
//      useCombiner = (job.getCombinerClass() != null);
//    }
//    if (useCombiner) {
//      combineProcessor = new MRCombiner(this);
//      combineProcessor.initialize(job, getTaskReporter());
//    } else {
//    }

    localizeConfiguration(jobConf);
  }

//  public void initPartitioner(JobConf job) throws IOException,
//      InterruptedException {
//    partitioner = new MRPartitioner(this);
//    ((MRPartitioner) partitioner).initialize(job, getTaskReporter());
//  }
  
  public void initCommitter(JobConf job, boolean useNewApi,
      boolean useNullCommitter) throws IOException, InterruptedException {
    if (useNullCommitter) {
      setCommitter(new TezNullOutputCommitter());
      return;
    }
    if (useNewApi) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("using new api for output committer");
      }
      OutputFormat<?, ?> outputFormat = null;
      try {
        outputFormat = ReflectionUtils.newInstance(
            taskAttemptContext.getOutputFormatClass(), job);
      } catch (ClassNotFoundException cnfe) {
        throw new IOException("Unknown OutputFormat", cnfe);
      }
      setCommitter(outputFormat.getOutputCommitter(taskAttemptContext));
    } else {
      setCommitter(job.getOutputCommitter());
    }

    Path outputPath = FileOutputFormat.getOutputPath(job);
    if (outputPath != null) {
      if ((getCommitter() instanceof FileOutputCommitter)) {
        FileOutputFormat.setWorkOutputPath(job,
            ((FileOutputCommitter) getCommitter())
                .getTaskAttemptPath(taskAttemptContext));
      } else {
        FileOutputFormat.setWorkOutputPath(job, outputPath);
      }
    }
    getCommitter().setupTask(taskAttemptContext);
  }
  
  public MRTaskReporter getMRReporter() {
    return mrReporter;
  }

  public void setState(State state) {
    // TODO Auto-generated method stub

  }

  public State getState() {
    // TODO Auto-generated method stub
    return null;
  }

  public OutputCommitter getCommitter() {
    return committer;
  }

  public void setCommitter(OutputCommitter committer) {
    this.committer = committer;
  }

  public TezCounters getCounters() { return counters; }

  public void setConf(JobConf jobConf) {
    this.jobConf = jobConf;
  }

  public JobConf getConf() {
    return this.jobConf;
  }

  /**
   * Gets a handle to the Statistics instance based on the scheme associated
   * with path.
   *
   * @param path the path.
   * @param conf the configuration to extract the scheme from if not part of
   *   the path.
   * @return a Statistics instance, or null if none is found for the scheme.
   */
  @Private
  public static List<Statistics> getFsStatistics(Path path, Configuration conf) throws IOException {
    List<Statistics> matchedStats = new ArrayList<FileSystem.Statistics>();
    path = path.getFileSystem(conf).makeQualified(path);
    String scheme = path.toUri().getScheme();
    for (Statistics stats : FileSystem.getAllStatistics()) {
      if (stats.getScheme().equals(scheme)) {
        matchedStats.add(stats);
      }
    }
    return matchedStats;
  }

  @Private
  public synchronized String getOutputName() {
    return "part-" + NUMBER_FORMAT.format(taskAttemptId.getTaskID().getId());
  }

  public void waitBeforeCompletion(MRTaskReporter reporter) throws IOException,
      InterruptedException {
  }

  public void outputReady(MRTaskReporter reporter, OutputContext outputContext)
      throws IOException,
      InterruptedException {
    LOG.info("Task: " + taskAttemptId + " reporting outputReady");
    updateCounters();
    statusUpdate();
  }

  public void done() throws IOException, InterruptedException {
    updateCounters();

    LOG.info("Task:" + taskAttemptId + " is done."
        + " And is in the process of committing");
    // TODO change this to use the new context
    // TODO TEZ Interaciton between Commit and OutputReady. Merge ?
    if (isCommitRequired()) {
      // TODO TEZ-439
//      int retries = MAX_RETRIES;
//      setState(TezTaskStatus.State.COMMIT_PENDING);
//      //say the task tracker that task is commit pending
//      // TODO TEZAM2 - Why is the commitRequired check missing ?
//      while (true) {
//        try {
//          umbilical.commitPending(taskAttemptId, status);
//          break;
//        } catch (InterruptedException ie) {
//          // ignore
//        } catch (IOException ie) {
//          LOG.warn("Failure sending commit pending: " +
//              StringUtils.stringifyException(ie));
//          if (--retries == 0) {
//            System.exit(67);
//          }
//        }
//      }
      //wait for commit approval and commit
      // TODO EVENTUALLY - Commit is not required for map tasks. skip a couple of RPCs before exiting.
      commit(committer);
    }
    taskDone.set(true);
    // Make sure we send at least one set of counter increments. It's
    // ok to call updateCounters() in this thread after comm thread stopped.
    updateCounters();
    sendLastUpdate();
    //signal the tasktracker that we are done
    //sendDone(umbilical);
  }


  private boolean isCommitRequired() throws IOException {
    return committer.needsTaskCommit(taskAttemptContext);
  }

  /**
   * Send a status update to the task tracker
   * @param umbilical
   * @throws IOException
   */
  public void statusUpdate() throws IOException, InterruptedException {
    // TODO call progress update here if not being called within Map/Reduce
  }

  /**
   * Sends last status update before sending umbilical.done();
   */
  private void sendLastUpdate()
      throws IOException, InterruptedException {
    statusUpdate();
  }

  private void commit(org.apache.hadoop.mapreduce.OutputCommitter committer
      ) throws IOException {
    while (!tezEngineTaskContext.canCommit()) {
      // This will loop till the AM asks for the task to be killed. As
      // against, the AM sending a signal to the task to kill itself
      // gracefully.
      try {
        Thread.sleep(1000);
      } catch(InterruptedException ie) {
        //ignore
      }
    }

    // task can Commit now
    try {
      LOG.info("Task " + taskAttemptId + " is allowed to commit now");
      committer.commitTask(taskAttemptContext);
      return;
    } catch (IOException iee) {
      LOG.warn("Failure committing: " +
          StringUtils.stringifyException(iee));
      //if it couldn't commit a successfully then delete the output
      discardOutput(taskAttemptContext);
      throw iee;
    }
  }

  private
  void discardOutput(TaskAttemptContext taskContext) {
    try {
      committer.abortTask(taskContext);
    } catch (IOException ioe)  {
      LOG.warn("Failure cleaning up: " +
               StringUtils.stringifyException(ioe));
    }
  }


  public void updateCounters() {
    // TODO Auto-generated method stub
    // TODO TEZAM Implement.
    Map<String, List<FileSystem.Statistics>> map = new
        HashMap<String, List<FileSystem.Statistics>>();
    for(Statistics stat: FileSystem.getAllStatistics()) {
      String uriScheme = stat.getScheme();
      if (map.containsKey(uriScheme)) {
        List<FileSystem.Statistics> list = map.get(uriScheme);
        list.add(stat);
      } else {
        List<FileSystem.Statistics> list = new ArrayList<FileSystem.Statistics>();
        list.add(stat);
        map.put(uriScheme, list);
      }
    }
    for (Map.Entry<String, List<FileSystem.Statistics>> entry: map.entrySet()) {
      FileSystemStatisticUpdater updater = statisticUpdaters.get(entry.getKey());
      if(updater==null) {//new FileSystem has been found in the cache
        updater =
            new FileSystemStatisticUpdater(counters, entry.getValue(),
                entry.getKey());
        statisticUpdaters.put(entry.getKey(), updater);
      }
      updater.updateCounters();
    }

    gcUpdater.incrementGcCounter();
    updateResourceCounters();
  }

  /**
   * Updates the {@link TaskCounter#COMMITTED_HEAP_BYTES} counter to reflect the
   * current total committed heap space usage of this JVM.
   */
  private void updateHeapUsageCounter() {
    long currentHeapUsage = Runtime.getRuntime().totalMemory();
    counters.findCounter(TaskCounter.COMMITTED_HEAP_BYTES)
            .setValue(currentHeapUsage);
  }

  /**
   * Update resource information counters
   */
  void updateResourceCounters() {
    // Update generic resource counters
    updateHeapUsageCounter();

    // Updating resources specified in ResourceCalculatorPlugin
    if (pTree == null) {
      return;
    }
    pTree.updateProcessTree();
    long cpuTime = pTree.getCumulativeCpuTime();
    long pMem = pTree.getCumulativeRssmem();
    long vMem = pTree.getCumulativeVmem();
    // Remove the CPU time consumed previously by JVM reuse
    cpuTime -= initCpuCumulativeTime;
    counters.findCounter(TaskCounter.CPU_MILLISECONDS).setValue(cpuTime);
    counters.findCounter(TaskCounter.PHYSICAL_MEMORY_BYTES).setValue(pMem);
    counters.findCounter(TaskCounter.VIRTUAL_MEMORY_BYTES).setValue(vMem);
  }


  public static String normalizeStatus(String status, Configuration conf) {
    // Check to see if the status string is too long
    // and truncate it if needed.
    int progressStatusLength = conf.getInt(
        MRConfig.PROGRESS_STATUS_LEN_LIMIT_KEY,
        MRConfig.PROGRESS_STATUS_LEN_LIMIT_DEFAULT);
    if (status.length() > progressStatusLength) {
      LOG.warn("Task status: \"" + status + "\" truncated to max limit ("
          + progressStatusLength + " characters)");
      status = status.substring(0, progressStatusLength);
    }
    return status;
  }

  protected static <INKEY,INVALUE,OUTKEY,OUTVALUE>
  org.apache.hadoop.mapreduce.Reducer<INKEY,INVALUE,OUTKEY,OUTVALUE>.Context
  createReduceContext(org.apache.hadoop.mapreduce.Reducer
                        <INKEY,INVALUE,OUTKEY,OUTVALUE> reducer,
                      Configuration job,
                      TaskAttemptID taskId,
                      final TezRawKeyValueIterator rIter,
                      org.apache.hadoop.mapreduce.Counter inputKeyCounter,
                      org.apache.hadoop.mapreduce.Counter inputValueCounter,
                      org.apache.hadoop.mapreduce.RecordWriter<OUTKEY,OUTVALUE> output,
                      org.apache.hadoop.mapreduce.OutputCommitter committer,
                      org.apache.hadoop.mapreduce.StatusReporter reporter,
                      RawComparator<INKEY> comparator,
                      Class<INKEY> keyClass, Class<INVALUE> valueClass
  ) throws IOException, InterruptedException {
    RawKeyValueIterator r =
        new RawKeyValueIterator() {

          @Override
          public boolean next() throws IOException {
            return rIter.next();
          }

          @Override
          public DataInputBuffer getValue() throws IOException {
            return rIter.getValue();
          }

          @Override
          public Progress getProgress() {
            return rIter.getProgress();
          }

          @Override
          public DataInputBuffer getKey() throws IOException {
            return rIter.getKey();
          }

          @Override
          public void close() throws IOException {
            rIter.close();
          }
        };
    org.apache.hadoop.mapreduce.ReduceContext<INKEY, INVALUE, OUTKEY, OUTVALUE>
    reduceContext =
      new ReduceContextImpl<INKEY, INVALUE, OUTKEY, OUTVALUE>(
          job,
          taskId,
          r,
          inputKeyCounter,
          inputValueCounter,
          output,
          committer,
          reporter,
          comparator,
          keyClass,
          valueClass);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Using key class: " + keyClass
          + ", valueClass: " + valueClass);
    }

    org.apache.hadoop.mapreduce.Reducer<INKEY,INVALUE,OUTKEY,OUTVALUE>.Context
        reducerContext =
          new WrappedReducer<INKEY, INVALUE, OUTKEY, OUTVALUE>().getReducerContext(
              reduceContext);

    return reducerContext;
  }

  public void taskCleanup()
      throws IOException, InterruptedException {
    // set phase for this task
    statusUpdate();
    LOG.info("Runnning cleanup for the task");
    // do the cleanup
    committer.abortTask(taskAttemptContext);
  }

  public void localizeConfiguration(JobConf jobConf)
      throws IOException, InterruptedException {
    jobConf.set(JobContext.TASK_ID, taskAttemptId.getTaskID().toString());
    jobConf.set(JobContext.TASK_ATTEMPT_ID, taskAttemptId.toString());
    jobConf.setInt(JobContext.TASK_PARTITION,
        taskAttemptId.getTaskID().getId());
    jobConf.set(JobContext.ID, taskAttemptId.getJobID().toString());
  }

  public abstract TezCounter getOutputRecordsCounter();

  public abstract TezCounter getInputRecordsCounter();

  public org.apache.hadoop.mapreduce.TaskAttemptContext getTaskAttemptContext() {
    return taskAttemptContext;
  }

  public JobContext getJobContext() {
    return jobContext;
  }

  public TaskAttemptID getTaskAttemptId() {
    return taskAttemptId;
  }

  public TezProcessorContext getTezEngineTaskContext() {
    return tezEngineTaskContext;
  }
}