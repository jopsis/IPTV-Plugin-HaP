package com.jopsis.httpaceserveproxy;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

final class AceServeAndroidInfo {
    private static final String PREFS_NAME = "aceserve_android_info";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String ACE_COMPAT_APP_VERSION_CODE = "302131302";

    private AceServeAndroidInfo() {
    }

    static void write(Context context, String abi, File root, File cache, File output) throws IOException {
        try {
            JSONObject info = build(context.getApplicationContext(), abi, root, cache);
            File parent = output.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Cannot create " + parent);
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(output), StandardCharsets.UTF_8)) {
                writer.write(info.toString());
                writer.write('\n');
            }
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Cannot write Android runtime info", e);
        }
    }

    private static JSONObject build(Context context, String abi, File root, File cache) throws Exception {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) activityManager.getMemoryInfo(memoryInfo);
        int memoryClass = activityManager == null ? 64 : activityManager.getMemoryClass();
        StatFs cacheStats = new StatFs(cache.getAbsolutePath());
        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        Locale locale = Locale.getDefault();

        JSONObject paths = new JSONObject()
                .put("aceStreamHome", cache.getAbsolutePath())
                .put("cacheDir", cache.getAbsolutePath())
                .put("logsDir", cache.getAbsolutePath())
                .put("androidDataDir", new File(root, "android-data").getAbsolutePath())
                .put("tempDir", new File(root, "tmp").getAbsolutePath());

        JSONObject memory = new JSONObject()
                .put("totalBytes", memoryInfo.totalMem > 0 ? memoryInfo.totalMem : memoryClass * 1024L * 1024L)
                .put("availableBytes", memoryInfo.availMem)
                .put("javaMaxBytes", Runtime.getRuntime().maxMemory())
                .put("memoryClassMb", memoryClass)
                .put("lowMemory", memoryInfo.lowMemory);

        JSONObject storage = new JSONObject()
                .put("cacheAvailableBytes", cacheStats.getAvailableBytes())
                .put("cacheTotalBytes", cacheStats.getTotalBytes())
                .put("cacheBlockSizeBytes", cacheStats.getBlockSizeLong())
                .put("cacheBlockCount", cacheStats.getBlockCountLong())
                .put("cacheAvailableBlocks", cacheStats.getAvailableBlocksLong());

        JSONObject device = new JSONObject()
                .put("deviceId", deviceId(context))
                .put("appId", context.getPackageName())
                .put("arch", abi)
                .put("deviceAbi", abi)
                .put("supportedAbis", String.join(",", Build.SUPPORTED_ABIS))
                .put("manufacturer", safe(Build.MANUFACTURER, "Android"))
                .put("model", safe(Build.MODEL, "Android"))
                .put("deviceName", safe(Build.DEVICE, "Android"))
                .put("productName", safe(Build.PRODUCT, "Android"))
                .put("androidRelease", safe(Build.VERSION.RELEASE, ""))
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("displayLanguage", locale.getLanguage())
                .put("locale", locale.toLanguageTag())
                .put("isAndroidTv", isAndroidTv(context))
                .put("hasBrowser", hasBrowser(context))
                .put("hasWebView", hasWebView());

        JSONObject app = new JSONObject()
                .put("packageName", context.getPackageName())
                .put("versionCode", versionCode(packageInfo))
                .put("versionName", safe(packageInfo.versionName, ""))
                .put("aceCompatVersionCode", ACE_COMPAT_APP_VERSION_CODE);

        return new JSONObject()
                .put("paths", paths)
                .put("memory", memory)
                .put("storage", storage)
                .put("device", device)
                .put("app", app);
    }

    private static String deviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_DEVICE_ID, null);
        if (current != null && !current.trim().isEmpty()) return current;
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, created).apply();
        return created;
    }

    private static boolean isAndroidTv(Context context) {
        UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        int uiMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private static boolean hasBrowser(Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1/"));
        return intent.resolveActivity(context.getPackageManager()) != null;
    }

    private static boolean hasWebView() {
        try {
            return WebView.getCurrentWebViewPackage() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long versionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= 28) return packageInfo.getLongVersionCode();
        return packageInfo.versionCode;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
