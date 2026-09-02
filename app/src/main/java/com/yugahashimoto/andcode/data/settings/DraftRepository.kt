package com.yugahashimoto.andcode.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Draft(
    val text: String,
    val attachments: List<String> = emptyList(),
    val model: String? = null,
    val agent: String? = null,
)

class DraftRepository(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    @Synchronized
    fun save(
        sessionId: String,
        draft: Draft,
    ) {
        preferences.edit()
            .putString(key(sessionId), json.encodeToString(draft))
            .apply()
    }

    @Synchronized
    fun load(sessionId: String): Draft? =
        runCatching {
            preferences.getString(key(sessionId), null)?.let { json.decodeFromString<Draft>(it) }
        }.getOrNull()

    @Synchronized
    fun clear(sessionId: String) {
        preferences.edit().remove(key(sessionId)).apply()
    }

    @Synchronized
    fun clearAll() {
        preferences.edit().clear().apply()
    }

    private fun key(sessionId: String): String = "$KEY_PREFIX$sessionId"

    companion object {
        private const val PREFS_NAME = "opencode_android_drafts"
        private const val KEY_PREFIX = "draft_"
    }
}
