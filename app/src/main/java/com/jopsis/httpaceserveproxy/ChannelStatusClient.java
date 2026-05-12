package com.jopsis.httpaceserveproxy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.List;

final class ChannelStatusClient {
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 20000;

    private ChannelStatusClient() {
    }

    static List<PluginChannels> loadPlugins(Context context) throws Exception {
        String body = get(PlaylistCatalog.BASE_URL + "/statplugin?action=get_plugins", READ_TIMEOUT_MS);
        JSONObject root = new JSONObject(body);
        JSONArray plugins = root.optJSONArray("plugins");
        List<PluginChannels> out = new ArrayList<>();
        if (plugins == null) return out;

        for (int i = 0; i < plugins.length(); i++) {
            JSONObject plugin = plugins.getJSONObject(i);
            String id = plugin.optString("name", "");
            if (PlaylistCatalog.sourceFor(id) == null) continue;

            PluginChannels entry = new PluginChannels(id, PlaylistCatalog.labelFor(context, id));
            JSONArray channels = plugin.optJSONArray("channels");
            if (channels != null) {
                for (int c = 0; c < channels.length(); c++) {
                    JSONObject channel = channels.getJSONObject(c);
                    String name = channel.optString("name", "");
                    String contentId = channel.optString("content_id", "");
                    if (!name.isEmpty() && !contentId.isEmpty()) {
                        entry.channels.add(new Channel(name, contentId));
                    }
                }
            }
            out.add(entry);
        }
        out.sort((left, right) -> Integer.compare(sourceOrder(left.id), sourceOrder(right.id)));
        return out;
    }

    static PeerResult checkPeers(String contentId, int maxWaitSeconds) throws Exception {
        String url = PlaylistCatalog.BASE_URL
                + "/statplugin?action=check_peers&content_id="
                + encode(contentId)
                + "&max_wait="
                + Math.max(5, Math.min(30, maxWaitSeconds));
        JSONObject data = new JSONObject(get(url, Math.max(8000, (maxWaitSeconds + 5) * 1000)));

        PeerResult result = new PeerResult();
        result.success = "success".equals(data.optString("status"));
        result.available = data.optBoolean("available", false);
        result.peers = data.optInt("peers", 0);
        result.httpPeers = data.optInt("http_peers", 0);
        result.totalPeers = data.optInt("total_peers", result.peers + result.httpPeers);
        result.statusText = data.optString("status_text", "unknown");
        result.error = data.optString("error", "");
        return result;
    }

    private static String get(String url, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = readAll(input);
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status + ": " + body);
        return body;
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static int sourceOrder(String id) {
        if (PlaylistCatalog.USER_M3U_PLUGIN_ID.equals(id)) return 0;
        if (PlaylistCatalog.CUSTOM_ID.equals(id)) return 1;
        return 2;
    }

    static final class PluginChannels {
        final String id;
        final String label;
        final List<Channel> channels = new ArrayList<>();

        PluginChannels(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label + " (" + channels.size() + ")";
        }
    }

    static final class Channel {
        final String name;
        final String contentId;

        Channel(String name, String contentId) {
            this.name = name;
            this.contentId = contentId;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final class PeerResult {
        boolean success;
        boolean available;
        int peers;
        int httpPeers;
        int totalPeers;
        String statusText;
        String error;
    }
}
