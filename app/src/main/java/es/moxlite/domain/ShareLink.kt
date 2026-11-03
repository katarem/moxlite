package es.moxlite.domain

import es.moxlite.data.remote.Http
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Copia de un mazo que viaja dentro del enlace compartido. Nombres cortos para que ocupe poco. */
@Serializable
data class DeckSnapshot(
    /** publicId de Moxfield, o null si el mazo solo existe en el móvil de quien lo comparte. */
    val id: String? = null,
    val n: String,
    val f: String = "",
    /** Autor. */
    val a: String = "",
    /** Lista en texto con edición y número (formato de [DeckText]). */
    val t: String,
    val v: Int = 1,
)

/**
 * Enlaces (y códigos) para compartir mazos entre usuarios de Moxlite:
 *
 * `moxlite://deck/<código>`
 *
 * - El código es el mazo entero comprimido (deflate + base64url): quien lo recibe lo ve aunque
 *   Moxfield esté bloqueado, el mazo sea privado o solo exista en el móvil de quien lo manda.
 * - El enlace abre directamente Moxlite (esquema propio, sin pasar por ninguna web).
 * - Donde el enlace no se pueda tocar (WhatsApp no convierte en enlace los esquemas propios), se
 *   copia y se pega en Moxlite (+ → Abrir enlace o código); vale el enlace, el mensaje entero o
 *   solo el código.
 * - [parse] entiende también los enlaces antiguos (`…#moxlite=<código>`).
 */
object ShareLink {
    const val SCHEME = "moxlite"
    const val PREFIX = "$SCHEME://deck/"

    /** Parámetro de los enlaces antiguos (`https://…#moxlite=<código>`). */
    const val PARAM = "moxlite"

    /** Ruta antigua para mazos que no estaban en Moxfield (enlaces viejos). */
    const val LOCAL_PATH = "moxlite-share"

    private const val MAX_INFLATED = 512 * 1024
    private val NEW_LINK = Regex("""moxlite://deck/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val OLD_LINK = Regex("""moxlite=([A-Za-z0-9_-]+)""")
    private val BARE_CODE = Regex("""[A-Za-z0-9_-]{16,}""")

    /** Enlace que abre el mazo en Moxlite. */
    fun build(snapshot: DeckSnapshot): String = PREFIX + code(snapshot)

    /** Código del mazo (lo que va detrás de `moxlite://deck/`); se puede pegar tal cual en la app. */
    fun code(snapshot: DeckSnapshot): String =
        encode(Http.json.encodeToString(DeckSnapshot.serializer(), snapshot))

    /** Busca un enlace o un código de Moxlite en [text] (puede venir en medio de un mensaje). */
    fun parse(text: String): DeckSnapshot? {
        val candidates = sequence {
            NEW_LINK.find(text)?.let { yield(it.groupValues[1]) }
            OLD_LINK.find(text)?.let { yield(it.groupValues[1]) }
            text.trim().takeIf { BARE_CODE.matches(it) }?.let { yield(it) }
        }
        return candidates.firstNotNullOfOrNull(::decodeSnapshot)
    }

    private fun decodeSnapshot(code: String): DeckSnapshot? = runCatching {
        Http.json.decodeFromString(DeckSnapshot.serializer(), decode(code))
    }.getOrNull()?.takeIf { it.t.isNotBlank() }

    fun encode(text: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun decode(payload: String): String {
        val inflater = Inflater(true)
        inflater.setInput(Base64.getUrlDecoder().decode(payload))
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                require(out.size() <= MAX_INFLATED) { "enlace demasiado grande" }
            }
        } finally {
            inflater.end()
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
