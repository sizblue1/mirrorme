package dev.mirrorme.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 위치 서비스가 몇 분마다 남기는 원본 좌표. */
@Entity(tableName = "location_points", indices = [Index("time")])
data class LocationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,          // epoch millis
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
)

/** 한 앱을 화면에 띄워 둔 구간. */
@Entity(tableName = "app_sessions", indices = [Index("start")])
data class AppSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val start: Long,
    val end: Long,
)

fun AppSession.durationMs(): Long = end - start

/** 화면 켜짐/꺼짐. 기상·취침 추정에 사용. */
@Entity(tableName = "screen_events", indices = [Index("time")])
data class ScreenEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val on: Boolean,
)

/** 자주 머무는 장소. 머문 기록을 묶어서 자동으로 만들고, 이름은 사용자가 바꿀 수 있음. */
@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val name: String,
    val userNamed: Boolean = false,
)
