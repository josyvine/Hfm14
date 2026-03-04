package com.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DeleteService extends Service {

    private static final String TAG = "DeleteService";
    
    public static final String ACTION_DELETE_LOG = "com.hfm.app.action.DELETE_LOG";
    public static final String EXTRA_LOG_MESSAGE = "extra_log_message";
    public static final String ACTION_DELETE_COMPLETE = "com.hfm.app.action.DELETE_COMPLETE";
    public static final String EXTRA_FILES_TO_DELETE = "com.hfm.app.extra.FILES_TO_DELETE";
    public static final String EXTRA_DELETED_COUNT = "com.hfm.app.extra.DELETED_COUNT";

    private NotificationManager notificationManager;
    private ExecutorService executorService;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicInteger idGenerator = new AtomicInteger(100); // Start IDs at 100
    private boolean isServiceForeground = false;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // Thread pool set to 6 for high-speed parallel disk I/O
        executorService = Executors.newFixedThreadPool(6);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        final ArrayList<String> fullList;
        if (FileBridge.mFilesToDelete != null && !FileBridge.mFilesToDelete.isEmpty()) {
            fullList = new ArrayList<>(FileBridge.mFilesToDelete);
            FileBridge.mFilesToDelete.clear();
        } else {
            fullList = intent.getStringArrayListExtra(EXTRA_FILES_TO_DELETE);
        }

        if (fullList == null || fullList.isEmpty()) return START_NOT_STICKY;

        // Requirement 1: Use the chosen Batch Size to split the work
        int batchSize = intent.getIntExtra("batch_size", 10);
        if (batchSize < 1) batchSize = 1;

        if (!isServiceForeground) {
            startForeground(9999, createNotification("HFM Delete Engine", "Launching parallel tasks...", 0, 0, true));
            isServiceForeground = true;
        }

        // REQUIREMENT: MULTI-NOTIFICATION (IDM STYLE)
        // We split the master list into smaller chunks and run them as separate jobs
        for (int i = 0; i < fullList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, fullList.size());
            final List<String> chunk = new ArrayList<>(fullList.subList(i, end));
            final int subJobId = idGenerator.incrementAndGet();
            
            activeTasks.incrementAndGet();
            executorService.execute(() -> {
                try {
                    performDeletionTask(chunk, subJobId);
                } finally {
                    if (activeTasks.decrementAndGet() <= 0) {
                        stopForeground(true);
                        stopSelf();
                    }
                }
            });
        }

        return START_STICKY;
    }

    private void performDeletionTask(List<String> filePaths, int jobId) {
        int totalInChunk = filePaths.size();
        int deletedCount = 0;
        ContentResolver resolver = getContentResolver();

        sendLog("Sub-Job #" + jobId + ": Initialising batch of " + totalInChunk + " files.");

        for (int i = 0; i < totalInChunk; i++) {
            String path = filePaths.get(i);
            File file = new File(path);
            String fileName = file.getName();
            
            boolean deleted = false;

            // Step 1: Attempt High-Speed Java Delete
            if (file.exists()) {
                deleted = file.delete();
            }

            // Step 2: Fallback to SAF (Slow path)
            if (!deleted && file.exists()) {
                sendLog("[Job " + jobId + "] [WAIT] SAF request for: " + fileName);
                deleted = StorageUtils.deleteFile(this, file);
                if (deleted) sendLog("[Job " + jobId + "] [SLOW] SAF Deleted: " + fileName);
            }

            if (deleted || !file.exists()) {
                deletedCount++;
                try {
                    resolver.delete(MediaStore.Files.getContentUri("external"), 
                        MediaStore.Files.FileColumns.DATA + "=?", new String[]{path});
                } catch (Exception ignored) {}
            }

            // Update individual notification bar for this specific chunk
            notificationManager.notify(jobId, createNotification("Deleting Batch (" + jobId + ")", (i+1)+"/"+totalInChunk, i+1, totalInChunk, true));
        }

        sendLog("Sub-Job #" + jobId + " finished.");
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_DELETE_COMPLETE).putExtra(EXTRA_DELETED_COUNT, deletedCount));
        
        // Keep "Done" on status bar for 2 seconds then clear
        notificationManager.notify(jobId, createNotification("Batch " + jobId + " Complete", "Finished " + deletedCount + " files", 100, 100, false));
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        notificationManager.cancel(jobId);
    }

    private void sendLog(String msg) {
        Intent intent = new Intent(ACTION_DELETE_LOG);
        intent.putExtra(EXTRA_LOG_MESSAGE, msg);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.d(TAG, msg);
    }

    private Notification createNotification(String title, String content, int p, int m, boolean ongoing) {
        return new NotificationCompat.Builder(this, "DeleteServiceChannel")
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true) // Prevents the phone from vibrating/beeping for every file
                .setProgress(m, p, m == 0 && ongoing)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel("DeleteServiceChannel", "Deletion", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(c);
        }
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}