package com.yugahashimoto.andcode.core.util

fun Throwable.safeMessage(fallback: String = "Unknown error"): String = message?.takeIf { it.isNotBlank() } ?: fallback
