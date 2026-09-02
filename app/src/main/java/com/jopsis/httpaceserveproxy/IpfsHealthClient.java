package com.jopsis.httpaceserveproxy;

import android.net.TrafficStats;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** kubo's HTTP API is POST-only; a plain GET to /api/v0/id returns 405, so HealthClient.waitForHttp cannot probe it. */
final class IpfsHealthClient {
    private static final int TRAFFIC_TAG = 0x49504653; // "IPFS"

    private IpfsHealthClient() {
    }

    static boolean waitForApi(String host, int port, long timeoutMillis) {
        int previousTag = TrafficStats.getThreadStatsTag();
        TrafficStats.setThreadStatsTag(TRAFFIC_TAG);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String url = "http://" + host + ":" + port + "/api/v0/id";
        try {
            while (System.currentTimeMillis() < deadline) {
                if (probeOnce(url)) return true;
                sleep(500);
            }
            return false;
        } finally {
            TrafficStats.clearThreadStatsTag();
        }
    }

    private static boolean probeOnce(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            int status = connection.getResponseCode();
            connection.disconnect();
            return status == 200;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
