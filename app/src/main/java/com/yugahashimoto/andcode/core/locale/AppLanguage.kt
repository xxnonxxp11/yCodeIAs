package com.yugahashimoto.andcode.core.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import java.util.Locale

object AppLanguage {
    const val SYSTEM = "system"

    val supported: List<String> =
        listOf(
            SYSTEM,
            "en",
            "ja",
            "zh-CN",
            "ru",
            "es",
            "fr",
            "pt-BR",
            "ar",
        )

    fun localeFor(language: String): Locale =
        when (language) {
            "en" -> Locale("en")
            "ja" -> Locale("ja")
            "zh-CN" -> Locale("zh", "CN")
            "ru" -> Locale("ru")
            "es" -> Locale("es")
            "fr" -> Locale("fr")
            "pt-BR" -> Locale("pt", "BR")
            "ar" -> Locale("ar")
            else -> Locale.getDefault()
        }

    fun applyTo(base: Context): Context {
        val language = SecureSettingsRepository.readLanguage(base)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(localeFor(language)))
        return base.createConfigurationContext(configuration)
    }
}
