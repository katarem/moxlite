package es.moxlite.data.remote.moxfield

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * Endpoints de lectura confirmados de api2.moxfield.com (ver docs/moxfield-api.md).
 *
 * Los de escritura (crear/editar/copiar) no están aquí a propósito: se añadirán cuando se
 * capturen del tráfico real con la pantalla "Registro de API" de la app.
 */
interface MoxfieldService {

    @GET("v3/decks/all/{publicId}")
    suspend fun deck(@Path("publicId") publicId: String): MoxDeckDto

    /** Búsqueda filtrada SFW (la que usa la web por defecto). */
    @GET("v2/decks/search-sfw")
    suspend fun searchDecks(@QueryMap params: Map<String, String>): MoxPage<MoxDeckSummaryDto>

    @GET("v2/users/search-sfw")
    suspend fun searchUsers(@Query("filter") filter: String): MoxPage<MoxAuthorDto>
}

/** Parámetros de búsqueda de mazos. */
data class DeckQuery(
    val text: String = "",
    val format: String? = null,
    val authorUserName: String? = null,
    val sortType: String = "updated",
    val sortDirection: String = "descending",
    val pageNumber: Int = 1,
    val pageSize: Int = 30,
) {
    fun toParams(): Map<String, String> = buildMap {
        put("pageNumber", pageNumber.toString())
        put("pageSize", pageSize.toString())
        put("sortType", sortType)
        put("sortDirection", sortDirection)
        if (text.isNotBlank()) put("q", text.trim())
        format?.takeIf { it.isNotBlank() }?.let { put("fmt", it) }
        authorUserName?.takeIf { it.isNotBlank() }?.let { put("authorUserNames", it.trim()) }
    }
}

object MoxfieldIds {
    private val deckUrl = Regex("""moxfield\.com/decks/([A-Za-z0-9_\-]+)""")

    /** Acepta un publicId suelto o cualquier URL de mazo de Moxfield. */
    fun parsePublicId(input: String): String? {
        val trimmed = input.trim()
        deckUrl.find(trimmed)?.let { return it.groupValues[1] }
        return trimmed.takeIf { it.matches(Regex("[A-Za-z0-9_\\-]{6,40}")) }
    }
}
