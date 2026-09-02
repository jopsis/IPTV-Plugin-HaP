package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the bundled kubo (IPFS) binary as its own foreground-service-owned process, mirroring AceServeRuntime. */
final class IpfsRuntime {
    private static final String TAG = "HaP-Ipfs";
    private static final String ABI_ARM64 = "arm64-v8a";
    private static final String ABI_ARM32 = "armeabi-v7a";
    private static final String PREPARED_MARKER = ".prepared-ipfs-v1";

    private final Context context;
    private final String abi;
    private Process process;
    private Thread logThread;
    private volatile boolean stopping;

    IpfsRuntime(Context context) {
        this.context = context.getApplicationContext();
        this.abi = selectSupportedAbi();
    }

    synchronized void prepare() throws IOException {
        if (abi == null) {
            throw new IOException("HaP ships IPFS (kubo) for arm64-v8a and armeabi-v7a. Device ABIs: "
                    + String.join(", ", Build.SUPPORTED_ABIS));
        }
        File repo = repoDir();
        if (!repo.exists() && !repo.mkdirs()) throw new IOException("Cannot create " + repo);
        File marker = new File(repo, PREPARED_MARKER);
        if (!marker.exists()) {
            Log.i(TAG, "prepare abi=" + abi + " repo=" + repo);
            runIpfsCommand(repo, "init", "--profile=lowpower");
            applyFixedConfig(repo);
            if (!marker.createNewFile()) throw new IOException("Cannot create " + marker);
        }
        applyUserConfig(repo);
    }

    synchronized void start() throws IOException {
        if (process != null) return;
        File repo = repoDir();
        clearStaleLock(repo);

        File runner = nativeRunner();
        if (!runner.exists()) throw new IOException("Missing native IPFS runner: " + runner);

        List<String> command = new ArrayList<>();
        command.add(runner.getAbsolutePath());
        command.add("daemon");
        command.add("--migrate=true");
        command.add("--enable-gc");
        Log.i(TAG, "start command=" + command);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repo);
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("IPFS_PATH", repo.getAbsolutePath());
        env.put("HOME", repo.getAbsolutePath());
        stopping = false;
        process = builder.start();
        startLogThread();
    }

    synchronized void stop() {
        if (process == null) return;
        stopping = true;
        process.destroy();
        try {
            if (!process.waitFor(5000, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }

    synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    String abi() {
        return abi;
    }

    File repoDir() {
        return new File(context.getFilesDir(), "ipfs/" + abi);
    }

    private void clearStaleLock(File repo) {
        File lock = new File(repo, "repo.lock");
        if (lock.exists() && !lock.delete()) {
            ServiceState.appendLog("ipfs: could not remove stale repo.lock");
        } else if (lock.exists()) {
            ServiceState.appendLog("ipfs: removed stale repo.lock");
        }
    }

    private void applyFixedConfig(File repo) throws IOException {
        setConfigString(repo, "Addresses.API", "/ip4/" + IpfsSettings.API_HOST + "/tcp/" + IpfsSettings.API_PORT);
        setConfigString(repo, "Routing.Type", "autoclient");
        setConfigJson(repo, "Swarm.ConnMgr.HighWater", "40");
        setConfigJson(repo, "Swarm.ConnMgr.LowWater", "20");
        setConfigJson(repo, "Swarm.RelayService.Enabled", "false");
        setConfigJson(repo, "Swarm.Transports.Network.Websocket", "false");
        setConfigString(repo, "Datastore.GCPeriod", "1h");
        setConfigJson(repo, "Discovery.MDNS.Enabled", "true");
    }

    private void applyUserConfig(File repo) throws IOException {
        String gatewayHost = IpfsSettings.gatewayBindHost(context);
        int gatewayPort = IpfsSettings.gatewayPort(context);
        setConfigString(repo, "Addresses.Gateway", "/ip4/" + gatewayHost + "/tcp/" + gatewayPort);
        setConfigString(repo, "Datastore.StorageMax", IpfsSettings.storageMaxGb(context) + "GB");
    }

    private void setConfigString(File repo, String key, String value) throws IOException {
        runIpfsCommand(repo, "config", key, value);
    }

    private void setConfigJson(File repo, String key, String jsonValue) throws IOException {
        runIpfsCommand(repo, "config", "--json", key, jsonValue);
    }

    private void runIpfsCommand(File repo, String... args) throws IOException {
        File runner = nativeRunner();
        if (!runner.exists()) throw new IOException("Missing native IPFS runner: " + runner);
        List<String> command = new ArrayList<>();
        command.add(runner.getAbsolutePath());
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repo);
        builder.redirectErrorStream(true);
        builder.environment().put("IPFS_PATH", repo.getAbsolutePath());
        builder.environment().put("HOME", repo.getAbsolutePath());

        Process proc = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                ServiceState.appendLog("ipfs: " + line);
            }
        }
        int exit;
        try {
            exit = proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running ipfs " + String.join(" ", args));
        }
        if (exit != 0) {
            throw new IOException("ipfs " + String.join(" ", args) + " failed (exit " + exit + "): " + output);
        }
    }

    private File nativeRunner() {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libipfs.so");
    }

    private void startLogThread() {
        logThread = new Thread(() -> {
            Process current = process;
            if (current == null) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ServiceState.appendLog("ipfs: " + line);
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
                    Log.i(TAG, "ipfs log ended during process stop: " + e);
                } else {
                    ServiceState.appendLog("ipfs log failed: " + e);
                    Log.e(TAG, "ipfs log failed", e);
                }
            }
        }, "ipfs-log");
        logThread.start();
    }

    private static String selectSupportedAbi() {
        for (String supported : Build.SUPPORTED_ABIS) {
            if (ABI_ARM64.equals(supported) || ABI_ARM32.equals(supported)) return supported;
        }
        return null;
    }
}
