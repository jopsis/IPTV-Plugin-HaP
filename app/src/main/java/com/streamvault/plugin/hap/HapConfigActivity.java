package com.streamvault.plugin.hap;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.jopsis.httpaceserveproxy.HapBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class HapConfigActivity extends Activity {
    private static final int COLOR_CANVAS = 0xFF07111B;
    private static final int COLOR_PANEL = 0xEE101B2B;
    private static final int COLOR_PANEL_SOFT = 0x99162435;
    private static final int COLOR_SURFACE = 0xFF162435;
    private static final int COLOR_SURFACE_FOCUSED = 0xFF1E3550;
    private static final int COLOR_STROKE = 0xFF24364C;
    private static final int COLOR_BRAND = 0xFF69A8FF;
    private static final int COLOR_BRAND_MUTED = 0x332E8BFF;
    private static final int COLOR_SUCCESS = 0xFF57D68D;
    private static final int COLOR_SUCCESS_MUTED = 0x3357D68D;
    private static final int COLOR_WARNING = 0xFFF4C430;
    private static final int COLOR_WARNING_MUTED = 0x33F4C430;
    private static final int COLOR_ERROR = 0xFFFF6B6B;
    private static final int COLOR_ERROR_MUTED = 0x33FF6B6B;
    private static final int COLOR_TEXT_PRIMARY = 0xFFF5F7FB;
    private static final int COLOR_TEXT_SECONDARY = 0xFFBBC6D8;
    private static final int COLOR_TEXT_TERTIARY = 0xFF7F8DA5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, HapBridge.PeerResultInfo> peerResults = new HashMap<>();
    private final List<View> busyControls = new ArrayList<>();

    private TextView statePill;
    private TextView phasePill;
    private TextView runtimeView;
    private LinearLayout runtimePillsContainer;
    private TextView errorView;
    private TextView messageView;
    private LinearLayout endpointsContainer;
    private LinearLayout sourcesListContainer;
    private LinearLayout customListContainer;
    private LinearLayout clientsContainer;
    private TextView clientsStatusView;
    private LinearLayout pluginTabsContainer;
    private LinearLayout channelRowsContainer;
    private TextView channelFeedbackView;
    private Button stopChannelTestButton;
    private TextView logView;
    private Switch enabledSwitch;
    private Switch lanSwitch;
    private EditText sourceNameEdit;
    private EditText sourceUrlEdit;
    private TextView sourceFeedbackView;
    private Button sourceSaveButton;
    private Button sourceCancelButton;
    private EditText customNameEdit;
    private EditText customContentIdEdit;

    private List<HapBridge.SourceInfo> currentSources = new ArrayList<>();
    private List<HapBridge.CustomChannelInfo> currentCustomChannels = new ArrayList<>();
    private List<HapBridge.PluginChannelsInfo> channelPlugins = new ArrayList<>();
    private String editingM3uSourceId = "";
    private String selectedPluginId;
    private String selectedChannelContentId;
    private int customEditingIndex = -1;
    private Future<?> channelTestTask;
    private volatile boolean cancelChannelTest;
    private boolean suppressSwitchCallbacks;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshRuntimeState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        refreshConfigState();
        refreshRuntimeState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRunnable.run();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (channelTestTask != null) channelTestTask.cancel(true);
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(COLOR_CANVAS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(22), dp(24), dp(28));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(text(getString(R.string.screen_title), 30, COLOR_TEXT_PRIMARY, Typeface.BOLD), matchWrap());

        TextView subtitle = text(getString(R.string.screen_subtitle), 14, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle, matchWrap());

        root.addView(buildHeaderPanel(), matchWrap());
        root.addView(buildAioPanel(), matchWrapTop(12));
        root.addView(buildSourcesPanel(), matchWrapTop(12));
        root.addView(buildClientsPanel(), matchWrapTop(12));
        root.addView(buildChannelStatusPanel(), matchWrapTop(12));
        root.addView(buildLogsPanel(), matchWrapTop(12));

        return scroll;
    }

    private View buildHeaderPanel() {
        boolean compact = isCompactWidth();
        PanelViews panel = panel(null, false, true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(row, matchWrap());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(left, compact ? matchWrap() : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(titleRow, matchWrap());

        titleRow.addView(text(getString(R.string.screen_title), 24, COLOR_TEXT_PRIMARY, Typeface.BOLD));
        statePill = pill(getString(R.string.status_stopped), COLOR_ERROR_MUTED, COLOR_ERROR);
        titleRow.addView(statePill, wrapWrapLeft(10));
        phasePill = pill(getString(R.string.status_idle), COLOR_SURFACE, COLOR_TEXT_SECONDARY);
        titleRow.addView(phasePill, wrapWrapLeft(8));

        runtimePillsContainer = new LinearLayout(this);
        runtimePillsContainer.setOrientation(LinearLayout.HORIZONTAL);
        runtimePillsContainer.setGravity(Gravity.CENTER_VERTICAL);
        runtimePillsContainer.setPadding(0, dp(10), 0, 0);
        left.addView(runtimePillsContainer, matchWrap());

        runtimeView = text("", 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        runtimeView.setSingleLine(true);
        runtimeView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        runtimeView.setPadding(0, dp(8), 0, 0);
        left.addView(runtimeView, matchWrap());

        errorView = text("", 13, COLOR_ERROR, Typeface.NORMAL);
        errorView.setPadding(0, dp(6), 0, 0);
        errorView.setVisibility(View.GONE);
        left.addView(errorView, matchWrap());

        messageView = text("", 13, COLOR_BRAND, Typeface.NORMAL);
        messageView.setPadding(0, dp(6), 0, 0);
        messageView.setVisibility(View.GONE);
        left.addView(messageView, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(compact ? Gravity.START : Gravity.RIGHT);
        actions.setPadding(compact ? 0 : dp(16), compact ? dp(12) : 0, 0, 0);
        row.addView(actions, compact ? matchWrap() : wrapWrap());

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        actions.addView(switchRow, wrapWrap());

        switchRow.addView(text(getString(R.string.label_enable_hap), 13, COLOR_TEXT_SECONDARY, Typeface.BOLD));
        enabledSwitch = new Switch(this);
        enabledSwitch.setPadding(dp(8), 0, 0, 0);
        enabledSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallbacks) return;
            setEnabled(checked);
        });
        busyControls.add(enabledSwitch);
        switchRow.addView(enabledSwitch, wrapWrap());

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(compact ? Gravity.START : Gravity.RIGHT);
        buttonRow.setPadding(0, dp(10), 0, 0);
        actions.addView(buttonRow, wrapWrap());

        Button restart = button(getString(R.string.button_restart_proxy));
        restart.setOnClickListener(v -> restartProxy());
        buttonRow.addView(restart, wrapWrap());

        Button prepare = button(getString(R.string.button_prepare_aio));
        prepare.setOnClickListener(v -> prepareAio());
        buttonRow.addView(prepare, wrapWrapLeft(8));

        return panel.container;
    }

    private View buildAioPanel() {
        boolean compact = isCompactWidth();
        PanelViews panel = panel(getString(R.string.section_aio_lan), false, true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(row, matchWrap());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        row.addView(left, compact ? matchWrap() : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        left.addView(text(getString(R.string.label_local_list), 12, COLOR_TEXT_TERTIARY, Typeface.BOLD), matchWrap());
        endpointsContainer = new LinearLayout(this);
        endpointsContainer.setOrientation(LinearLayout.VERTICAL);
        endpointsContainer.setPadding(0, dp(6), 0, 0);
        left.addView(endpointsContainer, matchWrap());

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL | (compact ? Gravity.START : Gravity.RIGHT));
        right.setPadding(compact ? 0 : dp(18), compact ? dp(12) : 0, 0, 0);
        row.addView(right, compact ? matchWrap() : wrapWrap());

        right.addView(text(getString(R.string.label_lan_server), 13, COLOR_TEXT_SECONDARY, Typeface.BOLD));
        lanSwitch = new Switch(this);
        lanSwitch.setPadding(dp(8), 0, 0, 0);
        lanSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallbacks) return;
            setServerMode(checked);
        });
        busyControls.add(lanSwitch);
        right.addView(lanSwitch, wrapWrap());

        return panel.container;
    }

    private View buildSourcesPanel() {
        boolean compact = isCompactWidth();
        PanelViews panel = panel(getString(R.string.section_sources), true, false);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));
        form.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.body.addView(form, matchWrap());

        LinearLayout editRow = new LinearLayout(this);
        editRow.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        editRow.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(editRow, matchWrap());

        sourceNameEdit = editText(getString(R.string.label_source_name), false);
        editRow.addView(sourceNameEdit, compact
                ? matchWrap()
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));

        sourceUrlEdit = editText(getString(R.string.label_source_url), false);
        LinearLayout.LayoutParams urlParams = compact
                ? matchWrapTop(8)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f);
        if (!compact) urlParams.leftMargin = dp(10);
        editRow.addView(sourceUrlEdit, urlParams);

        LinearLayout sourceButtons = new LinearLayout(this);
        sourceButtons.setOrientation(LinearLayout.HORIZONTAL);
        sourceButtons.setGravity(Gravity.RIGHT);
        sourceButtons.setPadding(0, dp(10), 0, 0);
        form.addView(sourceButtons, matchWrap());

        sourceSaveButton = button(getString(R.string.button_add_m3u_source));
        sourceSaveButton.setOnClickListener(v -> saveM3uSource());
        sourceButtons.addView(sourceSaveButton, wrapWrap());

        sourceCancelButton = button(getString(R.string.button_cancel));
        sourceCancelButton.setVisibility(View.GONE);
        sourceCancelButton.setOnClickListener(v -> clearSourceDraft());
        sourceButtons.addView(sourceCancelButton, wrapWrapLeft(8));

        sourceFeedbackView = text("", 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        sourceFeedbackView.setPadding(0, dp(8), 0, 0);
        sourceFeedbackView.setVisibility(View.GONE);
        form.addView(sourceFeedbackView, matchWrap());

        sourcesListContainer = new LinearLayout(this);
        sourcesListContainer.setOrientation(LinearLayout.VERTICAL);
        sourcesListContainer.setPadding(0, dp(12), 0, 0);
        panel.body.addView(sourcesListContainer, matchWrap());

        return panel.container;
    }

    private View buildCustomSection() {
        boolean compact = isCompactWidth();
        PanelViews panel = panel(getString(R.string.section_custom_channels), true, false);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));
        form.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.body.addView(form, matchWrap());

        LinearLayout editRow = new LinearLayout(this);
        editRow.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        editRow.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(editRow, matchWrap());

        customNameEdit = editText(getString(R.string.label_channel_name), false);
        editRow.addView(customNameEdit, compact
                ? matchWrap()
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f));

        customContentIdEdit = editText(getString(R.string.label_acestream_id), false);
        LinearLayout.LayoutParams contentParams = compact
                ? matchWrapTop(8)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f);
        if (!compact) contentParams.leftMargin = dp(10);
        editRow.addView(customContentIdEdit, contentParams);

        LinearLayout customButtons = new LinearLayout(this);
        customButtons.setOrientation(LinearLayout.HORIZONTAL);
        customButtons.setGravity(Gravity.RIGHT);
        customButtons.setPadding(0, dp(10), 0, 0);
        form.addView(customButtons, matchWrap());

        Button save = button(customEditingIndex >= 0 ? getString(R.string.button_update) : getString(R.string.button_save));
        save.setOnClickListener(v -> saveCustomChannel());
        customButtons.addView(save, wrapWrap());

        Button clear = button(getString(R.string.button_clear));
        clear.setOnClickListener(v -> clearCustomDraft());
        customButtons.addView(clear, wrapWrapLeft(8));

        customListContainer = new LinearLayout(this);
        customListContainer.setOrientation(LinearLayout.VERTICAL);
        customListContainer.setPadding(0, dp(10), 0, 0);
        panel.body.addView(customListContainer, matchWrap());

        return panel.container;
    }

    private View buildClientsPanel() {
        PanelViews panel = panel(getString(R.string.section_clients), false, true);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(controls, matchWrap());

        Button refresh = button(getString(R.string.button_refresh_clients));
        refresh.setOnClickListener(v -> loadClients());
        controls.addView(refresh, wrapWrap());

        clientsStatusView = text(getString(R.string.message_no_active_clients), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        clientsStatusView.setPadding(dp(10), 0, 0, 0);
        controls.addView(clientsStatusView, wrapWrap());

        clientsContainer = new LinearLayout(this);
        clientsContainer.setOrientation(LinearLayout.VERTICAL);
        clientsContainer.setPadding(0, dp(10), 0, 0);
        panel.body.addView(clientsContainer, matchWrap());

        return panel.container;
    }

    private View buildChannelStatusPanel() {
        PanelViews panel = panel(getString(R.string.section_channel_status), true, true);

        HorizontalScrollView controlsScroll = new HorizontalScrollView(this);
        controlsScroll.setHorizontalScrollBarEnabled(false);
        controlsScroll.setFocusable(false);
        controlsScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controlsScroll.addView(controls, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.body.addView(controlsScroll, matchWrap());

        Button load = button(getString(R.string.button_load_lists));
        load.setOnClickListener(v -> loadChannelPlugins());
        controls.addView(load, wrapWrap());

        Button testList = button(getString(R.string.button_test_list));
        testList.setOnClickListener(v -> checkSelectedList());
        controls.addView(testList, wrapWrapLeft(8));

        stopChannelTestButton = button(getString(R.string.button_stop));
        stopChannelTestButton.setEnabled(false);
        stopChannelTestButton.setOnClickListener(v -> stopChannelTest());
        controls.addView(stopChannelTestButton, wrapWrapLeft(8));

        channelFeedbackView = text("", 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        channelFeedbackView.setPadding(0, dp(10), 0, 0);
        channelFeedbackView.setVisibility(View.GONE);
        panel.body.addView(channelFeedbackView, matchWrap());

        HorizontalScrollView pluginScroll = new HorizontalScrollView(this);
        pluginScroll.setHorizontalScrollBarEnabled(false);
        pluginScroll.setFocusable(false);
        pluginScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        pluginScroll.setPadding(0, dp(10), 0, 0);
        pluginTabsContainer = new LinearLayout(this);
        pluginTabsContainer.setOrientation(LinearLayout.HORIZONTAL);
        pluginScroll.addView(pluginTabsContainer, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.body.addView(pluginScroll, matchWrap());

        ScrollView channelsScroll = new ScrollView(this);
        channelsScroll.setFillViewport(false);
        channelsScroll.setFocusable(false);
        channelsScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        channelRowsContainer = new LinearLayout(this);
        channelRowsContainer.setOrientation(LinearLayout.VERTICAL);
        channelRowsContainer.setFocusable(false);
        channelsScroll.addView(channelRowsContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320)
        );
        listParams.topMargin = dp(10);
        panel.body.addView(channelsScroll, listParams);
        renderChannels();

        return panel.container;
    }

    private View buildLogsPanel() {
        PanelViews panel = panel(getString(R.string.section_log), true, false);
        logView = text("", 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackground(roundRect(0xAA07111B, dp(10), 0, 0));
        panel.body.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220)
        ));
        return panel.container;
    }

    private void refreshRuntimeState() {
        HapBridge.HapStatus status = HapBridge.status(this);
        RuntimeBadge badge = runtimeBadge(status);

        statePill.setText(badge.label);
        statePill.setTextColor(badge.contentColor);
        statePill.setBackground(roundRect(badge.containerColor, dp(999), 0, 0));

        phasePill.setText(displayPhase(status.phase));
        phasePill.setTextColor(status.proxyRunning ? COLOR_BRAND : COLOR_TEXT_SECONDARY);
        phasePill.setBackground(roundRect(status.proxyRunning ? COLOR_BRAND_MUTED : COLOR_SURFACE, dp(999), 0, 0));

        runtimePillsContainer.removeAllViews();
        runtimePillsContainer.addView(statusPill(
                getString(R.string.label_aceserve) + " " + onlineLabel(status.aceRunning),
                status.aceRunning
        ), wrapWrap());
        runtimePillsContainer.addView(statusPill(
                getString(R.string.label_proxy) + " " + onlineLabel(status.proxyRunning),
                status.proxyRunning
        ), wrapWrapLeft(8));

        runtimeView.setText(HapBridge.LOCAL_AIO_URL);

        if (status.lastError.isEmpty()) {
            errorView.setVisibility(View.GONE);
        } else {
            errorView.setText(status.lastError);
            errorView.setVisibility(View.VISIBLE);
        }

        suppressSwitchCallbacks = true;
        enabledSwitch.setChecked(status.desiredRunning);
        lanSwitch.setChecked(status.serverModeEnabled);
        suppressSwitchCallbacks = false;

        renderEndpoints(status);
        renderLogs(status);
    }

    private void refreshConfigState() {
        currentSources = HapBridge.sources(this);
        currentCustomChannels = HapBridge.customChannels(this);
        renderSources();
    }

    private void renderEndpoints(HapBridge.HapStatus status) {
        endpointsContainer.removeAllViews();
        List<String> shown = new ArrayList<>();
        addEndpointRow(getString(R.string.label_local), HapBridge.LOCAL_AIO_URL, shown);
        String castUrl = HapBridge.castAioUrl(this);
        if (!castUrl.isEmpty()) addEndpointRow(getString(R.string.label_cast_lan), castUrl, shown);
        for (HapBridge.EndpointInfo endpoint : status.endpoints) {
            if ("127.0.0.1".equals(endpoint.host)) continue;
            addEndpointRow(endpoint.label, endpoint.baseUrl + "/aio", shown);
        }
    }

    private void addEndpointRow(String label, String url, List<String> shown) {
        if (shown.contains(url)) return;
        shown.add(url);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        row.addView(pill(label, COLOR_SURFACE, COLOR_TEXT_SECONDARY), wrapWrap());

        TextView value = text(url, 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        value.setPadding(dp(8), 0, dp(8), 0);
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button copy = smallButton(getString(R.string.button_copy));
        copy.setOnClickListener(v -> copyToClipboard("HaP URL", url));
        row.addView(copy, wrapWrap());

        endpointsContainer.addView(row, matchWrap());
    }

    private void renderSources() {
        sourcesListContainer.removeAllViews();

        boolean hasM3uSources = false;
        for (HapBridge.SourceInfo source : currentSources) {
            if (!"M3U".equals(source.format)) continue;
            hasM3uSources = true;
            sourcesListContainer.addView(sourceRow(source), matchWrapTop(sourcesListContainer.getChildCount() == 0 ? 0 : 8));
        }

        if (!hasM3uSources) {
            sourcesListContainer.addView(text(getString(R.string.message_no_sources), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL), matchWrap());
        }

        HapBridge.SourceInfo custom = customSource();
        if (custom != null) {
            sourcesListContainer.addView(sourceRow(custom), matchWrapTop(10));
            if (custom.selected) {
                sourcesListContainer.addView(buildCustomSection(), matchWrapTop(10));
                renderCustomChannels();
            }
        }
    }

    private View sourceRow(HapBridge.SourceInfo source) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));

        CheckBox selected = new CheckBox(this);
        selected.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_BRAND));
        selected.setChecked(source.selected);
        selected.setOnCheckedChangeListener((buttonView, checked) -> setSourceSelected(source.id, checked));
        row.addView(selected, wrapWrap());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(4), 0, dp(8), 0);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        left.addView(text(source.label, 14, COLOR_TEXT_PRIMARY, Typeface.BOLD), matchWrap());
        TextView value = text("CUSTOM".equals(source.format) ? source.description : source.value, 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        left.addView(value, matchWrap());

        if (source.removable) {
            Button edit = smallButton(getString(R.string.button_edit));
            edit.setOnClickListener(v -> editM3uSource(source));
            row.addView(edit, wrapWrapLeft(8));

            Button delete = smallButton(getString(R.string.button_delete));
            delete.setOnClickListener(v -> deleteM3uSource(source.id));
            row.addView(delete, wrapWrapLeft(8));
        }

        return row;
    }

    private void renderCustomChannels() {
        if (customListContainer == null) return;
        customListContainer.removeAllViews();
        if (currentCustomChannels.isEmpty()) {
            customListContainer.addView(text(getString(R.string.message_no_custom_channels), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL), matchWrap());
            return;
        }

        for (HapBridge.CustomChannelInfo channel : currentCustomChannels) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            left.addView(text(channel.name, 14, COLOR_TEXT_PRIMARY, Typeface.BOLD), matchWrap());
            TextView content = text(channel.contentId, 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
            content.setSingleLine(true);
            content.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            left.addView(content, matchWrap());

            Button edit = smallButton(getString(R.string.button_edit));
            edit.setOnClickListener(v -> editCustomChannel(channel));
            row.addView(edit, wrapWrapLeft(8));

            Button delete = smallButton(getString(R.string.button_delete));
            delete.setOnClickListener(v -> deleteCustomChannel(channel.index));
            row.addView(delete, wrapWrapLeft(8));

            customListContainer.addView(row, matchWrapTop(customListContainer.getChildCount() == 0 ? 0 : 8));
        }
    }

    private void renderClients(List<HapBridge.ConnectedClientInfo> clients) {
        clientsContainer.removeAllViews();
        clientsStatusView.setText(clients.isEmpty()
                ? getString(R.string.message_no_active_clients)
                : getString(R.string.message_client_count, clients.size()));
        for (HapBridge.ConnectedClientInfo client : clients) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));

            row.addView(weightedText(client.ip, 0.18f, COLOR_TEXT_PRIMARY, true));
            row.addView(weightedText(client.device, 0.22f, COLOR_TEXT_SECONDARY, false));
            row.addView(weightedText(client.channel, 0.43f, COLOR_TEXT_SECONDARY, false));
            row.addView(text(String.format(Locale.US, "%.1f MB", client.megabytes), 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL), wrapWrap());

            clientsContainer.addView(row, matchWrapTop(clientsContainer.getChildCount() == 0 ? 0 : 8));
        }
    }

    private void renderChannelPlugins() {
        pluginTabsContainer.removeAllViews();
        for (HapBridge.PluginChannelsInfo plugin : channelPlugins) {
            boolean selected = plugin.id.equals(selectedPluginId);
            TextView tab = pill(plugin.label + " (" + plugin.channels.size() + ")",
                    selected ? COLOR_BRAND_MUTED : COLOR_SURFACE,
                    selected ? COLOR_BRAND : COLOR_TEXT_SECONDARY);
            tab.setBackground(focusBackground(
                    selected ? COLOR_BRAND_MUTED : COLOR_SURFACE,
                    COLOR_SURFACE_FOCUSED,
                    dp(999),
                    dp(1),
                    selected ? COLOR_BRAND : COLOR_STROKE
            ));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setMinHeight(dp(36));
            tab.setOnClickListener(v -> {
                selectedPluginId = plugin.id;
                selectedChannelContentId = plugin.channels.isEmpty() ? null : plugin.channels.get(0).contentId;
                renderChannelPlugins();
                renderChannels();
                renderChannelFeedback(null);
            });
            pluginTabsContainer.addView(tab, wrapWrapLeft(pluginTabsContainer.getChildCount() == 0 ? 0 : 8));
        }
        renderChannels();
    }

    private void renderChannels() {
        channelRowsContainer.removeAllViews();
        HapBridge.PluginChannelsInfo selectedPlugin = selectedPlugin();
        if (selectedPlugin == null) {
            channelRowsContainer.addView(text(getString(R.string.message_load_channels_hint), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL), matchWrap());
            return;
        }

        for (HapBridge.ChannelInfo channel : selectedPlugin.channels) {
            HapBridge.PeerResultInfo result = peerResults.get(channel.contentId);
            boolean selected = channel.contentId.equals(selectedChannelContentId);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(focusBackground(
                    selected ? COLOR_BRAND_MUTED : COLOR_PANEL_SOFT,
                    COLOR_SURFACE_FOCUSED,
                    dp(10),
                    dp(1),
                    selected ? COLOR_BRAND : COLOR_STROKE
            ));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                selectedChannelContentId = channel.contentId;
                renderChannels();
                checkChannel(channel.contentId, channel.name);
            });

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = text(channel.name, 14, COLOR_TEXT_PRIMARY, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            left.addView(name, matchWrap());

            TextView content = text(channel.contentId, 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
            content.setSingleLine(true);
            content.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            left.addView(content, matchWrap());

            if (result != null) {
                TextView detail = text(peerDetailText(result), 12, peerColor(result), Typeface.NORMAL);
                detail.setMaxLines(2);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                left.addView(detail, matchWrap());
                row.addView(pill(peerShortStatus(result), result.available ? COLOR_SUCCESS_MUTED : COLOR_SURFACE, result.available ? COLOR_SUCCESS : COLOR_TEXT_TERTIARY), wrapWrapLeft(8));
            }

            channelRowsContainer.addView(row, matchWrapTop(channelRowsContainer.getChildCount() == 0 ? 0 : 8));
        }
    }

    private void renderChannelFeedback(String message) {
        String feedback = message;
        HapBridge.PluginChannelsInfo selectedPlugin = selectedPlugin();
        HapBridge.PeerResultInfo selectedResult = selectedChannelContentId == null ? null : peerResults.get(selectedChannelContentId);

        if (feedback == null && selectedResult != null) {
            feedback = getString(R.string.message_selected_channel_result, peerShortStatus(selectedResult), peerDetailText(selectedResult));
        }

        if (feedback == null && selectedPlugin != null) {
            int tested = 0;
            int available = 0;
            int errors = 0;
            for (HapBridge.ChannelInfo channel : selectedPlugin.channels) {
                HapBridge.PeerResultInfo result = peerResults.get(channel.contentId);
                if (result == null) continue;
                tested++;
                if (result.available) available++;
                if (!result.success || !result.error.isEmpty()) errors++;
            }
            if (tested > 0) {
                int unavailable = Math.max(0, tested - available - errors);
                feedback = getString(
                        R.string.message_list_test_summary,
                        selectedPlugin.label,
                        tested,
                        selectedPlugin.channels.size(),
                        available,
                        unavailable,
                        errors
                );
            }
        }

        if (feedback == null || feedback.trim().isEmpty()) {
            channelFeedbackView.setVisibility(View.GONE);
        } else {
            channelFeedbackView.setText(feedback);
            channelFeedbackView.setVisibility(View.VISIBLE);
        }
    }

    private void renderLogs(HapBridge.HapStatus status) {
        String log = status.log == null ? "" : status.log.trim();
        if (log.isEmpty()) {
            logView.setText(getString(R.string.message_no_log));
            return;
        }

        String[] lines = log.split("\\r?\\n");
        int start = Math.max(0, lines.length - 80);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (out.length() > 0) out.append('\n');
            out.append(lines[i]);
        }
        logView.setText(out.toString());
    }

    private void setEnabled(boolean enabled) {
        if (enabled) {
            runBusy(getString(R.string.message_starting_hap), () -> {
                HapBridge.start(this);
                boolean ready = HapBridge.waitForProxyReady(90_000);
                return ready ? getString(R.string.message_hap_ready) : getString(R.string.message_hap_start_timeout);
            }, this::refreshConfigState);
        } else {
            runBusy(getString(R.string.message_stopping_hap), () -> {
                HapBridge.stop(this);
                return getString(R.string.message_hap_stopped);
            }, this::refreshConfigState);
        }
    }

    private void restartProxy() {
        runBusy(getString(R.string.message_restarting_proxy), () -> {
            HapBridge.restartProxy(this);
            boolean ready = HapBridge.waitForProxyReady(45_000);
            return ready ? getString(R.string.message_proxy_ready) : getString(R.string.message_proxy_waiting);
        }, this::refreshConfigState);
    }

    private void prepareAio() {
        runBusy(getString(R.string.message_preparing_aio), () -> {
            HapBridge.start(this);
            boolean ready = HapBridge.waitForProxyReady(90_000);
            return ready ? getString(R.string.message_aio_ready, HapBridge.LOCAL_AIO_URL) : getString(R.string.message_aio_timeout);
        }, this::refreshConfigState);
    }

    private void setServerMode(boolean enabled) {
        runBusy(enabled ? getString(R.string.message_enabling_lan) : getString(R.string.message_disabling_lan), () -> {
            HapBridge.setServerModeEnabled(this, enabled);
            return enabled ? getString(R.string.message_lan_enabled) : getString(R.string.message_lan_disabled);
        }, this::refreshConfigState);
    }

    private void setSourceSelected(String sourceId, boolean selected) {
        runBusy(getString(R.string.message_updating_aio_selection), () -> {
            HapBridge.setSourceSelected(this, sourceId, selected);
            return getString(R.string.message_aio_selection_saved);
        }, this::refreshConfigState);
    }

    private void saveM3uSource() {
        String editId = editingM3uSourceId;
        String name = sourceNameEdit.getText().toString();
        String url = sourceUrlEdit.getText().toString();
        showMessage(getString(R.string.message_validating_source));
        showSourceFeedback(getString(R.string.message_validating_source), COLOR_BRAND);
        setBusy(true);
        executor.execute(() -> {
            try {
                HapBridge.SaveResult result = HapBridge.saveM3uSource(this, editId, name, url);
                main(() -> {
                    setBusy(false);
                    showMessage(result.message);
                    showSourceFeedback(result.message, result.success ? COLOR_SUCCESS : COLOR_ERROR);
                    if (result.success) {
                        clearSourceDraft();
                        showSourceFeedback(result.message, COLOR_SUCCESS);
                        refreshConfigState();
                    }
                    refreshRuntimeState();
                });
            } catch (Throwable error) {
                String message = errorMessage(error);
                main(() -> {
                    setBusy(false);
                    showMessage(message);
                    showSourceFeedback(message, COLOR_ERROR);
                    refreshRuntimeState();
                });
            }
        });
    }

    private void editM3uSource(HapBridge.SourceInfo source) {
        editingM3uSourceId = source.id;
        sourceNameEdit.setText(source.label);
        sourceUrlEdit.setText(source.value);
        sourceSaveButton.setText(getString(R.string.button_update_m3u_source));
        sourceCancelButton.setVisibility(View.VISIBLE);
    }

    private void clearSourceDraft() {
        editingM3uSourceId = "";
        sourceNameEdit.setText("");
        sourceUrlEdit.setText("");
        sourceSaveButton.setText(getString(R.string.button_add_m3u_source));
        sourceCancelButton.setVisibility(View.GONE);
        hideSourceFeedback();
    }

    private void deleteM3uSource(String sourceId) {
        runBusy(getString(R.string.message_deleting_source), () -> {
            HapBridge.SaveResult result = HapBridge.deleteM3uSource(this, sourceId);
            return result.message;
        }, this::refreshConfigState);
    }

    private void saveCustomChannel() {
        String name = customNameEdit == null ? "" : customNameEdit.getText().toString();
        String contentId = customContentIdEdit == null ? "" : customContentIdEdit.getText().toString();
        int editIndex = customEditingIndex;
        showMessage(getString(R.string.message_saving_custom_channel));
        setBusy(true);
        executor.execute(() -> {
            try {
                HapBridge.SaveResult result = HapBridge.saveCustomChannel(this, editIndex, name, contentId);
                main(() -> {
                    setBusy(false);
                    showMessage(result.message);
                    if (result.success) {
                        clearCustomDraft();
                        refreshConfigState();
                    }
                    refreshRuntimeState();
                });
            } catch (Throwable error) {
                main(() -> {
                    setBusy(false);
                    showMessage(errorMessage(error));
                    refreshRuntimeState();
                });
            }
        });
    }

    private void editCustomChannel(HapBridge.CustomChannelInfo channel) {
        customEditingIndex = channel.index;
        if (customNameEdit != null) customNameEdit.setText(channel.name);
        if (customContentIdEdit != null) customContentIdEdit.setText(channel.contentId);
        showMessage(getString(R.string.message_editing_channel, channel.name));
    }

    private void clearCustomDraft() {
        customEditingIndex = -1;
        if (customNameEdit != null) customNameEdit.setText("");
        if (customContentIdEdit != null) customContentIdEdit.setText("");
    }

    private void deleteCustomChannel(int index) {
        runBusy(getString(R.string.message_deleting_custom_channel), () -> {
            HapBridge.SaveResult result = HapBridge.deleteCustomChannel(this, index);
            return result.message;
        }, this::refreshConfigState);
    }

    private void loadClients() {
        clientsStatusView.setText(getString(R.string.message_loading_clients));
        executor.execute(() -> {
            try {
                List<HapBridge.ConnectedClientInfo> clients = HapBridge.connectedClients();
                main(() -> renderClients(clients));
            } catch (Throwable error) {
                main(() -> {
                    clientsStatusView.setText(errorMessage(error));
                    clientsContainer.removeAllViews();
                });
            }
        });
    }

    private void loadChannelPlugins() {
        renderChannelFeedback(getString(R.string.message_loading_lists));
        executor.execute(() -> {
            try {
                HapBridge.start(this);
                boolean ready = HapBridge.waitForProxyReady(90_000);
                if (!ready) throw new IllegalStateException(getString(R.string.message_aio_timeout));
                List<HapBridge.PluginChannelsInfo> plugins = HapBridge.channelPlugins(this);
                main(() -> {
                    channelPlugins = plugins;
                    selectedPluginId = plugins.isEmpty() ? null : plugins.get(0).id;
                    selectedChannelContentId = plugins.isEmpty() || plugins.get(0).channels.isEmpty()
                            ? null
                            : plugins.get(0).channels.get(0).contentId;
                    renderChannelPlugins();
                    renderChannelFeedback(plugins.isEmpty() ? getString(R.string.message_no_lists_available) : null);
                });
            } catch (Throwable error) {
                main(() -> renderChannelFeedback(errorMessage(error)));
            }
        });
    }

    private void checkChannel(String contentId, String channelName) {
        renderChannelFeedback(getString(R.string.message_testing_channel, channelName));
        executor.execute(() -> {
            try {
                HapBridge.PeerResultInfo result = HapBridge.checkPeers(contentId, 12);
                main(() -> {
                    peerResults.put(contentId, result);
                    renderChannels();
                    renderChannelFeedback(null);
                });
            } catch (Throwable error) {
                main(() -> renderChannelFeedback(errorMessage(error)));
            }
        });
    }

    private void checkSelectedList() {
        HapBridge.PluginChannelsInfo plugin = selectedPlugin();
        if (plugin == null) {
            renderChannelFeedback(getString(R.string.message_select_list_first));
            return;
        }

        if (channelTestTask != null && !channelTestTask.isDone()) {
            cancelChannelTest = true;
            channelTestTask.cancel(true);
        }
        cancelChannelTest = false;
        stopChannelTestButton.setEnabled(true);
        renderChannelFeedback(getString(R.string.message_testing_list_progress, plugin.label, 0, plugin.channels.size()));
        channelTestTask = executor.submit(() -> {
            int count = 0;
            for (HapBridge.ChannelInfo channel : plugin.channels) {
                if (cancelChannelTest || Thread.currentThread().isInterrupted()) break;
                count++;
                int current = count;
                main(() -> renderChannelFeedback(getString(R.string.message_testing_list_progress, channel.name, current, plugin.channels.size())));
                try {
                    HapBridge.PeerResultInfo result = HapBridge.checkPeers(channel.contentId, 8);
                    main(() -> {
                        peerResults.put(channel.contentId, result);
                        renderChannels();
                    });
                } catch (Throwable error) {
                    main(() -> renderChannelFeedback(errorMessage(error)));
                }
            }
            main(() -> {
                stopChannelTestButton.setEnabled(false);
                renderChannelFeedback(cancelChannelTest ? getString(R.string.message_test_stopped) : getString(R.string.message_list_test_complete));
            });
        });
    }

    private void stopChannelTest() {
        cancelChannelTest = true;
        if (channelTestTask != null) channelTestTask.cancel(true);
        stopChannelTestButton.setEnabled(false);
        renderChannelFeedback(getString(R.string.message_test_stopped));
    }

    private HapBridge.PluginChannelsInfo selectedPlugin() {
        if (selectedPluginId == null) return null;
        for (HapBridge.PluginChannelsInfo plugin : channelPlugins) {
            if (plugin.id.equals(selectedPluginId)) return plugin;
        }
        return null;
    }

    private HapBridge.SourceInfo customSource() {
        for (HapBridge.SourceInfo source : currentSources) {
            if ("CUSTOM".equals(source.format)) return source;
        }
        return null;
    }

    private void runBusy(String startMessage, Callable<String> work, Runnable after) {
        showMessage(startMessage);
        setBusy(true);
        executor.execute(() -> {
            String message;
            try {
                message = work.call();
            } catch (Throwable error) {
                message = errorMessage(error);
            }
            String finalMessage = message;
            main(() -> {
                setBusy(false);
                showMessage(finalMessage);
                if (after != null) after.run();
                refreshRuntimeState();
            });
        });
    }

    private void setBusy(boolean busy) {
        for (View control : busyControls) {
            control.setEnabled(!busy);
        }
    }

    private void showMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            messageView.setVisibility(View.GONE);
            return;
        }
        messageView.setText(message);
        messageView.setVisibility(View.VISIBLE);
    }

    private void showSourceFeedback(String message, int color) {
        if (sourceFeedbackView == null) return;
        if (message == null || message.trim().isEmpty()) {
            hideSourceFeedback();
            return;
        }
        sourceFeedbackView.setTextColor(color);
        sourceFeedbackView.setText(message);
        sourceFeedbackView.setVisibility(View.VISIBLE);
    }

    private void hideSourceFeedback() {
        if (sourceFeedbackView == null) return;
        sourceFeedbackView.setVisibility(View.GONE);
    }

    private void copyToClipboard(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        showMessage(getString(R.string.message_copied, value));
    }

    private void main(Runnable runnable) {
        handler.post(runnable);
    }

    private String onlineLabel(boolean value) {
        return value ? getString(R.string.status_online) : getString(R.string.status_offline);
    }

    private String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.toString() : message;
    }

    private String peerShortStatus(HapBridge.PeerResultInfo result) {
        if (result.available) return getString(R.string.peer_available, result.totalPeers);
        if (!result.success || !result.error.isEmpty()) return getString(R.string.peer_error);
        return getString(R.string.peer_no_seeds);
    }

    private String peerDetailText(HapBridge.PeerResultInfo result) {
        if (!result.error.isEmpty()) return result.error;
        if (result.available) {
            return getString(R.string.peer_detail_available, result.totalPeers, result.peers, result.httpPeers);
        }
        return result.statusText.isEmpty() ? getString(R.string.peer_detail_unavailable) : result.statusText;
    }

    private int peerColor(HapBridge.PeerResultInfo result) {
        if (result.available) return COLOR_SUCCESS;
        if (!result.success || !result.error.isEmpty()) return COLOR_ERROR;
        return COLOR_WARNING;
    }

    private String displayPhase(String rawPhase) {
        if (rawPhase == null || rawPhase.trim().isEmpty()) return getString(R.string.status_idle);
        String lower = rawPhase.toLowerCase(Locale.ROOT);
        if ("running".equals(lower)) return getString(R.string.status_running);
        if ("failed".equals(lower)) return getString(R.string.status_failed);
        if ("stopped".equals(lower)) return getString(R.string.status_stopped);
        if ("stopping".equals(lower)) return getString(R.string.phase_stopping);
        if ("preparing assets".equals(lower)) return getString(R.string.phase_preparing_assets);
        if ("starting acestream".equals(lower) || "starting aceserve".equals(lower)) return getString(R.string.phase_starting_aceserve);
        if ("starting httpaceproxy".equals(lower)) return getString(R.string.phase_starting_proxy);
        if ("restarting httpaceproxy".equals(lower)) return getString(R.string.phase_restarting_proxy);
        return rawPhase;
    }

    private RuntimeBadge runtimeBadge(HapBridge.HapStatus status) {
        if (!status.lastError.isEmpty()) {
            return new RuntimeBadge(getString(R.string.status_failed), COLOR_ERROR_MUTED, COLOR_ERROR);
        }
        if (status.aceRunning && status.proxyRunning) {
            return new RuntimeBadge(getString(R.string.status_running), COLOR_SUCCESS_MUTED, COLOR_SUCCESS);
        }
        if (status.desiredRunning) {
            return new RuntimeBadge(getString(R.string.status_starting), COLOR_WARNING_MUTED, COLOR_WARNING);
        }
        return new RuntimeBadge(getString(R.string.status_stopped), COLOR_ERROR_MUTED, COLOR_ERROR);
    }

    private TextView statusPill(String label, boolean online) {
        return pill(label, online ? COLOR_SUCCESS_MUTED : COLOR_ERROR_MUTED, online ? COLOR_SUCCESS : COLOR_ERROR);
    }

    private TextView weightedText(String value, float weight, int color, boolean bold) {
        TextView text = text(value, 12, color, bold ? Typeface.BOLD : Typeface.NORMAL);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0, 0, dp(8), 0);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        return text;
    }

    private PanelViews panel(String title, boolean collapsible, boolean expandedByDefault) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(16), dp(16), dp(16));
        container.setBackground(roundRect(COLOR_PANEL, dp(16), dp(1), COLOR_STROKE));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        if (title != null && !title.trim().isEmpty()) {
            TextView header = text(title, 17, COLOR_TEXT_PRIMARY, Typeface.BOLD);
            header.setSingleLine(true);
            header.setEllipsize(TextUtils.TruncateAt.END);
            if (collapsible) {
                header.setFocusable(true);
                header.setBackground(focusBackground(0x00000000, COLOR_SURFACE_FOCUSED, dp(10), 0, 0));
                header.setPadding(dp(8), dp(8), dp(8), dp(8));
                header.setText((expandedByDefault ? "- " : "+ ") + title);
                body.setVisibility(expandedByDefault ? View.VISIBLE : View.GONE);
                header.setOnClickListener(v -> {
                    boolean expanded = body.getVisibility() == View.VISIBLE;
                    body.setVisibility(expanded ? View.GONE : View.VISIBLE);
                    header.setText((expanded ? "+ " : "- ") + title);
                });
            }
            container.addView(header, matchWrap());
            LinearLayout.LayoutParams bodyParams = matchWrap();
            bodyParams.topMargin = dp(10);
            container.addView(body, bodyParams);
        } else {
            container.addView(body, matchWrap());
        }

        return new PanelViews(container, body);
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setTypeface(Typeface.DEFAULT, style);
        text.setIncludeFontPadding(true);
        return text;
    }

    private TextView pill(String label, int backgroundColor, int textColor) {
        TextView pill = text(label, 12, textColor, Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setBackground(roundRect(backgroundColor, dp(999), 0, 0));
        return pill;
    }

    private EditText editText(String hint, boolean multiline) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextSize(13);
        edit.setTextColor(COLOR_TEXT_PRIMARY);
        edit.setHintTextColor(COLOR_TEXT_TERTIARY);
        edit.setSingleLine(!multiline);
        edit.setMinLines(multiline ? 2 : 1);
        edit.setMaxLines(multiline ? 5 : 1);
        edit.setGravity(multiline ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL);
        edit.setInputType(InputType.TYPE_CLASS_TEXT
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_TEXT_VARIATION_URI));
        edit.setBackground(roundRect(0x33162435, dp(8), dp(1), COLOR_STROKE));
        edit.setPadding(dp(10), dp(8), dp(10), dp(8));
        return edit;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(COLOR_TEXT_PRIMARY);
        button.setAllCaps(false);
        button.setMinHeight(dp(38));
        button.setMinimumHeight(dp(38));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(focusBackground(COLOR_SURFACE, COLOR_SURFACE_FOCUSED, dp(8), dp(1), COLOR_STROKE));
        busyControls.add(button);
        return button;
    }

    private Button smallButton(String label) {
        Button button = button(label);
        button.setTextSize(12);
        button.setMinHeight(dp(32));
        button.setMinimumHeight(dp(32));
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private StateListDrawable focusBackground(int normalColor, int focusedColor, int radius, int strokeWidth, int strokeColor) {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_focused}, roundRect(focusedColor, radius, strokeWidth, COLOR_BRAND));
        drawable.addState(new int[]{android.R.attr.state_pressed}, roundRect(focusedColor, radius, strokeWidth, COLOR_BRAND));
        drawable.addState(new int[]{}, roundRect(normalColor, radius, strokeWidth, strokeColor));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isCompactWidth() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        return widthDp > 0 && widthDp < 600;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapTop(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams wrapWrapLeft(int left) {
        LinearLayout.LayoutParams params = wrapWrap();
        params.leftMargin = dp(left);
        return params;
    }

    private static final class RuntimeBadge {
        final String label;
        final int containerColor;
        final int contentColor;

        RuntimeBadge(String label, int containerColor, int contentColor) {
            this.label = label;
            this.containerColor = containerColor;
            this.contentColor = contentColor;
        }
    }

    private static final class PanelViews {
        final LinearLayout container;
        final LinearLayout body;

        PanelViews(LinearLayout container, LinearLayout body) {
            this.container = container;
            this.body = body;
        }
    }
}
