package dev.mirrorme

import android.content.Context

/** 간단한 설정값 저장. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("mirror", Context.MODE_PRIVATE)

    fun trackingOn(c: Context) = sp(c).getBoolean("tracking_on", false)
    fun setTrackingOn(c: Context, on: Boolean) = sp(c).edit().putBoolean("tracking_on", on).apply()

    /** 앱 사용 기록을 마지막으로 읽어 온 시각. */
    fun usageSyncedUntil(c: Context) = sp(c).getLong("usage_synced_until", 0L)
    fun setUsageSyncedUntil(c: Context, t: Long) = sp(c).edit().putLong("usage_synced_until", t).apply()

    /** 지난 동기화 끝에 아직 열려 있던 앱(다음 동기화에서 이어 붙임). */
    fun openPkg(c: Context): String? = sp(c).getString("open_pkg", null)
    fun setOpenPkg(c: Context, pkg: String?) = sp(c).edit().putString("open_pkg", pkg).apply()
}
