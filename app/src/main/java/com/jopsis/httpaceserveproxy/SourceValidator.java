package com.jopsis.httpaceserveproxy;

import android.content.Context;

import com.streamvault.plugin.hap.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

final class SourceValidator {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final String BROWSER_ONLY_IPFS_GATEWAY_HOST = "inbrowser.link";
    private static final String DWEB_GATEWAY_HOST = "dweb.link";
    private static final String DIRECT_IPFS_GATEWAY_BASE = "https://ipfs.io";
    private static final Pattern BARE_HASH = Pattern.compile("^[A-Fa-f0-9]{40}$");

    private SourceValidator() {
    }

    static ValidationResult validateM3uSource(Context context, String label, String rawUrl) {
        String normalized = rawUrl == null ? "" : rawUrl.trim();
        if (normalized.isEmpty()) {
            return ValidationResult.error(normalized, context.getString(R.string.error_source_url_empty));
        }
        if (!isHttpUrl(normalized)) {
            return ValidationResult.error(normalized, context.getString(R.string.error_source_url_http));
        }
        if (isBrowserOnlyIpfsGateway(normalized)) {
            return ValidationResult.error(normalized, context.getString(R.string.error_source_not_m3u, label));
        }

        try {
            validateM3u(context, fetch(context, normalized), label);
            return ValidationResult.ok(normalized);
        } catch (Exception e) {
            if (e instanceof IOException) {
                ValidationResult fallback = validateFallbackGateway(context, label, normalized);
                if (fallback.valid) return fallback;
            }
            String message = e.getMessage();
            return ValidationResult.error(
                    normalized,
                    message == null || message.isEmpty() ? e.toString() : message
            );
        }
    }

    private static ValidationResult validateFallbackGateway(Context context, String label, String normalized) {
        String fallbackUrl = directDwebGatewayUrl(normalized);
        if (fallbackUrl.isEmpty() || fallbackUrl.equals(normalized)) return ValidationResult.error(normalized, "");
        try {
            validateM3u(context, fetch(context, fallbackUrl), label);
            return ValidationResult.ok(fallbackUrl);
        } catch (Exception ignored) {
            return ValidationResult.error(normalized, "");
        }
    }

    private static void validateM3u(Context context, String body, String label) {
        String[] lines = body.split("\\r?\\n");
        boolean sawHeader = false;
        boolean sawExtinf = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!sawHeader && line.startsWith("\uFEFF")) line = line.substring(1).trim();
            if (line.isEmpty()) continue;
            if (!sawHeader) {
                if (!line.startsWith("#EXTM3U")) {
                    throw new IllegalArgumentException(context.getString(R.string.error_source_not_m3u, label));
                }
                sawHeader = true;
                continue;
            }
            if (!line.startsWith("#EXTINF:")) continue;
            sawExtinf = true;

            for (int urlIndex = i + 1; urlIndex < lines.length; urlIndex++) {
                String candidate = lines[urlIndex].trim();
                if (candidate.isEmpty()) continue;
                if (candidate.startsWith("#EXTINF:")) break;
                if (candidate.startsWith("#")) continue;
                if (isAceStreamUrl(candidate)) return;
                break;
            }
        }

        if (!sawHeader) throw new IllegalArgumentException(context.getString(R.string.error_source_header_m3u, label));
        if (!sawExtinf) throw new IllegalArgumentException(context.getString(R.string.error_source_empty_m3u, label));
        throw new IllegalArgumentException(context.getString(R.string.error_source_no_acestream, label));
    }

    private static boolean isHttpUrl(String value) {
        try {
            URL url = new URL(value);
            String scheme = url.getProtocol().toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme))
                    && url.getHost() != null
                    && !url.getHost().trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAceStreamUrl(String value) {
        String url = value.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("acestream://") || lower.startsWith("infohash://")) return true;
        if (BARE_HASH.matcher(url).matches()) return true;

        try {
            URL parsed = new URL(url);
            String scheme = parsed.getProtocol().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
            String path = parsed.getPath() == null ? "" : parsed.getPath().toLowerCase(Locale.ROOT);
            if (path.endsWith(".acelive")
                    || path.endsWith(".acestream")
                    || path.endsWith(".acemedia")
                    || path.endsWith(".torrent")) {
                return true;
            }

            String query = parsed.getQuery();
            if (query == null || query.isEmpty()) return false;
            for (String part : query.split("&")) {
                int equals = part.indexOf('=');
                String key = equals >= 0 ? part.substring(0, equals) : part;
                String queryValue = equals >= 0 ? part.substring(equals + 1) : "";
                String lowerKey = key.toLowerCase(Locale.ROOT);
                if ("id".equals(lowerKey)
                        || "infohash".equals(lowerKey)
                        || "content_id".equals(lowerKey)) {
                    return !queryValue.trim().isEmpty();
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean isBrowserOnlyIpfsGateway(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) return false;
            String lowerHost = host.toLowerCase(Locale.ROOT);
            return BROWSER_ONLY_IPFS_GATEWAY_HOST.equals(lowerHost)
                    || lowerHost.endsWith("." + BROWSER_ONLY_IPFS_GATEWAY_HOST);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String directDwebGatewayUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return "";

            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) return "";

            String lowerHost = host.toLowerCase(Locale.ROOT);
            String suffix = "." + DWEB_GATEWAY_HOST;
            if (!lowerHost.endsWith(suffix)) return "";

            String prefix = host.substring(0, host.length() - suffix.length());
            String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
            String namespace;
            String identifier;
            if (lowerPrefix.endsWith(".ipns")) {
                namespace = "ipns";
                identifier = prefix.substring(0, prefix.length() - ".ipns".length());
            } else if (lowerPrefix.endsWith(".ipfs")) {
                namespace = "ipfs";
                identifier = prefix.substring(0, prefix.length() - ".ipfs".length());
            } else {
                return "";
            }

            identifier = identifier.trim();
            if (identifier.isEmpty()) return "";

            String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery() == null || uri.getRawQuery().isEmpty() ? "" : "?" + uri.getRawQuery();
            return DIRECT_IPFS_GATEWAY_BASE + "/" + namespace + "/" + identifier + rawPath + query;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String fetch(Context context, String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (HaP Source Validator)");
            connection.setInstanceFollowRedirects(true);

            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readAll(context, input);
            if (status < 200 || status >= 300) {
                throw new IOException(context.getString(R.string.error_source_http_status, status));
            }
            if (body.trim().isEmpty()) throw new IOException(context.getString(R.string.error_source_empty_response));
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(Context context, InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        char[] buffer = new char[4096];
        int total = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    throw new IOException(context.getString(R.string.error_source_too_large));
                }
                out.append(buffer, 0, read);
            }
        }
        return out.toString();
    }

    static final class ValidationResult {
        final boolean valid;
        final String normalizedValue;
        final String message;

        private ValidationResult(boolean valid, String normalizedValue, String message) {
            this.valid = valid;
            this.normalizedValue = normalizedValue;
            this.message = message == null ? "" : message;
        }

        static ValidationResult ok(String normalizedValue) {
            return new ValidationResult(true, normalizedValue, "");
        }

        static ValidationResult error(String normalizedValue, String message) {
            return new ValidationResult(false, normalizedValue, message);
        }
    }
}
