package com.jopsis.httpaceserveproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.streamvault.plugin.hap.HapConfigActivity;
import com.streamvault.plugin.hap.R;

import java.io.File;

public class ProxySupervisorService extends Service {
    static final String ACTION_START = "com.jopsis.httpaceserveproxy.START";
    static final String ACTION_STOP = "com.jopsis.httpaceserveproxy.STOP";
    static final String ACTION_RESTART = "com.jopsis.httpaceserveproxy.RESTART";
    static final String ACTION_RESTART_PROXY = "com.jopsis.httpaceserveproxy.RESTART_PROXY";

    private static final String CHANNEL_ID = "proxy";
    private static final int NOTIFICATION_ID = 42;
    private static final int ACE_HTTP_PORT = 6878;
    private static final int ACE_API_PORT = 62062;

    private AceServeRuntime aceServe;
    private volatile Thread supervisorThread;
    private volatile Thread proxyThread;
    private volatile Thread proxyRestartThread;

    @Override
    public void onCreate() {
        super.onCreate();
        aceServe = new AceServeRuntime(this);
        HttpAceProxyNative.initHttpBridge();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_stopping), false));
            stopAll(true);
            clearNotification();
            stopSelf();
            return START_NOT_STICKY;
        }
        ServiceState.desiredRunning = true;
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_starting), true));
        if (ACTION_RESTART_PROXY.equals(action)) {
            startProxyRestart();
            return START_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            stopAll(false);
        }
        startAll();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!ServiceState.desiredRunning) {
            stopAll(true);
            clearNotification();
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopAll(true);
        clearNotification();
        super.onDestroy();
    }

    private synchronized void startAll() {
        if (supervisorThread != null && supervisorThread.isAlive()) return;
        ServiceState.desiredRunning = true;
        supervisorThread = new Thread(this::runSupervisor, "proxy-supervisor");
        supervisorThread.start();
    }

    private void runSupervisor() {
        try {
            ServiceState.error("");
            ServiceState.phase("Preparing assets");
            prepareProxyAssets();
            aceServe.prepare();

            ServiceState.phase("Starting AceServe");
            aceServe.start();
            ServiceState.aceRunning = waitForAceServe();
            if (!ServiceState.aceRunning) throw new IllegalStateException("AceServe did not open local ports");

            ServiceState.phase("Starting HTTPAceProxy");
            startProxyThread();
            if (!waitForProxyReady()) throw new IllegalStateException("HTTPAceProxy did not open local port");

            ServiceState.phase("Running");
            updateNotification(ProxyExposure.notificationText(this));
        } catch (Exception e) {
            ServiceState.error(e.toString());
            ServiceState.phase("Failed");
            updateNotification(getString(R.string.status_failed));
            stopAll(false);
        }
    }

    private synchronized void startProxyRestart() {
        ServiceState.desiredRunning = true;
        if (proxyRestartThread != null && proxyRestartThread.isAlive()) return;
        proxyRestartThread = new Thread(this::runProxyRestart, "proxy-restart");
        proxyRestartThread.start();
    }

    private void runProxyRestart() {
        try {
            ServiceState.error("");
            ServiceState.phase("Restarting HTTPAceProxy");
            updateNotification(getString(R.string.message_restarting_proxy));

            waitForActiveSupervisor();
            if (aceServe == null || !aceServe.isRunning()) {
                ServiceState.appendLog("proxy restart requested without AceServe; starting all");
                startAll();
                return;
            }

            prepareProxyAssets();
            if (!stopProxyOnly(10000)) {
                throw new IllegalStateException("HTTPAceProxy did not stop before restart");
            }

            ServiceState.phase("Starting HTTPAceProxy");
            startProxyThread();
            if (!waitForProxyReady()) throw new IllegalStateException("HTTPAceProxy did not open local port");

            ServiceState.phase("Running");
            updateNotification(ProxyExposure.notificationText(this));
        } catch (Exception e) {
            ServiceState.error(e.toString());
            ServiceState.phase("Failed");
            updateNotification(getString(R.string.status_proxy_restart_failed));
            stopProxyOnly(3000);
        }
    }

    private void waitForActiveSupervisor() throws InterruptedException {
        Thread thread = supervisorThread;
        if (thread == null || thread == Thread.currentThread() || !thread.isAlive()) return;
        ServiceState.appendLog("waiting for active supervisor before proxy restart");
        thread.join(90000);
        if (thread.isAlive()) throw new IllegalStateException("Timed out waiting for service startup");
    }

    private boolean waitForProxyReady() {
        boolean proxyOk = HealthClient.waitForTcp(ProxyExposure.LOCAL_HOST, ProxyExposure.PROXY_PORT, 30000);
        ServiceState.proxyRunning = proxyOk || HttpAceProxyNative.nativeIsRunning();
        return ServiceState.proxyRunning;
    }

    private synchronized void startProxyThread() {
        if (proxyThread != null && proxyThread.isAlive()) return;
        File root = new File(getFilesDir(), "httpaceproxy");
        String aioPlugins = PlaylistCatalog.loadAioPlugins(this);
        ServiceState.appendLog("aio plugins: " + aioPlugins);
        String userM3uSources = PlaylistCatalog.selectedUserM3uSourcesConfig(this);
        String customPlaylistPath = CustomPlaylistCatalog.playlistPath(this);
        String httpHost = ProxyExposure.listenHost(this);
        ServiceState.appendLog("proxy listen: " + httpHost + ":" + ProxyExposure.PROXY_PORT);
        proxyThread = new Thread(() -> {
            try {
                HttpAceProxyNative.start(
                        "127.0.0.1",
                        ACE_API_PORT,
                        ACE_HTTP_PORT,
                        httpHost,
                        ProxyExposure.PROXY_PORT,
                        root.getAbsolutePath(),
                        PlaylistCatalog.enabledPluginsCsv(),
                        aioPlugins,
                        userM3uSources,
                        customPlaylistPath,
                        8,
                        3);
            } catch (Throwable t) {
                ServiceState.error("proxy: " + t);
            } finally {
                ServiceState.proxyRunning = false;
            }
        }, "httpaceproxy");
        proxyThread.start();
    }

    private boolean waitForAceServe() {
        long deadline = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < deadline) {
            if (HealthClient.waitForTcp("127.0.0.1", ACE_HTTP_PORT, 250)
                    && HealthClient.waitForTcp("127.0.0.1", ACE_API_PORT, 250)) {
                return true;
            }
            if (!aceServe.isRunning()) {
                throw new IllegalStateException("AceServe process exited before opening local ports");
            }
        }
        return false;
    }

    private synchronized void stopAll() {
        stopAll(true);
    }

    private synchronized void stopAll(boolean showStoppedPhase) {
        ServiceState.desiredRunning = false;
        if (showStoppedPhase) ServiceState.phase("Stopping");
        try {
            HttpAceProxyNative.nativeStop();
        } catch (Throwable t) {
            ServiceState.appendLog("proxy stop failed: " + t);
        }
        waitForProxyThread(3000);
        if (aceServe != null) aceServe.stop();
        ServiceState.aceRunning = false;
        ServiceState.proxyRunning = false;
        if (showStoppedPhase) ServiceState.phase("Stopped");
    }

    private synchronized boolean stopProxyOnly(long timeoutMillis) {
        try {
            HttpAceProxyNative.nativeStop();
        } catch (Throwable t) {
            ServiceState.appendLog("proxy stop failed: " + t);
        }
        boolean stopped = waitForProxyThread(timeoutMillis);
        if (stopped) ServiceState.proxyRunning = false;
        return stopped;
    }

    private void clearNotification() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable t) {
            ServiceState.appendLog("stopForeground failed: " + t);
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private boolean waitForProxyThread(long timeoutMillis) {
        Thread thread = proxyThread;
        if (thread == null) return true;
        if (!thread.isAlive()) {
            if (proxyThread == thread) proxyThread = null;
            return true;
        }
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean stopped = !thread.isAlive();
        if (stopped && proxyThread == thread) proxyThread = null;
        return stopped;
    }

    private void prepareProxyAssets() throws Exception {
        File root = new File(getFilesDir(), "httpaceproxy");
        File marker = new File(root, ".prepared-v1");
        if (!marker.exists()) {
            AssetCopier.copyTree(this, "httpaceproxy", root);
            if (!marker.createNewFile()) throw new IllegalStateException("Cannot mark proxy assets prepared");
        }
        CustomPlaylistCatalog.writePlaylist(this);
    }

    private Notification buildNotification(String text) {
        return buildNotification(text, ServiceState.desiredRunning);
    }

    private Notification buildNotification(String text, boolean persistent) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "HaP",
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);

        Intent launchIntent = new Intent(this, HapConfigActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("HaP")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setAutoCancel(false)
                .setOngoing(persistent)
                .build();
        if (persistent) {
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        }
        return notification;
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
