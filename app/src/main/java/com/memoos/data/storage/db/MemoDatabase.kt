package com.memoos.data.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.memoos.data.storage.dao.AppEventDao
import com.memoos.data.storage.dao.ExperimentRecordDao
import com.memoos.data.storage.dao.PredictionDao
import com.memoos.data.storage.entity.AppEventEntity
import com.memoos.data.storage.entity.ExperimentRecordEntity
import com.memoos.data.storage.entity.PredictionEntity

@Database(
    entities = [AppEventEntity::class, PredictionEntity::class, ExperimentRecordEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MemoDatabase : RoomDatabase() {
    abstract fun appEventDao(): AppEventDao
    abstract fun predictionDao(): PredictionDao
    abstract fun experimentRecordDao(): ExperimentRecordDao
}
