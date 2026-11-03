package es.moxlite.domain

import es.moxlite.BuildConfig
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
 * `https://<usuario>.github.io/<repo>/d/#moxlite=<mazo comprimido>`
 *
 * - Es un enlace https normal (se puede tocar en WhatsApp, Telegram…) a la página `site/d/` del
 *   repositorio, publicada en GitHub Pages. No es un enlace de Moxfield.
 * - Lleva el mazo entero dentro (deflate + base64url en el fragmento `#`, que no llega a ningún
 *   servidor): quien lo recibe lo ve aunque Moxfield esté bloqueado, el mazo sea privado o solo
 *   exista en el móvil de quien lo manda.
 * - Con Moxlite se abre directamente en la app si en Android está activado "Abrir enlaces
 *   compatibles" para Moxlite. Si no, se abre la página, que enseña la lista y tiene el botón
 *   "Abrir en Moxlite" (`moxlite://d?moxlite=…`) y el enlace para descargar la app.
 * - [parse] entiende también los enlaces antiguos (`https://moxfield.com/decks/<id>#moxlite=…`).
 */
object ShareLink {
    const val PARAM = "moxlite"

    /** Esquema propio de la app (lo usa el botón de la página). */
    const val SCHEME = "moxlite"

    /** Ruta antigua para mazos que no están en Moxfield (enlaces viejos). */
    const val LOCAL_PATH = "moxlite-share"

    private const val MAX_INFLATED = 512 * 1024

    fun build(snapshot: DeckSnapshot, baseUrl: String = BuildConfig.SHARE_BASE_URL): String =
        "$baseUrl#$PARAM=${payload(snapshot)}"

    /** Enlace que abre la app directamente (sin pasar por el navegador). */
    fun appLink(snapshot: DeckSnapshot): String = "$SCHEME://d?$PARAM=${payload(snapshot)}"

    private fun payload(snapshot: DeckSnapshot): String =
        encode(Http.json.encodeToString(DeckSnapshot.serializer(), snapshot))

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
