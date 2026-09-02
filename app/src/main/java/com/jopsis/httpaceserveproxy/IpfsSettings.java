package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.content.SharedPreferences;

final class IpfsSettings {
    static final int GATEWAY_PORT_DEFAULT = 8080;
    static final int API_PORT = 5001;
    static final int SWARM_PORT = 4001;
    static final int EXTERNAL_PORT_DEFAULT = 8080;
    static final int STORAGE_MAX_GB_DEFAULT = 2;
    static final String API_HOST = "127.0.0.1";

    enum Mode {
        EMBEDDED,
        EXTERNAL
    }

    private static final String KEY_IPFS_ENABLED = "ipfs_enabled";
    private static final String KEY_IPFS_MODE = "ipfs_mode";
    private static final String KEY_IPFS_GATEWAY_PORT = "ipfs_gateway_port";
    private static final String KEY_IPFS_EXTERNAL_HOST = "ipfs_external_host";
    private static final String KEY_IPFS_EXTERNAL_PORT = "ipfs_external_port";
    private static final String KEY_IPFS_STORAGE_MAX_GB = "ipfs_storage_max_gb";

    private IpfsSettings() {
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_IPFS_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_IPFS_ENABLED, enabled).commit();
    }

    static Mode mode(Context context) {
        return "external".equals(prefs(context).getString(KEY_IPFS_MODE, "embedded")) ? Mode.EXTERNAL : Mode.EMBEDDED;
    }

    static void setMode(Context context, Mode mode) {
        prefs(context).edit().putString(KEY_IPFS_MODE, mode == Mode.EXTERNAL ? "external" : "embedded").commit();
    }

    static int gatewayPort(Context context) {
        return prefs(context).getInt(KEY_IPFS_GATEWAY_PORT, GATEWAY_PORT_DEFAULT);
    }

    static void setGatewayPort(Context context, int port) {
        prefs(context).edit().putInt(KEY_IPFS_GATEWAY_PORT, port).commit();
    }

    static String externalHost(Context context) {
        String host = prefs(context).getString(KEY_IPFS_EXTERNAL_HOST, "");
        return host == null ? "" : host.trim();
    }

    static void setExternalHost(Context context, String host) {
        prefs(context).edit().putString(KEY_IPFS_EXTERNAL_HOST, host == null ? "" : host.trim()).commit();
    }

    static int externalPort(Context context) {
        return prefs(context).getInt(KEY_IPFS_EXTERNAL_PORT, EXTERNAL_PORT_DEFAULT);
    }

    static void setExternalPort(Context context, int port) {
        prefs(context).edit().putInt(KEY_IPFS_EXTERNAL_PORT, port).commit();
    }

    static int storageMaxGb(Context context) {
        return prefs(context).getInt(KEY_IPFS_STORAGE_MAX_GB, STORAGE_MAX_GB_DEFAULT);
    }

    static void setStorageMaxGb(Context context, int gigabytes) {
        prefs(context).edit().putInt(KEY_IPFS_STORAGE_MAX_GB, gigabytes).commit();
    }

    /** Host the embedded gateway should bind to: LAN-wide when the shared "server LAN" switch is on. */
    static String gatewayBindHost(Context context) {
        return ProxyExposure.listenHost(context);
    }

    static String gatewayUrl(Context context) {
        if (mode(context) == Mode.EXTERNAL) {
            String host = externalHost(context);
            if (host.isEmpty()) return "";
            return "http://" + host + ":" + externalPort(context);
        }
        return "http://" + ProxyExposure.LOCAL_HOST + ":" + gatewayPort(context);
    }

    static String castGatewayUrl(Context context) {
        if (mode(context) == Mode.EXTERNAL) return gatewayUrl(context);
        for (ProxyExposure.Endpoint endpoint : ProxyExposure.endpoints(context)) {
            if (!ProxyExposure.LOCAL_HOST.equals(endpoint.host)) {
                return "http://" + endpoint.host + ":" + gatewayPort(context);
            }
        }
        return "";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PlaylistCatalog.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
