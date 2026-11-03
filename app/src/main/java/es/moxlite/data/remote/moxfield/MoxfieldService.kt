package es.moxlite.data.remote.moxfield

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * API interna de api2.moxfield.com (ver docs/moxfield-api.md).
 *
 * Lectura: confirmada. Escritura: rutas que usa la propia web de Moxfield, sacadas de su código
 * y de extensiones de la comunidad. Todas las escrituras usan el id interno del mazo (`deck.id`),
 * no el publicId de la URL, y mandan `X-Public-Deck-ID` y `X-Deck-Version`.
 */
interface MoxfieldService {

    // ---------- Lectura ----------

    @GET("v3/decks/all/{publicId}")
    suspend fun deck(@Path("publicId") publicId: String): MoxDeckDto

    /** Búsqueda filtrada SFW (la que usa la web por defecto). */
    @GET("v2/decks/search-sfw")
    suspend fun searchDecks(@QueryMap params: Map<String, String>): MoxPage<MoxDeckSummaryDto>

    /** Mazos de la cuenta con sesión iniciada, incluidos privados y ocultos. */
    @GET("v2/decks/personal/search")
    suspend fun personalDecks(@QueryMap params: Map<String, String>): MoxPage<MoxDeckSummaryDto>

    @GET("v2/users/search-sfw")
    suspend fun searchUsers(@Query("filter") filter: String): MoxPage<MoxAuthorDto>

    /** Búsqueda exacta de una carta (por nombre y, opcionalmente, edición y número). */
    @GET("v2/cards/lookup")
    suspend fun cardLookup(
        @Query("name") name: String,
        @Query("set") set: String?,
        @Query("cn") collectorNumber: String?,
    ): JsonElement

    /** Búsqueda de cartas con sintaxis de Scryfall en la base de datos de Moxfield. */
    @GET("v2/cards/search")
    suspend fun cardSearch(@Query("q") q: String): JsonElement

    // ---------- Escritura ----------

    @POST("v2/decks")
    suspend fun createDeck(@Body body: CreateDeckBody): Response<ResponseBody>

    @PUT("v2/decks/{deckId}")
    suspend fun updateDeck(
        @Path("deckId") deckId: String,
        @Header(PUBLIC_ID) publicId: String,
        @Header(VERSION) version: String?,
        @Body body: DeckInfoBody,
    ): Response<ResponseBody>

    /** Sustituye (o completa) el contenido del mazo con una lista en texto. */
    @POST("v3/decks/{deckId}/import")
    suspend fun importText(
        @Path("deckId") deckId: String,
        @Header(PUBLIC_ID) publicId: String,
        @Header(VERSION) version: String?,
        @Body body: ImportBody,
    ): Response<ResponseBody>

    @POST("v2/decks/{deckId}/cards/{board}")
    suspend fun addCard(
        @Path("deckId") deckId: String,
        @Path("board") board: String,
        @Header(PUBLIC_ID) publicId: String,
        @Header(VERSION) version: String?,
        @Body body: AddCardBody,
    ): Response<ResponseBody>

    /** Cantidad absoluta de una carta que ya está en el board. */
    @PUT("v2/decks/{deckId}/cards/{board}/{cardId}")
    suspend fun setQuantity(
        @Path("deckId") deckId: String,
        @Path("board") board: String,
        @Path("cardId") cardId: String,
        @Header(PUBLIC_ID) publicId: String,
        @Header(VERSION) version: String?,
        @Body body: QuantityBody,
    ): Response<ResponseBody>

    @DELETE("v2/decks/{deckId}/cards/{board}/{cardId}")
    suspend fun removeCard(
        @Path("deckId") deckId: String,
        @Path("board") board: String,
        @Path("cardId") cardId: String,
        @Header(PUBLIC_ID) publicId: String,
        @Header(VERSION) version: String?,
    ): Response<ResponseBody>

    @POST("v4/decks/{deckId}/{fromBoard}/cards/{cardId}/move-to/{toBoard}")
    suspend fun moveCard(
        @Path("deckId") deckId: String,
        @Path("fromBoard") fromBoard: String,
        @Path("cardId") cardId: String,
        @Path("toBoard") toBoard: String,
        @Query("quantity") quantity: Int,
        @Header(PUBLIC_ID) publicId: String,
        @Header(VERSION) version: String?,
        @Body body: MoveBody,
    ): Response<ResponseBody>

    /** Cambia la impresión (arte/edición) de una carta del mazo. */
    @PUT("v2/decks/{deckId}/cards/{cardId}/edition")
    suspend fun changeEdition(
        @Path("deckId") deckId: String,
        @Path("cardId") cardId: String,
        @Header(PUBLIC_ID) publicId: String,
        @Header(VERSION) version: String?,
        @Body body: EditionBody,
    ): Response<ResponseBody>

    companion object {
        const val PUBLIC_ID = "X-Public-Deck-ID"
        const val VERSION = "X-Deck-Version"
    }
}

@Serializable
data class CreateDeckBody(
    val name: String,
    val format: String,
    val visibility: String = "public",
    val description: String = "",
)

@Serializable
data class DeckInfoBody(
    val name: String,
    val format: String,
    val description: String,
    val visibility: String,
)

@Serializable
data class ImportBody(
    val importText: String,
    val replaceAll: Boolean = true,
    /** false: respetar la edición y el número de cada línea. */
    val usePrefPrintings: Boolean = false,
)

@Serializable
data class AddCardBody(val cardId: String, val quantity: Int, val usePrefPrinting: Boolean = false)

@Serializable
data class QuantityBody(val quantity: Int)

@Serializable
data class EditionBody(val newEditionCardId: String, val finish: String = "nonFoil", val isAlter: Boolean = false)

@Serializable
data class MoveBody(val sourcePrintingData: JsonObject? = null, val targetPrintingData: JsonObject? = null)

/** Parámetros de búsqueda de mazos. */
data class DeckQuery(
    val text: String = "",
    val format: String? = null,
    val authorUserName: String? = null,
    /** Id de impresión de Moxfield del comandante (no el uniqueCardId). */
    val commanderCardId: String? = null,
    val minBracket: Int? = null,
    val maxBracket: Int? = null,
    /** "Hub" (tipo de mazo) de Moxfield. */
    val hubName: String? = null,
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
        if (text.isNotBlank()) {
            // La web manda `filter`; versiones anteriores usaban `q`. El que no conoce, lo ignora.
            put("q", text.trim())
            put("filter", text.trim())
        }
        format?.takeIf { it.isNotBlank() }?.let { put("fmt", it) }
        authorUserName?.takeIf { it.isNotBlank() }?.let { put("authorUserNames", it.trim()) }
        commanderCardId?.takeIf { it.isNotBlank() }?.let { put("commanderCardId", it) }
        minBracket?.let { put("minBracket", it.toString()) }
        maxBracket?.let { put("maxBracket", it.toString()) }
        hubName?.takeIf { it.isNotBlank() }?.let { put("hubName", it.trim()) }
    }

    /** Parámetros de /v2/decks/personal/search (usa `filter` en vez de `q`). */
    fun toPersonalParams(): Map<String, String> = buildMap {
        put("pageNumber", pageNumber.toString())
        put("pageSize", pageSize.toString())
        put("sortType", sortType)
        put("sortDirection", sortDirection)
        if (text.isNotBlank()) put("filter", text.trim())
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

    fun deckUrl(publicId: String) = "https://moxfield.com/decks/$publicId"
}
