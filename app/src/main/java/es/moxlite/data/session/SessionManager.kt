package es.moxlite.data.session

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebSettings
import es.moxlite.data.remote.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class SessionState(
    /** Token Bearer (dura poco: se renueva solo con la cookie de sesión, ver [MoxfieldAuth]). */
    val accessToken: String? = null,
    /** Expiración del token en ms epoch. */
    val expiresAt: Long? = null,
    /** Usuario de Moxfield de la cuenta con la que se inició sesión. */
    val userName: String = "",
    val userId: String? = null,
    /** Refresh token que devuelve Moxfield; respaldo por si el WebView pierde la cookie. */
    val refreshToken: String? = null,
    /** La renovación falló de forma definitiva: hay que volver a iniciar sesión. */
    val needsLogin: Boolean = false,
) {
    val isExpired: Boolean get() = expiresAt != null && System.currentTimeMillis() > expiresAt - 60_000

    /** Token usable ahora mismo. */
    val hasValidToken: Boolean get() = accessToken != null && !isExpired

    /** Hay una cuenta iniciada (aunque el token concreto haya caducado y se vaya a renovar). */
    val hasAccount: Boolean get() = userName.isNotBlank() && !needsLogin && (accessToken != null || refreshToken != null)
}

/**
 * Guarda la sesión de Moxfield.
 *
 * El login directo exige captcha, así que se inicia sesión una vez en un WebView. Moxfield deja
 * una cookie de sesión (HttpOnly) y con ella [MoxfieldAuth] pide tokens nuevos cuando caducan,
 * igual que hace la web al abrirla: no hay que volver a iniciar sesión.
 *
 * SharedPreferences en almacenamiento privado de la app (uso personal).
 */
class SessionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("moxfield_session", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        SessionState(
            accessToken = prefs.getString(KEY_TOKEN, null),
            expiresAt = prefs.getLong(KEY_EXP, 0L).takeIf { it > 0 },
            userName = prefs.getString(KEY_USER, "").orEmpty(),
            userId = prefs.getString(KEY_USER_ID, null),
            refreshToken = prefs.getString(KEY_REFRESH, null),
            needsLogin = prefs.getBoolean(KEY_NEEDS_LOGIN, false),
        )
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * User-Agent del WebView del sistema. OkHttp usa el mismo para que la cookie cf_clearance
     * obtenida en el WebView siga siendo válida (Cloudflare la ata al UA).
     */
    val userAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        )
    }

    /** Token visto en una petición de la web (WebView). */
    fun onTokenCaptured(token: String) {
        if (token.isBlank() || token == "undefined" || token == _state.value.accessToken) return
        val claims = decodeJwt(token)
        val exp = claims?.get("exp")?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }?.times(1000)
        val claimUser = USER_CLAIMS.firstNotNullOfOrNull { key ->
            runCatching { claims?.get(key)?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        _state.update { current ->
            current.copy(
                accessToken = token,
                expiresAt = exp ?: current.expiresAt,
                userName = claimUser ?: current.userName,
                needsLogin = false,
            )
        }
        persist()
    }

    /** Respuesta de /v1/startup/authenticated ya interpretada. */
    fun onRefreshed(result: AuthResult) {
        _state.update { current ->
            current.copy(
                accessToken = result.accessToken,
                expiresAt = result.expiresAt ?: decodeJwt(result.accessToken)
                    ?.get("exp")?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }?.times(1000),
                userName = result.userName?.takeIf { it.isNotBlank() } ?: current.userName,
                userId = result.userId ?: current.userId,
                refreshToken = result.refreshToken ?: current.refreshToken,
                needsLogin = false,
            )
        }
        persist()
    }

    /** El token actual ya no vale (401). La cuenta se conserva: se intentará renovar. */
    fun dropAccessToken() {
        _state.update { it.copy(accessToken = null, expiresAt = null) }
        persist()
    }

    /** Moxfield ya no acepta la sesión: hay que volver a entrar. */
    fun markNeedsLogin() {
        _state.update { it.copy(accessToken = null, expiresAt = null, refreshToken = null, needsLogin = it.userName.isNotBlank()) }
        persist()
    }

    fun logout() {
        _state.value = SessionState()
        persist()
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

    private fun persist() {
        val s = _state.value
        prefs.edit()
            .putString(KEY_TOKEN, s.accessToken)
            .putLong(KEY_EXP, s.expiresAt ?: 0L)
            .putString(KEY_USER, s.userName)
            .putString(KEY_USER_ID, s.userId)
            .putString(KEY_REFRESH, s.refreshToken)
            .putBoolean(KEY_NEEDS_LOGIN, s.needsLogin)
            .apply()
    }

    private fun decodeJwt(token: String): JsonObject? = runCatching {
        val payload = token.split(".")[1]
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        Http.json.parseToJsonElement(String(bytes)).jsonObject
    }.getOrNull()

    private companion object {
        const val KEY_TOKEN = "access_token"
        const val KEY_EXP = "expires_at"
        const val KEY_USER = "user_name"
        const val KEY_USER_ID = "user_id"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_NEEDS_LOGIN = "needs_login"

        /** Claims donde podría venir el nombre de usuario (no documentado: se prueban varios). */
        val USER_CLAIMS = listOf(
            "userName", "username", "user_name", "preferred_username", "unique_name", "name",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name",
        )
    }
}
