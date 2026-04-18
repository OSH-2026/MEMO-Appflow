package com.memoos.data.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.memoos.data.storage.entity.PredictionEntity

@Dao
interface PredictionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(predictions: List<PredictionEntity>)

    @Query("DELETE FROM predictions WHERE batchTimestamp = :batchTimestamp")
    suspend fun deleteBatch(batchTimestamp: Long)

    @Query("SELECT MAX(batchTimestamp) FROM predictions")
    suspend fun latestBatchTimestamp(): Long?

    @Query("SELECT * FROM predictions WHERE batchTimestamp = :batchTimestamp ORDER BY rank ASC")
    suspend fun byBatch(batchTimestamp: Long): List<PredictionEntity>
}
