package com.jopsis.httpaceserveproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ExternalPlayerServerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!HapBridge.isExternalPlayerServerEnabled(context)) return;

        ServiceState.appendLog("external player server auto-start: " + action);
        try {
            HapBridge.enableExternalPlayerServer(context);
        } catch (RuntimeException error) {
            ServiceState.error("external player server auto-start failed: " + error);
        }
    }
}
