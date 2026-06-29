package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.streamvault.plugin.hap.R;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class HapBridge {
    public static final String AIO_PROVIDER_NAME = "HaP AIO";
    public static final String LOCAL_BASE_URL = ProxyExposure.localBaseUrl();
    public static final String LOCAL_AIO_URL = PlaylistCatalog.aioUrl();
    public static final String LOCAL_AIO_EPG_URL = PlaylistCatalog.aioEpgUrl();
    public static final int PROXY_PORT = ProxyExposure.PROXY_PORT;

    private HapBridge() {
    }

    public static void start(Context context) {
        serviceAction(context, ProxySupervisorService.ACTION_START);
    }

    public static void stop(Context context) {
        serviceAction(context, ProxySupervisorService.ACTION_STOP);
    }

    public static void restart(Context context) {
        serviceAction(context, ProxySupervisorService.ACTION_RESTART);
    }

    public static void restartProxy(Context context) {
        serviceAction(context, ProxySupervisorService.ACTION_RESTART_PROXY);
    }

    public static void restartProxyIfRunning(Context context) {
        if (ServiceState.desiredRunning) restartProxy(context);
    }

    private static void serviceAction(Context context, String action) {
        Intent intent = new Intent(context.getApplicationContext(), ProxySupervisorService.class)
                .setAction(action);
        ContextCompat.startForegroundService(context.getApplicationContext(), intent);
    }

    public static HapStatus status(Context context) {
        return new HapStatus(
                ServiceState.desiredRunning,
                ServiceState.aceRunning,
                ServiceState.proxyRunning,
                ServiceState.phase,
                ServiceState.lastError,
                ServiceState.lastUpdateMillis,
                ProxyExposure.isServerModeEnabled(context),
                ProxyExposure.isExternalPlayerServerEnabled(context),
                endpoints(context),
                ServiceState.log()
        );
    }

    public static boolean waitForProxyReady(long timeoutMillis) {
        return HealthClient.waitForTcp(ProxyExposure.LOCAL_HOST, ProxyExposure.PROXY_PORT, timeoutMillis);
    }

    public static boolean isServerModeEnabled(Context context) {
        return ProxyExposure.isServerModeEnabled(context);
    }

    public static void setServerModeEnabled(Context context, boolean enabled) {
        ProxyExposure.setServerModeEnabled(context, enabled);
        restartProxyIfRunning(context);
    }

    public static boolean isExternalPlayerServerEnabled(Context context) {
        return ProxyExposure.isExternalPlayerServerEnabled(context);
    }

    public static void setExternalPlayerServerEnabled(Context context, boolean enabled) {
        ProxyExposure.setExternalPlayerServerEnabled(context, enabled);
    }

    public static void enableExternalPlayerServer(Context context) {
        ProxyExposure.setExternalPlayerServerEnabled(context, true);
        if (!ProxyExposure.isServerModeEnabled(context)) {
            ProxyExposure.setServerModeEnabled(context, true);
            restartProxyIfRunning(context);
        }
        start(context);
    }

    public static void disableExternalPlayerServer(Context context) {
        ProxyExposure.setExternalPlayerServerEnabled(context, false);
        if (ProxyExposure.isServerModeEnabled(context)) {
            ProxyExposure.setServerModeEnabled(context, false);
            restartProxyIfRunning(context);
        }
    }

    public static List<EndpointInfo> endpoints(Context context) {
        List<EndpointInfo> out = new ArrayList<>();
        for (ProxyExposure.Endpoint endpoint : ProxyExposure.endpoints(context)) {
            out.add(new EndpointInfo(endpoint.label, endpoint.host, endpoint.baseUrl));
        }
        return out;
    }

    public static String bestLanBaseUrl(Context context) {
        for (EndpointInfo endpoint : endpoints(context)) {
            if (!ProxyExposure.LOCAL_HOST.equals(endpoint.host)) return endpoint.baseUrl;
        }
        return "";
    }

    public static String aioUrl(Context context) {
        return LOCAL_AIO_URL;
    }

    public static String castAioUrl(Context context) {
        String lanBase = bestLanBaseUrl(context);
        return lanBase.isEmpty() ? "" : PlaylistCatalog.aioUrl(lanBase);
    }

    public static String castAioEpgUrl(Context context) {
        String lanBase = bestLanBaseUrl(context);
        return lanBase.isEmpty() ? "" : PlaylistCatalog.aioEpgUrl(lanBase);
    }

    public static boolean isLocalHapUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            int port = uri.getPort();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && port == ProxyExposure.PROXY_PORT
                    && (ProxyExposure.LOCAL_HOST.equals(host) || "localhost".equalsIgnoreCase(host));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isCastableHapUrl(Context context, String url) {
        return !isLocalHapUrl(url) || !bestLanBaseUrl(context).isEmpty();
    }

    public static String rewriteLocalUrlForLan(Context context, String url) {
        if (!isLocalHapUrl(url)) return url;
        String lanBase = bestLanBaseUrl(context);
        if (lanBase.isEmpty()) return url;
        try {
            URI uri = URI.create(url.trim());
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment();
            return lanBase + path + query + fragment;
        } catch (Exception ignored) {
            return url;
        }
    }

    public static List<SourceInfo> sources(Context context) {
        Set<String> selected = PlaylistCatalog.loadSelectedSet(context);
        List<SourceInfo> out = new ArrayList<>();
        for (PlaylistCatalog.Source source : PlaylistCatalog.sources(context)) {
            out.add(new SourceInfo(
                    source.id,
                    source.label,
                    source.description,
                    source.format.name(),
                    source.defaultSource,
                    source.value,
                    PlaylistCatalog.sourceUrl(source.id),
                    selected.contains(source.id),
                    PlaylistCatalog.hasCustomSource(context, source),
                    source.userEditable
            ));
        }
        return out;
    }

    public static void saveSelectedSources(Context context, Set<String> selected) {
        PlaylistCatalog.saveSelectedSet(context, new LinkedHashSet<>(selected));
        restartProxyIfRunning(context);
    }

    public static void setSourceSelected(Context context, String sourceId, boolean selected) {
        LinkedHashSet<String> current = new LinkedHashSet<>(PlaylistCatalog.loadSelectedSet(context));
        if (selected) current.add(sourceId);
        else current.remove(sourceId);
        saveSelectedSources(context, current);
    }

    public static SaveResult saveSource(Context context, String sourceId, String rawValue) {
        PlaylistCatalog.Source source = PlaylistCatalog.sourceFor(context, sourceId);
        if (source == null) return new SaveResult(false, context.getString(R.string.error_unknown_source));
        if (source.format == PlaylistCatalog.SourceFormat.CUSTOM) {
            return new SaveResult(false, context.getString(R.string.error_custom_managed_in_custom));
        }
        PlaylistCatalog.SaveResult result = PlaylistCatalog.saveUserM3uSource(context, sourceId, source.label, rawValue);
        if (result.success) restartProxyIfRunning(context);
        return new SaveResult(result.success, result.message);
    }

    public static SaveResult saveM3uSource(Context context, String editId, String name, String url) {
        PlaylistCatalog.SaveResult result = PlaylistCatalog.saveUserM3uSource(context, editId, name, url);
        if (result.success) restartProxyIfRunning(context);
        return new SaveResult(result.success, result.message);
    }

    public static SaveResult deleteM3uSource(Context context, String sourceId) {
        PlaylistCatalog.SaveResult result = PlaylistCatalog.deleteUserM3uSource(context, sourceId);
        if (result.success) restartProxyIfRunning(context);
        return new SaveResult(result.success, result.message);
    }

    public static SaveResult resetSource(Context context, String sourceId) {
        PlaylistCatalog.Source source = PlaylistCatalog.sourceFor(context, sourceId);
        if (source == null) return new SaveResult(false, context.getString(R.string.error_unknown_source));
        PlaylistCatalog.resetSource(context, source);
        restartProxyIfRunning(context);
        return new SaveResult(true, context.getString(R.string.message_source_deleted));
    }

    public static List<CustomChannelInfo> customChannels(Context context) {
        List<CustomChannelInfo> out = new ArrayList<>();
        List<CustomPlaylistCatalog.Channel> channels = CustomPlaylistCatalog.load(context);
        for (int i = 0; i < channels.size(); i++) {
            CustomPlaylistCatalog.Channel channel = channels.get(i);
            out.add(new CustomChannelInfo(i, channel.name, channel.contentId));
        }
        return out;
    }

    public static SaveResult saveCustomChannel(Context context, int editIndex, String name, String contentId) {
        CustomPlaylistCatalog.SaveResult result =
                CustomPlaylistCatalog.saveChannel(context, editIndex, name, contentId);
        if (result.success) restartProxyIfRunning(context);
        return new SaveResult(result.success, result.message);
    }

    public static SaveResult deleteCustomChannel(Context context, int index) {
        CustomPlaylistCatalog.SaveResult result = CustomPlaylistCatalog.deleteChannel(context, index);
        if (result.success) restartProxyIfRunning(context);
        return new SaveResult(result.success, result.message);
    }

    public static List<ConnectedClientInfo> connectedClients() throws Exception {
        List<ConnectedClientInfo> out = new ArrayList<>();
        for (ConnectedClientsClient.Client client : ConnectedClientsClient.load()) {
            out.add(new ConnectedClientInfo(client.ip, client.device, client.channel, client.megabytes));
        }
        return out;
    }

    public static List<PluginChannelsInfo> channelPlugins(Context context) throws Exception {
        List<PluginChannelsInfo> out = new ArrayList<>();
        for (ChannelStatusClient.PluginChannels plugin : ChannelStatusClient.loadPlugins(context)) {
            List<ChannelInfo> channels = new ArrayList<>();
            for (ChannelStatusClient.Channel channel : plugin.channels) {
                channels.add(new ChannelInfo(channel.name, channel.contentId));
            }
            out.add(new PluginChannelsInfo(plugin.id, plugin.label, channels));
        }
        return out;
    }

    public static PeerResultInfo checkPeers(String contentId, int maxWaitSeconds) throws Exception {
        ChannelStatusClient.PeerResult result = ChannelStatusClient.checkPeers(contentId, maxWaitSeconds);
        return new PeerResultInfo(
                result.success,
                result.available,
                result.peers,
                result.httpPeers,
                result.totalPeers,
                result.statusText,
                result.error
        );
    }

    public static final class HapStatus {
        public final boolean desiredRunning;
        public final boolean aceRunning;
        public final boolean proxyRunning;
        public final String phase;
        public final String lastError;
        public final long lastUpdateMillis;
        public final boolean serverModeEnabled;
        public final boolean externalPlayerServerEnabled;
        public final List<EndpointInfo> endpoints;
        public final String log;

        HapStatus(
                boolean desiredRunning,
                boolean aceRunning,
                boolean proxyRunning,
                String phase,
                String lastError,
                long lastUpdateMillis,
                boolean serverModeEnabled,
                boolean externalPlayerServerEnabled,
                List<EndpointInfo> endpoints,
                String log
        ) {
            this.desiredRunning = desiredRunning;
            this.aceRunning = aceRunning;
            this.proxyRunning = proxyRunning;
            this.phase = phase == null ? "" : phase;
            this.lastError = lastError == null ? "" : lastError;
            this.lastUpdateMillis = lastUpdateMillis;
            this.serverModeEnabled = serverModeEnabled;
            this.externalPlayerServerEnabled = externalPlayerServerEnabled;
            this.endpoints = endpoints;
            this.log = log == null ? "" : log;
        }
    }

    public static final class EndpointInfo {
        public final String label;
        public final String host;
        public final String baseUrl;

        EndpointInfo(String label, String host, String baseUrl) {
            this.label = label;
            this.host = host;
            this.baseUrl = baseUrl;
        }
    }

    public static final class SourceInfo {
        public final String id;
        public final String label;
        public final String description;
        public final String format;
        public final String defaultSource;
        public final String value;
        public final String localUrl;
        public final boolean selected;
        public final boolean customized;
        public final boolean removable;

        SourceInfo(
                String id,
                String label,
                String description,
                String format,
                String defaultSource,
                String value,
                String localUrl,
                boolean selected,
                boolean customized,
                boolean removable
        ) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.format = format;
            this.defaultSource = defaultSource;
            this.value = value;
            this.localUrl = localUrl;
            this.selected = selected;
            this.customized = customized;
            this.removable = removable;
        }
    }

    public static final class CustomChannelInfo {
        public final int index;
        public final String name;
        public final String contentId;

        CustomChannelInfo(int index, String name, String contentId) {
            this.index = index;
            this.name = name;
            this.contentId = contentId;
        }
    }

    public static final class ConnectedClientInfo {
        public final String ip;
        public final String device;
        public final String channel;
        public final double megabytes;

        ConnectedClientInfo(String ip, String device, String channel, double megabytes) {
            this.ip = ip;
            this.device = device;
            this.channel = channel;
            this.megabytes = megabytes;
        }
    }

    public static final class ChannelInfo {
        public final String name;
        public final String contentId;

        ChannelInfo(String name, String contentId) {
            this.name = name;
            this.contentId = contentId;
        }
    }

    public static final class PluginChannelsInfo {
        public final String id;
        public final String label;
        public final List<ChannelInfo> channels;

        PluginChannelsInfo(String id, String label, List<ChannelInfo> channels) {
            this.id = id;
            this.label = label;
            this.channels = channels;
        }

        @Override
        public String toString() {
            return label + " (" + channels.size() + ")";
        }
    }

    public static final class PeerResultInfo {
        public final boolean success;
        public final boolean available;
        public final int peers;
        public final int httpPeers;
        public final int totalPeers;
        public final String statusText;
        public final String error;

        PeerResultInfo(
                boolean success,
                boolean available,
                int peers,
                int httpPeers,
                int totalPeers,
                String statusText,
                String error
        ) {
            this.success = success;
            this.available = available;
            this.peers = peers;
            this.httpPeers = httpPeers;
            this.totalPeers = totalPeers;
            this.statusText = statusText == null ? "" : statusText;
            this.error = error == null ? "" : error;
        }

        public static PeerResultInfo errorResult(String errorText) {
            return new PeerResultInfo(false, false, 0, 0, 0, "check failed", errorText);
        }
    }

    public static final class SaveResult {
        public final boolean success;
        public final String message;

        SaveResult(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }
    }
}
