package dev.mirrorme.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

/** 지도에 찍을 점. label은 동그라미 안에 들어가는 짧은 글자(번호). */
data class MapPin(
    val id: Long,
    val lat: Double,
    val lng: Double,
    val title: String,
    val label: String,
    val color: Int,
)

/**
 * OpenStreetMap 지도. 장소 점과 이동 경로를 그리고, focus가 바뀌면 그 장소로 이동한다.
 * 리스트 안에 있어도 지도를 끌 수 있도록 터치를 지도가 가져간다.
 */
@Composable
fun PlaceMap(pins: List<MapPin>, route: List<GeoPoint>, focus: MapPin?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val markers = remember { HashMap<Long, Marker>() }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 5.0
            maxZoomLevel = 19.0
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(pins, route, dark) {
        mapView.overlays.clear()
        markers.clear()
        // 어두운 테마에서는 지도 색을 뒤집어 눈부심을 줄인다
        mapView.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)

        if (route.size >= 2) {
            mapView.overlays.add(Polyline(mapView).apply {
                setPoints(route)
                outlinePaint.color = if (dark) 0xCC9DB2EC.toInt() else 0xCC2F4F9E.toInt()
                outlinePaint.strokeWidth = 4f * context.resources.displayMetrics.density
                outlinePaint.strokeCap = Paint.Cap.ROUND
            })
        }
        for (p in pins) {
            val m = Marker(mapView).apply {
                position = GeoPoint(p.lat, p.lng)
                title = p.title
                icon = dotDrawable(context, p.color, p.label)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            markers[p.id] = m
            mapView.overlays.add(m)
        }
        mapView.invalidate()

        val all = pins.map { GeoPoint(it.lat, it.lng) } + route
        if (all.isNotEmpty()) {
            if (mapView.isLayoutOccurred) fitTo(mapView, all)
            else mapView.addOnFirstLayoutListener { _, _, _, _, _ -> fitTo(mapView, all) }
        }
    }

    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(f.lat, f.lng), 16.0, 600L)
        markers[f.id]?.showInfoWindow()
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun fitTo(map: MapView, points: List<GeoPoint>) {
    if (points.size == 1) {
        map.controller.setZoom(15.0)
        map.controller.setCenter(points[0])
        return
    }
    val box = BoundingBox.fromGeoPointsSafe(points)
    val pad = (36 * map.resources.displayMetrics.density).toInt()
    map.zoomToBoundingBox(box, false, pad)
    if (map.zoomLevelDouble > 16.0) map.controller.setZoom(16.0)
}

/** 번호가 들어간 동그라미 표시. */
private fun dotDrawable(c: Context, color: Int, label: String): Drawable {
    val d = c.resources.displayMetrics.density
    val size = (26 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    p.color = Color.WHITE
    cv.drawCircle(r, r, r, p)
    p.color = color
    cv.drawCircle(r, r, r - 2 * d, p)
    p.color = Color.WHITE
    p.textSize = (if (label.length > 2) 9 else 11) * d
    p.textAlign = Paint.Align.CENTER
    p.typeface = Typeface.DEFAULT_BOLD
    cv.drawText(label, r, r - (p.descent() + p.ascent()) / 2, p)
    return BitmapDrawable(c.resources, bmp)
}

/** 폰에 설치된 지도 앱(네이버지도, 구글 지도 등)으로 그 위치를 연다. */
fun openInMaps(c: Context, lat: Double, lng: Double, name: String) {
    val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")
    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
        .onFailure {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://map.naver.com/p?c=$lng,$lat,16,0,0,0,dh")))
        }
}
