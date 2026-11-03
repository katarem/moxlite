package es.moxlite.i18n

import java.util.Locale

enum class AppLanguage { SYSTEM, ES, EN }

/**
 * Textos de la app en Kotlin (no en recursos de Android): así cambiar de idioma no obliga a
 * recrear la actividad, una traducción que falte es un error de compilación y los textos que
 * usan las capas sin UI (errores, notificaciones) se pueden probar con tests JVM.
 *
 * El español es el idioma por defecto.
 */
object I18n {
    @Volatile
    var strings: Strings = Es
        private set

    fun apply(language: AppLanguage, system: Locale = Locale.getDefault()) {
        strings = resolve(language, system)
    }

    fun resolve(language: AppLanguage, system: Locale = Locale.getDefault()): Strings = when (language) {
        AppLanguage.ES -> Es
        AppLanguage.EN -> En
        // Gallego, catalán y euskera: mejor español que inglés.
        AppLanguage.SYSTEM -> if (system.language in setOf("es", "ca", "gl", "eu")) Es else En
    }
}
