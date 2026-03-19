package com.memoos.data.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "app_events",
    indices = [
        Index(value = ["packageName", "timestamp", "sourceType"], unique = true),
    ],
)
data class AppEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long,
    val dayOfWeek: Int,
    val hourOfDay: Int,
    val sourceType: String,
    val metadata: String,
)
