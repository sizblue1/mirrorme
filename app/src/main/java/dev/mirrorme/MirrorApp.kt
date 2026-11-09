package dev.mirrorme

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.mirrorme.data.AppDb
import dev.mirrorme.tracking.UsageSyncWorker
import org.osmdroid.config.Configuration
import java.io.File

class MirrorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDb.get(this)
        // 지도 설정: 앱 전용 캐시 폴더 사용(저장소 권한 불필요), 타일 서버 정책상 앱 이름을 밝힘
        Configuration.getInstance().apply {
            load(this@MirrorApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
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
