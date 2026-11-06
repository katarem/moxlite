package es.moxlite.data.remote.edhrec

import es.moxlite.data.remote.Http
import es.moxlite.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/** Errores de EDHREC con un mensaje que la UI puede enseñar tal cual. */
sealed class EdhrecError(message: String) : IOException(message) {
    /** EDHREC no tiene esa página (comandante sin datos, o tema/bracket que no existe). */
    class NotFound : EdhrecError(I18n.strings.edhNotFound)
    class Server(code: Int) : EdhrecError(I18n.strings.edhServerError(code))
}

/**
 * Cliente de EDHREC. Las páginas se guardan en disco: si luego no hay conexión se usa la copia
 * (cambian poco: EDHREC las regenera una vez al día).
 */
class EdhrecClient(
    private val client: OkHttpClient,
    private val cacheDir: File,
) {
    /** Página JSON (ruta sin ".json", p. ej. "commanders/atraxa-praetors-voice"). Sigue las redirecciones. */
    suspend fun page(path: String): EdhPage = withContext(Dispatchers.IO) {
        var current = path
        repeat(MAX_REDIRECTS) {
            val page = Http.json.decodeFromString(EdhPage.serializer(), fetchPage(current))
            val redirect = page.redirect?.takeIf { it.isNotBlank() } ?: return@withContext page
            current = EdhrecPaths.fromRedirect(redirect)
        }
        throw EdhrecError.NotFound()
    }

    /** Recomendaciones para el mazo: qué añadir y qué sobra. */
    suspend fun recs(commanders: List<String>, cards: List<String>): EdhRecs = withContext(Dispatchers.IO) {
        val body = Http.json.encodeToString(EdhRecsRequest.serializer(), EdhRecsRequest(commanders, cards))
        val request = Request.Builder()
            .url("https://edhrec.com/api/recs/")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EdhrecError.Server(response.code)
            Http.json.decodeFromString(EdhRecs.serializer(), response.body?.string().orEmpty())
        }
    }

    private fun fetchPage(path: String): String {
        val file = cacheFile(path)
        val request = Request.Builder().url("https://json.edhrec.com/pages/$path.json").build()
        val text = try {
            client.newCall(request).execute().use { response ->
                when {
                    // S3 responde 403 (AccessDenied) a lo que no existe.
                    response.code == 403 || response.code == 404 -> throw EdhrecError.NotFound()
                    !response.isSuccessful -> throw EdhrecError.Server(response.code)
                    else -> response.body?.string().orEmpty()
                }
            }
        } catch (e: EdhrecError.NotFound) {
            throw e
        } catch (e: IOException) {
            // Sin red o EDHREC caído: la copia guardada, si la hay.
            if (file.isFile) return file.readText()
            throw e
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        return text
    }

    private fun cacheFile(path: String) = File(cacheDir, path.replace(Regex("[^a-z0-9/_-]"), "_") + ".json")

    private companion object {
        const val MAX_REDIRECTS = 3
        val JSON = "application/json".toMediaType()
    }
}
