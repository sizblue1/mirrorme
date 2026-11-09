package dev.mirrorme.analysis

import android.content.Context
import dev.mirrorme.data.AppDb
import dev.mirrorme.data.LocationPoint
import dev.mirrorme.data.Place
import dev.mirrorme.tracking.UsageCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** 화면에서 쓰는 데이터를 모아 계산해 주는 곳. 모든 계산은 기기 안에서만 한다. */
class Repo(private val context: Context) {
    private val dao = AppDb.get(context).dao()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    companion object {
        const val PLACE_RADIUS_M = 150.0
        const val ROUTINE_DAYS = 28L
    }

    /** 머문 곳을 계산하고 가까운 장소에 붙인다. 처음 보는 곳이면 새 장소를 만든다. */
    private suspend fun staysWithPlaces(from: Long, to: Long): Pair<List<Stay>, MutableList<Place>> {
        val raw = StayDetector.detect(dao.points(from, to))
        val places = dao.places().toMutableList()
        val assigned = raw.map { s ->
            val near = places.minByOrNull { distanceM(it.lat, it.lng, s.lat, s.lng) }
                ?.takeIf { distanceM(it.lat, it.lng, s.lat, s.lng) <= PLACE_RADIUS_M }
            val place = near ?: run {
                val p = Place(lat = s.lat, lng = s.lng, name = "장소 ${places.size + 1}")
                p.copy(id = dao.insertPlace(p)).also { places += it }
            }
            s.copy(placeId = place.id)
        }
        return StayDetector.mergeSamePlace(assigned) to places
    }

    data class TodayData(
        val date: LocalDate,
        val summary: DaySummary,
        val places: Map<Long, Place>,
        val route: List<LocationPoint>,
    )

    suspend fun day(date: LocalDate): TodayData = withContext(Dispatchers.IO) {
        UsageCollector.sync(context)
        val s = date.startMs(zone)
        val e = s + 86_400_000L
        val (stays, places) = staysWithPlaces(s - 12 * 3600_000L, e)
        val summary = TimelineBuilder.build(stays, dao.sessions(s, e), dao.screenEvents(s, e), s, e)
        TodayData(date, summary, places.associateBy { it.id }, dao.points(s, e))
    }

    suspend fun address(p: Place): String? = Geo.address(context, p.lat, p.lng)

    data class RoutineData(val routine: Routine, val places: Map<Long, Place>)

    suspend fun routine(): RoutineData = withContext(Dispatchers.IO) {
        UsageCollector.sync(context)
        val today = LocalDate.now(zone)
        val fromDate = today.minusDays(ROUTINE_DAYS)
        val from = fromDate.startMs(zone)
        val to = System.currentTimeMillis()
        val (stays, places) = staysWithPlaces(from, to)
        val r = RoutineAnalyzer.analyze(stays, dao.sessions(from, to), dao.screenEvents(from - 86_400_000L, to), fromDate, today, zone)

        // 이름을 직접 붙이지 않은 장소는 집/회사로 자동 이름 붙이기
        suspend fun autoName(id: Long?, name: String) {
            val i = places.indexOfFirst { it.id == id }
            if (i < 0 || places[i].userNamed || places.any { it.name == name }) return
            places[i] = places[i].copy(name = name).also { dao.updatePlace(it) }
        }
        autoName(r.homeId, "집")
        autoName(r.workId, "회사·학교")
        RoutineData(r, places.associateBy { it.id })
    }

    suspend fun places(): List<Place> = withContext(Dispatchers.IO) { dao.places() }

    suspend fun renamePlace(p: Place, name: String) = withContext(Dispatchers.IO) {
        dao.updatePlace(p.copy(name = name.trim(), userNamed = true))
    }

    data class Counts(val points: Int, val sessions: Int, val lastPoint: Long?)

    suspend fun counts(): Counts = withContext(Dispatchers.IO) {
        Counts(dao.pointCount(), dao.sessionCount(), dao.lastPointTime())
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearPoints(); dao.clearSessions(); dao.clearScreen(); dao.clearPlaces()
    }

    fun appLabel(pkg: String) = UsageCollector.appLabel(context, pkg)

    /**
     * 루틴을 사람이 읽을 수 있는 글로 정리한다.
     * 이 글을 Claude 같은 AI에 붙여 넣고 "이 사람처럼 생각해서 답해줘"라고 하면 '나를 닮은 AI'의 출발점이 된다.
     * 좌표는 넣지 않고 장소 이름만 넣는다.
     */
    suspend fun profileText(): String {
        val (r, places) = routine()
        fun pn(id: Long) = places[id]?.name ?: "어딘가"
        fun t(x: LocalTime) = "%02d:%02d".format(x.hour, x.minute)
        fun blocks(b: List<PlaceBlock>) = b.joinToString("\n") {
            "  - %02d시~%02d시: %s (%d%%의 날)".format(it.startHour, it.endHour, pn(it.placeId), (it.share * 100).toInt())
        }.ifEmpty { "  - 아직 데이터가 부족해요" }

        return buildString {
            appendLine("# 나의 생활 패턴 (최근 ${r.daysAnalyzed}일 기록 기준, 자동 생성)")
            appendLine()
            appendLine("## 수면")
            r.weekdaySleep?.let { appendLine("- 평일: ${t(it.first)}쯤 잠들고 ${t(it.second)}쯤 일어남") }
            r.weekendSleep?.let { appendLine("- 주말: ${t(it.first)}쯤 잠들고 ${t(it.second)}쯤 일어남") }
            if (r.weekdaySleep == null && r.weekendSleep == null) appendLine("- 아직 데이터가 부족해요")
            appendLine()
            appendLine("## 평일 하루 동선")
            appendLine(blocks(r.weekdayBlocks))
            appendLine("## 주말 하루 동선")
            appendLine(blocks(r.weekendBlocks))
            appendLine()
            if (r.weeklyVisits.isNotEmpty()) {
                appendLine("## 정해진 요일에 가는 곳")
                r.weeklyVisits.forEach { v ->
                    val days = v.days.joinToString("·") { it.getDisplayName(TextStyle.SHORT, Locale.KOREAN) }
                    appendLine("- ${pn(v.placeId)}: $days, 보통 ${t(v.typicalArrival)}쯤 도착 (${v.visits}회)")
                }
                appendLine()
            }
            appendLine("## 폰 사용")
            appendLine("- 하루 평균 화면 사용 %.0f분".format(r.avgScreenMinPerDay))
            RoutineAnalyzer.BANDS.forEach { (band, _) ->
                val h = r.appHabits.filter { it.band == band }
                if (h.isNotEmpty()) appendLine("- $band: " + h.joinToString(", ") { "${appLabel(it.pkg)} %.0f분".format(it.avgMinPerDay) })
            }
        }
    }
}
