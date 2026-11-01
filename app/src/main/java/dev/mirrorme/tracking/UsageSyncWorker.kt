package dev.mirrorme.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mirrorme.Prefs
import java.util.concurrent.TimeUnit

/** 15분마다(안드로이드가 허용하는 최소 주기) 사용 기록을 옮기고, 꺼진 위치 서비스를 되살린다. */
class UsageSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        UsageCollector.sync(applicationContext)
        if (Prefs.trackingOn(applicationContext) && TrackingService.hasLocationPermission(applicationContext)) {
            runCatching { TrackingService.start(applicationContext) }
        }
        return Result.success()
    }

    companion object {
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("usage_sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
