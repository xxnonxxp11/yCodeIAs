package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProviderAuthWhen(
    val key: String,
    val op: String,
    val value: String,
) {
    fun matches(inputs: Map<String, String>): Boolean {
        val actual = inputs[key] ?: return false
        return when (op) {
            "eq" -> actual == value
            "neq" -> actual != value
            else -> false
        }
    }
}

@Serializable
data class ProviderAuthOption(
    val label: String,
    val value: String,
    val hint: String? = null,
)

@Serializable
data class ProviderAuthPrompt(
    val type: String,
    val key: String,
    val message: String,
    val placeholder: String? = null,
    val options: List<ProviderAuthOption> = emptyList(),
    @SerialName("when") val whenCondition: ProviderAuthWhen? = null,
) {
    fun isVisible(inputs: Map<String, String>): Boolean = whenCondition?.matches(inputs) ?: true
}

@Serializable
data class ProviderAuthMethod(
    val type: String,
    val label: String,
    val prompts: List<ProviderAuthPrompt> = emptyList(),
)

@Serializable
data class ProviderAuthAuthorization(
    val url: String,
    val method: String,
    val instructions: String,
)
