package ink.yode.contenttransfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShareUploadService extends Service {
    public static final String ACTION_UPLOAD_PROGRESS = "ink.yode.contenttransfer.UPLOAD_PROGRESS";
    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_UPLOAD_ID = "uploadId";
    public static final String EXTRA_SAVED_NAME = "savedName";
    private static final String CHANNEL_ID = "share-uploads";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private NotificationManager notificationManager;
    private SyncDatabase syncDb;
    private SyncTransferEngine syncEngine;

    @Override public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        syncDb=new SyncDatabase(this);
        syncEngine=new SyncTransferEngine(this,syncDb,SyncTransferEngine.defaultConnections());
        notificationManager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "分享上传", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String baseUrl=ServerConfig.get(this);
        if(baseUrl.isEmpty()){Toast.makeText(this,"请先打开内容中转配置服务器地址",Toast.LENGTH_LONG).show();stopSelf(startId);return START_NOT_STICKY;}
        ArrayList<String> uris = intent == null ? null : intent.getStringArrayListExtra(EXTRA_URIS);
        ArrayList<String> names = intent == null ? null : intent.getStringArrayListExtra(EXTRA_NAMES);
        String sharedText = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        int count = uris == null ? 0 : uris.size();
        if (sharedText != null && !sharedText.trim().isEmpty()) {
            startForeground(2401, notification("正在发送文字到 Snippets"));
            io.execute(() -> {
                String itemId=UUID.randomUUID().toString(),operationId=null;
                try {
                    java.text.SimpleDateFormat titleFormat=new java.text.SimpleDateFormat("MM／dd HH-mm-ss",Locale.CHINA);titleFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));String title=titleFormat.format(new Date());
                    JSONObject item=new JSONObject().put("id",itemId).put("storageId","").put("type","text").put("filename",title).put("content",sharedText).put("createdAt",new Date().toInstant().toString()).put("modifiedAt",new Date().toInstant().toString()).put("size",0).put("favorite",false).put("revision",0);
                    syncDb.putLocal(baseUrl,item,SyncDatabase.PENDING);
                    JSONObject values=new JSONObject().put("clientId",itemId).put("content",sharedText).put("expiry","Never");JSONObject payload=new JSONObject().put("endpoint","/submit").put("values",values);
                    operationId=syncDb.enqueue(baseUrl,itemId,"create",payload,0);SyncRetryJobService.schedule(this);
                    SyncTransferEngine.DrainResult result=syncEngine.drainOperations(baseUrl,SyncTransferEngine.NO_SYNC_OBSERVER);
                    if(result.busy)throw new IOException("后台同步任务正在运行");
                    if(result.error!=null)throw result.error;
                    if(result.lastItem!=null)title=result.lastItem.optString("filename",title);
                    else if(result.lastResponse!=null)title=result.lastResponse.optString("title",title);
                    String savedTitle=title;
                    main.post(() -> Toast.makeText(this,
                            "已发送到 Snippets：" + savedTitle, Toast.LENGTH_LONG).show());
                } catch (Exception error) {
                    SyncRetryJobService.schedule(this);
                    String feedback=operationId==null?"文字发送失败："+error.getMessage():"已加入待同步，联网后自动发送";
                    main.post(() -> Toast.makeText(this,
                            feedback, Toast.LENGTH_LONG).show());
                } finally {
                    stopSelf(startId);
                }
            });
            return START_NOT_STICKY;
        }
        startForeground(2401, notification("正在上传 " + count + " 个文件到 Files"));
        if (count == 0 || names == null || names.size() != count) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        io.execute(() -> {
            for (int index = 0; index < count; index++) {
                String name = names.get(index);
                File file = null;
                SyncDatabase.PendingUpload pending=null;
                int position = index + 1;
                String[] taskId={UUID.randomUUID().toString()};
                try {
                    updateProgress("正在准备 " + position + "/" + count + " · " + name, 0, 0, true, name, taskId[0]);
                    file=syncEngine.stage(Uri.parse(uris.get(index)),name);
                    pending=syncDb.addUpload(baseUrl,file.getAbsolutePath(),name,"Never");
                    taskId[0]=pending.id;
                    updateProgress("正在准备 " + position + "/" + count + " · " + name, 0, 0, true, name, taskId[0]);
                    SyncRetryJobService.schedule(this);
                    SyncTransferEngine.UploadResult result=syncEngine.upload(pending,3,new SyncTransferEngine.UploadObserver(){
                        public void uploading(SyncDatabase.PendingUpload upload,long sent,long total){updateProgress("正在上传 " + position + "/" + count + " · " + name,sent,total,false,name,taskId[0]);}
                        public void waiting(SyncDatabase.PendingUpload upload){updateProgress("等待 NAS 保存 " + position + "/" + count + " · " + name,0,0,true,name,taskId[0]);}
                    });
                    if(result.busy)throw new IOException("后台上传任务正在运行");
                    String savedName=result.savedName;
                    broadcastProgress(savedName + " · 已上传到 Files", 100, 100, false, true, name, taskId[0], savedName);
                    main.post(() -> Toast.makeText(this,
                            savedName + " 已发送到 Files", Toast.LENGTH_LONG).show());
                } catch (Exception error) {
                    SyncRetryJobService.schedule(this);
                    String feedback=pending==null?name+" 准备失败："+error.getMessage():name+" 已加入待上传队列，联网后自动发送";
                    broadcastProgress(feedback, 0, 100, false, true, name, taskId[0], null);
                    main.post(() -> Toast.makeText(this,
                            feedback, Toast.LENGTH_LONG).show());
                }
            }
            stopSelf(startId);
        });
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("内容中转")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private void updateProgress(String text, long sent, long total, boolean indeterminate, String name, String uploadId) {
        int percent = total > 0 ? (int)Math.min(99, sent * 100 / total) : 0;
        String detail = indeterminate || total <= 0 ? text : text + " · " + percent + "%（" + formatSize(sent) + " / " + formatSize(total) + "）";
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("内容中转")
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, percent, indeterminate || total <= 0)
                .build();
        notificationManager.notify(2401, notification);
        broadcastProgress(text, sent, total, indeterminate, false, name, uploadId, null);
    }

    private void broadcastProgress(String text, long sent, long total, boolean indeterminate, boolean finished, String name, String uploadId, String savedName) {
        Intent progress = new Intent(ACTION_UPLOAD_PROGRESS).setPackage(getPackageName());
        progress.putExtra("message", text);
        progress.putExtra("sent", sent);
        progress.putExtra("total", total);
        progress.putExtra("indeterminate", indeterminate);
        progress.putExtra("finished", finished);
        if(name!=null)progress.putExtra(EXTRA_NAME,name);
        if(uploadId!=null)progress.putExtra(EXTRA_UPLOAD_ID,uploadId);
        if(savedName!=null)progress.putExtra(EXTRA_SAVED_NAME,savedName);
        sendBroadcast(progress);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return String.format(java.util.Locale.CHINA, value >= 10 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        io.shutdownNow();
        if(syncDb!=null)syncDb.close();
        super.onDestroy();
    }
}
