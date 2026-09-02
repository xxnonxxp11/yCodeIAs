package com.yugahashimoto.andcode.data.local

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import kotlinx.coroutines.flow.Flow

class SessionCacheRepository(private val sessionDao: SessionDao) {
    suspend fun cacheSessions(sessions: List<OpenCodeSession>) {
        sessionDao.insertSessions(
            sessions.map { session ->
                SessionEntity(
                    id = session.id,
                    title = session.title,
                    directory = session.directory,
                    createdAt = session.time.created,
                    updatedAt = session.time.updated ?: session.time.created,
                    providerId = null,
                    modelId = null,
                )
            },
        )
    }

    fun getCachedSessions(): Flow<List<SessionEntity>> = sessionDao.getAllSessions()

    suspend fun cacheMessages(
        sessionId: String,
        messages: List<OpenCodeMessage>,
    ) {
        sessionDao.insertMessages(
            messages.map { message ->
                MessageEntity(
                    id = message.info.id,
                    sessionId = sessionId,
                    role = message.info.role,
                    text = message.text,
                    createdAt = message.info.time.created,
                )
            },
        )
    }

    fun getCachedMessages(sessionId: String): Flow<List<MessageEntity>> = sessionDao.getMessagesForSession(sessionId)
}
