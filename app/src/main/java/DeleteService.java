package com.hfm.app;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.Manifest;

public class DeleteService extends IntentService {

    private static final String TAG = "DeleteService";

    public static final String ACTION_DELETE_COMPLETE = "com.hfm.app.action.DELETE_COMPLETE";
    public static final String EXTRA_FILES_TO_DELETE = "com.hfm.app.extra.FILES_TO_DELETE";
    public static final String EXTRA_DELETED_COUNT = "com.hfm.app.extra.DELETED_COUNT";

    private static final String NOTIFICATION_CHANNEL_ID = "DeleteServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private NotificationManager notificationManager;

    public DeleteService() {
        super("DeleteService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        // --- FIX 1: USE BRIDGE TO PREVENT CRASH ---
        // Check bridge first, fall back to intent (legacy support)
        ArrayList<String> filePathsToDelete = FileBridge.mFilesToDelete;
        if (filePathsToDelete != null && !filePathsToDelete.isEmpty()) {
            // Retrieve and clear immediately to free memory
            FileBridge.mFilesToDelete = new ArrayList<>();
        } else {
            filePathsToDelete = intent.getStringArrayListExtra(EXTRA_FILES_TO_DELETE);
        }

        // Enhancement 4: Retrieve chosen batch size
        int batchSize = intent.getIntExtra("batch_size", 1);

        if (filePathsToDelete == null || filePathsToDelete.isEmpty()) {
            return;
        }

        int totalFiles = filePathsToDelete.size();
        int deletedCount = 0;

        // --- UPDATE 2: Check for notification permission before showing notifications ---
        boolean canShowNotification = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                canShowNotification = true;
            }
        } else {
            canShowNotification = true;
        }

        if (canShowNotification) {
            startForeground(NOTIFICATION_ID, createNotification("Starting deletion...", 0, totalFiles));
        }

        ContentResolver resolver = getContentResolver();

        // Logic for Batch Processing
        for (int i = 0; i < totalFiles; i += batchSize) {
            int end = Math.min(i + batchSize, totalFiles);
            List<String> batchPaths = filePathsToDelete.subList(i, end);
            
            // --- FIX 2: HYBRID DELETE FOR SPEED + RELIABILITY ---

            // A. Batch Database Delete (FAST)
            // Removes items from Gallery/MediaStore instantly so user sees results immediately.
            try {
                StringBuilder selection = new StringBuilder(MediaStore.Files.FileColumns.DATA + " IN (");
                String[] selectionArgs = new String[batchPaths.size()];
                for (int j = 0; j < batchPaths.size(); j++) {
                    selection.append("?");
                    if (j < batchPaths.size() - 1) selection.append(",");
                    selectionArgs[j] = batchPaths.get(j);
                }
                selection.append(")");
                resolver.delete(MediaStore.Files.getContentUri("external"), selection.toString(), selectionArgs);
            } catch (Exception e) {
                Log.e(TAG, "Database batch delete error", e);
            }

            // B. Physical File Delete
            for (String path : batchPaths) {
                File file = new File(path);
                boolean success = false;

                if (file.exists()) {
                    // Try Standard Java Delete (Fastest - Works for Internal Storage)
                    if (file.delete()) {
                        success = true;
                    } 
                    // If failed, it might be SD Card on Android 11+. Use StorageUtils (SAF)
                    else if (StorageUtils.deleteFile(this, file)) {
                        success = true;
                    }
                } else {
                    // File already gone (likely deleted by database call or OS)
                    success = true;
                }

                if (success) {
                    deletedCount++;
                }
            }

            // --- UPDATE 4: Update Notification ---
            if (canShowNotification) {
                String progressText = "Deleted " + end + " of " + totalFiles + "...";
                notificationManager.notify(NOTIFICATION_ID, createNotification(progressText, end, totalFiles));
            }
        }

        // Send completion broadcast
        Intent broadcastIntent = new Intent(ACTION_DELETE_COMPLETE);
        broadcastIntent.putExtra(EXTRA_DELETED_COUNT, deletedCount);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
    }

    private Notification createNotification(String contentText, int progress, int max) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
			.setContentTitle("Deleting Files")
			.setContentText(contentText)
			.setSmallIcon(android.R.drawable.ic_menu_delete)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOngoing(true);

        if (max > 0) {
            builder.setProgress(max, progress, false);
        } else {
            builder.setProgress(0, 0, true); // Indeterminate progress
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
					NOTIFICATION_CHANNEL_ID,
					"File Deletion Service",
					NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Shows progress of background file deletion");
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
}