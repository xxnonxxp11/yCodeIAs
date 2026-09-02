package com.yugahashimoto.andcode.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
}
