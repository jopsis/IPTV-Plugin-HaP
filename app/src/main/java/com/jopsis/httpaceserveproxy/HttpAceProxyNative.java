package com.jopsis.httpaceserveproxy;

final class HttpAceProxyNative {
    static {
        System.loadLibrary("httpaceproxy_jni");
    }

    private HttpAceProxyNative() {
    }

    static void initHttpBridge() {
        nativeInitHttpBridge(NativeHttpBridge.class);
    }

    static void start(
            String aceHost,
            int aceApiPort,
            int aceHttpPort,
            String httpHost,
            int httpPort,
            String rootDir,
            String enabledPlugins,
            String aioPlugins,
            String userM3uSources,
            String customPlaylistPath,
            int maxConnections,
            int maxConcurrentChannels) {
        nativeStart(
                aceHost,
                aceApiPort,
                aceHttpPort,
                httpHost,
                httpPort,
                rootDir,
                enabledPlugins,
                aioPlugins,
                userM3uSources,
                customPlaylistPath,
                maxConnections,
                maxConcurrentChannels);
    }

    static native void nativeInitHttpBridge(Class<?> bridgeClass);

    static native void nativeStart(
            String aceHost,
            int aceApiPort,
            int aceHttpPort,
            String httpHost,
            int httpPort,
            String rootDir,
            String enabledPlugins,
            String aioPlugins,
            String userM3uSources,
            String customPlaylistPath,
            int maxConnections,
            int maxConcurrentChannels);

    static native void nativeStop();

    static native boolean nativeIsRunning();
}
