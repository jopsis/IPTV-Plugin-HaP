package com.jopsis.httpaceserveproxy;

import android.net.TrafficStats;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

final class HealthClient {
    private static final int TRAFFIC_TAG = 0x484150;
    private static final int THREAD_TAG_NONE = -1;

    private HealthClient() {
    }

    static boolean waitForHttp(String url, long timeoutMillis) {
        int previousTag = TrafficStats.getThreadStatsTag();
        TrafficStats.setThreadStatsTag(TRAFFIC_TAG);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try {
            while (System.currentTimeMillis() < deadline) {
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(1500);
                    connection.setReadTimeout(1500);
                    int status = connection.getResponseCode();
                    connection.disconnect();
                    if (status > 0) return true;
                } catch (IOException ignored) {
                }
                sleep(500);
            }
            return false;
        } finally {
            restoreThreadStatsTag(previousTag);
        }
    }

    static boolean waitForTcp(String host, int port, long timeoutMillis) {
        int previousTag = TrafficStats.getThreadStatsTag();
        TrafficStats.setThreadStatsTag(TRAFFIC_TAG);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try {
            while (System.currentTimeMillis() < deadline) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), 1500);
                    return true;
                } catch (IOException ignored) {
                }
                sleep(500);
            }
            return false;
        } finally {
            restoreThreadStatsTag(previousTag);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void restoreThreadStatsTag(int tag) {
        if (tag == THREAD_TAG_NONE) {
            TrafficStats.clearThreadStatsTag();
        } else {
            TrafficStats.setThreadStatsTag(tag);
        }
    }
}
