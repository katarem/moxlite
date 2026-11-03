package es.moxlite.data.remote

import es.moxlite.i18n.I18n
import kotlinx.serialization.json.Json
import okhttp3.Response
import java.io.IOException

object Http {
    /** Tolerante: la API de Moxfield no está documentada y cambia sin aviso. */
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }
}

/** Errores de red traducidos a algo que la UI puede explicar. */
sealed class MoxError(message: String) : IOException(message) {
    /** Cloudflare ha devuelto un reto (HTML) en vez de JSON. */
    class CloudflareBlocked : MoxError(I18n.strings.errCloudflare)

    /** Sin sesión o sesión cerrada por Moxfield. */
    class Unauthorized : MoxError(I18n.strings.errUnauthorized)

    class NotFound : MoxError(I18n.strings.errNotFound)
    class RateLimited(val retryAfterSeconds: Long?) : MoxError(I18n.strings.errRateLimited(retryAfterSeconds ?: 30))
    class Server(code: Int) : MoxError(I18n.strings.errServer(code))

    /**
     * Hay red pero Moxfield no responde: típico del bloqueo de IPs de Cloudflare que ordena LaLiga
     * durante el fútbol (o de una caída de Moxfield).
     */
    class Unreachable(cause: Throwable? = null) : MoxError(I18n.strings.errUnreachable) {
        init { cause?.let(::initCause) }
    }

    /** Moxfield ha rechazado un cambio (400/409…), normalmente porque el mazo cambió entretanto. */
    class WriteRejected(val code: Int, detail: String) : MoxError(I18n.strings.errWriteRejected(code, detail))

    /** Cambios sobre un mazo de otra persona: no se pueden subir. */
    class NotOwner : MoxError(I18n.strings.errNotOwner)
}

fun Response.isCloudflareChallenge(): Boolean {
    if (header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
    val type = header("Content-Type").orEmpty()
    return (code == 403 || code == 503) && type.contains("text/html", ignoreCase = true)
}

/**
 * Fallos de conexión que, con red disponible, indican que el servidor está bloqueado o caído
 * (y no que el móvil esté sin conexión).
 */
fun IOException.looksLikeBlocking(): Boolean = when (this) {
    is MoxError -> false
    is java.net.UnknownHostException -> false
    is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException,
    is javax.net.ssl.SSLException, is java.net.SocketException -> true
    else -> message?.contains("reset", ignoreCase = true) == true ||
        message?.contains("unexpected end of stream", ignoreCase = true) == true
}

fun Throwable.userMessage(): String = when (this) {
    is MoxError -> message.orEmpty()
    is java.net.UnknownHostException -> I18n.strings.errOffline
    is java.net.SocketTimeoutException -> I18n.strings.errTimeout
    is kotlinx.serialization.SerializationException -> I18n.strings.errFormat(message?.take(120).orEmpty())
    is retrofit2.HttpException -> I18n.strings.errServer(code())
    else -> message ?: this::class.java.simpleName
}
