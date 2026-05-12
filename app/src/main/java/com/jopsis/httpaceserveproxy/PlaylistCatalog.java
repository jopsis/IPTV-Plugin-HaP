package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.content.SharedPreferences;

import com.streamvault.plugin.hap.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class PlaylistCatalog {
    static final String PREFS_NAME = "httpaceserveproxy";
    static final String KEY_AIO_PLUGINS = "aio_plugins";
    static final String KEY_USER_M3U_SOURCES = "user_m3u_sources_json";
    static final String USER_M3U_PLUGIN_ID = "userm3u";
    static final String CUSTOM_ID = "custom";
    static final String BASE_URL = ProxyExposure.localBaseUrl();

    private PlaylistCatalog() {
    }

    static String enabledPluginsCsv() {
        return USER_M3U_PLUGIN_ID + "," + CUSTOM_ID + ",aio,stat,statplugin";
    }

    static String loadAioPlugins(Context context) {
        Set<String> selected = loadSelectedSet(context);
        LinkedHashSet<String> plugins = new LinkedHashSet<>();
        for (UserM3uSource source : loadUserM3uSources(context)) {
            if (selected.contains(source.id)) {
                plugins.add(USER_M3U_PLUGIN_ID);
                break;
            }
        }
        if (selected.contains(CUSTOM_ID)) plugins.add(CUSTOM_ID);
        return plugins.isEmpty() ? "none" : setToCsv(plugins);
    }

    static Set<String> loadSelectedSet(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeSelection(context, prefs.getString(KEY_AIO_PLUGINS, ""));
    }

    static void saveSelectedSet(Context context, Set<String> selected) {
        String value = setToCsv(normalizeSelection(context, setToCsv(selected)));
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_AIO_PLUGINS, value)
                .commit();
    }

    static List<Source> sources(Context context) {
        List<Source> out = new ArrayList<>();
        String m3uDescription = context.getString(R.string.source_m3u_description);
        for (UserM3uSource source : loadUserM3uSources(context)) {
            out.add(new Source(
                    source.id,
                    source.name,
                    m3uDescription,
                    SourceFormat.M3U,
                    "",
                    source.url,
                    true
            ));
        }
        out.add(customSource(context));
        return out;
    }

    static Source sourceFor(Context context, String id) {
        if (id == null) return null;
        for (Source source : sources(context)) {
            if (source.id.equals(id)) return source;
        }
        return null;
    }

    static Source sourceFor(String pluginId) {
        if (USER_M3U_PLUGIN_ID.equals(pluginId)) {
            return new Source(
                    USER_M3U_PLUGIN_ID,
                    "M3U lists",
                    "User M3U AceStream lists",
                    SourceFormat.M3U,
                    "",
                    "",
                    false
            );
        }
        if (CUSTOM_ID.equals(pluginId)) {
            return new Source(
                    CUSTOM_ID,
                    "Custom",
                    "Manually added AceStream channels",
                    SourceFormat.CUSTOM,
                    "",
                    "",
                    false
            );
        }
        return null;
    }

    static String labelFor(String pluginId) {
        Source source = sourceFor(pluginId);
        return source == null ? pluginId : source.label;
    }

    static String labelFor(Context context, String pluginId) {
        if (USER_M3U_PLUGIN_ID.equals(pluginId)) return context.getString(R.string.source_m3u_group_label);
        if (CUSTOM_ID.equals(pluginId)) return context.getString(R.string.source_custom_label);
        return labelFor(pluginId);
    }

    static String loadSource(Context context, Source source) {
        return source == null ? "" : source.value;
    }

    static String loadSource(Context context, String id) {
        Source source = sourceFor(context, id);
        return source == null ? "" : source.value;
    }

    static boolean hasCustomSource(Context context, Source source) {
        return source != null && source.userEditable;
    }

    static boolean saveSource(Context context, Source source, String value) {
        if (source == null || source.format != SourceFormat.M3U) return false;
        return saveUserM3uSource(context, source.id, source.label, value).success;
    }

    static void resetSource(Context context, Source source) {
        if (source != null && source.userEditable) deleteUserM3uSource(context, source.id);
    }

    static SaveResult saveUserM3uSource(Context context, String editId, String rawName, String rawUrl) {
        String name = normalizeName(rawName);
        if (name.isEmpty()) return SaveResult.error(context.getString(R.string.error_source_name_empty));

        SourceValidator.ValidationResult validation = SourceValidator.validateM3uSource(context, name, rawUrl);
        if (!validation.valid) return SaveResult.error(validation.message);

        List<UserM3uSource> sources = loadUserM3uSources(context);
        int editIndex = -1;
        if (editId != null && !editId.trim().isEmpty()) {
            for (int i = 0; i < sources.size(); i++) {
                if (sources.get(i).id.equals(editId)) {
                    editIndex = i;
                    break;
                }
            }
            if (editIndex < 0) return SaveResult.error(context.getString(R.string.error_source_not_found));
        }

        for (int i = 0; i < sources.size(); i++) {
            if (i == editIndex) continue;
            UserM3uSource existing = sources.get(i);
            if (existing.url.equalsIgnoreCase(validation.normalizedValue)) {
                return SaveResult.error(context.getString(R.string.error_source_url_duplicate));
            }
        }

        String id = editIndex >= 0 ? sources.get(editIndex).id : newSourceId();
        UserM3uSource next = new UserM3uSource(id, name, validation.normalizedValue);
        if (editIndex >= 0) sources.set(editIndex, next);
        else sources.add(next);

        saveUserM3uSources(context, sources);
        LinkedHashSet<String> selected = new LinkedHashSet<>(loadSelectedSet(context));
        selected.add(id);
        saveSelectedSet(context, selected);
        return SaveResult.ok(context.getString(editIndex >= 0 ? R.string.message_source_updated : R.string.message_source_added));
    }

    static SaveResult deleteUserM3uSource(Context context, String sourceId) {
        List<UserM3uSource> sources = loadUserM3uSources(context);
        boolean removed = false;
        for (int i = sources.size() - 1; i >= 0; i--) {
            if (sources.get(i).id.equals(sourceId)) {
                sources.remove(i);
                removed = true;
            }
        }
        if (!removed) return SaveResult.error(context.getString(R.string.error_source_not_found));
        saveUserM3uSources(context, sources);
        LinkedHashSet<String> selected = new LinkedHashSet<>(loadSelectedSet(context));
        selected.remove(sourceId);
        saveSelectedSet(context, selected);
        return SaveResult.ok(context.getString(R.string.message_source_deleted));
    }

    static String selectedUserM3uSourcesConfig(Context context) {
        Set<String> selected = loadSelectedSet(context);
        StringBuilder out = new StringBuilder();
        for (UserM3uSource source : loadUserM3uSources(context)) {
            if (!selected.contains(source.id)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(escapeConfigField(source.name))
                    .append('\t')
                    .append(escapeConfigField(source.url));
        }
        return out.toString();
    }

    static String aioUrl() {
        return aioUrl(BASE_URL);
    }

    static String aioUrl(String baseUrl) {
        return baseUrl + "/aio";
    }

    static String sourceUrl(String id) {
        return sourceUrl(BASE_URL, id);
    }

    static String sourceUrl(String baseUrl, String id) {
        if (CUSTOM_ID.equals(id)) return baseUrl + "/" + CUSTOM_ID;
        return baseUrl + "/" + USER_M3U_PLUGIN_ID;
    }

    static String selectedUrlsText(Context context) {
        Set<String> selected = loadSelectedSet(context);
        StringBuilder out = new StringBuilder();
        out.append("AIO: ").append(aioUrl()).append('\n');
        for (Source source : sources(context)) {
            if (selected.contains(source.id)) {
                out.append(source.label).append(": ").append(sourceUrl(source.id)).append('\n');
            }
        }
        return out.toString().trim();
    }

    static String normalizeSourceValue(Source source, String value) {
        return value == null ? "" : value.trim();
    }

    private static Source customSource(Context context) {
        return new Source(
                CUSTOM_ID,
                context.getString(R.string.source_custom_label),
                context.getString(R.string.source_custom_description),
                SourceFormat.CUSTOM,
                "",
                "",
                false
        );
    }

    private static List<UserM3uSource> loadUserM3uSources(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_USER_M3U_SOURCES, "[]");
        List<UserM3uSource> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw == null || raw.trim().isEmpty() ? "[]" : raw);
            LinkedHashSet<String> seenIds = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = normalizeId(item.optString("id", ""));
                String name = normalizeName(item.optString("name", ""));
                String url = item.optString("url", "").trim();
                if (id.isEmpty() || name.isEmpty() || url.isEmpty()) continue;
                if (!seenIds.add(id)) continue;
                out.add(new UserM3uSource(id, name, url));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveUserM3uSources(Context context, List<UserM3uSource> sources) {
        JSONArray array = new JSONArray();
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        for (UserM3uSource source : sources) {
            String id = normalizeId(source.id);
            String name = normalizeName(source.name);
            String url = source.url == null ? "" : source.url.trim();
            if (id.isEmpty() || name.isEmpty() || url.isEmpty()) continue;
            if (!seenIds.add(id)) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("id", id);
                item.put("name", name);
                item.put("url", url);
                array.put(item);
            } catch (Exception ignored) {
            }
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_USER_M3U_SOURCES, array.toString())
                .commit();
    }

    private static LinkedHashSet<String> normalizeSelection(Context context, String csv) {
        LinkedHashSet<String> known = new LinkedHashSet<>();
        for (UserM3uSource source : loadUserM3uSources(context)) known.add(source.id);
        known.add(CUSTOM_ID);

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (csv == null) return selected;
        String[] parts = csv.split(",");
        for (String raw : parts) {
            String id = normalizeId(raw);
            if (known.contains(id)) selected.add(id);
        }
        return selected;
    }

    private static String setToCsv(Set<String> selected) {
        StringBuilder out = new StringBuilder();
        for (String id : selected) {
            if (id == null || id.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(',');
            out.append(id.trim());
        }
        return out.toString();
    }

    private static String newSourceId() {
        return "m3u_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String normalizeId(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeName(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[\\t\\r\\n]+", " ").replaceAll("\\s+", " ");
    }

    private static String escapeConfigField(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    enum SourceFormat {
        M3U,
        CUSTOM
    }

    static final class Source {
        final String id;
        final String label;
        final String description;
        final SourceFormat format;
        final String defaultSource;
        final String value;
        final boolean userEditable;

        Source(
                String id,
                String label,
                String description,
                SourceFormat format,
                String defaultSource,
                String value,
                boolean userEditable
        ) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.format = format;
            this.defaultSource = defaultSource == null ? "" : defaultSource;
            this.value = value == null ? "" : value;
            this.userEditable = userEditable;
        }
    }

    private static final class UserM3uSource {
        final String id;
        final String name;
        final String url;

        UserM3uSource(String id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }
    }

    static final class SaveResult {
        final boolean success;
        final String message;

        private SaveResult(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }

        static SaveResult ok(String message) {
            return new SaveResult(true, message);
        }

        static SaveResult error(String message) {
            return new SaveResult(false, message);
        }
    }
}
