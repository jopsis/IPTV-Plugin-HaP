package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class AceServeRuntime {
    private static final String TAG = "HaP-AceServe";
    private static final String ABI_ARM64 = "arm64-v8a";
    private static final String ABI_ARM32 = "armeabi-v7a";

    private final Context context;
    private final String abi;
    private Process process;
    private Thread logThread;
    private volatile boolean stopping;

    AceServeRuntime(Context context) {
        this.context = context.getApplicationContext();
        this.abi = selectSupportedAbi();
    }

    synchronized void prepare() throws IOException {
        if (abi == null) {
            throw new IOException("HaP ships AceServe for arm64-v8a and armeabi-v7a. Device ABIs: "
                    + String.join(", ", Build.SUPPORTED_ABIS));
        }
        Log.i(TAG, "prepare abi=" + abi + " supported=" + String.join(",", Build.SUPPORTED_ABIS));
        long pageSize = pageSize();
        if (pageSize > 4096) {
            throw new IOException("Bundled AceServe runtime requires a 4 KB Android system image. "
                    + "Current page size is " + pageSize + " bytes; use a non-16 KB AVD or rebuild AceServe native libraries for 16 KB pages.");
        }
        cleanupForeignAbiDirs();
        File root = rootDir();
        File marker = new File(root, ".prepared-ace-" + abi + "-v4");
        if (!marker.exists()) {
            deleteRecursively(root);
            if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Cannot create " + root);
            File zip = new File(context.getCacheDir(), zipName());
            AssetCopier.copyFile(context, "aceserve/" + abi + "/" + zipName(), zip);
            unzip(zip, root);
            if (!zip.delete()) ServiceState.appendLog("warning: could not delete " + zip);
            if (!marker.createNewFile()) throw new IOException("Cannot create " + marker);
        }
        AssetCopier.copyFile(context, "aceserve/main_android.py", new File(root, "main_android.py"));
        deletePythonBridge(root);
    }

    synchronized void start() throws IOException {
        if (process != null) return;
        File root = rootDir();
        File cache = cacheDir();
        if (!cache.exists() && !cache.mkdirs()) throw new IOException("Cannot create " + cache);
        File androidInfo = new File(root, "android-runtime.json");
        AceServeAndroidInfo.write(context, abi, root, cache, androidInfo);

        File runner = nativeRunner();
        if (!runner.exists()) throw new IOException("Missing native Python runner: " + runner);

        List<String> command = new ArrayList<>();
        command.add(runner.getAbsolutePath());
        command.add(new File(root, "main_android.py").getAbsolutePath());
        command.add("--bind-all");
        command.add("--live-cache-type");
        command.add("memory");
        command.add("--live-mem-cache-size");
        command.add("104857600");
        command.add("--disable-sentry");
        command.add("--log-stdout");
        command.add("--disable-upnp");
        Log.i(TAG, "start command=" + command);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root);
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("ACE_ROOT", root.getAbsolutePath());
        env.put("ACE_CACHE_DIR", cache.getAbsolutePath());
        env.put("ACE_ANDROID_INFO", androidInfo.getAbsolutePath());
        env.put("ACESTREAM_HOME", root.getAbsolutePath());
        env.put("ANDROID_ROOT", "/system");
        env.put("ANDROID_DATA", new File(root, "android-data").getAbsolutePath());
        env.put("PYTHONHOME", new File(root, "python").getAbsolutePath());
        env.put("PYTHONPATH", pythonPath(root));
        env.put("LD_LIBRARY_PATH", ldLibraryPath(root));
        env.put("FROZENLIST_NO_EXTENSIONS", "1");
        env.put("MULTIDICT_NO_EXTENSIONS", "1");
        env.put("YARL_NO_EXTENSIONS", "1");
        env.put("TEMP", new File(root, "tmp").getAbsolutePath());
        env.put("PATH", new File(root, "python/bin").getAbsolutePath() + ":/system/bin");
        Log.i(TAG, "env ACE_ROOT=" + env.get("ACE_ROOT"));
        Log.i(TAG, "env ACE_CACHE_DIR=" + env.get("ACE_CACHE_DIR"));
        Log.i(TAG, "env ACE_ANDROID_INFO=" + env.get("ACE_ANDROID_INFO"));
        Log.i(TAG, "env LD_LIBRARY_PATH=" + env.get("LD_LIBRARY_PATH"));
        stopping = false;
        process = builder.start();
        startLogThread();
    }

    synchronized void stop() {
        if (process == null) return;
        stopping = true;
        process.destroy();
        try {
            if (!process.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }

    File rootDir() {
        return new File(context.getFilesDir(), "aceserve/" + abi);
    }

    private void cleanupForeignAbiDirs() {
        File parent = new File(context.getFilesDir(), "aceserve");
        File[] children = parent.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            if (abi.equals(name)) continue;
            if (!ABI_ARM64.equals(name) && !ABI_ARM32.equals(name)) continue;
            Log.i(TAG, "removing stale ABI dir " + child);
            ServiceState.appendLog("removing stale ABI dir " + name);
            deleteRecursively(child);
        }
    }

    File cacheDir() {
        return new File(context.getCacheDir(), "aceserve");
    }

    synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    private File nativeRunner() {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libacepython.so");
    }

    private static long pageSize() {
        long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
        return pageSize > 0 ? pageSize : 4096;
    }

    private void startLogThread() {
        logThread = new Thread(() -> {
            Process current = process;
            if (current == null) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ServiceState.appendLog("ace: " + line);
                    Log.i(TAG, line);
                }
                try {
                    Log.i(TAG, "process output ended exit=" + current.waitFor());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "interrupted while waiting for process exit");
                }
            } catch (IOException e) {
                if (stopping || !current.isAlive()) {
                    Log.i(TAG, "ace log ended during process stop: " + e);
                } else {
                    ServiceState.appendLog("ace log failed: " + e);
                    Log.e(TAG, "ace log failed", e);
                }
            }
        }, "aceserve-log");
        logThread.start();
    }

    private static String selectSupportedAbi() {
        // Match the bitness of the running app process: a 32-bit libacepython.so
        // cannot dlopen a 64-bit libpython, and vice versa.
        String preferred = android.os.Process.is64Bit() ? ABI_ARM64 : ABI_ARM32;
        for (String abi : Build.SUPPORTED_ABIS) {
            if (preferred.equals(abi)) return preferred;
        }
        return null;
    }

    private String zipName() {
        return ABI_ARM32.equals(abi) ? "ace-armeabi-v7a.zip" : "ace-arm64-v8a.zip";
    }

    private static String pythonPath(File root) {
        return new File(root, "python/lib/stdlib").getAbsolutePath()
                + ":" + new File(root, "python/lib/modules").getAbsolutePath()
                + ":" + new File(root, "data").getAbsolutePath()
                + ":" + new File(root, "modules.zip").getAbsolutePath()
                + ":" + new File(root, "eggs-unpacked").getAbsolutePath()
                + ":" + new File(root, "lib").getAbsolutePath();
    }

    private String ldLibraryPath(File root) {
        return new File(root, "python/lib").getAbsolutePath()
                + ":" + new File(root, "lib").getAbsolutePath()
                + ":" + new File(root, "acestreamengine").getAbsolutePath()
                + ":" + context.getApplicationInfo().nativeLibraryDir
                + ":/system/lib64:/system/lib";
    }

    private static void unzip(File zip, File destination) throws IOException {
        String destinationPath = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[128 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                File target = new File(destination, entry.getName());
                String targetPath = target.getCanonicalPath();
                if (!targetPath.startsWith(destinationPath)) throw new IOException("Unsafe zip entry " + entry.getName());
                if (entry.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) throw new IOException("Cannot create " + target);
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
                    try (FileOutputStream output = new FileOutputStream(target)) {
                        int read;
                        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete()) ServiceState.appendLog("warning: could not delete " + file);
    }

    private static void deletePythonBridge(File root) {
        deleteIfExists(new File(root, "app_bridge.py"));
        File pycache = new File(root, "__pycache__");
        File[] cached = pycache.listFiles();
        if (cached == null) return;
        for (File file : cached) {
            if (file.getName().startsWith("app_bridge.")) deleteIfExists(file);
        }
    }

    private static void deleteIfExists(File file) {
        if (file.exists() && !file.delete()) {
            ServiceState.appendLog("warning: could not delete " + file);
        }
    }
}
