package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.content.SharedPreferences;

import com.streamvault.plugin.hap.R;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;

final class ProxyExposure {
    static final int PROXY_PORT = 8888;
    static final String LOCAL_HOST = "127.0.0.1";
    static final String ANY_HOST = "0.0.0.0";

    private static final String KEY_SERVER_MODE_LAN = "server_mode_lan";

    private ProxyExposure() {
    }

    static boolean isServerModeEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PlaylistCatalog.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_SERVER_MODE_LAN, false);
    }

    static void setServerModeEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PlaylistCatalog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SERVER_MODE_LAN, enabled)
                .commit();
    }

    static String listenHost(Context context) {
        return isServerModeEnabled(context) ? ANY_HOST : LOCAL_HOST;
    }

    static String baseUrl(String host) {
        return "http://" + host + ":" + PROXY_PORT;
    }

    static String localBaseUrl() {
        return baseUrl(LOCAL_HOST);
    }

    static List<Endpoint> endpoints(Context context) {
        List<Endpoint> endpoints = new ArrayList<>();
        endpoints.add(new Endpoint("Local", LOCAL_HOST, localBaseUrl()));
        if (!isServerModeEnabled(context)) return endpoints;

        for (LanAddress address : lanAddresses()) {
            endpoints.add(new Endpoint(address.label, address.host, baseUrl(address.host)));
        }
        return endpoints;
    }

    static String notificationText(Context context) {
        if (!isServerModeEnabled(context)) {
            return context.getString(R.string.status_running_on, LOCAL_HOST + ":" + PROXY_PORT);
        }
        List<Endpoint> endpoints = endpoints(context);
        if (endpoints.size() > 1) {
            return context.getString(R.string.status_running_on, endpoints.get(1).host + ":" + PROXY_PORT);
        }
        return context.getString(R.string.status_running_on_lan, PROXY_PORT);
    }

    private static List<LanAddress> lanAddresses() {
        List<LanAddress> out = new ArrayList<>();
        LinkedHashSet<String> seenHosts = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return out;
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!isUsableInterface(networkInterface)) continue;

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!isUsableLanAddress(address)) continue;
                    String host = address.getHostAddress();
                    if (host == null || host.trim().isEmpty() || !seenHosts.add(host)) continue;
                    out.add(new LanAddress(labelFor(networkInterface), host));
                }
            }
        } catch (SocketException ignored) {
        }
        return out;
    }

    private static boolean isUsableInterface(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp()
                    && !networkInterface.isLoopback()
                    && !networkInterface.isVirtual();
        } catch (SocketException e) {
            return false;
        }
    }

    private static boolean isUsableLanAddress(InetAddress address) {
        return address instanceof Inet4Address
                && address.isSiteLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isMulticastAddress()
                && !address.isAnyLocalAddress();
    }

    private static String labelFor(NetworkInterface networkInterface) {
        String name = networkInterface.getName();
        if (name == null) name = "";
        String lowerName = name.toLowerCase();
        if (lowerName.startsWith("wlan") || lowerName.startsWith("wifi")) return "Wi-Fi";
        if (lowerName.startsWith("eth")) return "Ethernet";
        if (lowerName.startsWith("tun")) return "VPN";
        return name.isEmpty() ? "LAN" : "LAN " + name;
    }

    static final class Endpoint {
        final String label;
        final String host;
        final String baseUrl;

        Endpoint(String label, String host, String baseUrl) {
            this.label = label;
            this.host = host;
            this.baseUrl = baseUrl;
        }

        @Override
        public String toString() {
            return label + " - " + baseUrl;
        }
    }

    private static final class LanAddress {
        final String label;
        final String host;

        LanAddress(String label, String host) {
            this.label = label;
            this.host = host;
        }
    }
}
