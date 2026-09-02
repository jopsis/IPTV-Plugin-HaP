package com.jopsis.httpaceserveproxy;

final class ServiceState {
    static volatile boolean desiredRunning;
    static volatile boolean aceRunning;
    static volatile boolean proxyRunning;
    static volatile String phase = "Idle";
    static volatile String lastError = "";
    static volatile long lastUpdateMillis;

    static volatile boolean ipfsDesiredRunning;
    static volatile boolean ipfsRunning;
    static volatile String ipfsPhase = "Stopped";
    static volatile String ipfsLastError = "";

    private static final StringBuilder LOG = new StringBuilder();

    private ServiceState() {
    }

    static synchronized void phase(String value) {
        phase = value;
        lastUpdateMillis = System.currentTimeMillis();
        appendLog("phase: " + value);
    }

    static synchronized void error(String value) {
        lastError = value == null ? "" : value;
        lastUpdateMillis = System.currentTimeMillis();
        appendLog("error: " + lastError);
    }

    static synchronized void ipfsPhase(String value) {
        ipfsPhase = value;
        lastUpdateMillis = System.currentTimeMillis();
        appendLog("ipfs phase: " + value);
    }

    static synchronized void ipfsError(String value) {
        ipfsLastError = value == null ? "" : value;
        lastUpdateMillis = System.currentTimeMillis();
        appendLog("ipfs error: " + ipfsLastError);
    }

    static synchronized void appendLog(String value) {
        if (value == null || value.isEmpty()) return;
        LOG.append(value).append('\n');
        if (LOG.length() > 12000) LOG.delete(0, LOG.length() - 12000);
    }

    static synchronized String log() {
        return LOG.toString();
    }
}

