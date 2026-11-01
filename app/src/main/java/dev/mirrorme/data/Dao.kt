package dev.mirrorme.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface TrackDao {
    // 위치
    @Insert suspend fun insertPoint(p: LocationPoint)

    @Query("SELECT * FROM location_points WHERE time BETWEEN :from AND :to ORDER BY time")
    suspend fun points(from: Long, to: Long): List<LocationPoint>

    @Query("SELECT COUNT(*) FROM location_points")
    suspend fun pointCount(): Int

    @Query("SELECT MAX(time) FROM location_points")
    suspend fun lastPointTime(): Long?

    // 앱 사용
    @Insert suspend fun insertSessions(s: List<AppSession>)

    @Query("SELECT * FROM app_sessions WHERE `end` >= :from AND start <= :to ORDER BY start")
    suspend fun sessions(from: Long, to: Long): List<AppSession>

    @Query("SELECT COUNT(*) FROM app_sessions")
    suspend fun sessionCount(): Int

    // 화면
    @Insert suspend fun insertScreenEvents(e: List<ScreenEvent>)

    @Query("SELECT * FROM screen_events WHERE time BETWEEN :from AND :to ORDER BY time")
    suspend fun screenEvents(from: Long, to: Long): List<ScreenEvent>

    // 장소
    @Query("SELECT * FROM places ORDER BY id")
    suspend fun places(): List<Place>

    @Insert suspend fun insertPlace(p: Place): Long

    @Update suspend fun updatePlace(p: Place)

    // 전체 삭제
    @Query("DELETE FROM location_points") suspend fun clearPoints()
    @Query("DELETE FROM app_sessions") suspend fun clearSessions()
    @Query("DELETE FROM screen_events") suspend fun clearScreen()
    @Query("DELETE FROM places") suspend fun clearPlaces()
}
