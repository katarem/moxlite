package es.moxlite.data.remote

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
    class CloudflareBlocked : MoxError(
        "Cloudflare está bloqueando la petición. Abre Moxfield en la app para pasar la verificación y vuelve a intentarlo."
    )

    class Unauthorized : MoxError("La sesión de Moxfield ha caducado. Vuelve a iniciar sesión.")
    class NotFound : MoxError("No existe o es privado (los mazos privados necesitan sesión).")
    class RateLimited(val retryAfterSeconds: Long?) : MoxError(
        "Demasiadas peticiones. Espera ${retryAfterSeconds ?: 30} s."
    )
    class Server(code: Int) : MoxError("Moxfield ha respondido con error $code.")
    class NotSupportedYet(what: String) : MoxError(
        "$what todavía no está implementado contra Moxfield: se ha guardado en local y se sincronizará cuando lo esté."
    )
}

fun Response.isCloudflareChallenge(): Boolean {
    if (header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
    val type = header("Content-Type").orEmpty()
    return (code == 403 || code == 503) && type.contains("text/html", ignoreCase = true)
}

fun Throwable.userMessage(): String = when (this) {
    is MoxError -> message.orEmpty()
    is java.net.UnknownHostException -> "Sin conexión. Mostrando lo guardado en el móvil."
    is java.net.SocketTimeoutException -> "Moxfield tarda demasiado en responder."
    is kotlinx.serialization.SerializationException -> "Moxfield ha cambiado el formato de respuesta (${message?.take(120)})."
    else -> message ?: this::class.java.simpleName
}
