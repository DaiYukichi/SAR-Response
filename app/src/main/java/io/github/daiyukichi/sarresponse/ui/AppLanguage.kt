package io.github.daiyukichi.sarresponse.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * Idioma de la app: el del sistema, español o inglés.
 *
 * En Android 13+ se usa el idioma por app del sistema (también aparece en Ajustes del teléfono).
 * En versiones anteriores se guarda la elección y se aplica al crear la actividad.
 */
object AppLanguage {
    const val SYSTEM = "system"
    const val SPANISH = "es"
    const val ENGLISH = "en"

    private const val PREFS = "ajustes"
    private const val KEY = "idioma"

    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            return if (locales == null || locales.isEmpty) SYSTEM else locales[0].language
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM
    }

    /** Cambia el idioma y recrea la pantalla para que se vea de inmediato. */
    fun set(activity: Activity, language: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // El sistema recrea la actividad por su cuenta.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (language == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
            activity.recreate()
        }
    }

    /** Para Android < 13: contexto con el idioma elegido (se usa en attachBaseContext). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val lang = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM
        if (lang == SYSTEM) return base
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return base.createConfigurationContext(config)
    }
}

/** true si la app se está mostrando en inglés (para textos que no salen de los recursos). */
fun Context.isEnglish(): Boolean = resources.configuration.locales[0].language == "en"

@Composable
@ReadOnlyComposable
fun isEnglish(): Boolean = LocalConfiguration.current.locales[0].language == "en"
