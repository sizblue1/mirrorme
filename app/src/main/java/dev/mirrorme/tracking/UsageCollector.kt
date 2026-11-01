package dev.mirrorme.tracking

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import dev.mirrorme.Prefs
import dev.mirrorme.data.AppDb
import dev.mirrorme.data.AppSession
import dev.mirrorme.data.ScreenEvent

/**
 * 안드로이드의 사용 기록(UsageEvents)을 읽어 "어떤 앱을 언제부터 언제까지 봤는지"로 바꾼다.
 * 안드로이드는 이 기록을 며칠만 보관하므로 주기적으로 앱 DB에 옮겨 둔다.
 */
object UsageCollector {

    // UsageEvents.Event 상수 (minSdk 28에서도 쓰려고 숫자로 고정)
    private const val RESUMED = 1               // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
    private const val PAUSED = 2                // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
    private const val SCREEN_ON = 15            // SCREEN_INTERACTIVE
    private const val SCREEN_OFF = 16           // SCREEN_NON_INTERACTIVE
    private const val MIN_SESSION_MS = 3_000L
    private const val MERGE_GAP_MS = 10_000L

    fun hasPermission(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun launcherPackage(c: Context): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return c.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    suspend fun sync(c: Context) {
        if (!hasPermission(c)) return
        val usm = c.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        // 처음이면 안드로이드가 보관 중인 최근 7일치를 가져온다.
        val from = Prefs.usageSyncedUntil(c).takeIf { it > 0 } ?: (now - 7L * 24 * 3600_000)
        if (now - from < 30_000) return

        val ignore = setOfNotNull(c.packageName, launcherPackage(c), "com.android.systemui")
        val events = usm.queryEvents(from, now)
        val ev = UsageEvents.Event()

        val sessions = mutableListOf<AppSession>()
        val screens = mutableListOf<ScreenEvent>()
        var curPkg: String? = Prefs.openPkg(c)
        var curStart = from

        fun close(at: Long) {
            val p = curPkg ?: return
            if (p !in ignore && at - curStart >= MIN_SESSION_MS) {
                val last = sessions.lastOrNull()
                if (last != null && last.pkg == p && curStart - last.end <= MERGE_GAP_MS) {
                    sessions[sessions.lastIndex] = last.copy(end = at)
                } else {
                    sessions += AppSession(pkg = p, start = curStart, end = at)
                }
            }
            curPkg = null
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val t = ev.timeStamp
            when (ev.eventType) {
                RESUMED -> {
                    if (curPkg != ev.packageName) {
                        close(t)
                        curPkg = ev.packageName
                        curStart = t
                    }
                }
                PAUSED -> if (curPkg == ev.packageName) close(t)
                SCREEN_OFF -> { close(t); screens += ScreenEvent(time = t, on = false) }
                SCREEN_ON -> screens += ScreenEvent(time = t, on = true)
            }
        }

        // 아직 보고 있는 앱은 지금까지만 저장하고, 다음 동기화에서 이어 붙인다.
        val stillOpen = curPkg
        close(now)
        Prefs.setOpenPkg(c, stillOpen)

        val dao = AppDb.get(c).dao()
        if (sessions.isNotEmpty()) dao.insertSessions(sessions)
        if (screens.isNotEmpty()) dao.insertScreenEvents(screens)
        Prefs.setUsageSyncedUntil(c, now)
    }

    private val labelCache = HashMap<String, String>()

    fun appLabel(c: Context, pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }
}
