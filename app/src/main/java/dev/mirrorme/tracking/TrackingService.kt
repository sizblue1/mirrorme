package dev.mirrorme.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.mirrorme.MirrorApp
import dev.mirrorme.Prefs
import dev.mirrorme.data.AppDb
import dev.mirrorme.data.LocationPoint
import dev.mirrorme.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 동선 기록용 포그라운드 서비스.
 * 배터리를 아끼려고 GPS 대신 와이파이·기지국 위주(BALANCED)로 3분마다 위치를 받는다.
 * 3분 간격이면 하루 약 480개 좌표, 용량은 하루 수십 KB 수준.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val dao = AppDb.get(applicationContext).dao()
            scope.launch {
                for (loc in result.locations) {
                    if (loc.accuracy > ACCURACY_LIMIT_M) continue
                    dao.insertPoint(LocationPoint(time = loc.time, lat = loc.latitude, lng = loc.longitude, accuracy = loc.accuracy))
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        startUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(60_000)
            .setMaxUpdateDelayMillis(INTERVAL_MS * 3) // 몇 개씩 모아서 받으면 배터리 절약
            .build()
        client.removeLocationUpdates(callback)
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, MirrorApp.CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("하루를 기록하는 중")
            .setContentText("동선과 폰 사용을 이 기기에만 저장하고 있어요")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 3 * 60_000L
        private const val ACCURACY_LIMIT_M = 250f

        fun hasLocationPermission(c: Context) =
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(c: Context) {
            Prefs.setTrackingOn(c, true)
            ContextCompat.startForegroundService(c, Intent(c, TrackingService::class.java))
        }

        fun stop(c: Context) {
            Prefs.setTrackingOn(c, false)
            c.stopService(Intent(c, TrackingService::class.java))
        }
    }
}
