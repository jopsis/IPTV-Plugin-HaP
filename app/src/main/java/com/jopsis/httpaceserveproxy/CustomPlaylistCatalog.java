package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.content.SharedPreferences;

import com.streamvault.plugin.hap.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CustomPlaylistCatalog {
    private static final String KEY_CHANNELS = "custom_channels_json";
    private static final String PLAYLIST_FILE = "custom.m3u";
    private static final Pattern ACE_ID = Pattern.compile("^[A-Fa-f0-9]{40}$");
    private static final Pattern QUERY_ACE_ID = Pattern.compile("(?:^|[?&])(id|infohash|content_id)=([A-Fa-f0-9]{40})(?:&|$)");

    private CustomPlaylistCatalog() {
    }

    static List<Channel> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PlaylistCatalog.PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_CHANNELS, "[]");
        List<Channel> channels = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String name = normalizeName(item.optString("name", ""));
                String contentId = normalizeContentId(item.optString("content_id", ""));
                if (!name.isEmpty() && !contentId.isEmpty()) {
                    channels.add(new Channel(name, contentId));
                }
            }
        } catch (Exception ignored) {
        }
        return channels;
    }

    static SaveResult saveChannel(Context context, int editIndex, String rawName, String rawContentId) {
        String name = normalizeName(rawName);
        String contentId = normalizeContentId(rawContentId);
        if (name.isEmpty()) return SaveResult.error(context.getString(R.string.error_channel_name_empty));
        if (contentId.isEmpty()) return SaveResult.error(context.getString(R.string.error_acestream_id_invalid));

        List<Channel> channels = load(context);
        if (editIndex < -1 || editIndex >= channels.size()) {
            return SaveResult.error(context.getString(R.string.error_custom_channel_not_found));
        }

        for (int i = 0; i < channels.size(); i++) {
            if (i == editIndex) continue;
            Channel existing = channels.get(i);
            if (existing.contentId.equalsIgnoreCase(contentId)) {
                return SaveResult.error(context.getString(R.string.error_custom_channel_duplicate));
            }
        }

        Channel channel = new Channel(name, contentId.toLowerCase(Locale.ROOT));
        if (editIndex >= 0) channels.set(editIndex, channel);
        else channels.add(channel);

        try {
            saveAll(context, channels);
            writePlaylist(context, channels);
            return SaveResult.ok(context.getString(editIndex >= 0
                    ? R.string.message_custom_channel_updated
                    : R.string.message_custom_channel_saved));
        } catch (IOException e) {
            return SaveResult.error(context.getString(R.string.error_custom_playlist_generate, e.getMessage()));
        }
    }

    static SaveResult deleteChannel(Context context, int index) {
        List<Channel> channels = load(context);
        if (index < 0 || index >= channels.size()) {
            return SaveResult.error(context.getString(R.string.error_custom_channel_not_found));
        }
        channels.remove(index);
        try {
            saveAll(context, channels);
            writePlaylist(context, channels);
            return SaveResult.ok(context.getString(R.string.message_custom_channel_deleted));
        } catch (IOException e) {
            return SaveResult.error(context.getString(R.string.error_custom_playlist_generate, e.getMessage()));
        }
    }

    static void writePlaylist(Context context) throws IOException {
        writePlaylist(context, load(context));
    }

    static String playlistPath(Context context) {
        return playlistFile(context).getAbsolutePath();
    }

    private static void saveAll(Context context, List<Channel> channels) {
        JSONArray array = new JSONArray();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Channel channel : channels) {
            String name = normalizeName(channel.name);
            String contentId = normalizeContentId(channel.contentId);
            if (name.isEmpty() || contentId.isEmpty()) continue;
            String idKey = contentId.toLowerCase(Locale.ROOT);
            if (!seenIds.add(idKey)) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("name", name);
                item.put("content_id", idKey);
                array.put(item);
            } catch (Exception ignored) {
            }
        }

        context.getSharedPreferences(PlaylistCatalog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CHANNELS, array.toString())
                .commit();
    }

    private static void writePlaylist(Context context, List<Channel> channels) throws IOException {
        File file = playlistFile(context);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException(context.getString(R.string.error_custom_playlist_parent, parent.getAbsolutePath()));
        }

        StringBuilder out = new StringBuilder();
        out.append("#EXTM3U deinterlace=1 m3uautoload=1 cache=1000\n");
        for (Channel channel : channels) {
            out.append("#EXTINF:-1 group-title=\"Custom\" tvg-name=\"")
                    .append(escapeAttr(channel.name))
                    .append("\" tvg-id=\"")
                    .append(escapeAttr(channel.name))
                    .append("\",")
                    .append(escapeText(channel.name))
                    .append('\n')
                    .append("acestream://")
                    .append(channel.contentId)
                    .append('\n');
        }

        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static File playlistFile(Context context) {
        return new File(new File(context.getFilesDir(), "httpaceproxy"), PLAYLIST_FILE);
    }

    private static String normalizeName(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeContentId(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        if (cleaned.toLowerCase(Locale.ROOT).startsWith("acestream://")) {
            cleaned = cleaned.substring("acestream://".length());
        } else {
            Matcher matcher = QUERY_ACE_ID.matcher(cleaned);
            if (matcher.find()) cleaned = matcher.group(2);
        }
        int slash = cleaned.indexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(0, slash);
        cleaned = cleaned.trim();
        return ACE_ID.matcher(cleaned).matches() ? cleaned : "";
    }

    private static String escapeAttr(String value) {
        return escapeText(value).replace("\"", "'");
    }

    private static String escapeText(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    static final class Channel {
        final String name;
        final String contentId;

        Channel(String name, String contentId) {
            this.name = name;
            this.contentId = contentId;
        }
    }

    static final class SaveResult {
        final boolean success;
        final String message;

        private SaveResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        static SaveResult ok(String message) {
            return new SaveResult(true, message);
        }

        static SaveResult error(String message) {
            return new SaveResult(false, message);
        }
    }
}
