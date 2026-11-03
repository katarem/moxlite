package es.moxlite.data.remote.scryfall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

@Serializable
data class ScryImageUris(
    val small: String? = null,
    val normal: String? = null,
    val large: String? = null,
    @SerialName("art_crop") val artCrop: String? = null,
)

@Serializable
data class ScryFace(
    val name: String = "",
    @SerialName("mana_cost") val manaCost: String? = null,
    @SerialName("type_line") val typeLine: String? = null,
    @SerialName("oracle_text") val oracleText: String? = null,
    @SerialName("image_uris") val imageUris: ScryImageUris? = null,
    @SerialName("printed_name") val printedName: String? = null,
)

@Serializable
data class ScryPrices(val eur: String? = null, val usd: String? = null)

@Serializable
data class ScryCard(
    val id: String = "",
    @SerialName("oracle_id") val oracleId: String? = null,
    val name: String = "",
    /** Nombre impreso si la carta no está en inglés. */
    @SerialName("printed_name") val printedName: String? = null,
    val lang: String = "en",
    @SerialName("released_at") val releasedAt: String? = null,
    val set: String = "",
    @SerialName("set_name") val setName: String = "",
    @SerialName("collector_number") val collectorNumber: String = "",
    @SerialName("mana_cost") val manaCost: String? = null,
    @SerialName("type_line") val typeLine: String? = null,
    @SerialName("oracle_text") val oracleText: String? = null,
    val cmc: Double = 0.0,
    @SerialName("color_identity") val colorIdentity: List<String> = emptyList(),
    val rarity: String? = null,
    val layout: String? = null,
    @SerialName("image_uris") val imageUris: ScryImageUris? = null,
    @SerialName("card_faces") val cardFaces: List<ScryFace> = emptyList(),
    val prices: ScryPrices = ScryPrices(),
) {
    val hasBackImage: Boolean get() = imageUris == null && cardFaces.size > 1

    /** Nombre como aparece en la carta (en su idioma). */
    val displayName: String
        get() = printedName?.takeIf { it.isNotBlank() }
            ?: cardFaces.mapNotNull { it.printedName?.takeIf { n -> n.isNotBlank() } }.takeIf { it.isNotEmpty() }?.joinToString(" // ")
            ?: name

    /** Imagen pequeña: la de la API si viene, si no la del patrón de URL. */
    fun imageUrl(size: ScryfallImages.Size = ScryfallImages.Size.NORMAL): String {
        val uris = imageUris ?: cardFaces.firstOrNull()?.imageUris
        val fromApi = when (size) {
            ScryfallImages.Size.SMALL -> uris?.small
            ScryfallImages.Size.NORMAL -> uris?.normal
            ScryfallImages.Size.LARGE -> uris?.large
            ScryfallImages.Size.ART_CROP -> uris?.artCrop
        }
        return fromApi ?: ScryfallImages.url(id, size)
    }
    val fullOracle: String?
        get() = oracleText ?: cardFaces.takeIf { it.isNotEmpty() }
            ?.joinToString("\n//\n") { "${it.name}\n${it.oracleText.orEmpty()}" }
}

@Serializable
data class ScryList<T>(
    val data: List<T> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("total_cards") val totalCards: Int = 0,
    @SerialName("not_found") val notFound: List<ScryIdentifier> = emptyList(),
)

@Serializable
data class ScryNames(val data: List<String> = emptyList())

@Serializable
data class ScryIdentifier(
    val id: String? = null,
    val name: String? = null,
    val set: String? = null,
    @SerialName("collector_number") val collectorNumber: String? = null,
)

@Serializable
data class ScryCollectionRequest(val identifiers: List<ScryIdentifier>)

/** API pública de Scryfall: https://scryfall.com/docs/api (máx. ~10 peticiones/s). */
interface ScryfallService {
    @GET("cards/autocomplete")
    suspend fun autocomplete(@Query("q") q: String): ScryNames

    @GET("cards/search")
    suspend fun search(
        @Query("q") q: String,
        @Query("unique") unique: String = "cards",
        @Query("order") order: String = "name",
        @Query("page") page: Int = 1,
    ): ScryList<ScryCard>

    /** Búsqueda con parámetros libres (unique, order, dir, include_multilingual…). */
    @GET("cards/search")
    suspend fun searchAdvanced(@QueryMap params: Map<String, String>): ScryList<ScryCard>

    /** Una impresión concreta (en inglés). */
    @GET("cards/{set}/{number}")
    suspend fun printing(@Path("set") set: String, @Path("number") number: String): ScryCard

    @GET("cards/named")
    suspend fun named(@Query("exact") exact: String): ScryCard

    @GET("cards/{id}")
    suspend fun byId(@Path("id") id: String): ScryCard

    /** Hasta 75 identificadores por llamada. */
    @POST("cards/collection")
    suspend fun collection(@Body body: ScryCollectionRequest): ScryList<ScryCard>
}

/**
 * Las imágenes de Scryfall siguen un patrón fijo a partir del id, así que no hace falta
 * llamar a la API: se piden directamente a cards.scryfall.io (CDN, sin rate limit de API).
 */
object ScryfallImages {
    enum class Size(val path: String) { SMALL("small"), NORMAL("normal"), LARGE("large"), ART_CROP("art_crop") }

    fun url(scryfallId: String, size: Size = Size.NORMAL, back: Boolean = false): String {
        val id = scryfallId.lowercase()
        val face = if (back) "back" else "front"
        return "https://cards.scryfall.io/${size.path}/$face/${id[0]}/${id[1]}/$id.jpg"
    }
}
