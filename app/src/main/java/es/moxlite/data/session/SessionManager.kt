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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class SessionState(
    /** Token Bearer capturado del WebView (null = sin sesión). */
    val accessToken: String? = null,
    /** Expiración del token en ms epoch, si el JWT la trae. */
    val expiresAt: Long? = null,
    /** Usuario de Moxfield: se usa para "Mis mazos". Puede escribirlo el usuario a mano. */
    val userName: String = "",
) {
    val isLoggedIn: Boolean get() = accessToken != null && !isExpired
    val isExpired: Boolean get() = expiresAt != null && System.currentTimeMillis() > expiresAt - 30_000
}

/**
 * Guarda la sesión de Moxfield.
 *
 * El login directo (usuario/contraseña contra /v2/account/token) exige un token de captcha,
 * así que la sesión se obtiene iniciando sesión en un WebView y capturando la cabecera
 * Authorization que manda el propio frontend de Moxfield (ver [MoxfieldWebBridge]).
 *
 * Nota: SharedPreferences en almacenamiento privado de la app. Para uso personal es suficiente;
 * si la app se distribuye, cifrar con Keystore.
 */
class SessionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("moxfield_session", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        SessionState(
            accessToken = prefs.getString(KEY_TOKEN, null),
            expiresAt = prefs.getLong(KEY_EXP, 0L).takeIf { it > 0 },
            userName = prefs.getString(KEY_USER, "").orEmpty(),
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

    fun onTokenCaptured(token: String) {
        if (token.isBlank() || token == _state.value.accessToken) return
        val claims = decodeJwt(token)
        val exp = claims?.get("exp")?.jsonPrimitive?.longOrNull?.times(1000)
        val claimUser = USER_CLAIMS.firstNotNullOfOrNull { key ->
            runCatching { claims?.get(key)?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        _state.update { current ->
            current.copy(
                accessToken = token,
                expiresAt = exp,
                userName = current.userName.ifBlank { claimUser.orEmpty() },
            )
        }
        persist()
    }

    fun setUserName(userName: String) {
        _state.update { it.copy(userName = userName.trim()) }
        persist()
    }

    fun markExpired() {
        _state.update { it.copy(accessToken = null, expiresAt = null) }
        persist()
    }

    fun logout() {
        markExpired()
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
            .apply()
    }

    private fun decodeJwt(token: String) = runCatching {
        val payload = token.split(".")[1]
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        Http.json.parseToJsonElement(String(bytes)).jsonObject
    }.getOrNull()

    private companion object {
        const val KEY_TOKEN = "access_token"
        const val KEY_EXP = "expires_at"
        const val KEY_USER = "user_name"

        /** Claims donde podría venir el nombre de usuario (no documentado: se prueban varios). */
        val USER_CLAIMS = listOf(
            "userName", "username", "preferred_username", "unique_name", "name",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name",
        )
    }
}
