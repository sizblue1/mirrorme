package dev.mirrorme.analysis

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 좌표 → 한국어 주소. 안드로이드 기본 주소 변환(Geocoder)을 쓰고,
 * 한 번 찾은 주소는 폰에 저장해 두어 다시 묻지 않는다.
 */
object Geo {
    private val mem = HashMap<String, String>()

    private fun key(lat: Double, lng: Double) = String.format(Locale.US, "%.4f,%.4f", lat, lng)

    suspend fun address(c: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val k = key(lat, lng)
        mem[k]?.let { return@withContext it }
        val sp = c.getSharedPreferences("addr", Context.MODE_PRIVATE)
        sp.getString(k, null)?.let { mem[k] = it; return@withContext it }
        if (!Geocoder.isPresent()) return@withContext null

        val line = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(c, Locale.KOREA).getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0)
        }.getOrNull() ?: return@withContext null

        val text = line.removePrefix("대한민국").trim()
        mem[k] = text
        sp.edit().putString(k, text).apply()
        text
    }
}
