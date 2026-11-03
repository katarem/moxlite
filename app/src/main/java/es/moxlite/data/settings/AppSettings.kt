package es.moxlite.data.settings

import android.content.Context
import es.moxlite.i18n.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Cómo se pinta la lista de cartas de un mazo (como las vistas de la web de Moxfield). */
enum class DeckViewMode {
    /** Solo texto: cantidad, nombre y coste. */
    TEXT,

    /** Lista con miniatura y precio. */
    LIST,

    /** Cuadrícula de cartas enteras. */
    GRID,

    /** Carta grande con su texto al lado. */
    FULL,
}

enum class DeckGrouping { TYPE, CMC, COLOR, NONE }

enum class DeckSorting { NAME, CMC, PRICE }

enum class ProxyType { HTTP, SOCKS }

/** Proxy opcional para saltarse bloqueos de IP (p. ej. el de LaLiga a Cloudflare). */
data class ProxySettings(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
) {
    val isUsable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535
}

/** Idiomas en los que Scryfall tiene cartas impresas. */
object CardLanguages {
    val all = listOf("en", "es", "fr", "de", "it", "pt", "ja", "ko", "ru", "zhs", "zht")

    fun default(locale: Locale = Locale.getDefault()): String =
        locale.language.lowercase().takeIf { it in all } ?: "en"
}

/** Preferencias de la app (SharedPreferences expuestas como StateFlow). */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _appLanguage = MutableStateFlow(
        runCatching { AppLanguage.valueOf(prefs.getString(KEY_APP_LANG, null) ?: "") }.getOrDefault(AppLanguage.SYSTEM)
    )
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    private val _cardLanguage = MutableStateFlow(prefs.getString(KEY_CARD_LANG, null) ?: CardLanguages.default())
    val cardLanguage: StateFlow<String> = _cardLanguage.asStateFlow()

    private val _viewMode = MutableStateFlow(enumPref(KEY_VIEW, DeckViewMode.LIST))
    val viewMode: StateFlow<DeckViewMode> = _viewMode.asStateFlow()

    private val _grouping = MutableStateFlow(enumPref(KEY_GROUP, DeckGrouping.TYPE))
    val grouping: StateFlow<DeckGrouping> = _grouping.asStateFlow()

    private val _sorting = MutableStateFlow(enumPref(KEY_SORT, DeckSorting.CMC))
    val sorting: StateFlow<DeckSorting> = _sorting.asStateFlow()

    private val _proxy = MutableStateFlow(
        ProxySettings(
            enabled = prefs.getBoolean(KEY_PROXY_ON, false),
            type = enumPref(KEY_PROXY_TYPE, ProxyType.HTTP),
            host = prefs.getString(KEY_PROXY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PROXY_PORT, 0),
        )
    )
    val proxy: StateFlow<ProxySettings> = _proxy.asStateFlow()

    private val _autoSaveMine = MutableStateFlow(prefs.getBoolean(KEY_AUTOSAVE, true))
    val autoSaveMine: StateFlow<Boolean> = _autoSaveMine.asStateFlow()

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun setAppLanguage(value: AppLanguage) { _appLanguage.value = value; prefs.edit().putString(KEY_APP_LANG, value.name).apply() }
    fun setCardLanguage(value: String) { _cardLanguage.value = value; prefs.edit().putString(KEY_CARD_LANG, value).apply() }
    fun setViewMode(value: DeckViewMode) { _viewMode.value = value; prefs.edit().putString(KEY_VIEW, value.name).apply() }
    fun setGrouping(value: DeckGrouping) { _grouping.value = value; prefs.edit().putString(KEY_GROUP, value.name).apply() }
    fun setSorting(value: DeckSorting) { _sorting.value = value; prefs.edit().putString(KEY_SORT, value.name).apply() }
    fun setAutoSaveMine(value: Boolean) { _autoSaveMine.value = value; prefs.edit().putBoolean(KEY_AUTOSAVE, value).apply() }
    fun setOnboarded(value: Boolean) { _onboarded.value = value; prefs.edit().putBoolean(KEY_ONBOARDED, value).apply() }

    fun setProxy(value: ProxySettings) {
        _proxy.value = value
        prefs.edit()
            .putBoolean(KEY_PROXY_ON, value.enabled)
            .putString(KEY_PROXY_TYPE, value.type.name)
            .putString(KEY_PROXY_HOST, value.host.trim())
            .putInt(KEY_PROXY_PORT, value.port)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        runCatching { enumValueOf<T>(prefs.getString(key, null) ?: default.name) }.getOrDefault(default)

    private companion object {
        const val KEY_APP_LANG = "app_language"
        const val KEY_CARD_LANG = "card_language"
        const val KEY_VIEW = "deck_view"
        const val KEY_GROUP = "deck_group"
        const val KEY_SORT = "deck_sort"
        const val KEY_PROXY_ON = "proxy_enabled"
        const val KEY_PROXY_TYPE = "proxy_type"
        const val KEY_PROXY_HOST = "proxy_host"
        const val KEY_PROXY_PORT = "proxy_port"
        const val KEY_AUTOSAVE = "autosave_mine"
        const val KEY_ONBOARDED = "onboarded"
    }
}
