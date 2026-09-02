package com.yugahashimoto.andcode.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val createdAt: Long,
)
