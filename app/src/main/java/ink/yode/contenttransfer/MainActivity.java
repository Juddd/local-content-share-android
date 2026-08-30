package ink.yode.contenttransfer;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.util.LruCache;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.net.*;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.*;
import android.webkit.MimeTypeMap;
import android.widget.*;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 4101;
    private final ExecutorService metadataIo = Executors.newSingleThreadExecutor();
    private final ExecutorService transferIo = Executors.newFixedThreadPool(2);
    private final ExecutorService thumbnailIo = Executors.newFixedThreadPool(2);
    private final ExecutorService realtimeIo = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbnailCache = new LruCache<String, Bitmap>(12 * 1024) { @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; } };
    private final ArrayList<Item> allItems = new ArrayList<>(), visibleItems = new ArrayList<>();
    private final Map<String,Integer> downloadProgress = new ConcurrentHashMap<>();
    private final Set<String> deletingItems = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> favoritePending = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicBoolean outboxDrainScheduled = new AtomicBoolean();
    private final AtomicBoolean outboxStatusPending = new AtomicBoolean();
    private final Runnable outboxRetry = this::drainOutbox;
    private SharedPreferences prefs;
    private LinearLayout root, toolbar, uploadPanel, uploadTasks;
    private TextView status, sortButton, addButton;
    private ListView list;
    private ItemAdapter adapter;
    private String section = "text", activeBase = "";
    private Network activeNetwork;
    private EditText notepad;
    private ScrollView notepadPreviewScroll;
    private TextView notepadPreview, notepadRead, notepadSave;
    private LinearLayout notepadActions;
    private boolean notepadReading, notepadDirty, updatingNotepad;
    private String notepadSavedText = "";
    private String pendingExpiry = "Never";
    private final Map<String, TextView> tabViews = new LinkedHashMap<>();
    private SwipeRefreshLayout swipeRefresh;
    private AlertDialog uploadProgressDialog;
    private TextView uploadProgressMessage;
    private ProgressBar uploadProgressBar;
    private int activeUploadCount;
    private final Map<String, UploadTaskUi> uploadTaskUis = new ConcurrentHashMap<>();
    private final Set<String> uploadExecutions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private boolean uploadDialogSuppressed;
    private boolean shareProgressReceiverRegistered;
    private boolean networkCallbackRegistered;
    private final ConnectivityManager.NetworkCallback networkCallback=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network network){mainHandler.post(()->{if(isVpnNetwork(network))activeNetwork=null;drainOutbox();resumePendingUploads();});}};
    private final BroadcastReceiver shareProgressReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
        String message=intent.getStringExtra("message");if(message==null)return;
        String name=intent.getStringExtra("name"),uploadId=intent.getStringExtra("uploadId"),savedName=intent.getStringExtra("savedName");
        boolean finished=intent.getBooleanExtra("finished",false),indeterminate=intent.getBooleanExtra("indeterminate",false);
        long sent=intent.getLongExtra("sent",0),total=intent.getLongExtra("total",0);
        if(!finished&&uploadProgressDialog==null)uploadDialogSuppressed=false;
        showUploadProgress(message,sent,indeterminate?-1:total);
        if(name!=null&&!name.isEmpty()){
            UploadTaskUi task=findUploadTask(name,uploadId);
            if(task==null)task=new UploadTaskUi(name,uploadId);
            else task.bindUploadId(uploadId);
            if(finished){
                if(message.contains("已上传到 Files"))task.complete(savedName==null||savedName.isEmpty()?name:savedName);
                else task.failed(name,message);
            }else if(indeterminate){
                if(message.contains("等待 NAS 保存"))task.waiting(name);else task.preparing();
            }else task.uploading(name,sent,total);
        }
        if(finished)mainHandler.postDelayed(()->{if(uploadProgressDialog!=null){uploadProgressDialog.dismiss();uploadProgressDialog=null;}},2500);
    }};
    private volatile boolean realtimeEnabled;
    private volatile int realtimeGeneration;
    private volatile HttpURLConnection realtimeConnection;
    private volatile long lastEventSequence = -1;
    private volatile int serverGeneration;
    private SyncDatabase syncDb;
    private SyncTransferEngine syncEngine;
    private final Runnable realtimeRefresh = this::refresh;
    private Dialog deviceCenterDialog;
    private LinearLayout deviceCenterList;
    private TextView deviceCenterStatus;
    private TextView deviceStrip;
    private JSONArray cachedDevices = new JSONArray();
    private boolean deviceSummaryLoading, deviceSummaryActive;
    private final Runnable deviceSummaryPoll = this::loadDeviceSummary;

    private class TransferUi {
        final ProgressBar progress;
        final TextView message;
        final AlertDialog dialog;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile String serverTaskId;
        TransferUi(String title){
            LinearLayout box=new LinearLayout(MainActivity.this);box.setPadding(dp(24),dp(8),dp(24),0);box.setOrientation(LinearLayout.VERTICAL);
            message=text("准备中…",14,Color.DKGRAY);box.addView(message);
            progress=new ProgressBar(MainActivity.this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setIndeterminate(true);box.addView(progress,new LinearLayout.LayoutParams(-1,dp(18)));
            dialog=new AlertDialog.Builder(MainActivity.this).setTitle(title).setView(box).setNegativeButton("取消",null).setCancelable(false).create();
            dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->cancel()));dialog.show();
        }
        void update(String label,long received,long total){runOnUiThread(()->{if(cancelled)return;if(total>0){progress.setIndeterminate(false);progress.setProgress((int)Math.min(100,received*100/total));message.setText(label+" · "+(received*100/total)+"%（"+formatSize(received)+" / "+formatSize(total)+"）");}else{progress.setIndeterminate(true);message.setText(label+" · "+formatSize(received));}});}
        void cancel(){cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();String task=serverTaskId;if(task!=null)metadataIo.execute(()->{try{HttpURLConnection x=MainActivity.this.connection(activeNetwork,activeBase+"/api/v1/download-tasks/"+task,7000);x.setRequestMethod("DELETE");read(x);}catch(Exception ignored){}});dialog.dismiss();setStatus("下载已取消");}
        void success(String text){runOnUiThread(()->{dialog.dismiss();toast(text);});}
        void fail(Exception e){runOnUiThread(()->{dialog.dismiss();setStatus((cancelled?"下载已取消":"下载失败 · "+e.getMessage()));});}
    }

    private class UploadTaskUi {
        final LinearLayout row;
        final TextView label;
        final ProgressBar progress;
        final String taskName;
        final AtomicBoolean finished = new AtomicBoolean();
        String uploadId;
        UploadTaskUi(String name) { this(name,null); }
        UploadTaskUi(String name,String id) {
            taskName=name;uploadId=id;
            if(activeUploadCount==0)uploadDialogSuppressed=false;
            activeUploadCount++;
            registerUploadTask(this);
            showUploadProgress(name+" · 等待处理",0,-1);
            row=new LinearLayout(MainActivity.this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(12),dp(7),dp(12),dp(7));row.setBackground(rounded(Color.rgb(245,241,247),12));
            label=text(name+" · 等待处理",13,Color.rgb(73,62,80));label.setPadding(0,0,0,dp(4));row.addView(label);
            progress=new ProgressBar(MainActivity.this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setIndeterminate(true);row.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,0,0,dp(6));uploadTasks.addView(row,params);showUploadPanel();
        }
        void preparing(){runOnUiThread(()->{String value=labelName()+" · 正在准备";label.setText(value);progress.setIndeterminate(true);showUploadProgress(value,0,-1);});}
        void uploading(String name,long sent,long total){runOnUiThread(()->{long percent=total>0?Math.min(99,sent*100/total):0;progress.setIndeterminate(total<=0);if(total>0)progress.setProgress((int)percent);String value=name+" · "+percent+"%（"+formatSize(sent)+" / "+formatSize(total)+"）";label.setText(value);showUploadProgress(value,sent,total);});}
        void waiting(String name){runOnUiThread(()->{String value=name+" · 等待 NAS 保存";progress.setIndeterminate(true);label.setText(value);showUploadProgress(value,0,-1);});}
        void complete(String name){finish(name+" · 已上传到 Files",Color.rgb(34,139,70),100);}
        void failed(String name,String error){finish(name+" · 失败："+error,Color.rgb(180,40,40),0);}
        void bindUploadId(String id){if(id==null||id.isEmpty()||id.equals(uploadId))return;uploadId=id;uploadTaskUis.put(id,this);}
        private String labelName(){String value=label.getText().toString();int split=value.indexOf(" · ");return split<0?value:value.substring(0,split);}
        private void finish(String textValue,int color,int value){if(!finished.compareAndSet(false,true))return;runOnUiThread(()->{progress.setIndeterminate(false);progress.setProgress(value);label.setText(textValue);label.setTextColor(color);showUploadProgress(textValue,value,100);activeUploadCount=Math.max(0,activeUploadCount-1);removeUploadTask(this);if(activeUploadCount==0)mainHandler.postDelayed(()->{if(activeUploadCount==0&&uploadProgressDialog!=null){uploadProgressDialog.dismiss();uploadProgressDialog=null;}},2500);mainHandler.postDelayed(()->{uploadTasks.removeView(row);if(uploadTasks.getChildCount()==0)uploadPanel.setVisibility(View.GONE);},10000);});}
    }

    private void registerUploadTask(UploadTaskUi task){if(task.uploadId!=null&&!task.uploadId.isEmpty())uploadTaskUis.put(task.uploadId,task);uploadTaskUis.putIfAbsent(task.taskName,task);}
    private UploadTaskUi findUploadTask(String name,String uploadId){UploadTaskUi task=null;if(uploadId!=null&&!uploadId.isEmpty())task=uploadTaskUis.get(uploadId);if(task==null&&name!=null)task=uploadTaskUis.get(name);return task;}
    private void removeUploadTask(UploadTaskUi task){if(task.uploadId!=null&&!task.uploadId.isEmpty())uploadTaskUis.remove(task.uploadId,task);uploadTaskUis.remove(task.taskName,task);}

    static class Item {
        String id, storageId, type, filename, content, createdAt, modifiedAt, syncState="synced";
        long size, revision; boolean favorite; JSONObject conflict;
        static Item from(JSONObject o) {
            Item i = new Item();
            i.id=o.optString("id"); i.storageId=o.optString("storageId"); i.type=o.optString("type"); i.filename=o.optString("filename");
            i.content=o.optString("content"); i.createdAt=o.optString("createdAt"); i.modifiedAt=o.optString("modifiedAt"); i.size=o.optLong("size"); i.favorite=o.optBoolean("favorite");i.revision=o.optLong("revision");i.syncState=o.optString("syncState","synced");i.conflict=o.optJSONObject("conflict");
            return i;
        }
        JSONObject json()throws JSONException {JSONObject o=new JSONObject();o.put("id",id).put("storageId",storageId).put("type",type).put("filename",filename).put("content",content).put("createdAt",createdAt).put("modifiedAt",modifiedAt).put("size",size).put("favorite",favorite).put("revision",revision).put("syncState",syncState);return o;}
    }

    private class FavoriteDrawable extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Item item;
        FavoriteDrawable(Item item){this.item=item;paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(dp(18));}
        @Override public void draw(Canvas canvas){String symbol=item.favorite?"★":"☆";paint.setColor(item.favorite?Color.rgb(245,183,0):Color.rgb(120,115,122));paint.setAlpha(favoritePending.contains(item.id)?115:255);Paint.FontMetrics fm=paint.getFontMetrics();float x=getBounds().right-dp(18),y=getBounds().bottom-dp(2)-fm.descent;canvas.drawText(symbol,x,y,paint);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    private class TabUnderlineDrawable extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final boolean selected;
        TabUnderlineDrawable(boolean selected){this.selected=selected;}
        @Override public void draw(Canvas canvas){
            int bottom=getBounds().bottom;
            paint.setColor(Color.rgb(224,218,227));canvas.drawRect(getBounds().left,bottom-dp(1),getBounds().right,bottom,paint);
            if(selected){paint.setColor(Color.rgb(103,80,164));canvas.drawRect(getBounds().left,bottom-dp(3),getBounds().right,bottom,paint);}
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    private class SectionSwipeLayout extends SwipeRefreshLayout {
        private float downX,downY;private long downAt;private boolean tracking;
        SectionSwipeLayout(Context context){super(context);}
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            int action=event.getActionMasked();int direction=0;
            if(action==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();downAt=SystemClock.uptimeMillis();tracking=true;}
            else if(action==MotionEvent.ACTION_UP&&tracking){float dx=event.getX()-downX,dy=event.getY()-downY;long elapsed=SystemClock.uptimeMillis()-downAt;if(Math.abs(dx)>=dp(64)&&Math.abs(dx)>Math.abs(dy)*1.25f&&elapsed<=750)direction=dx<0?1:-1;tracking=false;}
            else if(action==MotionEvent.ACTION_CANCEL)tracking=false;
            boolean handled=super.dispatchTouchEvent(event);int move=direction;if(move!=0)post(()->moveSection(move));return handled;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},4201);
        prefs = getSharedPreferences("content-transfer", MODE_PRIVATE);
        syncDb=new SyncDatabase(this);
        syncEngine=new SyncTransferEngine(this,syncDb,(url,timeout)->connection(findNetwork(false),url,timeout));
        activeBase=ServerConfig.get(this);
        buildUi();
        loadCache();
        if(activeBase.isEmpty())mainHandler.post(()->showServerConfig(true));
    }

    @Override protected void onResume(){super.onResume();deviceSummaryActive=true;if(adapter!=null)renderSection();if(!activeBase.isEmpty()){refresh();startRealtimeUpdates();drainOutbox();resumePendingUploads();SyncRetryJobService.schedule(this);loadDeviceSummary();}}

    @Override protected void onStart(){super.onStart();if(!shareProgressReceiverRegistered){IntentFilter filter=new IntentFilter(ShareUploadService.ACTION_UPLOAD_PROGRESS);if(Build.VERSION.SDK_INT>=33)registerReceiver(shareProgressReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(shareProgressReceiver,filter);shareProgressReceiverRegistered=true;}if(!networkCallbackRegistered){try{getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);networkCallbackRegistered=true;}catch(Exception ignored){}}}

    @Override protected void onStop(){if(shareProgressReceiverRegistered){unregisterReceiver(shareProgressReceiver);shareProgressReceiverRegistered=false;}if(networkCallbackRegistered){try{getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}networkCallbackRegistered=false;}super.onStop();}

    @Override protected void onPause(){deviceSummaryActive=false;mainHandler.removeCallbacks(deviceSummaryPoll);stopRealtimeUpdates();super.onPause();}

    private void takePersistable(Uri uri,Intent intent){try{int flags=intent.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getContentResolver().takePersistableUriPermission(uri,flags&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}

    private TextView text(String value, float sp, int color) {
        if (value.startsWith("1.0.24\n")) value = "1.0.36\n• App 内为每个文件独立显示上传进度和状态\n• 新增“删除手机本地副本”，不影响 NAS 文件\n• 无本地副本时删除本地按钮自动置灰\n• 原删除操作明确标注为删除 NAS 内容\n\n1.0.35\n• 移除不稳定的剪贴板监听、快捷圆钮和诊断功能\n• 分享文字时后台发送到 Snippets\n• 分享文件时后台上传到 Files，完成提示显示文件名\n• 大文件使用分块上传，NAS 确认前不再提前显示 100%\n\n" + value;
        if (value.startsWith("1.0.36\n")) value = "1.0.38\n• 分享文字上传成功后显示明确回执\n• 回执显示 NAS 实际保存的 Snippet 标题\n\n1.0.37\n• 修复 App 内上传任务进度不明显、容易看不到的问题\n• 上传任务改为固定醒目的面板，选中文件后立即显示\n• 新增文件加入上传队列的即时提示\n\n" + value;
        if (value.startsWith("1.0.38\n")) value = "1.0.40\n• 系统分享文件时在通知中显示实时上传进度\n• 通知显示文件名、任务数量、已传大小和 NAS 保存状态\n• App 内上传与系统分享上传使用一致的进度阶段\n\n1.0.39\n• App 内选择文件后立即弹出上传进度窗口\n• 显示当前文件百分比及等待 NAS 保存状态\n• 可选择后台运行，页面任务面板仍会保留\n\n" + value;
        if (value.startsWith("1.0.40\n") && !value.contains("App 已打开时同步在界面显示分享上传进度")) value=value.replace("• 系统分享文件时在通知中显示实时上传进度\n","• 系统分享文件时在通知中显示实时上传进度\n• App 已打开时同步在界面显示分享上传进度\n");
        if (value.startsWith("1.0.40\n")) value="1.0.44\n• NAS 删除成功后只移除对应卡片，不再全量刷新列表\n• 手机下载进度改为卡片底部绿色进度条\n• 下载完成后进度条自动变为本地副本状态线\n\n1.0.43\n• 删除手机本地副本和 NAS 文件时不再弹出确认窗口\n• 点击两个删除按钮后立即执行对应删除操作\n\n1.0.42\n• 缩小 Files 卡片操作按钮和图标\n• 按钮之间增加留白，文件大小获得更多显示空间\n• 保留六个直达操作及带相同图标的长按菜单\n\n1.0.41\n• Files 卡片新增六个常用操作图标按钮\n• 文件大小与操作按钮同一行均匀排列\n• 长按菜单保留，并显示与按钮一致的图标\n• 无本地副本时打开和删除本地按钮自动置灰\n\n"+value;
        if (value.startsWith("1.0.44\n")) value="1.0.45\n• 记事本新增 Markdown 阅读模式和阅读/保存状态按钮\n• 修复上传重试重复文件、实时同步竞态和分享缓存残留风险\n• 上传、缩略图、同步任务分离执行，避免互相阻塞\n• 扩大常用操作触控区域并增强服务端稳定性\n\n"+value;
        if (value.startsWith("1.0.45\n")) value="1.0.46\n• 修复 Markdown 阅读模式仍显示语法标记的问题\n• 标题、粗斜体、链接、列表和代码块改为真正解析显示\n• 缩窄 Files 操作按钮，完整显示文件大小单位\n\n"+value;
        if (value.startsWith("1.0.46\n")) value="1.0.47\n• 下载、打开和重命名改为与网页一致的矢量图标\n• 长按文件菜单同步使用同一套图标\n\n"+value;
        if (value.startsWith("1.0.47\n")) value="1.0.48\n• 三个矢量操作改为网页 Font Awesome 同款图形\n• 修复图标在按钮背景中偏左的问题\n• 可用操作统一为蓝绿色，不可用操作显示为灰色\n\n"+value;
        if (value.startsWith("1.0.48\n")) value="1.0.49\n• 重命名改为与网页视觉一致的简洁 I 形光标\n• 六个文件操作按钮整体再缩小少许\n• 直达按钮和长按菜单统一使用蓝绿色矢量图标\n• 不可用操作统一显示为灰色\n\n"+value;
        if (value.startsWith("1.0.49\n")) value="1.0.50\n• 恢复复制地址、删除本地副本和删除 NAS 的原始图标\n• 三个原始图标只随按钮整体缩小，不改变样式\n\n"+value;
        if (value.startsWith("1.0.50\n")) value="1.0.51\n• 文字和链接长按菜单补充查看、复制、重命名和删除对应图标\n• 文字和链接区仍保持只有长按菜单，不增加直达按钮\n\n"+value;
        if (value.startsWith("1.0.51\n")) value="1.0.52\n• 链接打开改为网页端跳转图标\n• 文字编辑改为网页端编辑图标\n\n"+value;
        if (value.startsWith("1.0.52\n")) value="1.0.53\n• 文字复制改为网页 fa-copy 同款图标\n• 收藏的 Snippet 始终排在普通项目之前\n\n"+value;
        if (value.startsWith("1.0.53\n")) value="1.0.54\n• Snippet 新增可同步的星标收藏按钮和收藏优先排序\n• 首次启动必须配置服务器地址，设置中可随时更改\n• 服务器地址永久保存，系统分享与主界面统一使用该配置\n• 移除源码中写死的个人 NAS 地址\n\n"+value;
        if (value.startsWith("1.0.54\n")) value="1.0.55\n• 收藏星星改为直接覆盖绘制，不再改变文字卡片布局\n• 恢复原有卡片字号、间距、测量和分隔线表现\n• 保留右下角收藏触控区与无障碍操作\n\n"+value;
        if (value.startsWith("1.0.55\n")) value="1.0.59\n• 新增真正的 SQLite 本地优先数据层和持久待同步队列\n• 新建、编辑、收藏、重命名和删除可离线完成并自动重试\n• 文件待上传队列在 App 重启后仍会保留并恢复上传\n• 卡片显示待同步、同步中、已同步和冲突状态\n• 冲突可选择保留本地、使用服务器或将本地另存副本\n• 内容改用稳定 UUID 和 revision，重命名不再改变身份\n\n1.0.58\n• 文件区和链接区采用与文字区一致的紧凑横向留白\n• 收藏星星下移至卡片底部，底边保留约 2px 间距\n\n1.0.57\n• 进一步缩窄文字卡片横向留白，左侧减少约三分之二，右侧减少约三分之一\n\n1.0.56\n• 缩窄文字卡片两侧横向留白，不改变字号和其它视觉\n\n"+value;
        if (value.startsWith("1.0.59\n")) value="1.0.60\n• 修复升级稳定 UUID 后已下载文件的本地状态可能丢失\n• 切换服务器时立即显示该服务器的离线数据库，不再先清空页面\n• 系统分享的文字和文件也进入同一持久待同步队列\n• App 未打开时由联网约束后台任务自动补交\n• 加强离线删除和链接身份迁移的兼容性\n\n"+value;
        if (value.startsWith("1.0.60\n")) value="1.0.61\n• 修复 NAS 操作成功后底部仍停留在待同步的问题\n• 修复前台同步与后台任务竞争时可能丢失自动重试的问题\n• 成功与冲突状态现在会及时、准确地回写到界面\n\n"+value;
        if (value.startsWith("1.0.61\n")) value="1.0.62\n• 恢复同步成功提示中的局域网或外网标识\n• 保留操作完成后自动从待同步切换为已同步的修复\n\n"+value;
        if (value.startsWith("1.0.62\n")) value="1.0.63\n• 新增设备中心，可查看在线、后台和离线的网页客户端\n• 支持给浏览器设备重命名、远程关闭并锁定或解除锁定\n• 锁定覆盖同一浏览器配置的所有标签页，刷新后仍显示 404\n• 设备身份不使用浏览器指纹，名称和锁定状态由 NAS 永久保存\n\n"+value;
        if (value.startsWith("1.0.63\n")) value="1.0.64\n• 有浏览器设备时，各内容区顶部显示设备状态窄条\n• 设备列表用图标直接重命名、关闭锁定或解除锁定\n• IP 地址标明相对于 NAS 的局域网或外网类型\n• 超过 30 天未活动且已离线的浏览器设备自动清理\n\n"+value;
        if (value.startsWith("1.0.64\n")) value="1.0.65\n• 设备列表改为从底部滑入的全窗口页面\n• 设备窄条只显示在文字、文件和链接区\n• 系统分享上传与主界面任务卡统一实时进度\n• 修复文件已上传但任务仍停留在等待处理的问题\n• 后台重试不再与正在执行的分享上传争抢任务\n\n"+value;
        if (value.startsWith("1.0.65\n")) value="1.0.66\n• 离线同步、后台重试和文件上传统一使用同一引擎\n• 前台、系统分享与后台任务共用进度和完成状态\n• 修复并发接手上传时可能停留在等待处理的问题\n• 服务端内容身份、收藏、revision 和时间元数据集中管理\n• 文件上传与 URL 下载支持事务恢复和跨重启幂等\n• 设备中心仅显示可靠地址，并清理旧诊断与无用权限\n\n"+value;
        if (value.startsWith("1.0.66\n")) value="1.0.67\n• 修复开启 VPN 时主界面同步可能错误绑定底层 Wi-Fi 的问题\n• VPN 生效时同步、实时更新和传输统一遵循系统默认网络\n• Snippet 查看正文支持长按选中和局部复制，仍保持只读\n\n"+value;
        if (value.startsWith("1.0.67\n")) value="1.0.68\n• 四个内容区改为扁平等宽标签，选中项使用紫色底部指示线\n• 支持左右滑动依次切换四个内容区，到达两端后停止，不循环\n• 新增改为标题栏中的紫色圆形加号，排序改为相邻小图标\n• 新增、排序、设置和刷新统一放在同一行且尺寸一致\n• 记事本区自动灰显不适用的新增与排序操作\n\n"+value;
        TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color); v.setPadding(dp(12),dp(10),dp(12),dp(10)); return v;
    }
    private GradientDrawable rounded(int color,int radius) { GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d; }
    private Button button(String label) { Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(16),dp(10),dp(16),dp(10));b.setBackground(rounded(Color.rgb(235,229,239),18));return b; }
    private TextView iconButton(String symbol,String description) { TextView v=text(symbol,23,Color.rgb(73,62,80));v.setGravity(Gravity.CENTER);v.setContentDescription(description);v.setBackground(rounded(Color.rgb(235,229,239),22));v.setPadding(0,0,0,0);return v; }
    private TextView fileActionButton(String symbol,String description,boolean enabled,View.OnClickListener action) { TextView v=text(symbol,16,enabled?Color.rgb(75,151,174):Color.LTGRAY);v.setGravity(Gravity.CENTER);v.setContentDescription(description);v.setEnabled(enabled);v.setAlpha(enabled?1f:.38f);v.setBackground(rounded(Color.rgb(244,240,246),13));v.setPadding(0,0,0,0);if(enabled)v.setOnClickListener(action);v.setOnLongClickListener(x->{toast(description);return true;});return v; }
    private ImageView fileActionButton(int drawableId,String description,boolean enabled,View.OnClickListener action) { ImageView v=new ImageView(this);v.setImageResource(drawableId);v.setImageTintList(android.content.res.ColorStateList.valueOf(enabled?Color.rgb(75,151,174):Color.LTGRAY));v.setScaleType(ImageView.ScaleType.CENTER);v.setContentDescription(description);v.setEnabled(enabled);v.setBackground(rounded(Color.rgb(244,240,246),12));v.setPadding(dp(8),dp(8),dp(8),dp(8));if(enabled)v.setOnClickListener(action);v.setOnLongClickListener(x->{toast(description);return true;});return v; }
    private TextView actionChip(String label,boolean primary) { TextView v=text(label,14,primary?Color.WHITE:Color.rgb(73,62,80));v.setGravity(Gravity.CENTER);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setBackground(rounded(primary?Color.rgb(103,80,164):Color.rgb(235,229,239),20));v.setPadding(dp(18),dp(10),dp(18),dp(10));return v; }
    private int dp(int n) { return (int)(n*getResources().getDisplayMetrics().density+.5f); }

    private void showUploadPanel(){uploadPanel.setVisibility(View.VISIBLE);uploadPanel.requestLayout();}

    private void showUploadProgress(String message,long sent,long total){
        if(uploadDialogSuppressed)return;
        if(uploadProgressDialog==null){
            LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(8),dp(24),0);
            uploadProgressMessage=text(message,14,Color.DKGRAY);uploadProgressMessage.setPadding(0,0,0,dp(8));box.addView(uploadProgressMessage);
            uploadProgressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);uploadProgressBar.setMax(100);box.addView(uploadProgressBar,new LinearLayout.LayoutParams(-1,dp(18)));
            uploadProgressDialog=new AlertDialog.Builder(this).setTitle("正在上传到 Files").setView(box).setNegativeButton("后台运行",(d,w)->uploadDialogSuppressed=true).create();
            uploadProgressDialog.setOnDismissListener(d->{uploadProgressDialog=null;uploadProgressMessage=null;uploadProgressBar=null;});uploadProgressDialog.show();
        }
        if(uploadProgressMessage!=null)uploadProgressMessage.setText(message);
        if(uploadProgressBar!=null){boolean known=total>0;uploadProgressBar.setIndeterminate(!known);if(known)uploadProgressBar.setProgress((int)Math.min(100,sent*100/total));}
    }

    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(12),dp(10),dp(12),0); root.setBackgroundColor(Color.rgb(250,247,252));
        root.setOnApplyWindowInsetsListener((view,insets)->{int top=Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.statusBars()).top:insets.getSystemWindowInsetTop();view.setPadding(dp(12),top+dp(10),dp(12),0);return insets;});
        LinearLayout title = new LinearLayout(this); title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(dp(4),0,dp(4),dp(4));
        TextView heading=text("内容中转",22,Color.rgb(45,39,49)); heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);heading.setPadding(dp(4),dp(6),dp(4),dp(6)); title.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        addButton=text("＋",24,Color.WHITE);addButton.setGravity(Gravity.CENTER);addButton.setTypeface(Typeface.DEFAULT,Typeface.BOLD);addButton.setContentDescription("新增");addButton.setPadding(0,0,0,dp(1));addButton.setBackground(rounded(Color.rgb(103,80,164),21));addButton.setOnClickListener(v->addCurrent());LinearLayout.LayoutParams addParams=new LinearLayout.LayoutParams(dp(42),dp(42));addParams.setMarginStart(dp(6));title.addView(addButton,addParams);
        sortButton=iconButton("⇅","排序");sortButton.setTextSize(19);sortButton.setOnClickListener(v->showSort());LinearLayout.LayoutParams sortParams=new LinearLayout.LayoutParams(dp(42),dp(42));sortParams.setMarginStart(dp(6));title.addView(sortButton,sortParams);
        TextView settings=iconButton("⚙","设置"); settings.setOnClickListener(v->showSettings());LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(dp(42),dp(42));iconParams.setMarginStart(dp(6));title.addView(settings,iconParams);
        TextView refresh=iconButton("↻","刷新"); refresh.setOnClickListener(v->refresh());LinearLayout.LayoutParams refreshParams=new LinearLayout.LayoutParams(dp(42),dp(42));refreshParams.setMarginStart(dp(6));title.addView(refresh,refreshParams); root.addView(title);
        status=text("正在连接…",12,Color.rgb(96,87,101)); status.setPadding(dp(8),0,dp(8),dp(10)); root.addView(status);
        toolbar=new LinearLayout(this); toolbar.setGravity(Gravity.CENTER); toolbar.setWeightSum(4);toolbar.setPadding(0,0,0,dp(8));
        addTab("文字","text"); addTab("文件","file"); addTab("链接","link"); addTab("记事本","notepad"); root.addView(toolbar);
        uploadPanel=new LinearLayout(this);uploadPanel.setOrientation(LinearLayout.VERTICAL);uploadPanel.setPadding(dp(10),dp(8),dp(10),dp(4));uploadPanel.setBackground(rounded(Color.rgb(229,218,240),16));uploadPanel.setVisibility(View.GONE);
        TextView uploadHeading=text("上传任务",15,Color.rgb(62,43,78));uploadHeading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);uploadHeading.setPadding(dp(2),0,dp(2),dp(6));uploadPanel.addView(uploadHeading);
        uploadTasks=new LinearLayout(this);uploadTasks.setOrientation(LinearLayout.VERTICAL);uploadPanel.addView(uploadTasks,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams uploadPanelParams=new LinearLayout.LayoutParams(-1,-2);uploadPanelParams.setMargins(0,0,0,dp(8));root.addView(uploadPanel,uploadPanelParams);
        deviceStrip=text("",13,Color.rgb(73,62,80));deviceStrip.setGravity(Gravity.CENTER_VERTICAL);deviceStrip.setPadding(dp(12),0,dp(12),0);deviceStrip.setBackground(rounded(Color.rgb(239,234,242),8));deviceStrip.setVisibility(View.GONE);deviceStrip.setOnClickListener(v->showDeviceCenter());
        LinearLayout.LayoutParams deviceStripParams=new LinearLayout.LayoutParams(-1,dp(38));deviceStripParams.setMargins(0,0,0,dp(7));root.addView(deviceStrip,deviceStripParams);
        list=new ListView(this); adapter=new ItemAdapter(); list.setAdapter(adapter); root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        swipeRefresh = new SectionSwipeLayout(this);
        swipeRefresh.setColorSchemeColors(Color.rgb(103,80,164));
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.WHITE);
        swipeRefresh.setOnRefreshListener(this::refresh);
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> section.equals("notepad") ? notepad != null && notepad.canScrollVertically(-1) : list.canScrollVertically(-1));
        swipeRefresh.addView(root,new SwipeRefreshLayout.LayoutParams(-1,-1));
        setContentView(swipeRefresh);
    }

    private void addTab(String label,String key) {
        TextView b=text(label,15,Color.rgb(105,96,109));b.setGravity(Gravity.CENTER);b.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);b.setPadding(0,0,0,dp(2));b.setBackground(new TabUnderlineDrawable(false));b.setOnClickListener(v->{section=key;updateTabs();renderSection();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(45),1);toolbar.addView(b,p);tabViews.put(key,b);if(key.equals(section))updateTabs();
    }

    private void updateTabs(){
        for(Map.Entry<String,TextView> e:tabViews.entrySet()){
            boolean selected=e.getKey().equals(section);TextView tab=e.getValue();
            tab.setTextColor(selected?Color.rgb(103,80,164):Color.rgb(105,96,109));
            tab.setTypeface(Typeface.DEFAULT,selected?Typeface.BOLD:Typeface.NORMAL);
            tab.setBackground(new TabUnderlineDrawable(selected));
        }
        boolean contentActions=!section.equals("notepad");
        if(addButton!=null){addButton.setEnabled(contentActions);addButton.setAlpha(contentActions?1f:.32f);}
        if(sortButton!=null){sortButton.setEnabled(contentActions);sortButton.setAlpha(contentActions?1f:.32f);}
    }

    private void moveSection(int direction){
        String[] sections={"text","file","link","notepad"};int current=Arrays.asList(sections).indexOf(section),next=current+direction;
        if(current<0||next<0||next>=sections.length)return;
        section=sections[next];updateTabs();renderSection();
        mainHandler.post(()->{View content=section.equals("notepad")?(notepadReading&&notepadPreviewScroll!=null?notepadPreviewScroll:notepad):list;if(content==null)return;content.animate().cancel();content.setAlpha(.68f);content.setTranslationX(direction*dp(42));content.animate().alpha(1f).translationX(0).setDuration(180).start();});
    }

    private void setStatus(String s) { runOnUiThread(()->status.setText(s)); }

    private void loadCache() {
        try { parseItems(activeBase.isEmpty()?new JSONArray():syncDb.load(activeBase)); status.setText("已显示离线数据库，正在同步…"); } catch(Exception ignored) {}
    }

    private void parseItems(JSONArray array) throws JSONException {
        allItems.clear(); for(int n=0;n<array.length();n++){Item item=Item.from(array.getJSONObject(n));if(!item.storageId.isEmpty()&&prefs.getString("downloaded_"+item.id,"").isEmpty()){String legacy=prefs.getString("downloaded_"+item.storageId,"");if(!legacy.isEmpty())prefs.edit().putString("downloaded_"+item.id,legacy).remove("downloaded_"+item.storageId).apply();}allItems.add(item);} renderSection();
    }

    private Network findNetwork(boolean wifiOnly) {
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        Network active=cm.getActiveNetwork();
        NetworkCapabilities activeCapabilities=active==null?null:cm.getNetworkCapabilities(active);
        // A VPN is the system-selected route. Binding its underlying Wi-Fi directly can be
        // rejected with EPERM on some OEM builds and would unexpectedly bypass the VPN.
        if(activeCapabilities!=null&&activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return null;
        if(activeCapabilities!=null&&activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                &&(!wifiOnly||activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)))return active;
        Network fallback=null;
        for(Network n:cm.getAllNetworks()) {
            NetworkCapabilities c=cm.getNetworkCapabilities(n); if(c==null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            if(!wifiOnly && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) fallback=n;
        }
        return fallback;
    }

    private boolean isVpnNetwork(Network network){
        if(network==null)return false;
        NetworkCapabilities capabilities=getSystemService(ConnectivityManager.class).getNetworkCapabilities(network);
        return capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private HttpURLConnection connection(Network n,String url,int timeout) throws IOException {
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        Network current=cm.getActiveNetwork();
        if(isVpnNetwork(current))n=null;
        URL u=new URL(url); HttpURLConnection c=(HttpURLConnection)(n!=null?n.openConnection(u):u.openConnection());
        c.setConnectTimeout(timeout); c.setReadTimeout(Math.max(timeout,8000)); c.setUseCaches(false); c.setRequestProperty("Accept","application/json"); return c;
    }

    private String read(HttpURLConnection c) throws IOException {
        int code=c.getResponseCode(); InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); if(in!=null){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        String s=out.toString(StandardCharsets.UTF_8.name()); if(code<200||code>=300)throw new IOException("HTTP "+code+" "+s); return s;
    }

    private void refresh() {
        if(activeBase.isEmpty()){if(swipeRefresh!=null)swipeRefresh.setRefreshing(false);setStatus("请先配置服务器地址");return;}
        final String requestBase=activeBase;final int requestGeneration=serverGeneration;
        if (swipeRefresh != null && !swipeRefresh.isRefreshing()) swipeRefresh.setRefreshing(true);
        setStatus("正在同步…");
        metadataIo.execute(()->{
            try {
                Network net=findNetwork(false);
                HttpURLConnection request=connection(net,requestBase+"/api/v1/items",7000);
                String raw=read(request);
                long snapshotSequence;
                try{snapshotSequence=Long.parseLong(request.getHeaderField("X-Content-Sequence"));}catch(Exception ignored){snapshotSequence=-1;}
                activeNetwork=net;
                String route=syncRoute(net);
                if(requestGeneration!=serverGeneration||!requestBase.equals(activeBase))return;
                JSONArray data=new JSONArray(raw); syncDb.replaceRemote(requestBase,data);
                long finalSnapshotSequence=snapshotSequence;
                runOnUiThread(()->{if(finalSnapshotSequence>=0&&lastEventSequence>finalSnapshotSequence){swipeRefresh.setRefreshing(false);mainHandler.postDelayed(this::refresh,150);return;}try{parseItems(syncDb.load(requestBase));}catch(Exception ignored){}if(finalSnapshotSequence>=0)lastEventSequence=Math.max(lastEventSequence,finalSnapshotSequence);status.setText("已同步（"+route+"）");swipeRefresh.setRefreshing(false);drainOutbox();});
            } catch(Exception e) { if(requestGeneration==serverGeneration&&requestBase.equals(activeBase)){setStatus("离线显示缓存 · "+e.getMessage());runOnUiThread(()->swipeRefresh.setRefreshing(false));} }
        });
    }

    private String syncRoute(Network net){try{String host=Uri.parse(activeBase).getHost();if(host==null)return "网络类型未知";InetAddress[] addresses=net!=null?net.getAllByName(host):InetAddress.getAllByName(host);for(InetAddress address:addresses){if(address.isSiteLocalAddress()||address.isLinkLocalAddress()||address.isLoopbackAddress())return "局域网";byte[] raw=address.getAddress();if(raw.length==16&&(raw[0]&0xfe)==0xfc)return "局域网";}return "外网";}catch(Exception ignored){return "网络类型未知";}}

    private synchronized void startRealtimeUpdates() {
        if(realtimeEnabled||activeBase.isEmpty())return;
        realtimeEnabled=true;
        final int generation=++realtimeGeneration;
        realtimeIo.execute(()->{
            while(realtimeEnabled&&realtimeGeneration==generation){
                HttpURLConnection c=null;
                try{
                    Network net=findNetwork(false);
                    c=connection(net,activeBase+"/api/updates"+(lastEventSequence>=0?"?since="+lastEventSequence:""),10000);
                    c.setReadTimeout(45000);
                    c.setRequestProperty("Accept","text/event-stream");
                    realtimeConnection=c;
                    if(c.getResponseCode()!=HttpURLConnection.HTTP_OK)throw new IOException("HTTP "+c.getResponseCode());
                    try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                        String line;
                        while(realtimeEnabled&&realtimeGeneration==generation&&(line=reader.readLine())!=null){
                            if(line.equals("data: content_updated")){
                                mainHandler.removeCallbacks(realtimeRefresh);
                                mainHandler.postDelayed(realtimeRefresh,250);
                            }else if(line.startsWith("data: {")&&line.endsWith("}")){
                                try{JSONObject change=new JSONObject(line.substring(6));long sequence=change.optLong("sequence",-1);String type=change.optString("type");if(type.equals("connected")){lastEventSequence=sequence;mainHandler.removeCallbacks(realtimeRefresh);mainHandler.postDelayed(realtimeRefresh,250);}else if(lastEventSequence<0||sequence!=lastEventSequence+1){lastEventSequence=sequence;mainHandler.removeCallbacks(realtimeRefresh);mainHandler.postDelayed(realtimeRefresh,250);}else{lastEventSequence=sequence;if(type.equals("deleted")){String id=change.optString("id");mainHandler.post(()->applyRemoteDelete(id));}else if(type.equals("created")||type.equals("updated")||type.equals("renamed")){JSONObject itemJson=change.optJSONObject("item");String oldId=change.optString("oldId",change.optString("id"));if(itemJson!=null){Item item=Item.from(itemJson);mainHandler.post(()->applyRemoteItem(oldId,item));}}else{mainHandler.removeCallbacks(realtimeRefresh);mainHandler.postDelayed(realtimeRefresh,250);}}}
                                catch(Exception ignored){}
                            }
                        }
                    }
                }catch(Exception ignored){}
                finally{if(c!=null)c.disconnect();if(realtimeConnection==c)realtimeConnection=null;}
                if(realtimeEnabled&&realtimeGeneration==generation){try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}
            }
        });
    }

    private synchronized void stopRealtimeUpdates(){
        realtimeEnabled=false;
        realtimeGeneration++;
        mainHandler.removeCallbacks(realtimeRefresh);
        HttpURLConnection c=realtimeConnection;
        realtimeConnection=null;
        if(c!=null)c.disconnect();
    }

    private void applyRemoteDelete(String id){if(id==null||id.isEmpty())return;syncDb.applyDelete(activeBase,id);deletingItems.remove(id);downloadProgress.remove(id);allItems.removeIf(item->item.id.equals(id));visibleItems.removeIf(item->item.id.equals(id));adapter.notifyDataSetChanged();}
    private void applyRemoteItem(String oldId,Item updated){if(updated==null)return;try{syncDb.applyRemote(activeBase,oldId,updated.json());}catch(Exception ignored){}if(syncDb.hasPending(activeBase,oldId)||syncDb.hasPending(activeBase,updated.id)){reloadLocal(activeBase);return;}allItems.removeIf(item->item.id.equals(oldId)||item.id.equals(updated.id));allItems.add(updated);renderSection();}

    private void renderSection() {
        updateDeviceStrip();
        if(section.equals("notepad")){ showNotepad(); return; }
        if(notepad!=null){root.removeView(notepad);notepad=null;if(notepadPreviewScroll!=null){root.removeView(notepadPreviewScroll);notepadPreviewScroll=null;notepadPreview=null;}if(notepadActions!=null){root.removeView(notepadActions);notepadActions=null;notepadRead=null;notepadSave=null;}if(list.getParent()==null)root.addView(list,new LinearLayout.LayoutParams(-1,0,1));}
        visibleItems.clear(); for(Item i:allItems) if(i.type.equals(section)) visibleItems.add(i);
        String key=prefs.getString("sort_"+section,section.equals("file")?"created_desc":"created_desc");
        Comparator<Item> cmp;
        if(key.startsWith("title")) cmp=Comparator.comparing(a->a.filename,java.text.Collator.getInstance(Locale.CHINA));
        else if(key.startsWith("modified")) cmp=Comparator.comparing(a->a.modifiedAt);
        else if(key.startsWith("size")) cmp=Comparator.comparingLong(a->a.size);
        else cmp=Comparator.comparing(a->a.createdAt);
        if(key.endsWith("desc")) cmp=cmp.reversed(); if(section.equals("text"))cmp=Comparator.<Item,Boolean>comparing(a->a.favorite).reversed().thenComparing(cmp); Collections.sort(visibleItems,cmp); adapter.notifyDataSetChanged();
    }

    private void showSort() {
        if(section.equals("notepad")) return;
        ArrayList<String> labels=new ArrayList<>(Arrays.asList("创建时间（新到旧）","创建时间（旧到新）","修改时间（新到旧）","修改时间（旧到新）","标题（正序）","标题（倒序）"));
        ArrayList<String> keys=new ArrayList<>(Arrays.asList("created_desc","created_asc","modified_desc","modified_asc","title_asc","title_desc"));
        if(section.equals("file")){labels.add("文件大小（大到小）");labels.add("文件大小（小到大）");keys.add("size_desc");keys.add("size_asc");}
        new AlertDialog.Builder(this).setTitle("排序方式").setItems(labels.toArray(new String[0]),(d,w)->{prefs.edit().putString("sort_"+section,keys.get(w)).apply();renderSection();}).show();
    }

    private class ItemAdapter extends BaseAdapter {
        public int getCount(){return visibleItems.size();} public Object getItem(int p){return visibleItems.get(p);} public long getItemId(int p){return p;}
        public View getView(int p,View old,android.view.ViewGroup parent){
            Item i=visibleItems.get(p); LinearLayout box=new LinearLayout(MainActivity.this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(0,dp(8),0,dp(8));
            LinearLayout heading=new LinearLayout(MainActivity.this);heading.setGravity(Gravity.CENTER_VERTICAL); ImageView thumb=null;
            if(i.type.equals("file")&&isImageName(i.filename)){thumb=new ImageView(MainActivity.this);thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);thumb.setTag(i.id);Bitmap cached=thumbnailCache.get(i.id);if(cached!=null)thumb.setImageBitmap(cached);heading.addView(thumb,new LinearLayout.LayoutParams(dp(70),dp(56)));if(cached==null)loadThumbnail(i,thumb);}
            String stateMark=i.syncState.equals(SyncDatabase.PENDING)?"  ⏳ 待同步":i.syncState.equals(SyncDatabase.SYNCING)?"  ↻ 同步中":i.syncState.equals(SyncDatabase.CONFLICT)?"  ⚠ 冲突":"";TextView name=text(i.filename+stateMark,17,i.syncState.equals(SyncDatabase.CONFLICT)?Color.rgb(190,80,40):Color.rgb(40,35,45));name.setTypeface(null,1);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(0,dp(10),dp(8),dp(10));heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));box.addView(heading);
            String sub=i.type.equals("text")?preview(i.content):i.type.equals("file")?formatSize(i.size):i.content;
            TextView detail=text(sub,14,Color.DKGRAY);detail.setMaxLines(i.type.equals("text")?5:2);detail.setPadding(0,dp(10),dp(8),dp(10));
            if(i.type.equals("file")) {
                boolean local=isLocalAvailable(i);LinearLayout actionRow=new LinearLayout(MainActivity.this);actionRow.setGravity(Gravity.CENTER_VERTICAL);
                detail.setGravity(Gravity.CENTER_VERTICAL);detail.setSingleLine(true);detail.setPadding(0,0,dp(8),0);actionRow.addView(detail,new LinearLayout.LayoutParams(0,dp(40),1));
                View[] buttons={fileActionButton(R.drawable.ic_action_download,"下载",true,v->download(i,false)),fileActionButton(R.drawable.ic_action_view,"打开",local,v->openLocal(i)),fileActionButton("🔗","复制地址",true,v->copy(activeBase+"/view/"+path(i.id))),fileActionButton(R.drawable.ic_action_rename,"重命名",true,v->rename(i)),fileActionButton("⌫","删除手机本地副本",local,v->deleteLocalCopy(i)),fileActionButton("🗑","删除 NAS 文件",true,v->delete(i))};
                for(View button:buttons){LinearLayout.LayoutParams buttonParams=new LinearLayout.LayoutParams(dp(36),dp(38));buttonParams.setMargins(dp(1),0,dp(1),0);actionRow.addView(button,buttonParams);}box.addView(actionRow);
                Integer progress=downloadProgress.get(i.id);box.setPadding(dp(14),dp(8),dp(14),dp(local||progress!=null?4:8));box.setBackground(rounded(Color.WHITE,16));box.setForeground(null);box.setAlpha(deletingItems.contains(i.id)?.45f:1f);if(progress!=null){ProgressBar line=new ProgressBar(MainActivity.this,null,android.R.attr.progressBarStyleHorizontal);line.setMax(100);line.setProgress(progress);line.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(34,139,70)));line.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(226,235,228)));box.addView(line,new LinearLayout.LayoutParams(-1,dp(4)));}else if(local){View line=new View(MainActivity.this);line.setBackgroundColor(Color.rgb(34,139,70));box.addView(line,new LinearLayout.LayoutParams(-1,dp(3)));}
            } else box.addView(detail);
            box.setOnClickListener(v->{if(i.syncState.equals(SyncDatabase.CONFLICT)){showConflict(i);return;}if(i.type.equals("file")){if(!prefs.getString("downloaded_"+i.id,"").isEmpty())openLocal(i);}else openItem(i);});box.setOnLongClickListener(v->{actions(i);return true;});
            if(!i.type.equals("text"))return box;
            box.setForeground(new FavoriteDrawable(i));
            box.setOnTouchListener((v,event)->{boolean inStar=event.getX()>=v.getWidth()-dp(36)&&event.getY()>=v.getHeight()-dp(36);if(!inStar)return false;if(event.getAction()==MotionEvent.ACTION_UP&&!favoritePending.contains(i.id))toggleFavorite(i);return true;});
            box.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,i.favorite?"取消收藏":"收藏"));}});
            return box;
        }
    }

    private void toggleFavorite(Item item){if(!favoritePending.add(item.id))return;item.favorite=!item.favorite;queueMutation(item,"favorite",map("favorite",String.valueOf(item.favorite)));favoritePending.remove(item.id);renderSection();}

    private String preview(String s){int limit=prefs.getInt("preview",600);int[] cp=s.codePoints().toArray();return cp.length<=limit?s:new String(cp,0,limit)+"… 点击查看全文";}
    private String formatSize(long b){if(b<1024)return b+" B";if(b<1048576)return String.format(Locale.CHINA,"%.1f KB",b/1024d);if(b<1073741824)return String.format(Locale.CHINA,"%.1f MB",b/1048576d);return String.format(Locale.CHINA,"%.2f GB",b/1073741824d);}
    private boolean isImageName(String name){String n=name==null?"":name.toLowerCase(Locale.ROOT);return n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".png")||n.endsWith(".gif")||n.endsWith(".webp")||n.endsWith(".bmp");}
    private void loadThumbnail(Item item,ImageView view){thumbnailIo.execute(()->{try{HttpURLConnection c=connection(activeNetwork,activeBase+"/thumbnail/"+path(item.id),7000);try(InputStream in=c.getInputStream()){Bitmap b=BitmapFactory.decodeStream(in);if(b!=null){thumbnailCache.put(item.id,b);runOnUiThread(()->{if(item.id.equals(view.getTag()))view.setImageBitmap(b);});}}}catch(Exception ignored){}});}

    private void openItem(Item i) {
        if(i.type.equals("text")) {
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(i.filename).setMessage(i.content).setPositiveButton("复制",(d,w)->copy(i.content)).setNeutralButton("编辑",(d,w)->editText(i)).setNegativeButton("关闭",null).create();
            dialog.setOnShowListener(ignored->{TextView message=dialog.findViewById(android.R.id.message);if(message!=null){message.setTextIsSelectable(true);message.setLongClickable(true);}});
            dialog.show();
        }
        else if(i.type.equals("link")) { try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(i.content)));}catch(Exception e){toast("无法打开链接");} }
        else download(i,true);
    }

    private void actions(Item i) {
        String[] a=i.type.equals("text")?new String[]{"查看","复制","编辑","重命名","删除 NAS 内容"}:i.type.equals("file")?new String[]{"下载","打开","🔗  复制地址","重命名","⌫  删除手机本地副本","🗑  删除 NAS 文件"}:new String[]{"打开","复制地址","重命名","删除 NAS 内容"};
        boolean fileOpenEnabled=!i.type.equals("file")||isLocalAvailable(i);
        ArrayAdapter<String> menuAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,a){@Override public boolean isEnabled(int position){String action=a[position];return !((action.contains("打开")||action.contains("删除手机本地副本"))&&!fileOpenEnabled);}@Override public View getView(int position,View convert,android.view.ViewGroup parent){TextView view=(TextView)super.getView(position,convert,parent);boolean enabled=isEnabled(position);int color=enabled?Color.rgb(75,151,174):Color.LTGRAY;view.setEnabled(enabled);view.setTextColor(color);view.setCompoundDrawablePadding(dp(16));view.setCompoundDrawables(null,null,null,null);if(i.type.equals("file")&&(position==0||position==1||position==3)){int iconId=position==0?R.drawable.ic_action_download:position==1?R.drawable.ic_action_view:R.drawable.ic_action_rename;Drawable icon=getDrawable(iconId);icon.setBounds(0,0,dp(18),dp(18));icon.setTint(color);view.setCompoundDrawables(icon,null,null,null);}else if(i.type.equals("text")||i.type.equals("link")){boolean first=i.type.equals("text")&&position==0;boolean open=i.type.equals("link")&&position==0;boolean copy=(i.type.equals("text")&&position==1)||(i.type.equals("link")&&position==1);boolean edit=i.type.equals("text")&&position==2;boolean rename=(i.type.equals("text")&&position==3)||(i.type.equals("link")&&position==2);boolean remove=(i.type.equals("text")&&position==4)||(i.type.equals("link")&&position==3);int iconId=(open?R.drawable.ic_action_external_link:(first?R.drawable.ic_action_view:(i.type.equals("text")&&copy?R.drawable.ic_action_copy:(edit?R.drawable.ic_action_edit:(rename?R.drawable.ic_action_rename:0)))));if((i.type.equals("link")&&copy)||remove){view.setText((copy?"🔗  ":"🗑  ")+view.getText());}else if(iconId!=0){Drawable icon=getDrawable(iconId);icon.setBounds(0,0,dp(18),dp(18));icon.setTint(color);view.setCompoundDrawables(icon,null,null,null);}}return view;}};
        new AlertDialog.Builder(this).setTitle(i.filename).setAdapter(menuAdapter,(d,w)->{
            String x=a[w]; if(x.contains("查看"))openItem(i); else if(x.contains("下载")){download(i,false);} else if(x.contains("打开")){if(i.type.equals("file"))openLocal(i);else openItem(i);} else if(x.contains("复制"))copy(i.type.equals("text")?i.content:i.type.equals("file")?activeBase+"/view/"+path(i.id):i.content); else if(x.contains("编辑"))editText(i); else if(x.contains("重命名"))rename(i); else if(x.contains("删除手机本地副本"))deleteLocalCopy(i); else delete(i);
        }).show();
    }

    private void addCurrent() {
        if(section.equals("file")){fileUploadForm();}
        else if(section.equals("text")) textForm(null); else if(section.equals("link")) linkForm();
    }

    private EditText input(String hint,boolean multi){EditText e=new EditText(this);e.setHint(hint);if(multi){e.setMinLines(5);e.setGravity(Gravity.TOP);}return e;}
    private void textForm(Item existing) {
        LinearLayout box=new LinearLayout(this);box.setPadding(dp(18),0,dp(18),0);box.setOrientation(LinearLayout.VERTICAL);EditText name=input("标题（可选）",false),body=input("正文",true);box.addView(name);box.addView(body);
        Spinner expiry=new Spinner(this);String[] expiryLabels={"永不过期","1 小时","4 小时","1 天"};String[] expiryValues={"Never","1 hour","4 hours","1 day"};ArrayAdapter<String> expiryAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,expiryLabels);expiry.setAdapter(expiryAdapter);LinearLayout expiryRow=new LinearLayout(this);expiryRow.setGravity(Gravity.CENTER_VERTICAL);TextView expiryHint=text("保存时间",14,Color.DKGRAY);expiryRow.addView(expiryHint,new LinearLayout.LayoutParams(0,-2,1));expiryRow.addView(expiry,new LinearLayout.LayoutParams(dp(150),-2));box.addView(expiryRow);
        if(existing!=null){name.setText(existing.filename);name.setEnabled(false);body.setText(existing.content);}
        new AlertDialog.Builder(this).setTitle(existing==null?"新建文字":"编辑文字").setView(box).setPositiveButton("保存",(d,w)->{if(existing==null)postForm("/submit",map("name",name.getText().toString(),"content",body.getText().toString(),"expiry",expiryValues[expiry.getSelectedItemPosition()]));else{existing.content=body.getText().toString();existing.modifiedAt=isoNow();queueMutation(existing,"edit",map("content",existing.content));}}).setNegativeButton("取消",null).show();
    }
    private void editText(Item i){textForm(i);}
    private void linkForm(){LinearLayout box=new LinearLayout(this);box.setPadding(dp(18),0,dp(18),0);box.setOrientation(LinearLayout.VERTICAL);EditText n=input("标题",false),u=input("https://example.com",false);box.addView(n);box.addView(u);new AlertDialog.Builder(this).setTitle("新建链接").setView(box).setPositiveButton("保存",(d,w)->postForm("/submit",map("type","link","name",n.getText().toString(),"content",u.getText().toString()))).setNegativeButton("取消",null).show();}
    private void fileUploadForm(){LinearLayout box=new LinearLayout(this);box.setPadding(dp(18),0,dp(18),0);box.setOrientation(LinearLayout.VERTICAL);Spinner expiry=new Spinner(this);String[] labels={"永不过期","1 小时","4 小时","1 天"};String[] values={"Never","1 hour","4 hours","1 day"};expiry.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));box.addView(text("保存时间",14,Color.DKGRAY));box.addView(expiry);new AlertDialog.Builder(this).setTitle("添加文件").setView(box).setPositiveButton("选择文件",(d,w)->{pendingExpiry=values[expiry.getSelectedItemPosition()];Intent x=new Intent(Intent.ACTION_OPEN_DOCUMENT);x.setType("*/*");x.addCategory(Intent.CATEGORY_OPENABLE);x.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(x,PICK_FILE);}).setNeutralButton("从 URL 下载",(d,w)->urlDownloadForm(values[expiry.getSelectedItemPosition()])).setNegativeButton("取消",null).show();}
    private void urlDownloadForm(String expiry){LinearLayout box=new LinearLayout(this);box.setPadding(dp(18),0,dp(18),0);box.setOrientation(LinearLayout.VERTICAL);EditText u=input("https://example.com/file.zip",false),n=input("保存名称（可选）",false);box.addView(u);box.addView(n);new AlertDialog.Builder(this).setTitle("从 URL 下载").setView(box).setPositiveButton("下载",(d,w)->downloadURLToNAS(u.getText().toString(),n.getText().toString(),expiry)).setNegativeButton("取消",null).show();}
    private Map<String,String> map(String...x){Map<String,String>m=new LinkedHashMap<>();for(int i=0;i<x.length;i+=2)m.put(x[i],x[i+1]);return m;}

    private void rename(Item i){EditText e=input("新名称",false);e.setText(i.filename);new AlertDialog.Builder(this).setTitle("重命名").setView(e).setPositiveButton("保存",(d,w)->{i.filename=e.getText().toString();i.modifiedAt=isoNow();queueMutation(i,"rename",map("newname",i.filename));}).setNegativeButton("取消",null).show();}
    private void delete(Item i){if(!deletingItems.add(i.id))return;try{JSONObject local=i.json().put("localDeleted",true);syncDb.putLocal(activeBase,local,SyncDatabase.PENDING);JSONObject payload=operationPayload("/delete/"+path(i.id),map());syncDb.enqueue(activeBase,i.id,"delete",payload,i.revision);allItems.removeIf(item->item.id.equals(i.id));visibleItems.removeIf(item->item.id.equals(i.id));adapter.notifyDataSetChanged();outboxStatusPending.set(true);setStatus("待同步 · 删除 "+i.filename);drainOutbox();SyncRetryJobService.schedule(this);}catch(Exception e){deletingItems.remove(i.id);setStatus("无法加入待同步队列 · "+e.getMessage());}}
    private void deleteLocalCopy(Item item){String key="downloaded_"+item.id;String saved=prefs.getString(key,"");if(saved.isEmpty()){renderSection();return;}try{Uri uri=Uri.parse(saved);int deleted=getContentResolver().delete(uri,null,null);if(deleted<=0){try(android.os.ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(uri,"r")){if(fd!=null)throw new IOException("系统未允许删除该文件");}}prefs.edit().remove(key).apply();toast("已删除手机本地副本");renderSection();}catch(Exception error){toast("删除本地副本失败："+error.getMessage());}}

    private void postForm(String endpoint,Map<String,String> values) {
        try{Item i=new Item();i.id=UUID.randomUUID().toString();i.storageId="";i.type="link".equals(values.get("type"))?"link":"text";i.filename=values.get("name");if(i.filename==null||i.filename.trim().isEmpty())i.filename=new java.text.SimpleDateFormat("MM／dd HH-mm-ss",Locale.CHINA).format(new Date());i.content=values.get("content");i.createdAt=i.modifiedAt=isoNow();i.syncState=SyncDatabase.PENDING;values.put("clientId",i.id);allItems.add(i);syncDb.putLocal(activeBase,i.json(),SyncDatabase.PENDING);syncDb.enqueue(activeBase,i.id,"create",operationPayload(endpoint,values),0);renderSection();outboxStatusPending.set(true);setStatus("待同步 · "+i.filename);drainOutbox();SyncRetryJobService.schedule(this);}catch(Exception e){setStatus("无法加入待同步队列 · "+e.getMessage());}
    }

    private String isoNow(){return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.ROOT).format(new Date());}
    private JSONObject operationPayload(String endpoint,Map<String,String> values)throws JSONException{JSONObject p=new JSONObject(),v=new JSONObject();p.put("endpoint",endpoint);for(Map.Entry<String,String> e:values.entrySet())v.put(e.getKey(),e.getValue());p.put("values",v);return p;}
    private void queueMutation(Item item,String type,Map<String,String> values){try{item.syncState=SyncDatabase.PENDING;syncDb.putLocal(activeBase,item.json(),SyncDatabase.PENDING);syncDb.enqueue(activeBase,item.id,type,operationPayload("/"+type+"/"+path(item.id),values),item.revision);renderSection();outboxStatusPending.set(true);setStatus("待同步 · "+item.filename);drainOutbox();SyncRetryJobService.schedule(this);}catch(Exception e){setStatus("无法加入待同步队列 · "+e.getMessage());}}

    private String formBody(Map<String,String> values)throws Exception{StringBuilder b=new StringBuilder();for(Map.Entry<String,String>e:values.entrySet()){if(b.length()>0)b.append('&');b.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));}return b.toString();}

    private void drainOutbox(){
        final String base=activeBase;
        if(base.isEmpty()||metadataIo.isShutdown()||!outboxDrainScheduled.compareAndSet(false,true))return;
        try{metadataIo.execute(()->{
            long retryDelay=0;
            try{
                SyncTransferEngine.DrainResult result=syncEngine.drainOperations(base,new SyncTransferEngine.SyncObserver(){
                    public void syncing(SyncDatabase.Operation operation){runOnUiThread(()->reloadLocal(base));}
                    public void completed(SyncDatabase.Operation operation,JSONObject item){runOnUiThread(()->{deletingItems.remove(operation.itemId);reloadLocal(base);});}
                    public void conflict(SyncDatabase.Operation operation){runOnUiThread(()->{deletingItems.remove(operation.itemId);reloadLocal(base);toast("检测到同步冲突，请处理标记项目");});}
                    public void retrying(SyncDatabase.Operation operation,Exception error){setStatus("待同步，稍后重试 · "+error.getMessage());}
                });
                if(result.busy)retryDelay=250;
                else if(result.error!=null)retryDelay=Math.min(60000L,1500L*(1L<<Math.min(result.retryAttempts,5)));
            }finally{
                outboxDrainScheduled.set(false);
                boolean pending=syncDb.hasOperations(base),conflicts=syncDb.hasConflicts(base),reportComplete=!pending&&outboxStatusPending.getAndSet(false);
                String route=reportComplete&&!conflicts?syncRoute(activeNetwork):"";
                long delay=retryDelay>0?retryDelay:250;
                runOnUiThread(()->{reloadLocal(base);if(base.equals(activeBase)&&reportComplete)setStatus(conflicts?"存在同步冲突":"已同步（"+route+"）");});
                mainHandler.removeCallbacks(outboxRetry);
                if(base.equals(activeBase)&&pending&&!metadataIo.isShutdown())mainHandler.postDelayed(outboxRetry,delay);
            }
        });}catch(RejectedExecutionException ignored){outboxDrainScheduled.set(false);}
    }
    private void reloadLocal(String base){if(!base.equals(activeBase))return;try{parseItems(syncDb.load(base));}catch(Exception ignored){}}
    private void showConflict(Item item){if(item.conflict==null){toast("冲突详情不可用");return;}new AlertDialog.Builder(this).setTitle("同步冲突 · "+item.filename).setMessage("服务器内容在离线期间已被其它客户端修改。请选择保留哪一份。").setPositiveButton("保留本地",(d,w)->resolveConflict(item,true,false)).setNegativeButton("使用服务器",(d,w)->resolveConflict(item,false,false)).setNeutralButton("本地另存副本",(d,w)->resolveConflict(item,false,true)).show();}
    private void resolveConflict(Item item,boolean keepLocal,boolean copyLocal){try{JSONObject remote=item.conflict.optJSONObject("remote");if(copyLocal&&item.type.equals("text")){postForm("/submit",map("name",item.filename+"（冲突副本）","content",item.content,"expiry","Never"));}if(keepLocal){JSONObject payload=item.conflict.getJSONObject("payload");String type=item.conflict.optString("type");long revision=remote==null?0:remote.optLong("revision");item.revision=revision;item.syncState=SyncDatabase.PENDING;JSONObject local=item.json();if(type.equals("delete"))local.put("localDeleted",true);syncDb.putLocal(activeBase,local,SyncDatabase.PENDING);syncDb.enqueue(activeBase,item.id,type,payload,revision);outboxStatusPending.set(true);setStatus("待同步 · "+item.filename);drainOutbox();SyncRetryJobService.schedule(this);}else if(remote!=null){syncDb.putLocal(activeBase,remote,SyncDatabase.SYNCED);}else syncDb.removeLocal(activeBase,item.id);reloadLocal(activeBase);}catch(Exception e){toast("处理冲突失败："+e.getMessage());}}

    private void downloadURLToNAS(String url,String name,String expiry){
        TransferUi ui=new TransferUi("NAS 从 URL 下载");
        transferIo.execute(()->{try{
            HttpURLConnection create=connection(activeNetwork,activeBase+"/api/v1/download-tasks",10000);ui.connection=create;create.setRequestMethod("POST");create.setDoOutput(true);create.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=utf-8");create.getOutputStream().write(formBody(map("url",url,"name",name,"expiry",expiry)).getBytes(StandardCharsets.UTF_8));
            JSONObject task=new JSONObject(read(create));ui.serverTaskId=task.getString("id");
            while(!ui.cancelled){
                HttpURLConnection poll=connection(activeNetwork,activeBase+"/api/v1/download-tasks/"+ui.serverTaskId,7000);ui.connection=poll;task=new JSONObject(read(poll));String state=task.optString("status");ui.update("NAS 正在下载",task.optLong("received"),task.optLong("total",-1));
                if(state.equals("completed")){ui.connection=null;ui.serverTaskId=null;ui.success("下载完成，已加入 Files");refresh();return;}
                if(state.equals("failed"))throw new IOException(task.optString("error","NAS 下载失败"));
                if(state.equals("cancelled"))throw new InterruptedIOException("下载已取消");
                Thread.sleep(500);
            }
            throw new InterruptedIOException("下载已取消");
        }catch(Exception e){ui.fail(e);}});
    }

    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_FILE&&result==RESULT_OK&&data!=null){if(data.getData()!=null)takePersistable(data.getData(),data);if(data.getClipData()!=null){for(int n=0;n<data.getClipData().getItemCount();n++){Uri u=data.getClipData().getItemAt(n).getUri();takePersistable(u,data);upload(u);}}else if(data.getData()!=null)upload(data.getData());}}
    private String displayName(Uri uri){String name=null;try(Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}if(name==null||name.trim().isEmpty())name="upload";if(name.lastIndexOf('.')<=0){String type=getContentResolver().getType(uri);String extension=type==null?null:MimeTypeMap.getSingleton().getExtensionFromMimeType(type.toLowerCase(Locale.ROOT));if(extension!=null&&!extension.isEmpty())name+="."+extension;}return name;}
    private void upload(Uri uri){stageAndUpload(uri);}
    private void stageAndUpload(Uri uri){String name=displayName(uri),server=activeBase,expiry=pendingExpiry;UploadTaskUi task=new UploadTaskUi(name);toast("已加入持久上传队列："+name);transferIo.execute(()->{File temp=null;try{task.preparing();temp=syncEngine.stage(uri,name);SyncDatabase.PendingUpload upload=syncDb.addUpload(server,temp.getAbsolutePath(),name,expiry);task.bindUploadId(upload.id);uploadPendingFile(upload,task);}catch(Exception e){if(temp!=null)temp.delete();task.failed(name,e.getMessage());}});}
    private void resumePendingUploads(){for(SyncDatabase.PendingUpload upload:syncDb.uploads(activeBase)){UploadTaskUi task=findUploadTask(upload.name,upload.id);if(task==null)task=new UploadTaskUi(upload.name,upload.id);UploadTaskUi pendingTask=task;transferIo.execute(()->uploadPendingFile(upload,pendingTask));}}
    private void uploadPendingFile(SyncDatabase.PendingUpload upload,UploadTaskUi task){if(!uploadExecutions.add(upload.id))return;try{uploadPendingFileOwned(upload,task);}finally{uploadExecutions.remove(upload.id);}}
    private void uploadPendingFileOwned(SyncDatabase.PendingUpload upload,UploadTaskUi task){SyncRetryJobService.schedule(this);try{if(!upload.server.equals(activeBase))throw new IOException("该任务属于另一服务器");SyncTransferEngine.UploadResult result=syncEngine.upload(upload,3,new SyncTransferEngine.UploadObserver(){public void uploading(SyncDatabase.PendingUpload value,long sent,long total){task.uploading(value.name,sent,total);}public void waiting(SyncDatabase.PendingUpload value){task.waiting(value.name);}});if(result.busy){mainHandler.postDelayed(()->{if(syncDb.hasUpload(upload.id))resumePendingUploads();else{task.complete(upload.name);refresh();}},1000);return;}task.complete(result.savedName);runOnUiThread(this::refresh);}catch(Exception error){task.failed(upload.name,"待网络恢复后自动重试："+error.getMessage());mainHandler.postDelayed(this::resumePendingUploads,Math.min(60000L,3000L*(upload.attempts+1)));}}

    private String downloadName(String filename){
        if(filename==null||filename.trim().isEmpty())return "download.bin";
        String name=filename;
        while(name.toLowerCase(Locale.ROOT).endsWith(".apk.zip"))name=name.substring(0,name.length()-4);
        return name;
    }
    private String mimeType(String filename,String fallback){
        String lower=filename==null?"":filename.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".apk"))return "application/vnd.android.package-archive";
        int dot=lower.lastIndexOf('.');
        if(dot>=0&&dot<lower.length()-1){String detected=MimeTypeMap.getSingleton().getMimeTypeFromExtension(lower.substring(dot+1));if(detected!=null)return detected;}
        return fallback==null||fallback.isEmpty()?"application/octet-stream":fallback;
    }
    private void download(Item item,boolean open){if(downloadProgress.putIfAbsent(item.id,0)!=null){toast("该文件正在下载");return;}renderSection();transferIo.execute(()->{Uri uri=null;try{HttpURLConnection c=connection(activeNetwork,activeBase+"/download/"+path(item.id),15000);c.setReadTimeout(120000);long total=c.getContentLengthLong();String name=downloadName(item.filename);String mime=mimeType(name,null);ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,mime);v.put(MediaStore.Downloads.IS_PENDING,1);uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new IOException("无法创建下载文件");long received=0;int shown=0;try(InputStream in=c.getInputStream();OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法写入下载文件");byte[]b=new byte[65536];int n;while((n=in.read(b))>0){out.write(b,0,n);received+=n;int percent=total>0?(int)Math.min(99,received*100/total):Math.min(99,shown+1);if(percent!=shown){shown=percent;downloadProgress.put(item.id,percent);runOnUiThread(()->adapter.notifyDataSetChanged());}}}v.clear();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,mime);v.put(MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(uri,v,null,null);prefs.edit().putString("downloaded_"+item.id,uri.toString()).apply();downloadProgress.remove(item.id);runOnUiThread(()->{renderSection();toast("已保存到下载目录");if(open)openLocal(item);});}catch(Exception e){if(uri!=null)getContentResolver().delete(uri,null,null);downloadProgress.remove(item.id);runOnUiThread(()->{renderSection();setStatus("下载失败 · "+e.getMessage());});}});}
    private boolean isLocalAvailable(Item item){String key="downloaded_"+item.id;String saved=prefs.getString(key,"");if(saved.isEmpty())return false;try(android.os.ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(Uri.parse(saved),"r")){if(fd!=null)return true;}catch(Exception ignored){}prefs.edit().remove(key).apply();return false;}
    private void openLocal(Item item){String saved=prefs.getString("downloaded_"+item.id,"");if(!isLocalAvailable(item)){toast("本地文件已不存在，请重新下载");renderSection();return;}String mime=mimeType(downloadName(item.filename),"*/*");try{if(mime.equals("application/vnd.android.package-archive")&&Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){toast("请允许内容中转安装未知应用，然后再次点击打开");startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));return;}Intent x=new Intent(Intent.ACTION_VIEW);x.setDataAndType(Uri.parse(saved),mime);x.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(x);}catch(Exception e){toast("无法打开文件 · "+e.getMessage());}}

    private void showNotepad(){
        if(notepad!=null)return;
        if(list.getParent()!=null)root.removeView(list);
        notepad=input("记事本",true);
        notepadSavedText=prefs.getString("notepad","");
        updatingNotepad=true;notepad.setText(notepadSavedText);updatingNotepad=false;
        notepad.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){if(!updatingNotepad){notepadDirty=!s.toString().equals(notepadSavedText);updateNotepadButtons();}}public void afterTextChanged(Editable e){}});
        root.addView(notepad,new LinearLayout.LayoutParams(-1,0,1));
        notepadPreview=text("",16,Color.rgb(45,39,49));notepadPreview.setTextIsSelectable(true);notepadPreview.setMovementMethod(LinkMovementMethod.getInstance());notepadPreview.setPadding(dp(18),dp(16),dp(18),dp(20));
        notepadPreviewScroll=new ScrollView(this);notepadPreviewScroll.addView(notepadPreview);notepadPreviewScroll.setVisibility(View.GONE);root.addView(notepadPreviewScroll,new LinearLayout.LayoutParams(-1,0,1));
        notepadActions=new LinearLayout(this);notepadActions.setWeightSum(2);notepadActions.setPadding(0,dp(8),0,dp(8));
        notepadRead=actionChip("阅读",false);notepadRead.setOnClickListener(v->setNotepadReading(!notepadReading));notepadActions.addView(notepadRead,new LinearLayout.LayoutParams(0,dp(48),1));
        notepadSave=actionChip("保存",true);notepadSave.setOnClickListener(v->saveNotepad());LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(0,dp(48),1);saveParams.setMarginStart(dp(10));notepadActions.addView(notepadSave,saveParams);root.addView(notepadActions);
        notepadReading=false;notepadDirty=false;updateNotepadButtons();
        metadataIo.execute(()->{try{String s=read(connection(activeNetwork,activeBase+"/notepad/md.file",7000));prefs.edit().putString("notepad",s).apply();runOnUiThread(()->{if(notepad!=null&&!notepad.hasFocus()&&!notepadDirty){notepadSavedText=s;updatingNotepad=true;notepad.setText(s);updatingNotepad=false;updateNotepadButtons();}});}catch(Exception ignored){}});
    }

    private void setNotepadReading(boolean reading){notepadReading=reading;if(reading){notepadPreview.setText(MarkdownRenderer.render(notepad.getText().toString()));notepad.setVisibility(View.GONE);notepadPreviewScroll.setVisibility(View.VISIBLE);}else{notepadPreviewScroll.setVisibility(View.GONE);notepad.setVisibility(View.VISIBLE);notepad.requestFocus();}updateNotepadButtons();}
    private void updateNotepadButtons(){if(notepadRead==null)return;styleStateButton(notepadRead,notepadReading);styleStateButton(notepadSave,!notepadDirty);notepadSave.setContentDescription(notepadDirty?"保存，存在未保存修改":"保存，所有修改已保存");}
    private void styleStateButton(TextView view,boolean pressed){view.setTextColor(pressed?Color.WHITE:Color.rgb(73,62,80));view.setBackground(rounded(pressed?Color.rgb(103,80,164):Color.rgb(235,229,239),20));}
    private void saveNotepad(){if(!notepadDirty){toast("所有修改均已保存");return;}String value=notepad.getText().toString();metadataIo.execute(()->{try{HttpURLConnection c=connection(activeNetwork,activeBase+"/notepad/md.file",7000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","text/plain; charset=utf-8");c.getOutputStream().write(value.getBytes(StandardCharsets.UTF_8));read(c);prefs.edit().putString("notepad",value).apply();runOnUiThread(()->{notepadSavedText=value;notepadDirty=!notepad.getText().toString().equals(value);updateNotepadButtons();toast("记事本已保存");});}catch(Exception e){setStatus("保存失败 · "+e.getMessage());}});}


    private void showDeviceCenter(){
        if(activeBase.isEmpty()){toast("请先配置服务器地址");return;}
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(14),0,dp(14),0);page.setBackgroundColor(Color.rgb(250,247,252));
        page.setOnApplyWindowInsetsListener((view,insets)->{int top=Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.statusBars()).top:insets.getSystemWindowInsetTop();int bottom=Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.navigationBars()).bottom:insets.getSystemWindowInsetBottom();view.setPadding(dp(14),top+dp(10),dp(14),bottom);return insets;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);TextView close=iconButton("×","关闭设备列表");close.setOnClickListener(v->deviceCenterDialog.dismiss());header.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44)));TextView heading=text("浏览器设备",18,Color.rgb(45,39,49));heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);heading.setGravity(Gravity.CENTER);header.addView(heading,new LinearLayout.LayoutParams(0,dp(44),1));TextView spacer=text("",18,Color.TRANSPARENT);header.addView(spacer,new LinearLayout.LayoutParams(dp(44),dp(44)));page.addView(header);
        deviceCenterStatus=text("正在读取设备…",13,Color.rgb(96,87,101));deviceCenterStatus.setPadding(dp(4),dp(2),dp(4),dp(8));page.addView(deviceCenterStatus);
        deviceCenterList=new LinearLayout(this);deviceCenterList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(deviceCenterList);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        deviceCenterDialog=new Dialog(this);deviceCenterDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);deviceCenterDialog.setContentView(page);deviceCenterDialog.setCancelable(true);
        deviceCenterDialog.setOnDismissListener(d->{deviceCenterDialog=null;deviceCenterList=null;deviceCenterStatus=null;});
        deviceCenterDialog.show();Window window=deviceCenterDialog.getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.rgb(250,247,252)));window.setGravity(Gravity.BOTTOM);window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);}
        page.post(()->{page.setTranslationY(page.getHeight());page.animate().translationY(0).setDuration(280).start();});
        renderDevices(cachedDevices);loadDeviceSummary();
    }

    private void loadDeviceSummary(){
        mainHandler.removeCallbacks(deviceSummaryPoll);
        if(activeBase.isEmpty()||deviceSummaryLoading)return;
        deviceSummaryLoading=true;String base=activeBase;
        metadataIo.execute(()->{try{
            HttpURLConnection c=connection(activeNetwork,base+"/api/v1/devices",7000);JSONArray devices=new JSONObject(read(c)).optJSONArray("devices");if(devices==null)devices=new JSONArray();JSONArray result=devices;
            runOnUiThread(()->{deviceSummaryLoading=false;if(!base.equals(activeBase))return;cachedDevices=result;updateDeviceStrip();if(deviceCenterDialog!=null&&deviceCenterDialog.isShowing())renderDevices(result);if(deviceSummaryActive)mainHandler.postDelayed(deviceSummaryPoll,5000);});
        }catch(Exception e){runOnUiThread(()->{deviceSummaryLoading=false;if(deviceCenterStatus!=null){if(cachedDevices.length()>0){deviceCenterStatus.setText("暂时无法更新 · 保留上次结果");}else{deviceCenterStatus.setText("暂时无法读取设备列表");}}if(deviceSummaryActive)mainHandler.postDelayed(deviceSummaryPoll,5000);});}});
    }

    private void updateDeviceStrip(){if(deviceStrip==null)return;int count=cachedDevices.length();if(section.equals("notepad")||count==0){deviceStrip.setVisibility(View.GONE);return;}deviceStrip.setVisibility(View.VISIBLE);deviceStrip.setText("共 "+count+" 台浏览器设备 · 每 5 秒更新");}

    private void renderDevices(JSONArray devices){
        if(deviceCenterList==null)return;deviceCenterList.removeAllViews();
        if(devices.length()==0){deviceCenterStatus.setText("还没有浏览器设备打开过网页");return;}
        deviceCenterStatus.setText("共 "+devices.length()+" 台浏览器设备 · 每 5 秒更新");
        for(int index=0;index<devices.length();index++)try{
            JSONObject device=devices.getJSONObject(index);String id=device.getString("id"),name=device.optString("name"),display=device.optString("displayName","浏览器设备"),platform=device.optString("platform"),browser=device.optString("browser"),ip=device.optString("ip"),state=device.optString("state","offline");boolean locked=device.optBoolean("locked");int tabs=device.optInt("tabs");
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(11),dp(14),dp(11));card.setBackground(rounded(Color.WHITE,16));
            LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);TextView title=text(display,16,Color.rgb(45,39,49));title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setPadding(0,0,dp(6),dp(3));titleRow.addView(title,new LinearLayout.LayoutParams(0,-2,1));ImageView rename=deviceIconButton(R.drawable.ic_action_rename,"重命名",v->renameDevice(id,name,display));titleRow.addView(rename,new LinearLayout.LayoutParams(dp(36),dp(36)));ImageView toggle=deviceIconButton(locked?R.drawable.ic_action_lock_closed:R.drawable.ic_action_lock_open,locked?"解除锁定":"关闭并锁定",v->setDeviceLocked(id,!locked,display));LinearLayout.LayoutParams toggleParams=new LinearLayout.LayoutParams(dp(36),dp(36));toggleParams.setMarginStart(dp(4));titleRow.addView(toggle,toggleParams);card.addView(titleRow);
            String details=joinDeviceDetails(platform,browser,ip,tabs);TextView detail=text(details,13,Color.rgb(96,87,101));detail.setPadding(0,0,0,dp(3));card.addView(detail);
            String moment=locked?device.optString("lockedAt"):device.optString("lastActivity");String stateText=deviceStateText(state,moment);TextView stateView=text(stateText,13,locked?Color.rgb(180,62,62):Color.rgb(75,151,174));stateView.setPadding(0,0,0,0);card.addView(stateView);
            LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.setMargins(0,0,0,dp(9));deviceCenterList.addView(card,cardParams);
        }catch(JSONException ignored){}
    }

    private ImageView deviceIconButton(int drawableId,String description,View.OnClickListener action){ImageView view=new ImageView(this);view.setImageResource(drawableId);view.setImageTintList(android.content.res.ColorStateList.valueOf(Color.rgb(75,151,174)));view.setScaleType(ImageView.ScaleType.CENTER);view.setPadding(dp(9),dp(9),dp(9),dp(9));view.setBackground(rounded(Color.rgb(244,240,246),9));view.setContentDescription(description);view.setOnClickListener(action);return view;}
    private String joinDeviceDetails(String platform,String browser,String ip,int tabs){ArrayList<String> parts=new ArrayList<>();if(!platform.isEmpty())parts.add(platform);if(!browser.isEmpty())parts.add(browser);if(!ip.isEmpty())parts.add(ip);if(tabs>0)parts.add(tabs+" 个页面");return android.text.TextUtils.join(" · ",parts);}
    private String deviceStateText(String state,String iso){String label=state.equals("online")?"在线":state.equals("background")?"后台":state.equals("locked")?"已锁定":"离线";String time=deviceTimeText(iso,state.equals("locked"));return time.isEmpty()?label:label+" · "+time;}
    private String deviceTimeText(String iso,boolean absolute){
        if(iso==null||iso.isEmpty()||iso.startsWith("0001-"))return "";try{long value=java.time.Instant.parse(iso).toEpochMilli();if(absolute)return new java.text.SimpleDateFormat("MM/dd HH:mm",Locale.CHINA).format(new Date(value));long seconds=Math.max(0,(System.currentTimeMillis()-value)/1000);if(seconds<5)return "刚刚活动";if(seconds<60)return seconds+" 秒前活动";if(seconds<3600)return seconds/60+" 分钟前活动";if(seconds<86400)return seconds/3600+" 小时前活动";return new java.text.SimpleDateFormat("MM/dd HH:mm",Locale.CHINA).format(new Date(value))+" 活动";}catch(Exception ignored){return "";}
    }

    private void renameDevice(String id,String current,String fallback){EditText field=input("设备名称",false);field.setText(current.isEmpty()?fallback:current);field.selectAll();new AlertDialog.Builder(this).setTitle("重命名设备").setView(field).setPositiveButton("保存",(d,w)->deviceAction(id,"rename",field.getText().toString().trim(),"设备名称已保存")).setNegativeButton("取消",null).show();}
    private void setDeviceLocked(String id,boolean locked,String display){deviceAction(id,locked?"lock":"unlock",null,locked?"已请求关闭并锁定 "+display:"已解除锁定 "+display);}
    private void deviceAction(String id,String action,String name,String success){
        if(deviceCenterStatus!=null)deviceCenterStatus.setText("正在执行…");String base=activeBase;
        metadataIo.execute(()->{try{HttpURLConnection c=connection(activeNetwork,base+"/api/v1/devices/"+Uri.encode(id)+"/"+action,7000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");JSONObject body=new JSONObject();if(name!=null)body.put("name",name);try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}read(c);runOnUiThread(()->{toast(success);loadDeviceSummary();});}catch(Exception e){runOnUiThread(()->{if(deviceCenterStatus!=null)deviceCenterStatus.setText("操作失败 · "+e.getMessage());});}});
    }

    private void showSettings(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),0,dp(24),0);TextView server=button("配置服务器地址");server.setOnClickListener(v->showServerConfig(false));LinearLayout.LayoutParams serverParams=new LinearLayout.LayoutParams(-1,-2);serverParams.setMargins(0,0,0,dp(8));box.addView(server,serverParams);box.addView(text("Snippet 预览字数",14,Color.DKGRAY));EditText e=input("1 至 100000",false);e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);e.setText(String.valueOf(prefs.getInt("preview",600)));box.addView(e);new AlertDialog.Builder(this).setTitle("设置").setView(box).setPositiveButton("保存",(d,w)->{try{int n=Integer.parseInt(e.getText().toString());if(n<1||n>100000)throw new Exception();prefs.edit().putInt("preview",n).apply();renderSection();}catch(Exception x){toast("请输入 1 至 100000");}}).setNeutralButton("关于",(d,w)->showAbout()).setNegativeButton("取消",null).show();}
    private void showServerConfig(boolean required){EditText field=input("例如 http://192.168.1.10:8084/",false);field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);field.setText(activeBase);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("配置服务器地址").setMessage("请输入包含 http:// 或 https:// 的服务地址").setView(field).setPositiveButton("保存",null).setNegativeButton(required?null:"取消",null).setCancelable(!required).create();dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String normalized=ServerConfig.normalize(field.getText().toString());if(normalized.isEmpty()){field.setError("请输入有效的 http:// 或 https:// 地址");return;}if(!ServerConfig.save(this,normalized)){field.setError("保存失败");return;}stopRealtimeUpdates();serverGeneration++;lastEventSequence=-1;mainHandler.removeCallbacks(realtimeRefresh);thumbnailCache.evictAll();activeBase=normalized;cachedDevices=new JSONArray();updateDeviceStrip();reloadLocal(normalized);status.setText("已显示该服务器的离线数据，正在连接…");dialog.dismiss();refresh();startRealtimeUpdates();drainOutbox();resumePendingUploads();loadDeviceSummary();}));dialog.show();}
    private void showAbout(){String changes="1.0.24\n• 修复特殊字符文件名的访问与上传编码\n• 限制超大剪贴板文本，避免辅助服务阻塞\n\n1.0.23\n• 复制纯文本后显示右侧快捷上传圆钮\n• 点击圆钮发送到 Snippets，1.5 秒未点击自动消失\n• 微信分享的无后缀图片会按 MIME 类型补充扩展名\n\n1.0.22\n• 手机下载实时显示进度，可取消并自动清理失败文件\n• NAS 从 URL 下载显示真实进度，支持取消和失败状态\n\n1.0.21\n• 手机端即时同步其它设备上的新增、修改和删除\n• 返回前台时自动同步最新内容\n\n1.0.20\n• 上传失败自动重试三次并显示具体原因\n\n1.0.19\n• 分享上传和 App 内上传统一先复制到本地缓存\n• 大文件上传显示文件名和实时百分比\n\n1.0.18\n• 修复系统分享大文件时来源 URI 过早失效的问题\n\n1.0.17\n• 文件来源按钮统一改名为从 URL 下载\n\n1.0.16\n• Files 新增添加 URL，由 NAS 直接下载公网文件\n\n1.0.15\n• 缩略图改由 NAS 生成并传输小图\n\n1.0.14\n• 修复列表滚动后图片缩略图反复消失并重新下载的问题\n\n1.0.13\n• Files 中的图片文件显示按比例缩放的缩略图\n\n1.0.12\n• 同步状态仅显示局域网或外网\n\n1.0.11\n• 同步网址后显示当前使用局域网还是外网\n• 关于页面补齐版本更新记录\n\n1.0.10\n• 未下载或本地已删除的文件灰显打开选项\n• 删除本地文件后自动清除绿色状态线\n• 增加 APK 安装权限检查与授权引导\n\n1.0.9\n• Files 操作恢复到长按菜单，下载与打开拆分\n• 强制纠正 APK 的文件名和 MIME 类型\n\n1.0.8\n• 修复 APK 下载后被追加 .zip 后缀\n• 设置中增加关于与版本记录\n\n1.0.7\n• 统一使用 配置的服务器域名，由 DNS 完成内外网分流\n\n1.0.6\n• Files 上传增加保存时间\n• 本地文件使用底部绿色粗线标记\n\n1.0.5\n• 新建文字默认永不过期，可直接选择保存时间\n• Files 分离下载和打开按钮，下载后才可打开\n\n1.0.4\n• 根据 本地网关判断家庭网络\n\n1.0.3\n• 增加下拉刷新并保留刷新按钮\n\n1.0.2\n• 支持从系统分享面板上传单个或多个文件\n\n1.0.1\n• 修复 Android 16 状态栏遮挡并优化顶部界面\n\n1.0.0\n• 首个 Android 原生版本，支持文字、文件、链接和记事本";TextView content=text(changes,14,Color.rgb(45,39,49));content.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.setPadding(dp(12),0,dp(12),0);scroll.addView(content);new AlertDialog.Builder(this).setTitle("内容中转  " + appVersion()).setView(scroll).setPositiveButton("关闭",null).show();}
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){return "";}}
    private String path(String id){return Uri.encode(id,"/");}
    private void copy(String s){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("内容中转",s));toast("已复制");}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override protected void onDestroy(){stopRealtimeUpdates();mainHandler.removeCallbacks(outboxRetry);mainHandler.removeCallbacks(deviceSummaryPoll);metadataIo.shutdownNow();transferIo.shutdownNow();thumbnailIo.shutdownNow();realtimeIo.shutdownNow();super.onDestroy();}
}
