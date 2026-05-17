package com.jopsis.httpaceserveproxy;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Resolves the TestCookie-Nginx JavaScript challenge used by some M3U sources.
 * The challenge page returns a 200 with inline JS that performs AES-CBC decryption
 * (via slowAES) to compute the value of a "__test" cookie required for real access.
 * Cookie validity is 6 hours; solved values are cached per hostname.
 */
final class TestCookieChallengeHandler {

    private static final Pattern HEX_PATTERN =
            Pattern.compile("toNumbers\\(\"([a-f0-9]+)\"\\)");
    private static final long COOKIE_TTL_MS = 6L * 60 * 60 * 1000;
    private static final Map<String, CachedCookie> CACHE = new ConcurrentHashMap<>();

    private TestCookieChallengeHandler() {
    }

    static boolean isChallenge(String body) {
        return body != null && body.contains("slowAES");
    }

    /** Returns the solved "__test" cookie value, or null on failure. */
    static String solve(String html) {
        Matcher m = HEX_PATTERN.matcher(html);
        byte[] key = null, iv = null, ct = null;
        if (m.find()) key = fromHex(m.group(1));
        if (m.find()) iv  = fromHex(m.group(1));
        if (m.find()) ct  = fromHex(m.group(1));
        if (key == null || iv == null || ct == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            return toHex(cipher.doFinal(ct));
        } catch (Exception e) {
            return null;
        }
    }

    static String getCachedCookie(String url) {
        String host = host(url);
        if (host == null) return null;
        CachedCookie cc = CACHE.get(host);
        if (cc == null || System.currentTimeMillis() > cc.expiresAt) {
            CACHE.remove(host);
            return null;
        }
        return cc.value;
    }

    static void cacheCookie(String url, String cookieValue) {
        String host = host(url);
        if (host == null || cookieValue == null) return;
        CACHE.put(host, new CachedCookie(cookieValue, System.currentTimeMillis() + COOKIE_TTL_MS));
    }

    /** Appends the ?i=1 redirect parameter expected after setting the challenge cookie. */
    static String appendChallengeParam(String url) {
        return url.contains("?") ? url + "&i=1" : url + "?i=1";
    }

    private static String host(String url) {
        try {
            return new URI(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] fromHex(String hex) {
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    + Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return data;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static final class CachedCookie {
        final String value;
        final long expiresAt;

        CachedCookie(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
