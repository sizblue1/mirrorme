package dev.mirrorme.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mirrorme.Prefs

/** 폰을 다시 켜거나 앱을 업데이트하면 기록을 이어서 시작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.trackingOn(context) && TrackingService.hasLocationPermission(context)) {
            runCatching { TrackingService.start(context) }
        }
        UsageSyncWorker.schedule(context)
    }
}
