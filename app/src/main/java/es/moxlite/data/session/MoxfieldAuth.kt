package es.moxlite.data.session

import es.moxlite.data.diagnostics.DiagnosticLog
import es.moxlite.data.remote.Http
import es.moxlite.data.remote.isCloudflareChallenge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Lo que interesa de la respuesta de `POST /v1/startup/authenticated`. */
data class AuthResult(
    val accessToken: String,
    val refreshToken: String? = null,
    val userName: String? = null,
    val userId: String? = null,
    val expiresAt: Long? = null,
) {
    companion object {
        /**
         * La web de Moxfield llama a este endpoint al arrancar: con la cookie de sesión devuelve
         * `{ "refresh": { "access_token", "refresh_token", "user_name", "user_id", "expiration",
         * "expires_in_minutes", … }, … }`. Se acepta también el objeto sin envolver.
         */
        fun parse(body: String, now: Long = System.currentTimeMillis()): AuthResult? = runCatching {
            val root = Http.json.parseToJsonElement(body).jsonObject
            val r = (root["refresh"] as? JsonObject) ?: root
            fun str(key: String) = (r[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val token = str("access_token") ?: str("accessToken") ?: return null
            val expiresAt = str("expiration")?.let(::parseInstant)
                ?: (r["expires_in_minutes"] as? JsonPrimitive)?.longOrNull?.let { now + it * 60_000 }
            AuthResult(
                accessToken = token,
                refreshToken = str("refresh_token"),
                userName = str("user_name") ?: str("userName"),
                userId = str("user_id") ?: str("userId"),
                expiresAt = expiresAt,
            )
        }.getOrNull()

        fun parseInstant(text: String): Long? =
            runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
}

/**
 * Renueva el token de Moxfield con la cookie de sesión que dejó el login en el WebView
 * (compartida con OkHttp vía [WebViewCookieJar]). Es lo que hace la propia web al abrirse, así
 * que una vez iniciada sesión no hace falta volver a entrar mientras Moxfield no la cierre.
 */
class MoxfieldAuth(
    private val session: SessionManager,
    baseClient: OkHttpClient,
    private val log: DiagnosticLog,
) {
    private val lock = Any()

    @Volatile
    private var lastFailureAt = 0L

    private val client = baseClient.newBuilder()
        .cookieJar(FallbackRefreshCookieJar(WebViewCookieJar()) { session.state.value.refreshToken })
        .build()

    /**
     * Bloqueante (llamar fuera del hilo principal). Devuelve true si al terminar hay un token válido.
     * [force] renueva aunque el token actual parezca válido (tras un 401).
     * [probing] = se está comprobando si el usuario ya ha iniciado sesión en el WebView: un fallo
     * no se considera "sesión cerrada".
     */
    fun refreshBlocking(force: Boolean = false, probing: Boolean = false): Boolean = synchronized(lock) {
        val before = session.state.value
        if (!force && before.hasValidToken) return true
        if (!probing && !before.hasAccount) return false
        // No machacar a Moxfield si acaba de fallar (sin red, bloqueo…).
        if (!probing && System.currentTimeMillis() - lastFailureAt < RETRY_AFTER_FAILURE_MS) {
            log.w(TAG, "Token renewal skipped: the previous attempt failed less than ${RETRY_AFTER_FAILURE_MS / 1000} s ago")
            return false
        }

        val request = Request.Builder()
            .url(URL)
            .header("User-Agent", session.userAgent)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://moxfield.com")
            .header("Referer", "https://moxfield.com/")
            .post(BODY.toRequestBody(JSON))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val result = if (response.isSuccessful) AuthResult.parse(response.body?.string().orEmpty()) else null
                when {
                    result != null -> {
                        session.onRefreshed(result)
                        lastFailureAt = 0
                        val minutes = result.expiresAt?.let { (it - System.currentTimeMillis()) / 60_000 }
                        log.i(TAG, "Token renewed (force=$force, probing=$probing, expires in ${minutes ?: "?"} min)")
                        true
                    }
                    response.isCloudflareChallenge() || response.code == 429 || response.code >= 500 -> {
                        lastFailureAt = System.currentTimeMillis()
                        log.w(TAG, "Token renewal failed temporarily: HTTP ${response.code} (cloudflare=${response.isCloudflareChallenge()})")
                        false
                    }
                    else -> {
                        // 200 sin token, 400 o 401: Moxfield no reconoce la sesión.
                        lastFailureAt = System.currentTimeMillis()
                        val signOut = !probing && before.hasAccount
                        log.w(TAG, "Token renewal rejected: HTTP ${response.code}${if (signOut) ", the session is marked as needing login" else ""}")
                        if (signOut) session.markNeedsLogin()
                        false
                    }
                }
            }
        } catch (e: IOException) {
            lastFailureAt = System.currentTimeMillis()
            log.w(TAG, "Token renewal failed: no response from Moxfield", e)
            false
        }
    }

    suspend fun refresh(force: Boolean = false, probing: Boolean = false): Boolean =
        withContext(Dispatchers.IO) { refreshBlocking(force, probing) }

    private companion object {
        const val TAG = "Auth"
        const val URL = "https://api2.moxfield.com/v1/startup/authenticated"
        const val BODY = """{"ignoreCookie":false,"isAppLogin":false}"""
        val JSON = "application/json".toMediaType()
        const val RETRY_AFTER_FAILURE_MS = 30_000L
    }
}

/**
 * Cookies del WebView; si la cookie `refresh_token` no está (p. ej. Android la ha perdido) y
 * Moxfield nos dio el refresh token en el cuerpo, se manda igualmente.
 */
private class FallbackRefreshCookieJar(
    private val delegate: CookieJar,
    private val refreshToken: () -> String?,
) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = delegate.saveFromResponse(url, cookies)

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookies = delegate.loadForRequest(url)
        val stored = refreshToken()
        if (stored.isNullOrBlank() || cookies.any { it.name == "refresh_token" }) return cookies
        val fallback = Cookie.Builder().name("refresh_token").value(stored).domain(url.host).path("/").secure().httpOnly().build()
        return cookies + fallback
    }
}
