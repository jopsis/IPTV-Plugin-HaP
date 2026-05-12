package com.jopsis.httpaceserveproxy;

import android.net.TrafficStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class NativeHttpBridge {
    private static final int TRAFFIC_TAG = 0x484150;
    private static final int THREAD_TAG_NONE = -1;

    private NativeHttpBridge() {
    }

    public static String[] fetch(String url, String[] headerPairs, int timeoutSeconds, boolean followRedirects) {
        int previousTag = TrafficStats.getThreadStatsTag();
        TrafficStats.setThreadStatsTag(TRAFFIC_TAG);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            int timeoutMillis = Math.max(1, timeoutSeconds) * 1000;
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setInstanceFollowRedirects(followRedirects);
            connection.setRequestProperty("User-Agent", "HaP");
            if (headerPairs != null) {
                for (int i = 0; i + 1 < headerPairs.length; i += 2) {
                    connection.setRequestProperty(headerPairs[i], headerPairs[i + 1]);
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = stream == null ? "" : new String(readAll(stream, 32 * 1024 * 1024), StandardCharsets.UTF_8);
            return new String[] {
                    String.valueOf(status),
                    connection.getURL().toString(),
                    flattenHeaders(connection.getHeaderFields()),
                    body
            };
        } catch (Exception e) {
            return new String[] {"0", url, "", e.toString()};
        } finally {
            if (connection != null) connection.disconnect();
            restoreThreadStatsTag(previousTag);
        }
    }

    private static byte[] readAll(InputStream stream, int maxBytes) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) throw new IOException("HTTP response too large");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String flattenHeaders(Map<String, List<String>> headers) {
        StringBuilder out = new StringBuilder();
        if (headers == null) return "";
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            for (String value : entry.getValue()) {
                out.append(entry.getKey()).append('\t').append(value == null ? "" : value).append('\n');
            }
        }
        return out.toString();
    }

    private static void restoreThreadStatsTag(int tag) {
        if (tag == THREAD_TAG_NONE) {
            TrafficStats.clearThreadStatsTag();
        } else {
            TrafficStats.setThreadStatsTag(tag);
        }
    }
}
