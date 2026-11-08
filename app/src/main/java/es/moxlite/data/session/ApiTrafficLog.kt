package es.moxlite.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ApiCall(
    val time: String,
    val method: String,
    val url: String,
    val body: String,
    val source: String,
)

/**
 * Registro de las llamadas a la API de Moxfield que hace la web dentro del WebView.
 *
 * Sirve para descubrir los endpoints de escritura (crear mazo, añadir carta, clonar…):
 * se hace la acción una vez en "Moxfield web" y aquí queda el método, la URL y el cuerpo,
 * sin tokens ni contraseñas.
 */
class ApiTrafficLog(private val maxEntries: Int = 400) {

    private val _calls = MutableStateFlow<List<ApiCall>>(emptyList())
    val calls: StateFlow<List<ApiCall>> = _calls.asStateFlow()

    fun record(method: String, url: String, body: String?, source: String) {
        if (!isMoxfieldApi(url)) return
        val entry = ApiCall(
            time = LocalTime.now().format(TIME),
            method = method.uppercase(),
            url = redactUrl(url),
            body = redactBody(body.orEmpty()).take(4_000),
            source = source,
        )
        _calls.update { list ->
            // Los dos mecanismos de captura pueden ver la misma petición: evita duplicados seguidos.
            val last = list.lastOrNull()
            if (last != null && last.method == entry.method && last.url == entry.url && last.time == entry.time) list
            else (list + entry).takeLast(maxEntries)
        }
    }

    fun clear() = _calls.update { emptyList() }

    /** Texto plano listo para compartir y documentar endpoints. */
    fun export(): String = calls.value.joinToString("\n\n") { c ->
        buildString {
            append("${c.time}  ${c.method} ${c.url}")
            if (c.body.isNotBlank()) append("\n").append(c.body)
        }
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val SECRET_KEYS = Regex(
            """("(?:password|newPassword|token|refreshToken|refresh_token|access_token|accessToken|captcha\w*|recaptcha\w*)"\s*:\s*)"[^"]*"""",
            RegexOption.IGNORE_CASE,
        )
        private val SECRET_QUERY = Regex("""([?&](?:token|access_token|code)=)[^&\s]+""", RegexOption.IGNORE_CASE)

        fun isMoxfieldApi(url: String): Boolean =
            url.contains("api2.moxfield.com") || url.contains("api.moxfield.com")

        fun redactBody(body: String): String = body.replace(SECRET_KEYS) { "${it.groupValues[1]}\"***\"" }
        fun redactUrl(url: String): String = url.replace(SECRET_QUERY) { "${it.groupValues[1]}***" }
    }
}
