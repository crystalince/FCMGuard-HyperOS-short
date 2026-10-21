package com.reed.fcmguard;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

public class GuardService extends Service {
    public static final String ACTION_ALARM_CHECK = "com.reed.fcmguard.ACTION_ALARM_CHECK";
    // v2 intentionally uses a new channel id. Android does not allow an app to raise
    // an existing channel from IMPORTANCE_MIN to IMPORTANCE_LOW after creation.
    private static final String CHANNEL_ID = "fcm_guard_persistent_v2";
    private static final int NOTIFICATION_ID = 426;
    private static final int ALARM_REQUEST_CODE = 4260;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;
    private BroadcastReceiver eventReceiver;
    private boolean foreground;

    private final Runnable repairDebounced = new Runnable() {
        @Override public void run() {
            repair(true);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        applyExecutionMode();
        registerObserver();
        registerEventReceiver();
        SettingsGuard.rememberIfUseful(this);
        scheduleNextAlarm();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_ALARM_CHECK.equals(intent.getAction())) {
            if (SettingsGuard.isProtectionEnabled(this)) {
                repair(false);
                scheduleNextAlarm();
            }
            return START_STICKY;
        }

        SettingsGuard.setProtectionEnabled(this, true);
        registerObserver();
        registerEventReceiver();
        applyExecutionMode();
        repair(false);
        scheduleNextAlarm();
        return START_STICKY;
    }

    private void repair(boolean notifyFailure) {
        SettingsGuard.Result result = SettingsGuard.repair(this);
        // Periodically ping FCM/MCS heartbeats to prevent carrier NAT timeouts
        FcmReconnect.kick(this);
        if (result.changed) {
            if (foreground) refreshNotification(getString(R.string.notification_repaired));
        } else if (!result.success && notifyFailure && foreground) {
            refreshNotification(result.message);
        }
    }

    private void scheduleNextAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        int intervalMinutes = SettingsGuard.getCheckIntervalMinutes(this);
        long intervalMs = intervalMinutes * 60L * 1000L;
        long triggerAt = SystemClock.elapsedRealtime() + intervalMs;

        Intent intent = new Intent(this, GuardService.class).setAction(ACTION_ALARM_CHECK);
        PendingIntent pi = PendingIntent.getService(
                this,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        }
    }

    private void cancelAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(this, GuardService.class).setAction(ACTION_ALARM_CHECK);
        PendingIntent pi = PendingIntent.getService(
                this,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    private void registerObserver() {
        ContentResolver resolver = getContentResolver();
        try {
            if (observer != null) resolver.unregisterContentObserver(observer);
        } catch (Throwable ignored) {}

        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                handler.removeCallbacks(repairDebounced);
                handler.postDelayed(repairDebounced, 400L);
            }
        };
        resolver.registerContentObserver(
                Settings.System.getUriFor(SettingsGuard.getConfiguredKey(this)),
                false,
                observer
        );
    }

    private void registerEventReceiver() {
        if (eventReceiver != null) return;
        eventReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action) ||
                        ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                    handler.removeCallbacks(repairDebounced);
                    handler.postDelayed(repairDebounced, 200L);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(eventReceiver, filter);
    }

    private void applyExecutionMode() {
        if (SettingsGuard.usePersistentNotification(this)) {
            ensureNotificationChannel(this);
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_active)));
            foreground = true;
        } else {
            if (foreground) stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
            foreground = false;
        }
    }

    /**
     * Creates the visible-but-silent foreground-service channel. Returns true only
     * when this call created the channel for the first time.
     */
    public static boolean ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        boolean created = nm.getNotificationChannel(CHANNEL_ID) == null;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(channel);
        return created;
    }

    /** True when Android will actually place the foreground notification in the shade. */
    public static boolean canShowPersistentNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        cancelAlarm();
        try {
            if (observer != null) getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {}
        try {
            if (eventReceiver != null) unregisterReceiver(eventReceiver);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void refreshNotification(String text) {
        if (!foreground) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }

        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
