package dev.mirrorme.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mirrorme.Prefs
import dev.mirrorme.analysis.PlaceBlock
import dev.mirrorme.analysis.Repo
import dev.mirrorme.analysis.RoutineAnalyzer
import dev.mirrorme.analysis.TimelineItem
import dev.mirrorme.data.Place
import dev.mirrorme.tracking.TrackingService
import dev.mirrorme.tracking.UsageCollector
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// ───────────────────────── 공통 ─────────────────────────

internal fun hm(ms: Long?): String = ms?.let {
    val t = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()
    "%02d:%02d".format(t.hour, t.minute)
} ?: "–"

internal fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

internal fun dur(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}

internal val PlacePalette = listOf(
    Color(0xFF2F4F9E), Color(0xFFD08A2E), Color(0xFF4E8F6A), Color(0xFF9A5BA8),
    Color(0xFFC0504D), Color(0xFF3F8FA8), Color(0xFF8A7F3A),
)

internal fun placeColor(id: Long) = PlacePalette[(id % PlacePalette.size).toInt()]

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
internal fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp))
}

internal fun hasAllPermissions(c: Context) =
    TrackingService.hasLocationPermission(c) && UsageCollector.hasPermission(c)

// ───────────────────────── 루틴 ─────────────────────────

@Composable
private fun DayBar(blocks: List<PlaceBlock>) {
    Column {
        Row(Modifier.fillMaxWidth().height(22.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
            var h = 0
            for (b in blocks) {
                if (b.startHour > h) Spacer(Modifier.weight((b.startHour - h).toFloat()))
                Box(Modifier.weight((b.endHour - b.startHour).toFloat()).fillMaxHeight().background(placeColor(b.placeId)))
                h = b.endHour
            }
            if (h < 24) Spacer(Modifier.weight((24 - h).toFloat()))
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("0", "6", "12", "18", "24").forEachIndexed { i, l ->
                Text(l, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (i < 4) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun RoutineScreen(repo: Repo, refresh: Int, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<Repo.RoutineData?>(null) }
    LaunchedEffect(refresh) { data = repo.routine() }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Text("나의 루틴", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 20.dp))
        }
        val d = data
        if (d == null) { item { Hint("최근 4주 기록을 분석하는 중…") }; return@LazyColumn }
        val r = d.routine
        fun pn(id: Long) = d.places[id]?.name ?: "어딘가"
        item {
            Text("최근 ${r.daysAnalyzed}일 기록 기준. 일주일 정도 쌓이면 패턴이 또렷해져요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { SectionTitle("수면 (화면이 오래 꺼져 있던 시간으로 추정)") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(r.weekdaySleep?.let { "${hm(it.first)}→${hm(it.second)}" } ?: "–", "평일", Modifier.weight(1f))
                Stat(r.weekendSleep?.let { "${hm(it.first)}→${hm(it.second)}" } ?: "–", "주말", Modifier.weight(1f))
            }
        }

        for ((title, blocks) in listOf("평일 하루" to r.weekdayBlocks, "주말 하루" to r.weekendBlocks)) {
            item { SectionTitle(title) }
            if (blocks.isEmpty()) item { Hint("아직 반복되는 동선이 없어요.") }
            else {
                item { DayBar(blocks) }
                items(blocks) { b ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(placeColor(b.placeId), CircleShape))
                        Text("  %02d–%02d시  %s".format(b.startHour, b.endHour, pn(b.placeId)), Modifier.weight(1f))
                        Text("${(b.share * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (r.weeklyVisits.isNotEmpty()) {
            item { SectionTitle("정해진 요일에 가는 곳") }
            items(r.weeklyVisits) { v ->
                val days = v.days.joinToString("·") { it.getDisplayName(TextStyle.SHORT, Locale.KOREAN) }
                Text("${pn(v.placeId)} · $days ${hm(v.typicalArrival)}쯤 · ${v.visits}회", Modifier.padding(vertical = 3.dp))
            }
        }

        item { SectionTitle("시간대별 폰 습관 (하루 평균)") }
        if (r.appHabits.isEmpty()) item { Hint("아직 앱 사용 기록이 부족해요.") }
        for ((band, _) in RoutineAnalyzer.BANDS) {
            val hs = r.appHabits.filter { it.band == band }
            if (hs.isNotEmpty()) item {
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(band, Modifier.width(48.dp), fontWeight = FontWeight.SemiBold)
                    Text(hs.joinToString(", ") { "${repo.appLabel(it.pkg)} %.0f분".format(it.avgMinPerDay) })
                }
            }
        }

        item {
            SectionTitle("나를 닮은 AI 만들기")
            Text("루틴을 글로 정리해서 AI에 넘길 수 있어요. 좌표는 빼고 장소 이름만 들어가요.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 8.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val text = repo.profileText()
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "생활 패턴 공유"))
                    }
                }) { Text("요약 공유하기") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("생활 패턴", repo.profileText()))
                    }
                }) { Text("복사") }
            }
        }
    }
}

// ───────────────────────── 설정 ─────────────────────────

@Composable
private fun PermissionRow(title: String, desc: String, granted: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) Text("허용됨", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        else OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
fun SettingsScreen(repo: Repo, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val fine = remember(tick) { TrackingService.hasLocationPermission(context) }
    val background = remember(tick) { Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val batteryFree = remember(tick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val usage = remember(tick) { UsageCollector.hasPermission(context) }
    val notif = remember(tick) { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) }
    var trackingOn by remember { mutableStateOf(Prefs.trackingOn(context)) }
    var counts by remember { mutableStateOf<Repo.Counts?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { counts = repo.counts() }

    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Text("설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            SectionTitle("권한 (순서대로 허용해 주세요)")
            PermissionRow("1. 위치", "머문 장소와 이동을 기록해요", fine, "허용") {
                locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            PermissionRow("2. 위치 – 항상 허용", "앱을 닫아도 기록하려면 '항상 허용'을 골라 주세요", background, "열기") {
                if (fine) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            PermissionRow("3. 사용 기록 접근", "목록에서 '미러미'를 찾아 켜 주세요", usage, "열기") {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionRow("4. 알림", "기록 중 표시를 띄우는 데 필요해요", notif, "허용") {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            PermissionRow("배터리 최적화 제외 (권장)", "제조사가 앱을 꺼 버리는 걸 막아 줘요. 목록에서 '미러미'를 '제한 없음'으로", batteryFree, "열기") {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
            }
        }
        item {
            SectionTitle("기록")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("동선 기록", fontWeight = FontWeight.SemiBold)
                    Text(if (trackingOn) "3분마다 위치를 저장하고 있어요" else "꺼져 있어요",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = trackingOn, enabled = fine, onCheckedChange = { on ->
                    if (on) TrackingService.start(context) else TrackingService.stop(context)
                    trackingOn = on
                })
            }
            counts?.let {
                Text("저장된 위치 ${it.points}개 · 앱 사용 구간 ${it.sessions}개 · 마지막 위치 ${hm(it.lastPoint)}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            SectionTitle("개인정보")
            Text("모든 기록은 이 폰 안에만 저장되고 앱을 지우면 함께 사라져요. 지도를 볼 때만 화면에 보이는 지역의 지도 이미지(OpenStreetMap)와 장소 주소(안드로이드 주소 변환)를 인터넷에서 받아와요.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 12.dp, bottom = 32.dp)) {
                Text("모든 기록 지우기")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("모든 기록을 지울까요?") },
            text = { Text("동선, 앱 사용 기록, 장소 이름이 모두 지워지고 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.clearAll(); confirmDelete = false; tick++ } }) { Text("지우기") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}
