package com.treadmill.ftms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("FtmsBridge", "Boot completed — starting FTMS bridge service");
            Intent service = new Intent(context, FtmsBridgeService.class);
            context.startForegroundService(service);
        }
    }
}
