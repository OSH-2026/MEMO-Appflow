package com.memoos.data.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.memoos.data.storage.entity.ExperimentRecordEntity

@Dao
interface ExperimentRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ExperimentRecordEntity)

    @Query("SELECT * FROM experiment_records ORDER BY observationTimestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ExperimentRecordEntity>

    @Query(
        """
        SELECT * FROM experiment_records
        WHERE mode = :mode AND actualNextApp IS NULL
        ORDER BY predictionTimestamp DESC
        LIMIT 1
        """,
    )
    suspend fun latestPending(mode: String): ExperimentRecordEntity?

    @Query(
        """
        UPDATE experiment_records
        SET actualNextApp = :actualNextApp,
            hitAt1 = :hitAt1,
            hitAt3 = :hitAt3,
            observationTimestamp = :observationTimestamp
        WHERE id = :id
        """,
    )
    suspend fun resolvePending(
        id: Long,
        actualNextApp: String,
        hitAt1: Boolean,
        hitAt3: Boolean,
        observationTimestamp: Long,
    )
}
