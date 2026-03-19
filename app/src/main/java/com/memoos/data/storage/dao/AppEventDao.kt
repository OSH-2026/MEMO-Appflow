package com.memoos.data.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.memoos.data.storage.entity.AppEventEntity

@Dao
interface AppEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<AppEventEntity>)

    @Query("SELECT * FROM app_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<AppEventEntity>

    @Query("SELECT * FROM app_events WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<AppEventEntity>

    @Query("SELECT MAX(timestamp) FROM app_events")
    suspend fun latestTimestamp(): Long?
}
