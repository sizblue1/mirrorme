package dev.mirrorme

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.mirrorme.data.AppDb
import dev.mirrorme.tracking.UsageSyncWorker

class MirrorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDb.get(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "기록 중 알림", NotificationManager.IMPORTANCE_MIN).apply {
                description = "동선을 기록하는 동안 표시되는 알림"
                setShowBadge(false)
            }
        )
        UsageSyncWorker.schedule(this)
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
    }
}
