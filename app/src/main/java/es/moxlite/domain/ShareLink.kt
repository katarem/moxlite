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
 * Enlaces para compartir mazos entre usuarios de Moxlite:
 *
 * `https://moxfield.com/decks/<publicId>#moxlite=<lista comprimida>`
 *
 * - Es un enlace https normal, así que se puede tocar en WhatsApp, Telegram, etc. Moxlite abre
 *   los enlaces de moxfield.com (si en Android está activado "Abrir enlaces compatibles").
 * - Lleva el mazo entero dentro (deflate + base64url en el fragmento `#`, que no llega a ningún
 *   servidor): quien lo recibe lo ve aunque Moxfield esté bloqueado o el mazo sea privado o
 *   solo exista en el móvil de quien lo manda.
 * - Sin Moxlite, el enlace abre el mazo en la web de Moxfield (si es público).
 */
object ShareLink {
    const val PARAM = "moxlite"

    /** Ruta para mazos que no están en Moxfield. */
    const val LOCAL_PATH = "moxlite-share"

    private const val MAX_INFLATED = 512 * 1024

    fun build(snapshot: DeckSnapshot): String {
        val json = Http.json.encodeToString(DeckSnapshot.serializer(), snapshot)
        val path = snapshot.id ?: LOCAL_PATH
        return "https://moxfield.com/decks/$path#$PARAM=${encode(json)}"
    }

    /** Busca un enlace de Moxlite en [text] (puede venir en medio de un mensaje). */
    fun parse(text: String): DeckSnapshot? {
        val start = text.indexOf("$PARAM=")
        if (start < 0) return null
        val payload = text.substring(start + PARAM.length + 1).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (payload.isEmpty()) return null
        return runCatching {
            Http.json.decodeFromString(DeckSnapshot.serializer(), decode(payload))
        }.getOrNull()?.takeIf { it.t.isNotBlank() }
    }

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
