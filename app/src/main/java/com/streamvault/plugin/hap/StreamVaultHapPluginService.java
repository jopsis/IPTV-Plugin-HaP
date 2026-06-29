package com.streamvault.plugin.hap;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.jopsis.httpaceserveproxy.HapBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;

public class StreamVaultHapPluginService extends Service {
    private static final String KEY_NEW_M3U_NAME = "m3uSourceName";
    private static final String KEY_NEW_M3U_URL = "m3uSourceUrl";
    private static final String KEY_CUSTOM_CHANNEL_NAME = "customChannelName";
    private static final String KEY_CUSTOM_ACESTREAM_ID = "customAcestreamId";

    private HandlerThread handlerThread;
    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();
        handlerThread = new HandlerThread("streamvault-hap-plugin");
        handlerThread.start();
        messenger = new Messenger(new Handler(handlerThread.getLooper(), this::handleMessage));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }

    private boolean handleMessage(Message request) {
        Bundle response = newResponse(request);
        try {
            switch (request.what) {
                case PluginContract.MSG_GET_MANIFEST:
                    response.putBoolean(PluginContract.KEY_SUCCESS, true);
                    response.putString(PluginContract.KEY_MANIFEST_JSON, manifestJson());
                    break;
                case PluginContract.MSG_SET_ENABLED:
                    handleSetEnabled(request.getData(), response);
                    break;
                case PluginContract.MSG_GET_STATUS:
                    handleStatus(response);
                    break;
                case PluginContract.MSG_GET_PROVIDER_URL:
                    handleProviderUrl(response);
                    break;
                case PluginContract.MSG_PREPARE_PLAYBACK:
                    handlePreparePlayback(request.getData(), response);
                    break;
                case PluginContract.MSG_REWRITE_CAST_URL:
                    handleRewriteCastUrl(request.getData(), response);
                    break;
                case PluginContract.MSG_GET_CONFIGURATION_SCHEMA:
                    response.putBoolean(PluginContract.KEY_SUCCESS, true);
                    response.putString(PluginContract.KEY_CONFIGURATION_SCHEMA_JSON, configurationSchemaJson());
                    break;
                case PluginContract.MSG_GET_CONFIGURATION_VALUES:
                    response.putBoolean(PluginContract.KEY_SUCCESS, true);
                    response.putString(PluginContract.KEY_CONFIGURATION_VALUES_JSON, configurationValuesJson());
                    break;
                case PluginContract.MSG_SET_CONFIGURATION_VALUES:
                    handleSetConfigurationValues(request.getData(), response);
                    break;
                case PluginContract.MSG_RUN_CONFIGURATION_ACTION:
                    handleRunConfigurationAction(request.getData(), response);
                    break;
                default:
                    response.putBoolean(PluginContract.KEY_SUCCESS, false);
                    response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_unsupported_plugin_request));
                    break;
            }
        } catch (Throwable error) {
            response.putBoolean(PluginContract.KEY_SUCCESS, false);
            response.putString(PluginContract.KEY_MESSAGE, error.toString());
        }
        sendResponse(request, response);
        return true;
    }

    private void handleSetEnabled(Bundle request, Bundle response) {
        boolean enabled = request.getBoolean(PluginContract.KEY_ENABLED, false);
        if (enabled) {
            HapBridge.start(this);
            boolean ready = HapBridge.waitForProxyReady(90_000);
            response.putBoolean(PluginContract.KEY_SUCCESS, ready);
            response.putString(
                    PluginContract.KEY_MESSAGE,
                    ready ? getString(R.string.message_hap_ready) : getString(R.string.message_hap_start_timeout)
            );
        } else {
            HapBridge.stop(this);
            response.putBoolean(PluginContract.KEY_SUCCESS, true);
            response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_hap_stopped));
        }
    }

    private void handleStatus(Bundle response) {
        HapBridge.HapStatus status = HapBridge.status(this);
        response.putBoolean(PluginContract.KEY_SUCCESS, true);
        response.putString(PluginContract.KEY_STATUS_LABEL, displayPhase(status.phase));
        response.putString(
                PluginContract.KEY_MESSAGE,
                status.lastError.isEmpty() ? statusText(status) : status.lastError
        );
    }

    private void handleProviderUrl(Bundle response) {
        HapBridge.start(this);
        boolean ready = HapBridge.waitForProxyReady(90_000);
        response.putBoolean(PluginContract.KEY_SUCCESS, ready);
        response.putString(PluginContract.KEY_PROVIDER_NAME, HapBridge.AIO_PROVIDER_NAME);
        response.putString(PluginContract.KEY_URL, HapBridge.LOCAL_AIO_URL);
        response.putString(
                PluginContract.KEY_MESSAGE,
                ready ? getString(R.string.message_provider_ready) : getString(R.string.message_provider_not_ready)
        );
    }

    private void handlePreparePlayback(Bundle request, Bundle response) {
        String url = request.getString(PluginContract.KEY_INPUT_URL, "");
        if (!HapBridge.isLocalHapUrl(url)) {
            response.putBoolean(PluginContract.KEY_HANDLED, false);
            response.putBoolean(PluginContract.KEY_SUCCESS, true);
            return;
        }
        response.putBoolean(PluginContract.KEY_HANDLED, true);
        HapBridge.start(this);
        boolean ready = HapBridge.waitForProxyReady(90_000);
        if (!ready) {
            response.putBoolean(PluginContract.KEY_SUCCESS, false);
            response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_playback_not_ready));
            return;
        }

        String localUrl = startTsfProxy(url);
        if (localUrl == null) {
            response.putBoolean(PluginContract.KEY_SUCCESS, false);
            response.putString(PluginContract.KEY_MESSAGE, "Could not start local stream proxy");
            return;
        }

        response.putBoolean(PluginContract.KEY_SUCCESS, true);
        response.putString(PluginContract.KEY_OUTPUT_URL, localUrl);
        response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_playback_ready));
        android.util.Log.d("HaP-TSF", "handlePreparePlayback output_url=" + localUrl + " original=" + url);
    }

    private String startTsfProxy(String originalUrl) {
        try {
            ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
            int port = server.getLocalPort();
            String localUrl = "http://127.0.0.1:" + port + "/stream";

            new Thread(() -> {
                Socket client = null;
                Socket proxySocket = null;
                InputStream fromProxy = null;
                try {
                    client = server.accept();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    }

                    OutputStream toClient = client.getOutputStream();
                    toClient.write("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".getBytes());

                    String proxyUrl = originalUrl;
                    for (int redirectCount = 0; redirectCount < 5; redirectCount++) {
                        URL realUrl = new URL(proxyUrl);
                        android.util.Log.d("HaP-TSF", "connecting to " + realUrl.getHost() + ":" + realUrl.getPort() + realUrl.getFile());
                        if (proxySocket != null) try { proxySocket.close(); } catch (Exception ignored) {}
                        proxySocket = new Socket(realUrl.getHost(), realUrl.getPort());
                        proxySocket.setTcpNoDelay(true);
                        proxySocket.setSoTimeout(120000);
                        OutputStream proxyOut = proxySocket.getOutputStream();
                        fromProxy = proxySocket.getInputStream();
                        android.util.Log.d("HaP-TSF", "connected fd=" + proxySocket);

                        String path = realUrl.getFile();
                        if (realUrl.getQuery() != null) path += "?" + realUrl.getQuery();
                        String req = "GET " + path + " HTTP/1.1\r\nHost: " + realUrl.getHost() + ":" + realUrl.getPort() + "\r\nConnection: close\r\n\r\n";
                        proxyOut.write(req.getBytes());
                        proxyOut.flush();

                        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
                        int b1 = -1, b2 = -1, b3 = -1, b4 = -1;
                        while (true) {
                            int b = fromProxy.read();
                            if (b < 0) throw new IOException("proxy connection closed before headers");
                            headerBuf.write(b);
                            b1 = b2; b2 = b3; b3 = b4; b4 = b;
                            if (b1 == '\r' && b2 == '\n' && b3 == '\r' && b4 == '\n') break;
                        }
                        String headerStr = new String(headerBuf.toByteArray(), "UTF-8");
                        String firstLine = headerStr.substring(0, Math.min(headerStr.length(), 200)).replace("\r\n", " | ");
                        android.util.Log.d("HaP-TSF", "proxy response: " + firstLine);

                        if (headerStr.startsWith("HTTP/1.1 302") || headerStr.startsWith("HTTP/1.0 302")
                                || headerStr.startsWith("HTTP/1.1 301") || headerStr.startsWith("HTTP/1.0 301")) {
                            String location = extractHeader(headerStr, "Location");
                            if (location == null || location.isEmpty())
                                throw new IOException("redirect without Location");
                            if (location.startsWith("/")) {
                                location = "http://" + realUrl.getHost() + ":" + realUrl.getPort() + location;
                            }
                            android.util.Log.d("HaP-TSF", "redirect to " + location);
                            proxyUrl = location;
                            continue;
                        }

                        if (!headerStr.startsWith("HTTP/1.1 200") && !headerStr.startsWith("HTTP/1.0 200")) {
                            throw new IOException("proxy returned non-200: " + headerStr.substring(0, Math.min(headerStr.length(), 200)));
                        }
                        break;
                    }

                    android.util.Log.d("HaP-TSF", "starting TS streaming with retry");
                    byte[] buf = new byte[262144];
                    byte[] packetBuf = new byte[131072];
                    int streamingRetries = 0;

                    while (streamingRetries < 60) {
                        int pos = 0;
                        int packetCount = 0;
                        boolean gotData = false;

                        try {
                            while (true) {
                                int n;
                                try {
                                    n = fromProxy.read(buf, pos, buf.length - pos);
                                } catch (IOException e) {
                                    if (packetCount > 0) toClient.write(packetBuf, 0, packetCount * 188);
                                    throw e;
                                }
                                if (n < 0) {
                                    if (packetCount > 0) toClient.write(packetBuf, 0, packetCount * 188);
                                    android.util.Log.d("HaP-TSF", "stream ended cleanly");
                                    return;
                                }
                                pos += n;
                                gotData = true;

                                int readIdx = 0;
                                while (readIdx + 376 <= pos) {
                                    if ((buf[readIdx] & 0xFF) == 0x47
                                            && (buf[readIdx + 188] & 0xFF) == 0x47
                                            && (buf[readIdx + 376] & 0xFF) == 0x47) {
                                        System.arraycopy(buf, readIdx, packetBuf, packetCount * 188, 188);
                                        packetCount++;
                                        readIdx += 188;
                                        if (packetCount * 188 + 188 > packetBuf.length || readIdx + 376 > pos) {
                                            toClient.write(packetBuf, 0, packetCount * 188);
                                            packetCount = 0;
                                        }
                                    } else {
                                        if (packetCount > 0) {
                                            toClient.write(packetBuf, 0, packetCount * 188);
                                            packetCount = 0;
                                        }
                                        readIdx++;
                                    }
                                }

                                if (packetCount > 0) {
                                    toClient.write(packetBuf, 0, packetCount * 188);
                                    packetCount = 0;
                                }

                                if (readIdx > 0) {
                                    int remaining = pos - readIdx;
                                    if (remaining > 0) System.arraycopy(buf, readIdx, buf, 0, remaining);
                                    pos = remaining;
                                }
                            }
                        } catch (IOException e) {
                            streamingRetries++;
                            android.util.Log.w("HaP-TSF", "stream error (" + streamingRetries + "): " + e);
                            if (!gotData && streamingRetries >= 15) {
                                android.util.Log.e("HaP-TSF", "no data after " + streamingRetries + " retries, giving up");
                                break;
                            }
                            try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                            try { if (proxySocket != null) { proxySocket.close(); } } catch (Exception ignored) {}
                            try {
                                URL retryUrl = new URL(proxyUrl);
                                proxySocket = new Socket(retryUrl.getHost(), retryUrl.getPort());
                                proxySocket.setTcpNoDelay(true);
                                proxySocket.setSoTimeout(120000);
                                fromProxy = proxySocket.getInputStream();
                                OutputStream proxyOut = proxySocket.getOutputStream();
                                String path2 = retryUrl.getFile();
                                if (retryUrl.getQuery() != null) path2 += "?" + retryUrl.getQuery();
                                String req2 = "GET " + path2 + " HTTP/1.1\r\nHost: " + retryUrl.getHost() + ":" + retryUrl.getPort() + "\r\nConnection: close\r\n\r\n";
                                proxyOut.write(req2.getBytes());
                                proxyOut.flush();
                                ByteArrayOutputStream hb = new ByteArrayOutputStream();
                                int bb1=-1,bb2=-1,bb3=-1,bb4=-1;
                                while (true) {
                                    int b = fromProxy.read();
                                    if (b < 0) throw new IOException("retry closed");
                                    hb.write(b);
                                    bb1=bb2; bb2=bb3; bb3=bb4; bb4=b;
                                    if (bb1=='\r'&&bb2=='\n'&&bb3=='\r'&&bb4=='\n') break;
                                }
                                String hs = new String(hb.toByteArray(), "UTF-8");
                                android.util.Log.d("HaP-TSF", "retry " + streamingRetries + " response: " + hs.substring(0, Math.min(hs.length(), 100)).replace("\r\n"," "));
                                if (!hs.startsWith("HTTP/1.1 200") && !hs.startsWith("HTTP/1.0 200")) {
                                    android.util.Log.w("HaP-TSF", "retry got non-200, retrying...");
                                    continue;
                                }
                            } catch (IOException e2) {
                                android.util.Log.w("HaP-TSF", "retry connect failed: " + e2);
                                continue;
                            }
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.e("HaP-TSF", "proxy thread error: " + e);
                } finally {
                    try { if (proxySocket != null) proxySocket.close(); } catch (Exception ignored) {}
                    try { if (client != null) client.close(); } catch (Exception ignored) {}
                    try { server.close(); } catch (Exception ignored) {}
                }
            }, "tsf-proxy-" + port).start();

            android.util.Log.d("HaP-TSF", "started tsf proxy on port " + port + " url=" + localUrl);
            return localUrl;
        } catch (Exception e) {
            android.util.Log.e("HaP-TSF", "startTsfProxy failed: " + e);
            return null;
        }
    }

    private static String extractHeader(String headers, String name) {
        String lower = headers.toLowerCase(java.util.Locale.ROOT);
        String search = "\r\n" + name.toLowerCase(java.util.Locale.ROOT) + ": ";
        int idx = lower.indexOf(search);
        if (idx < 0) {
            search = "\r\n" + name.toLowerCase(java.util.Locale.ROOT) + ":";
            idx = lower.indexOf(search);
        }
        if (idx < 0) return null;
        int start = idx + search.length();
        int end = headers.indexOf("\r\n", start);
        return end < 0 ? headers.substring(start).trim() : headers.substring(start, end).trim();
    }

    private void handleRewriteCastUrl(Bundle request, Bundle response) {
        String url = request.getString(PluginContract.KEY_INPUT_URL, "");
        if (!HapBridge.isLocalHapUrl(url)) {
            response.putBoolean(PluginContract.KEY_HANDLED, false);
            response.putBoolean(PluginContract.KEY_SUCCESS, true);
            return;
        }
        response.putBoolean(PluginContract.KEY_HANDLED, true);
        HapBridge.setServerModeEnabled(this, true);
        HapBridge.start(this);
        boolean ready = HapBridge.waitForProxyReady(90_000);
        if (!ready || !HapBridge.isCastableHapUrl(this, url)) {
            response.putBoolean(PluginContract.KEY_SUCCESS, false);
            response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_cast_endpoint_unavailable));
            return;
        }
        response.putBoolean(PluginContract.KEY_SUCCESS, true);
        response.putString(PluginContract.KEY_OUTPUT_URL, HapBridge.rewriteLocalUrlForLan(this, url));
        response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_cast_url_rewritten));
    }

    private void handleSetConfigurationValues(Bundle request, Bundle response) throws Exception {
        String rawValues = request.getString(PluginContract.KEY_CONFIGURATION_VALUES_JSON, "{}");
        JSONObject values = new JSONObject(rawValues == null || rawValues.isEmpty() ? "{}" : rawValues);

        if (values.has("externalPlayerServer")) {
            if (values.optBoolean("externalPlayerServer", false)) {
                HapBridge.enableExternalPlayerServer(this);
            } else {
                HapBridge.disableExternalPlayerServer(this);
            }
        } else if (values.has("serverMode")) {
            if (!values.optBoolean("serverMode", false) && HapBridge.isExternalPlayerServerEnabled(this)) {
                HapBridge.setExternalPlayerServerEnabled(this, false);
            }
            HapBridge.setServerModeEnabled(this, values.optBoolean("serverMode", false));
        }

        String message = getString(R.string.message_aio_selection_saved);

        for (HapBridge.SourceInfo source : HapBridge.sources(this)) {
            String selectedKey = sourceSelectedKey(source.id);
            if (values.has(selectedKey)) {
                HapBridge.setSourceSelected(this, source.id, values.optBoolean(selectedKey, source.selected));
            }

            String valueKey = sourceValueKey(source.id);
            if (values.has(valueKey) && !"CUSTOM".equals(source.format)) {
                String nextValue = values.optString(valueKey, source.value);
                if (!nextValue.equals(source.value)) {
                    HapBridge.SaveResult saveResult = HapBridge.saveSource(this, source.id, nextValue);
                    if (!saveResult.success) {
                        response.putBoolean(PluginContract.KEY_SUCCESS, false);
                        response.putString(PluginContract.KEY_MESSAGE, saveResult.message);
                        return;
                    }
                    message = saveResult.message;
                }
            }
        }

        if (values.has(KEY_NEW_M3U_NAME) || values.has(KEY_NEW_M3U_URL)) {
            String name = values.optString(KEY_NEW_M3U_NAME, "");
            String url = values.optString(KEY_NEW_M3U_URL, "");
            if (!name.trim().isEmpty() || !url.trim().isEmpty()) {
                HapBridge.SaveResult saveResult = HapBridge.saveM3uSource(this, "", name, url);
                if (!saveResult.success) {
                    response.putBoolean(PluginContract.KEY_SUCCESS, false);
                    response.putString(PluginContract.KEY_MESSAGE, saveResult.message);
                    return;
                }
                message = saveResult.message;
            }
        }

        if (values.has(KEY_CUSTOM_CHANNEL_NAME) || values.has(KEY_CUSTOM_ACESTREAM_ID)) {
            String name = values.optString(KEY_CUSTOM_CHANNEL_NAME, "");
            String contentId = values.optString(KEY_CUSTOM_ACESTREAM_ID, "");
            if (!name.trim().isEmpty() || !contentId.trim().isEmpty()) {
                HapBridge.SaveResult saveResult = HapBridge.saveCustomChannel(this, -1, name, contentId);
                if (!saveResult.success) {
                    response.putBoolean(PluginContract.KEY_SUCCESS, false);
                    response.putString(PluginContract.KEY_MESSAGE, saveResult.message);
                    return;
                }
                HapBridge.setSourceSelected(this, "custom", true);
                message = saveResult.message;
            }
        }

        response.putBoolean(PluginContract.KEY_SUCCESS, true);
        response.putString(PluginContract.KEY_MESSAGE, message);
    }

    private void handleRunConfigurationAction(Bundle request, Bundle response) {
        String actionId = request.getString(PluginContract.KEY_CONFIGURATION_ACTION_ID, "");
        boolean ready;
        switch (actionId) {
            case "start":
                HapBridge.start(this);
                ready = HapBridge.waitForProxyReady(90_000);
                response.putBoolean(PluginContract.KEY_SUCCESS, ready);
                response.putString(
                        PluginContract.KEY_MESSAGE,
                        ready ? getString(R.string.message_hap_ready) : getString(R.string.message_hap_start_timeout)
                );
                break;
            case "stop":
                HapBridge.stop(this);
                response.putBoolean(PluginContract.KEY_SUCCESS, true);
                response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_hap_stopped));
                break;
            case "restart":
                HapBridge.restart(this);
                ready = HapBridge.waitForProxyReady(90_000);
                response.putBoolean(PluginContract.KEY_SUCCESS, ready);
                response.putString(
                        PluginContract.KEY_MESSAGE,
                        ready ? getString(R.string.message_hap_restarted) : getString(R.string.message_hap_restart_timeout)
                );
                break;
            case "restartProxy":
                HapBridge.restartProxy(this);
                ready = HapBridge.waitForProxyReady(90_000);
                response.putBoolean(PluginContract.KEY_SUCCESS, ready);
                response.putString(
                        PluginContract.KEY_MESSAGE,
                        ready ? getString(R.string.message_proxy_restarted) : getString(R.string.message_proxy_restart_timeout)
                );
                break;
            case "prepareAio":
                HapBridge.start(this);
                ready = HapBridge.waitForProxyReady(90_000);
                response.putBoolean(PluginContract.KEY_SUCCESS, ready);
                response.putString(
                        PluginContract.KEY_MESSAGE,
                        ready ? getString(R.string.message_provider_ready) : getString(R.string.message_provider_not_ready)
                );
                break;
            default:
                response.putBoolean(PluginContract.KEY_SUCCESS, false);
                response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_unknown_hap_action));
                break;
        }
    }

    private Bundle newResponse(Message request) {
        Bundle response = new Bundle();
        Bundle data = request.getData();
        response.putInt(PluginContract.KEY_API_VERSION, PluginContract.API_VERSION);
        if (data != null) {
            response.putString(PluginContract.KEY_REQUEST_ID, data.getString(PluginContract.KEY_REQUEST_ID, ""));
        }
        return response;
    }

    private void sendResponse(Message request, Bundle response) {
        if (request.replyTo == null) return;
        Message reply = Message.obtain(null, request.what);
        reply.setData(response);
        try {
            request.replyTo.send(reply);
        } catch (RemoteException ignored) {
        }
    }

    private String statusText(HapBridge.HapStatus status) {
        return "AceServe " + onlineLabel(status.aceRunning) +
                " / HTTPAceProxy " + onlineLabel(status.proxyRunning) +
                " / " + HapBridge.LOCAL_AIO_URL;
    }

    private String onlineLabel(boolean value) {
        return value ? getString(R.string.status_online) : getString(R.string.status_offline);
    }

    private String configurationSchemaJson() throws Exception {
        JSONArray sections = new JSONArray()
                .put(new JSONObject()
                        .put("id", "runtime")
                        .put("title", getString(R.string.label_runtime))
                        .put("description", getString(R.string.screen_subtitle))
                        .put("fields", new JSONArray()
                                .put(infoField("phase", getString(R.string.label_phase)))
                                .put(infoField("runtime", getString(R.string.label_runtime)))
                                .put(infoField("lastError", getString(R.string.label_last_error)))
                                .put(infoField("localAioUrl", getString(R.string.label_local_aio_url)))
                                .put(infoField("localAioEpgUrl", getString(R.string.label_local_aio_epg_url)))
                                .put(infoField("castAioUrl", getString(R.string.label_cast_aio_url)))
                                .put(infoField("castAioEpgUrl", getString(R.string.label_cast_aio_epg_url)))))
                .put(new JSONObject()
                        .put("id", "network")
                        .put("title", getString(R.string.section_aio_lan))
                        .put("description", getString(R.string.message_lan_enabled))
                        .put("fields", new JSONArray()
                                .put(new JSONObject()
                                        .put("key", "externalPlayerServer")
                                        .put("type", "boolean")
                                        .put("label", getString(R.string.label_external_player_server))
                                        .put("description", getString(R.string.message_external_player_server_detail)))
                                .put(new JSONObject()
                                        .put("key", "serverMode")
                                        .put("type", "boolean")
                                        .put("label", getString(R.string.label_lan_server))
                                        .put("description", getString(R.string.message_lan_enabled)))));

        JSONArray sourceFields = new JSONArray()
                .put(new JSONObject()
                        .put("key", KEY_NEW_M3U_NAME)
                        .put("type", "text")
                        .put("label", getString(R.string.label_source_name))
                        .put("description", getString(R.string.source_m3u_description)))
                .put(new JSONObject()
                        .put("key", KEY_NEW_M3U_URL)
                        .put("type", "url")
                        .put("label", getString(R.string.label_source_url))
                        .put("description", getString(R.string.source_m3u_description)));
        for (HapBridge.SourceInfo source : HapBridge.sources(this)) {
            sourceFields.put(new JSONObject()
                    .put("key", sourceSelectedKey(source.id))
                    .put("type", "boolean")
                    .put("label", source.label)
                    .put("description", source.description));
            if (!"CUSTOM".equals(source.format)) {
                sourceFields.put(new JSONObject()
                        .put("key", sourceValueKey(source.id))
                        .put("type", "url")
                        .put("label", source.label + " URL")
                        .put("description", source.description + " (" + source.format + ")")
                        .put("placeholder", source.value));
            }
        }
        sourceFields.put(new JSONObject()
                        .put("key", KEY_CUSTOM_CHANNEL_NAME)
                        .put("type", "text")
                        .put("label", getString(R.string.label_channel_name))
                        .put("description", getString(R.string.source_custom_description)))
                .put(new JSONObject()
                        .put("key", KEY_CUSTOM_ACESTREAM_ID)
                        .put("type", "text")
                        .put("label", getString(R.string.label_acestream_id))
                        .put("description", getString(R.string.source_custom_description)));
        sections.put(new JSONObject()
                .put("id", "sources")
                .put("title", getString(R.string.section_sources))
                .put("description", getString(R.string.source_m3u_description))
                .put("fields", sourceFields));

        JSONArray actions = new JSONArray()
                .put(action("start", getString(R.string.status_starting), getString(R.string.message_starting_hap)))
                .put(action("stop", getString(R.string.button_stop), getString(R.string.message_stopping_hap)))
                .put(action("restart", getString(R.string.button_restart_hap), getString(R.string.message_starting_hap)))
                .put(action("restartProxy", getString(R.string.button_restart_proxy), getString(R.string.message_restarting_proxy)))
                .put(action("prepareAio", getString(R.string.button_prepare_aio), getString(R.string.message_preparing_aio)));

        return new JSONObject()
                .put("schemaVersion", 1)
                .put("title", getString(R.string.screen_title))
                .put("description", getString(R.string.screen_subtitle))
                .put("sections", sections)
                .put("actions", actions)
                .toString();
    }

    private String configurationValuesJson() throws Exception {
        HapBridge.HapStatus status = HapBridge.status(this);
        JSONObject values = new JSONObject()
                .put("phase", displayPhase(status.phase))
                .put("runtime", statusText(status))
                .put("lastError", status.lastError)
                .put("localAioUrl", HapBridge.LOCAL_AIO_URL)
                .put("localAioEpgUrl", HapBridge.LOCAL_AIO_EPG_URL)
                .put("castAioUrl", HapBridge.castAioUrl(this))
                .put("castAioEpgUrl", HapBridge.castAioEpgUrl(this))
                .put("externalPlayerServer", status.externalPlayerServerEnabled)
                .put("serverMode", status.serverModeEnabled)
                .put(KEY_NEW_M3U_NAME, "")
                .put(KEY_NEW_M3U_URL, "")
                .put(KEY_CUSTOM_CHANNEL_NAME, "")
                .put(KEY_CUSTOM_ACESTREAM_ID, "");

        for (HapBridge.SourceInfo source : HapBridge.sources(this)) {
            values.put(sourceSelectedKey(source.id), source.selected);
            values.put(sourceValueKey(source.id), source.value);
        }
        return values.toString();
    }

    private JSONObject infoField(String key, String label) throws Exception {
        return new JSONObject()
                .put("key", key)
                .put("type", "info")
                .put("label", label)
                .put("readOnly", true);
    }

    private JSONObject action(String id, String label, String description) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("label", label)
                .put("description", description)
                .put("refreshAfterRun", true);
    }

    private String sourceSelectedKey(String sourceId) {
        return "source." + sourceId + ".selected";
    }

    private String sourceValueKey(String sourceId) {
        return "source." + sourceId + ".value";
    }

    private String manifestJson() throws Exception {
        JSONArray capabilities = new JSONArray()
                .put("provider.m3u")
                .put("playback.prepare")
                .put("cast.rewriteUrl")
                .put("configuration.activity");
        return new JSONObject()
                .put("schemaVersion", 1)
                .put("id", "com.streamvault.plugins.hap")
                .put("name", "HaP")
                .put("versionName", "1.2.2")
                .put("versionCode", 10)
                .put("description", getString(R.string.plugin_description))
                .put("providerName", HapBridge.AIO_PROVIDER_NAME)
                .put("configurationMode", "activity")
                .put("configurationActivityAction", "com.streamvault.plugin.hap.CONFIGURE")
                .put("capabilities", capabilities)
                .toString();
    }

    private String displayPhase(String rawPhase) {
        if (rawPhase == null || rawPhase.trim().isEmpty()) return getString(R.string.status_idle);
        String lower = rawPhase.toLowerCase(Locale.ROOT);
        if ("running".equals(lower)) return getString(R.string.status_running);
        if ("failed".equals(lower)) return getString(R.string.status_failed);
        if ("stopped".equals(lower)) return getString(R.string.status_stopped);
        if ("stopping".equals(lower)) return getString(R.string.phase_stopping);
        if ("preparing assets".equals(lower)) return getString(R.string.phase_preparing_assets);
        if ("starting acestream".equals(lower) || "starting aceserve".equals(lower)) {
            return getString(R.string.phase_starting_aceserve);
        }
        if ("starting httpaceproxy".equals(lower)) return getString(R.string.phase_starting_proxy);
        if ("restarting httpaceproxy".equals(lower)) return getString(R.string.phase_restarting_proxy);
        return rawPhase;
    }
}
