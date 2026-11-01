package dev.mirrorme.analysis

import dev.mirrorme.data.AppSession
import dev.mirrorme.data.LocationPoint
import dev.mirrorme.data.ScreenEvent
import dev.mirrorme.data.durationMs
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ───────────────────────── 공통 ─────────────────────────

fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

fun Long.toLocalTime(zone: ZoneId): LocalTime = Instant.ofEpochMilli(this).atZone(zone).toLocalTime()
fun Long.toLocalDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
fun LocalDate.startMs(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

// ───────────────────────── 머문 곳 감지 ─────────────────────────

/** 한 장소에 일정 시간 이상 머문 구간. placeId는 장소 묶기 후에 채워진다. */
data class Stay(
    val lat: Double,
    val lng: Double,
    val start: Long,
    val end: Long,
    val placeId: Long = 0,
)

object StayDetector {
    const val RADIUS_M = 120.0
    const val MIN_STAY_MS = 10 * 60_000L

    /** 반경 120m 안에서 10분 이상 이어진 좌표 묶음을 "머문 곳"으로 본다. */
    fun detect(points: List<LocationPoint>): List<Stay> {
        val out = mutableListOf<Stay>()
        var group = mutableListOf<LocationPoint>()
        var cLat = 0.0
        var cLng = 0.0

        fun flush() {
            if (group.size >= 2 && group.last().time - group.first().time >= MIN_STAY_MS) {
                out += Stay(cLat, cLng, group.first().time, group.last().time)
            }
        }

        for (p in points) {
            if (group.isEmpty()) {
                group.add(p); cLat = p.lat; cLng = p.lng
            } else if (distanceM(cLat, cLng, p.lat, p.lng) <= RADIUS_M) {
                group.add(p)
                cLat += (p.lat - cLat) / group.size
                cLng += (p.lng - cLng) / group.size
            } else {
                flush()
                group = mutableListOf(p); cLat = p.lat; cLng = p.lng
            }
        }
        flush()
        return out
    }

    /** 같은 장소에 잠깐 끊겼다 다시 잡힌 구간(30분 이내)은 하나로 합친다. */
    fun mergeSamePlace(stays: List<Stay>, maxGapMs: Long = 30 * 60_000L): List<Stay> {
        val out = mutableListOf<Stay>()
        for (s in stays.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && last.placeId != 0L && last.placeId == s.placeId && s.start - last.end <= maxGapMs) {
                out[out.lastIndex] = last.copy(end = maxOf(last.end, s.end))
            } else out += s
        }
        return out
    }
}

// ───────────────────────── 하루 타임라인 ─────────────────────────

sealed interface TimelineItem {
    val start: Long
    val end: Long

    data class AtPlace(
        val placeId: Long,
        override val start: Long,
        override val end: Long,
        val topApps: List<Pair<String, Long>>, // pkg to ms
    ) : TimelineItem

    data class Moving(override val start: Long, override val end: Long) : TimelineItem
}

data class DaySummary(
    val items: List<TimelineItem>,
    val screenTimeMs: Long,
    val screenOnCount: Int,
    val firstOn: Long?,
    val lastOff: Long?,
    val topApps: List<Pair<String, Long>>,
)

object TimelineBuilder {
    fun build(stays: List<Stay>, sessions: List<AppSession>, screens: List<ScreenEvent>, dayStart: Long, dayEnd: Long): DaySummary {
        fun clip(s: AppSession, from: Long, to: Long) = (minOf(s.end, to) - maxOf(s.start, from)).coerceAtLeast(0)
        fun topIn(from: Long, to: Long, n: Int): List<Pair<String, Long>> =
            sessions.groupBy { it.pkg }
                .mapValues { (_, v) -> v.sumOf { clip(it, from, to) } }
                .filter { it.value >= 60_000 }
                .entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

        val items = mutableListOf<TimelineItem>()
        val dayStays = stays.filter { it.end > dayStart && it.start < dayEnd }.sortedBy { it.start }
        for ((i, s) in dayStays.withIndex()) {
            val st = maxOf(s.start, dayStart)
            val en = minOf(s.end, dayEnd)
            if (i > 0) {
                val prevEnd = (items.last() as TimelineItem.AtPlace).end
                if (st - prevEnd >= 5 * 60_000) items += TimelineItem.Moving(prevEnd, st)
            }
            items += TimelineItem.AtPlace(s.placeId, st, en, topIn(st, en, 3))
        }

        val screenTime = sessions.sumOf { clip(it, dayStart, dayEnd) }
        val ons = screens.filter { it.on && it.time in dayStart..dayEnd }
        val offs = screens.filter { !it.on && it.time in dayStart..dayEnd }
        return DaySummary(
            items = items,
            screenTimeMs = screenTime,
            screenOnCount = ons.size,
            firstOn = ons.firstOrNull()?.time,
            lastOff = offs.lastOrNull()?.time,
            topApps = topIn(dayStart, dayEnd, 5),
        )
    }
}

// ───────────────────────── 루틴 분석 ─────────────────────────

data class SleepNight(val date: LocalDate, val sleep: Long, val wake: Long)

data class PlaceBlock(val startHour: Int, val endHour: Int, val placeId: Long, val share: Double)

data class WeeklyVisit(val placeId: Long, val days: List<DayOfWeek>, val typicalArrival: LocalTime, val visits: Int)

data class AppHabit(val band: String, val pkg: String, val avgMinPerDay: Double)

data class Routine(
    val daysAnalyzed: Int,
    val weekdaySleep: Pair<LocalTime, LocalTime>?,  // 잠드는 시각, 일어나는 시각
    val weekendSleep: Pair<LocalTime, LocalTime>?,
    val weekdayBlocks: List<PlaceBlock>,
    val weekendBlocks: List<PlaceBlock>,
    val weeklyVisits: List<WeeklyVisit>,
    val appHabits: List<AppHabit>,
    val avgScreenMinPerDay: Double,
    val homeId: Long?,
    val workId: Long?,
)

object RoutineAnalyzer {

    val BANDS = listOf("새벽" to (0 until 6), "오전" to (6 until 12), "오후" to (12 until 18), "저녁" to (18 until 24))

    /** 화면이 3시간 이상 꺼져 있던 가장 긴 구간을 그날의 잠으로 본다. */
    fun sleepNights(screens: List<ScreenEvent>, zone: ZoneId): List<SleepNight> {
        val byWakeDay = HashMap<LocalDate, SleepNight>()
        var lastOff: Long? = null
        for (e in screens.sortedBy { it.time }) {
            if (!e.on) { lastOff = e.time; continue }
            val off = lastOff ?: continue
            lastOff = null
            val gap = e.time - off
            val wakeT = e.time.toLocalTime(zone)
            if (gap < 3 * 3600_000L || wakeT.hour < 3 || wakeT.hour >= 14) continue
            val d = e.time.toLocalDate(zone)
            val prev = byWakeDay[d]
            if (prev == null || gap > prev.wake - prev.sleep) byWakeDay[d] = SleepNight(d, off, e.time)
        }
        return byWakeDay.values.sortedBy { it.date }
    }

    /** 시각의 중앙값. 자정을 넘는 취침 시각도 다룰 수 있게 정오 기준으로 돌려서 계산. */
    fun medianTime(times: List<LocalTime>): LocalTime? {
        if (times.isEmpty()) return null
        val shifted = times.map { (it.toSecondOfDay() + 12 * 3600) % 86400 }.sorted()
        val mid = shifted[shifted.size / 2]
        return LocalTime.ofSecondOfDay(((mid - 12 * 3600 + 86400) % 86400).toLong())
    }

    fun analyze(
        stays: List<Stay>,
        sessions: List<AppSession>,
        screens: List<ScreenEvent>,
        fromDate: LocalDate,
        toDate: LocalDate,
        zone: ZoneId,
    ): Routine {
        val dates = generateSequence(fromDate) { it.plusDays(1) }.takeWhile { !it.isAfter(toDate) }.toList()

        // 각 날짜·시각에 어디 있었는지
        fun placeAt(t: Long): Long? = stays.firstOrNull { t in it.start..it.end }?.placeId?.takeIf { it != 0L }
        val stayDays = dates.filter { d -> val s = d.startMs(zone); stays.any { it.end > s && it.start < s + 86_400_000 } }

        fun blocksFor(days: List<LocalDate>): List<PlaceBlock> {
            if (days.isEmpty()) return emptyList()
            val hourly = (0 until 24).map { h ->
                val counts = HashMap<Long, Int>()
                for (d in days) {
                    val t = d.startMs(zone) + h * 3600_000L + 30 * 60_000L
                    placeAt(t)?.let { counts[it] = (counts[it] ?: 0) + 1 }
                }
                counts.maxByOrNull { it.value }?.let { (id, n) -> id to n.toDouble() / days.size }
                    ?.takeIf { it.second >= 0.4 }
            }
            val out = mutableListOf<PlaceBlock>()
            for (h in 0 until 24) {
                val cell = hourly[h] ?: continue
                val last = out.lastOrNull()
                if (last != null && last.placeId == cell.first && last.endHour == h) {
                    out[out.lastIndex] = last.copy(endHour = h + 1, share = minOf(last.share, cell.second))
                } else out += PlaceBlock(h, h + 1, cell.first, cell.second)
            }
            return out
        }

        val weekdayBlocks = blocksFor(stayDays.filterNot(::isWeekend))
        val weekendBlocks = blocksFor(stayDays.filter(::isWeekend))

        // 집: 새벽 1~5시에 가장 많이 있던 곳 / 회사·학교: 평일 10~16시에 가장 많이 있던 곳
        fun dominant(days: List<LocalDate>, hours: IntRange, exclude: Long?): Long? {
            val c = HashMap<Long, Int>()
            for (d in days) for (h in hours) {
                placeAt(d.startMs(zone) + h * 3600_000L + 30 * 60_000L)?.takeIf { it != exclude }?.let { c[it] = (c[it] ?: 0) + 1 }
            }
            return c.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key
        }
        val homeId = dominant(stayDays, 1..4, null)
        val workId = dominant(stayDays.filterNot(::isWeekend), 10..15, homeId)

        // 특정 요일에만 가는 곳 (예: 화·목 헬스장)
        val weekly = stays.filter { it.placeId != 0L && it.placeId != homeId && it.placeId != workId }
            .groupBy { it.placeId }
            .mapNotNull { (pid, list) ->
                val byDow = list.groupBy { it.start.toLocalDate(zone).dayOfWeek }
                    .mapValues { (_, v) -> v.map { it.start.toLocalDate(zone) }.distinct().size }
                val regular = byDow.filter { it.value >= 2 }.keys.sorted()
                if (regular.isEmpty() || regular.size > 4) return@mapNotNull null
                val arrivals = list.filter { it.start.toLocalDate(zone).dayOfWeek in regular }.map { it.start.toLocalTime(zone) }
                WeeklyVisit(pid, regular, medianTime(arrivals)!!, list.map { it.start.toLocalDate(zone) }.distinct().size)
            }
            .sortedByDescending { it.visits }

        // 시간대별 앱 습관
        val usageDays = sessions.map { it.start.toLocalDate(zone) }.distinct().size.coerceAtLeast(1)
        val habits = BANDS.flatMap { (band, hours) ->
            sessions.filter { it.start.toLocalTime(zone).hour in hours }
                .groupBy { it.pkg }
                .map { (pkg, v) -> AppHabit(band, pkg, v.sumOf { it.durationMs() } / 60_000.0 / usageDays) }
                .filter { it.avgMinPerDay >= 5 }
                .sortedByDescending { it.avgMinPerDay }
                .take(3)
        }

        val nights = sleepNights(screens, zone)
        fun sleepPair(ns: List<SleepNight>): Pair<LocalTime, LocalTime>? {
            if (ns.size < 2) return null
            return medianTime(ns.map { it.sleep.toLocalTime(zone) })!! to medianTime(ns.map { it.wake.toLocalTime(zone) })!!
        }

        return Routine(
            daysAnalyzed = maxOf(stayDays.size, usageDays.takeIf { sessions.isNotEmpty() } ?: 0),
            weekdaySleep = sleepPair(nights.filterNot { isWeekend(it.date) }),
            weekendSleep = sleepPair(nights.filter { isWeekend(it.date) }),
            weekdayBlocks = weekdayBlocks,
            weekendBlocks = weekendBlocks,
            weeklyVisits = weekly,
            appHabits = habits,
            avgScreenMinPerDay = sessions.sumOf { it.durationMs() } / 60_000.0 / usageDays,
            homeId = homeId,
            workId = workId,
        )
    }
}
