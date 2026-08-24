package ink.yode.contenttransfer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the durable outbox and file-transfer execution rules. Android screens,
 * foreground services and jobs are lifecycle adapters around this interface.
 */
final class SyncTransferEngine {
    interface ConnectionFactory {
        HttpURLConnection open(String url, int connectTimeout) throws Exception;
    }

    interface SyncObserver {
        void syncing(SyncDatabase.Operation operation);
        void completed(SyncDatabase.Operation operation, JSONObject item);
        void conflict(SyncDatabase.Operation operation);
        void retrying(SyncDatabase.Operation operation, Exception error);
    }

    interface UploadObserver {
        void uploading(SyncDatabase.PendingUpload upload, long sent, long total);
        void waiting(SyncDatabase.PendingUpload upload);
    }

    static final SyncObserver NO_SYNC_OBSERVER = new SyncObserver() {
        public void syncing(SyncDatabase.Operation operation) {}
        public void completed(SyncDatabase.Operation operation, JSONObject item) {}
        public void conflict(SyncDatabase.Operation operation) {}
        public void retrying(SyncDatabase.Operation operation, Exception error) {}
    };
    static final UploadObserver NO_UPLOAD_OBSERVER = new UploadObserver() {
        public void uploading(SyncDatabase.PendingUpload upload, long sent, long total) {}
        public void waiting(SyncDatabase.PendingUpload upload) {}
    };

    static final class DrainResult {
        boolean busy;
        Exception error;
        int retryAttempts;
        JSONObject lastItem;
        JSONObject lastResponse;
        boolean successful() { return !busy && error == null; }
    }

    static final class UploadResult {
        final boolean busy;
        final String savedName;
        private UploadResult(boolean busy, String savedName) {
            this.busy = busy;
            this.savedName = savedName;
        }
        static UploadResult busy() { return new UploadResult(true, null); }
        static UploadResult complete(String savedName) { return new UploadResult(false, savedName); }
    }

    private static final AtomicBoolean OPERATIONS_DRAINING = new AtomicBoolean();
    private static final Set<String> UPLOADS_IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private final Context context;
    private final SyncDatabase database;
    private final ConnectionFactory connections;

    SyncTransferEngine(Context context, SyncDatabase database, ConnectionFactory connections) {
        this.context = context.getApplicationContext();
        this.database = database;
        this.connections = connections;
    }

    static ConnectionFactory defaultConnections() {
        return (url, timeout) -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeout);
            return connection;
        };
    }

    File stage(Uri uri, String name) throws Exception {
        File directory = new File(context.getFilesDir(), "pending_uploads");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建上传队列");
        File target = File.createTempFile("upload-", ".pending", directory);
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(uri); OutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new FileNotFoundException("无法读取 " + name);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
        } catch (Exception error) {
            target.delete();
            throw error;
        }
        return target;
    }

    DrainResult drainOperations(String server, SyncObserver observer) {
        DrainResult report = new DrainResult();
        if (!OPERATIONS_DRAINING.compareAndSet(false, true)) {
            report.busy = true;
            return report;
        }
        try {
            while (true) {
                SyncDatabase.Operation operation = database.next(server);
                if (operation == null) return report;
                database.syncing(server, operation.itemId);
                observer.syncing(operation);
                try {
                    JSONObject payload = new JSONObject(operation.payload);
                    JSONObject values = payload.getJSONObject("values");
                    values.put("expectedRevision", operation.baseRevision);
                    HttpResult response = postForm(server + payload.getString("endpoint"), values, operation.id);
                    report.lastResponse = response.jsonObject();
                    if (response.code == HttpURLConnection.HTTP_CONFLICT) {
                        JSONObject conflict = report.lastResponse;
                        database.conflict(operation.id, server, operation.itemId,
                                conflict == null ? null : conflict.optJSONObject("item"));
                        observer.conflict(operation);
                        continue;
                    }
                    if (response.code < 200 || response.code >= 300) {
                        throw new IOException("HTTP " + response.code + " " + response.body);
                    }
                    JSONObject item = response.item();
                    database.complete(operation.id, server, operation.itemId, item);
                    report.lastItem = item;
                    observer.completed(operation, item);
                } catch (Exception error) {
                    database.retry(operation.id, error.getMessage());
                    report.error = error;
                    report.retryAttempts = operation.attempts;
                    observer.retrying(operation, error);
                    return report;
                }
            }
        } finally {
            OPERATIONS_DRAINING.set(false);
        }
    }

    boolean drainServer(String server) {
        DrainResult operations = drainOperations(server, NO_SYNC_OBSERVER);
        if (!operations.successful()) return false;
        boolean busy = false;
        for (SyncDatabase.PendingUpload upload : database.uploads(server)) {
            try {
                UploadResult result = upload(upload, 1, NO_UPLOAD_OBSERVER);
                busy |= result.busy;
            } catch (Exception error) {
                return false;
            }
        }
        return !busy;
    }

    UploadResult upload(SyncDatabase.PendingUpload upload, int attempts, UploadObserver observer) throws Exception {
        if (!UPLOADS_IN_FLIGHT.add(upload.id)) return UploadResult.busy();
        try {
            Exception failure = null;
            int count = Math.max(1, attempts);
            for (int attempt = 0; attempt < count; attempt++) {
                try {
                    String savedName = uploadOnce(upload, observer);
                    database.uploadComplete(upload.id);
                    new File(upload.path).delete();
                    return UploadResult.complete(savedName);
                } catch (Exception error) {
                    failure = error;
                }
            }
            String message = failure == null ? "未知错误" : failure.getMessage();
            database.uploadFailed(upload.id, message);
            throw failure == null ? new IOException(message) : failure;
        } finally {
            UPLOADS_IN_FLIGHT.remove(upload.id);
        }
    }

    private HttpResult postForm(String endpoint, JSONObject values, String idempotencyKey) throws Exception {
        StringBuilder body = new StringBuilder();
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(name, "UTF-8")).append('=')
                    .append(URLEncoder.encode(values.optString(name), "UTF-8"));
        }
        HttpURLConnection connection = connections.open(endpoint, 15000);
        connection.setReadTimeout(60000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        connection.setRequestProperty("Idempotency-Key", idempotencyKey);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return response(connection);
    }

    private String uploadOnce(SyncDatabase.PendingUpload upload, UploadObserver observer) throws Exception {
        File file = new File(upload.path);
        if (!file.isFile()) throw new FileNotFoundException(upload.path);
        String boundary = "----ContentTransfer" + System.nanoTime();
        HttpURLConnection connection = connections.open(upload.server + "/upload-stream", 30000);
        connection.setReadTimeout(300000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(65536);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Idempotency-Key", upload.id);
        String safeName = upload.name.replace("\"", "").replace("\r", " ").replace("\n", " ");
        try (OutputStream output = connection.getOutputStream()) {
            String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"expiry\"\r\n\r\n"
                    + upload.expiry + "\r\n--" + boundary
                    + "\r\nContent-Disposition: form-data; name=\"file-upload\"; filename=\""
                    + safeName + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
            output.write(head.getBytes(StandardCharsets.UTF_8));
            long sent = 0;
            long total = file.length();
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) > 0) {
                    output.write(buffer, 0, count);
                    sent += count;
                    observer.uploading(upload, sent, total);
                }
            }
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        observer.waiting(upload);
        HttpResult response = response(connection);
        if (response.code < 200 || response.code >= 300) {
            throw new IOException("HTTP " + response.code + " " + response.body);
        }
        JSONObject item = response.item();
        return item == null ? upload.name : item.optString("filename", upload.name);
    }

    private HttpResult response(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream source = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        if (source != null) try (InputStream input = source) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) sink.write(buffer, 0, count);
        }
        return new HttpResult(code, sink.toString("UTF-8"));
    }

    private static final class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body; }
        JSONObject jsonObject() {
            try { return body.trim().isEmpty() ? null : new JSONObject(body); }
            catch (Exception ignored) { return null; }
        }
        JSONObject item() {
            JSONObject root = jsonObject();
            if (root == null) return null;
            JSONObject item = root.optJSONObject("item");
            if (item != null) return item;
            JSONArray items = root.optJSONArray("items");
            return items != null && items.length() > 0 ? items.optJSONObject(0) : null;
        }
    }
}
