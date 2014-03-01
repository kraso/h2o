package water;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import water.H2O.H2OCountedCompleter;
import water.H2O.H2OEmptyCompleter;
import water.api.Constants;
import water.api.DocGen;
import water.api.Progress2;
import water.api.Request.Validator.NOPValidator;
import water.api.RequestServer.API_VERSION;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.Log;
import water.util.Utils;
import water.util.Utils.ExpectedExceptionForDebug;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;

import static water.util.Utils.difference;
import static water.util.Utils.isEmpty;

public class Job extends Request2 {
  static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
  static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

  /** A system key for global list of Job keys. */
  static final Key LIST = Key.make(Constants.BUILT_IN_KEY_JOBS, (byte) 0, Key.BUILT_IN_KEY);
  public static final long CANCELLED_END_TIME = -1;
  private static final int[] EMPTY = new int[0];

  @API(help = "Job key")
  protected Key job_key;
  @API(help = "Destination key", filter = Default.class, json = true, validator = DestKeyValidator.class)
  public Key destination_key; // Key holding final value after job is removed
  static class DestKeyValidator extends NOPValidator<Key> {
    @Override public void validateRaw(String value) {
      if (Utils.contains(value, Key.ILLEGAL_USER_KEY_CHARS))
        throw new IllegalArgumentException("Key '" + value + "' contains illegal character! Please avoid these characters: " + Key.ILLEGAL_USER_KEY_CHARS);
    }
  }
  @API(help = "Job description") public String   description;
  @API(help = "Job start time")  public long     start_time;
  @API(help = "Job end time")    public long     end_time;
  @API(help = "Exception")       public String   exception;
  @API(help = "Job state")       public JobState state;

  transient public H2OCountedCompleter _fjtask; // Top-level task you can block on

  /** Possible job states. */
  public static enum JobState {
    RUNNING,   // Job is running
    CANCELLED, // Job was cancelled by user
    CRASHED,   // Job crashed, error message/exception is available
    DONE       // Job was successfully finished
  }

  public Job(Key jobKey, Key dstKey){
   job_key = jobKey;
   destination_key = dstKey;
  }
  public Job() {
    job_key = defaultJobKey();
    description = getClass().getSimpleName();
  }
  /** Private copy constructor used by {@link JobHandle}. */
  private Job(final Job prior) {
    this(prior.job_key, prior.destination_key);
    this.description = prior.description;
    this.start_time  = prior.start_time;
    this.end_time    = prior.end_time;
    this.state       = prior.state;
  }

  public Key self() { return job_key; }
  public Key dest() { return destination_key; }

  public int gridParallelism() {
    return 1;
  }

  /**
   *
   */
  public static abstract class FrameJob extends Job {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @API(help = "Source frame", required = true, filter = Default.class, json = true)
    public Frame source;

    /**
     * Annotate the number of columns and rows of the training data set in the job parameter JSON
     * @return JsonObject annotated with num_cols and num_rows of the training data set
     */
    @Override protected JsonObject toJSON() {
      JsonObject jo = super.toJSON();
      if (source != null) {
        jo.getAsJsonObject("source").addProperty("num_cols", source.numCols());
        jo.getAsJsonObject("source").addProperty("num_rows", source.numRows());
      }
      return jo;
    }
  }

  /**
   *
   */
  public static abstract class ColumnsJob extends FrameJob {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @API(help = "Input columns (Indexes start at 0)", filter=colsFilter.class, hide=true)
    public int[] cols;
    class colsFilter extends MultiVecSelect { public colsFilter() { super("source"); } }

    @API(help = "Ignored columns by name and zero-based index", filter=colsNamesIdxFilter.class, displayName="Ignored columns")
    public int[] ignored_cols = EMPTY;
    class colsNamesIdxFilter extends MultiVecSelect { public colsNamesIdxFilter() {super("source", MultiVecSelectType.NAMES_THEN_INDEXES); } }

    @API(help = "Ignored columns by name", filter=colsNamesFilter.class, displayName="Ignored columns by name", hide=true)
    public int[] ignored_cols_by_name = EMPTY;
    class colsNamesFilter extends MultiVecSelect { public colsNamesFilter() {super("source", MultiVecSelectType.NAMES_ONLY); } }

    /**
     * Annotate the used and ignored columns in the job parameter JSON
     * For both the used and the ignored columns, the following rules apply:
     * If the number of columns is less or equal than 100, a dense list of used columns is reported.
     * If the number of columns is greater than 100, the number of columns is reported.
     * If the number of columns is 0, a "N/A" is reported.
     * @return JsonObject annotated with used/ignored columns
     */
    @Override protected JsonObject toJSON() {
      JsonObject jo = super.toJSON();
      if (!jo.has("source")) return jo;
      HashMap<String, int[]> map = new HashMap<String, int[]>();
      map.put("used_cols", cols);
      map.put("ignored_cols", ignored_cols);
      for (String key : map.keySet()) {
        int[] val = map.get(key);
        if (val != null) {
          if(val.length>100) jo.getAsJsonObject("source").addProperty("num_" + key, val.length);
          else if(val.length>0) {
            StringBuilder sb = new StringBuilder();
            for (int c : val) sb.append(c + ",");
            jo.getAsJsonObject("source").addProperty(key, sb.toString().substring(0, sb.length()-1));
          } else {
            jo.getAsJsonObject("source").addProperty(key, "N/A");
          }
        }
      }
      return jo;
    }

    @Override protected void init() {
      super.init();

      // At most one of the following may be specified.
      int specified = 0;
      if (!isEmpty(cols)) { specified++; }
      if (!isEmpty(ignored_cols)) { specified++; }
      if (!isEmpty(ignored_cols_by_name)) { specified++; }
      if (specified > 1) throw new IllegalArgumentException("Arguments 'cols', 'ignored_cols_by_name', and 'ignored_cols' are exclusive");

      // If the column are not specified, then select everything.
      if (isEmpty(cols)) {
        cols = new int[source.vecs().length];
        for( int i = 0; i < cols.length; i++ )
          cols[i] = i;
      } else {
        if (!checkIdx(source, cols)) throw new IllegalArgumentException("Argument 'cols' specified invalid column!");
      }
      // Make a set difference between cols and (ignored_cols || ignored_cols_by_name)
      if (!isEmpty(ignored_cols) || !isEmpty(ignored_cols_by_name)) {
        int[] icols = ! isEmpty(ignored_cols) ? ignored_cols : ignored_cols_by_name;
        if (!checkIdx(source, icols)) throw new IllegalArgumentException("Argument '"+(!isEmpty(ignored_cols) ? "ignored_cols" : "ignored_cols_by_name")+"' specified invalid column!");
        cols = difference(cols, icols);
        // Setup all variables in consistence way
        ignored_cols = icols;
        ignored_cols_by_name = icols;
      }

      if( cols.length == 0 )
        throw new IllegalArgumentException("No column selected");
    }

    protected final Vec[] selectVecs(Frame frame) {
      Vec[] vecs = new Vec[cols.length];
      for( int i = 0; i < cols.length; i++ )
        vecs[i] = frame.vecs()[cols[i]];
      return vecs;
    }

    protected final Frame selectFrame(Frame frame) {
      Vec[] vecs = new Vec[cols.length];
      String[] names = new String[cols.length];
      for( int i = 0; i < cols.length; i++ ) {
        vecs[i] = frame.vecs()[cols[i]];
        names[i] = frame.names()[cols[i]];
      }
      return new Frame(names, vecs);
    }
  }

  /**
   *
   */
  public static abstract class ModelJob extends ColumnsJob {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @API(help="Column to use as class", required=true, filter=responseFilter.class, json = true)
    public Vec response;
    class responseFilter extends VecClassSelect { responseFilter() { super("source"); } }

    @API(help="Do Classification or regression", filter=myClassFilter.class, json = true)
    public boolean classification = true;
    class myClassFilter extends DoClassBoolean { myClassFilter() { super("source"); } }

    @Override protected void registered(API_VERSION ver) {
      super.registered(ver);
      Argument c = find("ignored_cols");
      Argument r = find("response");
      int ci = _arguments.indexOf(c);
      int ri = _arguments.indexOf(r);
      _arguments.set(ri, c);
      _arguments.set(ci, r);
      ((FrameKeyMultiVec) c).setResponse((FrameClassVec) r);
    }

    /**
     * Annotate the name of the response column in the job parameter JSON
     * @return JsonObject annotated with the name of the response column
     */
    @Override protected JsonObject toJSON() {
      JsonObject jo = super.toJSON();
      int idx = source.find(response);
      if( idx == -1 ) {
        Vec vm = response.masterVec();
        if( vm != null ) idx = source.find(vm);
      }
      jo.getAsJsonObject("response").add("name", new JsonPrimitive(idx == -1 ? "null" : source._names[idx]));
      return jo;
    }

    @Override protected void init() {
      super.init();
      // Does not alter the Response to an Enum column if Classification is
      // asked for: instead use the classification flag to decide between
      // classification or regression.
      Vec[] vecs = source.vecs();
      for( int i = cols.length - 1; i >= 0; i-- )
        if( vecs[cols[i]] == response )
          cols = Utils.remove(cols,i);

      final boolean has_constant_response = response.isEnum() ?
              response.domain().length <= 1 : response.min() == response.max();
      if (has_constant_response)
        throw new IllegalArgumentException("Constant response column!");
    }
  }

  /**
   *
   */
  public static abstract class ValidatedJob extends ModelJob {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;
    protected transient Vec[] _train, _valid;
    protected transient Vec _validResponse;
    protected transient String[] _names;
    protected transient String _responseName;

    @API(help = "Validation frame", filter = Default.class, mustExist = true, json = true)
    public Frame validation;

    /**
     * Annotate the number of columns and rows of the validation data set in the job parameter JSON
     * @return JsonObject annotated with num_cols and num_rows of the validation data set
     */
    @Override protected JsonObject toJSON() {
      JsonObject jo = super.toJSON();
      if (validation != null) {
        jo.getAsJsonObject("validation").addProperty("num_cols", validation.numCols());
        jo.getAsJsonObject("validation").addProperty("num_rows", validation.numRows());
      }
      return jo;
    }

    @Override protected void init() {
      super.init();

      int rIndex = 0;
      for( int i = 0; i < source.vecs().length; i++ )
        if( source.vecs()[i] == response )
          rIndex = i;
      _responseName = source._names != null && rIndex >= 0 ? source._names[rIndex] : "response";

      _train = selectVecs(source);
      _names = new String[cols.length];
      for( int i = 0; i < cols.length; i++ )
        _names[i] = source._names[cols[i]];

      if( validation != null ) {
        int idx = validation.find(source.names()[rIndex]);
        if( idx == -1 ) throw new IllegalArgumentException("Validation set does not have a response column called "+_responseName);
        _validResponse = validation.vecs()[idx];
      }
    }
  }

  /**
   *
   */
  public static abstract class HexJob extends Job {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @API(help = "Source key", required = true, filter = source_keyFilter.class)
    public Key source_key;
    class source_keyFilter extends H2OHexKey { public source_keyFilter() { super(""); } }
  }

  public interface Progress {
    float progress();
  }

  public interface ProgressMonitor {
    public void update(long n);
  }

  public static class Fail extends Iced {
    public final String _message;
    public Fail(String message) { _message = message; }
  }

  static final class List extends Iced {
    Key[] _jobs = new Key[0];

    @Override
    public List clone(){
      List l = new List();
      l._jobs = _jobs.clone();
      for(int i = 0; i < l._jobs.length; ++i)
        l._jobs[i] = (Key)l._jobs[i].clone();
      return l;
    }
  }

  public static Job[] all() {
    List list = UKV.get(LIST);
    Job[] jobs = new Job[list==null?0:list._jobs.length];
    int j=0;
    for( int i=0; i<jobs.length; i++ ) {
      Job job = UKV.get(list._jobs[i]);
      if( job != null ) jobs[j++] = job;
    }
    if( j<jobs.length ) jobs = Arrays.copyOf(jobs,j);
    return jobs;
  }

  protected Key defaultJobKey() {
    // Pinned to self, because it should be almost always updated locally
    return Key.make((byte) 0, Key.JOB, H2O.SELF);
  }

  protected Key defaultDestKey() {
    return Key.make(getClass().getSimpleName() + Key.rand());
  }

  public Job start(final H2OCountedCompleter fjtask) {
    _fjtask = fjtask;
    //Futures fs = new Futures();
    start_time = System.currentTimeMillis();
    state      = JobState.RUNNING;
    // Save the full state of the job
    UKV.put(self(), this);
    // Update job list
    new TAtomic<List>() {
      @Override public List atomic(List old) {
        if( old == null ) old = new List();
        Key[] jobs = old._jobs;
        old._jobs = Arrays.copyOf(jobs, jobs.length + 1);
        old._jobs[jobs.length] = job_key;
        return old;
      }
    }.invoke(LIST);
    return this;
  }
  // Overridden for Parse
  public float progress() {
    Freezable f = UKV.get(destination_key);
    if( f instanceof Progress )
      return ((Progress) f).progress();
    return 0;
  }

  // Block until the Job finishes.
  public <T> T get() {
    _fjtask.join();             // Block until top-level job is done
    T ans = (T) UKV.get(destination_key);
    remove();                   // Remove self-job
    return ans;
  }

  public void cancel() {
    cancel((String)null);
  }
  public void cancel(Throwable ex){
    ex.printStackTrace();
    if(_fjtask != null && !_fjtask.isDone())_fjtask.completeExceptionally(ex);
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    ex.printStackTrace(pw);
    String stackTrace = sw.toString();
    cancel("Got exception '" + ex.getClass() + "', with msg '" + ex.getMessage() + "'\n" + stackTrace);
  }
  public void cancel(final String msg) {
    state = msg == null ? JobState.CANCELLED : JobState.CRASHED;
    // replace finished job by a job handle
    replaceByJobHandle();
    DKV.write_barrier();
    final Job job = this;
    H2O.submitTask(new H2OCountedCompleter() {
      @Override public void compute2() {
        job.onCancelled();
      }
    });
  }

  /**
   *
   */
  protected void onCancelled() {
  }
  // This querys the *current object* for its status.
  // Only valid if you have a Job object that is being updated by somebody.
  public boolean isCancelled() { return state == JobState.CANCELLED || state == JobState.CRASHED; }

  // Check the K/V store to see the Job is still running
  public static boolean isRunning(Key job_key) {
    Job j = UKV.get(job_key);
    return j!=null && j.state == JobState.RUNNING;
  }

  /**
   *
   */
  public void remove() {
    end_time = System.currentTimeMillis();
    state = state == JobState.RUNNING ? JobState.DONE : state;
    // Overwrite handle - copy end_time, state, msg
    replaceByJobHandle();
  }

  /** Finds a job with given key or returns null
   * @param key
   * @return
   */
  public static final Job findJob(final Key key) {
    Job job = null;
    for( Job current : Job.all() ) {
      if( current.self().equals(key) ) {
        job = current;
        break;
      }
    }
    return job;
  }

  /** Finds a job with given dest key or returns null */
  public static final Job findJobByDest(final Key destKey) {
    Job job = null;
    for( Job current : Job.all() ) {
      if( current.dest().equals(destKey) ) {
        job = current;
        break;
      }
    }
    return job;
  }

  /** Returns job execution time in milliseconds */
  public final long runTimeMs() {
    long until = end_time != 0 ? end_time : System.currentTimeMillis();
    return until - start_time;
  }

  /** Description of a speed criteria: msecs/frob */
  public String speedDescription() { return null; }

  /** Value of the described speed criteria: msecs/frob */
  public long speedValue() { return 0; }

  // If job is a request

  @Override protected Response serve() {
    fork();
    return redirect();
  }

  protected Response redirect() {
    return Progress2.redirect(this, job_key, destination_key);
  }

  //

  public Job fork() {
    init();
    H2OCountedCompleter task = new H2OCountedCompleter() {
      @Override public void compute2() {
        Throwable t = null;
        try {
          JobState status = Job.this.exec();
          if(status == JobState.DONE)
            Job.this.remove();
        } catch (Throwable t_) {
          t = t_;
          if(!(t instanceof ExpectedExceptionForDebug))
            Log.err(t);
        } finally {
          tryComplete();
        }
        if(t != null)
          Job.this.cancel(t);
      }
    };
    start(task);
    H2O.submitTask(task);
    return this;
  }

  public void invoke() {
    init();
    start(new H2OEmptyCompleter());
    JobState status = exec();
    if(status == JobState.DONE)
      remove();
  }

  /**
   * Invoked before job runs. This is the place to checks arguments are valid or throw
   * IllegalArgumentException. It will get invoked both from the Web and Java APIs.
   */
  protected void init() throws IllegalArgumentException {
    if (destination_key == null) destination_key = defaultDestKey();
  }

  /**
   * Actual job code.
   *
   * @return true if job is done, false if it will still be running after the method returns.
   */
  protected JobState exec() {
    throw new RuntimeException("Should be overridden if job is a request");
  }

  public static boolean isJobEnded(Key jobkey) {
    boolean done = false;

    Job[] jobs = Job.all();
    boolean found = false;
    for (int i = jobs.length - 1; i >= 0; i--) {
      if (jobs[i].job_key == null) {
        continue;
      }

      if (! jobs[i].job_key.equals(jobkey)) {
        continue;
      }

      // This is the job we are looking for.
      found = true;

      if (jobs[i].end_time > 0) {
        done = true;
      }

      if (jobs[i].isCancelled()) {
        done = true;
      }

      break;
    }

    if (! found) {
      done = true;
    }

    return done;
  }

  /**
   * Block synchronously waiting for a job to end, success or not.
   * @param jobkey Job to wait for.
   * @param pollingIntervalMillis Polling interval sleep time.
   */
  public static void waitUntilJobEnded(Key jobkey, int pollingIntervalMillis) {
    while (true) {
      if (isJobEnded(jobkey)) {
        return;
      }

      try { Thread.sleep (pollingIntervalMillis); } catch (Exception _) {}
    }
  }

  /**
   * Block synchronously waiting for a job to end, success or not.
   * @param jobkey Job to wait for.
   */
  public static void waitUntilJobEnded(Key jobkey) {
    int THREE_SECONDS_MILLIS = 3 * 1000;
    waitUntilJobEnded(jobkey, THREE_SECONDS_MILLIS);
  }

  public static class ChunkProgress extends Iced implements Progress {
    final long _nchunks;
    final long _count;
    private final Status _status;
    final String _error;
    protected DException _ex;
    public enum Status { Computing, Done, Cancelled, Error };

    public Status status() { return _status; }

    public boolean isDone() { return _status == Status.Done || _status == Status.Error; }
    public String error() { return _error; }

    public ChunkProgress(long chunksTotal) {
      _nchunks = chunksTotal;
      _count = 0;
      _status = Status.Computing;
      _error = null;
    }

    private ChunkProgress(long nchunks, long computed, Status s, String err) {
      _nchunks = nchunks;
      _count = computed;
      _status = s;
      _error = err;
    }

    public ChunkProgress update(int count) {
      if( _status == Status.Cancelled || _status == Status.Error )
        return this;
      long c = _count + count;
      return new ChunkProgress(_nchunks, c, Status.Computing, null);
    }

    public ChunkProgress done() {
      return new ChunkProgress(_nchunks, _nchunks, Status.Done, null);
    }

    public ChunkProgress cancel() {
      return new ChunkProgress(0, 0, Status.Cancelled, null);
    }

    public ChunkProgress error(String msg) {
      return new ChunkProgress(0, 0, Status.Error, msg);
    }

    @Override public float progress() {
      if( _status == Status.Done ) return 1.0f;
      return Math.min(0.99f, (float) ((double) _count / (double) _nchunks));
    }
  }

  public static class ChunkProgressJob extends Job {
    Key _progress;

    public ChunkProgressJob(long chunksTotal, Key destinationKey) {
      destination_key = destinationKey;
      _progress = Key.make(Key.make()._kb, (byte) 0, Key.DFJ_INTERNAL_USER, destinationKey.home_node());
      UKV.put(_progress, new ChunkProgress(chunksTotal));
    }

    public void updateProgress(final int c) { // c == number of processed chunks
      if( isRunning(self()) ) {
        new TAtomic<ChunkProgress>() {
          @Override public ChunkProgress atomic(ChunkProgress old) {
            if( old == null ) return null;
            return old.update(c);
          }
        }.fork(_progress);
      }
    }

    @Override public void remove() {
      super.remove();
      UKV.remove(_progress);
    }

    public final Key progressKey() { return _progress; }

    public void onException(Throwable ex) {
      UKV.remove(dest());
      Value v = DKV.get(progressKey());
      if( v != null ) {
        ChunkProgress p = v.get();
        p = p.error(ex.getMessage());
        DKV.put(progressKey(), p);
      }
      cancel(ex);
    }
  }

  public static boolean checkIdx(Frame source, int[] idx) {
    for (int i : idx) if (i<0 || i>source.vecs().length-1) return false;
    return true;
  }

  private JobHandle jobHandle() {
    return new JobHandle(this);
  }

  /* Update end_time, state, msg, preserve start_time */
  private void replaceByJobHandle() {
    assert state != JobState.RUNNING : "Running job cannot be replaced.";
    final Job self = this;
    new TAtomic<Job>() {
      @Override public Job atomic(Job old) {
        if( old == null ) return null;
        JobHandle jh = new JobHandle(self);
        jh.start_time = old.start_time;
        return jh;
      }
    }.fork(job_key);
  }

  /** Almost lightweight job handle containing the same content
   * as pure Job class.
   */
  private static class JobHandle extends Job {
    public JobHandle(final Job job) { super(job); }
  }
  public static class JobCancelledException extends RuntimeException {
    public JobCancelledException(){super("job was cancelled!");}
    public JobCancelledException(String msg){super("job was cancelled! with msg '" + msg + "'");}
  }

}
