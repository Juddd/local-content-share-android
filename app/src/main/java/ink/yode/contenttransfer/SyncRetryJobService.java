package ink.yode.contenttransfer;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Network-constrained lifecycle adapter for the durable synchronization engine. */
public class SyncRetryJobService extends JobService {
    private static final int JOB_ID = 6059;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(context, SyncRetryJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(15000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        scheduler.schedule(info);
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        io.execute(() -> {
            boolean retry = false;
            try (SyncDatabase database = new SyncDatabase(this)) {
                SyncTransferEngine engine = new SyncTransferEngine(
                        this, database, SyncTransferEngine.defaultConnections());
                for (String server : database.pendingServers()) retry |= !engine.drainServer(server);
            } finally {
                jobFinished(parameters, retry);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        return true;
    }
}
