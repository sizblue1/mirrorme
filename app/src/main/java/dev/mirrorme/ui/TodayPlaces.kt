package dev.mirrorme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mirrorme.Prefs
import dev.mirrorme.analysis.Repo
import dev.mirrorme.analysis.TimelineItem
import dev.mirrorme.data.Place
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun NumberDot(label: String, color: Color) {
    Box(Modifier.size(22.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = if (label.length > 2) 9.sp else 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ───────────────────────── 오늘 ─────────────────────────

@Composable
fun TodayScreen(repo: Repo, refresh: Int, modifier: Modifier, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(LocalDate.now()) }
    var data by remember { mutableStateOf<Repo.TodayData?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf<MapPin?>(null) }
    val addr = remember { mutableStateMapOf<Long, String>() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    LaunchedEffect(date, refresh, resumeTick) { data = repo.day(date) }

    val d = data
    // 그날 처음 들른 순서대로 번호를 붙인다 (같은 곳을 다시 가면 같은 번호)
    val order = remember(d) {
        d?.summary?.items?.filterIsInstance<TimelineItem.AtPlace>()?.map { it.placeId }?.distinct().orEmpty()
    }
    val pins = remember(d) {
        order.mapIndexedNotNull { i, id ->
            d?.places?.get(id)?.let { MapPin(id, it.lat, it.lng, it.name, "${i + 1}", placeColor(id).toArgb()) }
        }
    }
    val route = remember(d) { d?.route?.map { GeoPoint(it.lat, it.lng) }.orEmpty() }
    LaunchedEffect(d) {
        focus = null
        if (d == null) return@LaunchedEffect
        for (id in order) {
            if (addr.containsKey(id)) continue
            val p = d.places[id] ?: continue
            addr[id] = repo.address(p) ?: ""
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { date = date.minusDays(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "전날") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${date.monthValue}월 ${date.dayOfMonth}일 ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (date == LocalDate.now()) Text("오늘", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
                IconButton(onClick = { date = date.plusDays(1) }, enabled = date < LocalDate.now()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 날")
                }
            }
        }
        if (!hasAllPermissions(context) || !Prefs.trackingOn(context)) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onOpenSettings() }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("기록이 아직 꺼져 있어요", fontWeight = FontWeight.SemiBold)
                        Text("설정 탭에서 권한을 허용하고 기록을 켜 주세요.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (d == null) {
            item { Hint("불러오는 중…") }
            return@LazyColumn
        }
        val s = d.summary
        item {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(dur(s.screenTimeMs), "화면 사용", Modifier.weight(1f))
                Stat("${s.screenOnCount}회", "화면 켬", Modifier.weight(1f))
                Stat(hm(s.firstOn), "첫 사용", Modifier.weight(1f))
            }
        }
        if (pins.isNotEmpty() || route.size >= 2) {
            item {
                PlaceMap(pins, route, focus, Modifier.fillMaxWidth().padding(top = 20.dp).height(280.dp))
                Text("선은 기록된 이동 경로예요. 아래 일정을 누르면 지도가 그곳으로 이동해요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { SectionTitle("동선") }
        if (s.items.isEmpty()) item { Hint("이 날은 아직 머문 곳이 기록되지 않았어요. 한 곳에 10분 이상 있으면 여기에 나타나요.") }
        items(s.items) { item ->
            when (item) {
                is TimelineItem.AtPlace -> {
                    val p = d.places[item.placeId]
                    val n = order.indexOf(item.placeId) + 1
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { focus = pins.firstOrNull { it.id == item.placeId } }
                            .padding(vertical = 6.dp),
                    ) {
                        Text(hm(item.start), Modifier.width(52.dp), fontWeight = FontWeight.Medium)
                        NumberDot("$n", placeColor(item.placeId))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(p?.name ?: "알 수 없는 곳", fontWeight = FontWeight.SemiBold)
                            addr[item.placeId]?.takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text("${hm(item.start)}–${hm(item.end)} · ${dur(item.end - item.start)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item.topApps.isNotEmpty()) {
                                Text(item.topApps.joinToString("  ") { (pkg, ms) -> "${repo.appLabel(pkg)} ${dur(ms)}" },
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                is TimelineItem.Moving -> Row(Modifier.padding(vertical = 2.dp)) {
                    Spacer(Modifier.width(52.dp))
                    Text("  ↓ 이동 ${dur(item.end - item.start)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionTitle("많이 쓴 앱") }
        if (s.topApps.isEmpty()) item { Hint("사용 기록 접근을 허용하면 앱별 사용 시간이 보여요.") }
        items(s.topApps) { (pkg, ms) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(repo.appLabel(pkg), Modifier.weight(1f))
                Text(dur(ms), fontWeight = FontWeight.Medium)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ───────────────────────── 장소 ─────────────────────────

@Composable
fun PlacesScreen(repo: Repo, refresh: Int, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var editing by remember { mutableStateOf<Place?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf<MapPin?>(null) }
    val addr = remember { mutableStateMapOf<Long, String>() }
    LaunchedEffect(refresh, reload) { places = repo.places() }
    LaunchedEffect(places) {
        for (p in places) if (!addr.containsKey(p.id)) addr[p.id] = repo.address(p) ?: ""
    }
    val pins = remember(places) {
        places.mapIndexed { i, p -> MapPin(p.id, p.lat, p.lng, p.name, "${i + 1}", placeColor(p.id).toArgb()) }
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("장소", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 20.dp))
            Text("10분 이상 머문 곳이 자동으로 모여요. 목록을 누르면 지도에서 그 장소를 보여 줘요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        }
        if (pins.isNotEmpty()) {
            PlaceMap(pins, emptyList(), focus, Modifier.fillMaxWidth().height(260.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            if (places.isEmpty()) item { Hint("아직 저장된 장소가 없어요.") }
            itemsIndexed(places, key = { _, p -> p.id }) { i, p ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { focus = pins.firstOrNull { it.id == p.id } }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NumberDot("${i + 1}", placeColor(p.id))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        val a = addr[p.id]
                        Text(
                            when {
                                a == null -> "주소 찾는 중…"
                                a.isEmpty() -> "주소를 찾지 못했어요"
                                else -> a
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text("%.4f, %.4f".format(p.lat, p.lng), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { editing = p }) { Text("이름") }
                    TextButton(onClick = { openInMaps(context, p.lat, p.lng, p.name) }) { Text("지도앱") }
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("장소 이름") },
            text = {
                Column {
                    addr[p.id]?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it.take(20) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { repo.renamePlace(p, name); editing = null; reload++ }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("취소") } },
        )
    }
}
