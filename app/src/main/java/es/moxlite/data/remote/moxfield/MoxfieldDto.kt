package es.moxlite.data.remote.moxfield

import es.moxlite.i18n.I18n
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

// Modelos parciales de api2.moxfield.com. Solo se declaran los campos que usa la app;
// el resto se ignora (ignoreUnknownKeys) para resistir cambios del backend.

@Serializable
data class MoxAuthorDto(
    val userName: String = "",
    val displayName: String = "",
    val profileImageUrl: String? = null,
    val badges: List<String> = emptyList(),
)

@Serializable
data class MoxCardFaceDto(
    val name: String = "",
    @SerialName("mana_cost") val manaCost: String? = null,
    @SerialName("type_line") val typeLine: String? = null,
    @SerialName("oracle_text") val oracleText: String? = null,
)

@Serializable
data class MoxCardDto(
    /** Id interno corto de Moxfield (p. ej. "b5Xg6"). */
    val id: String = "",
    val uniqueCardId: String = "",
    @SerialName("scryfall_id") val scryfallId: String? = null,
    val name: String = "",
    val set: String = "",
    @SerialName("set_name") val setName: String? = null,
    /** Número de coleccionista. */
    val cn: String? = null,
    @SerialName("type_line") val typeLine: String? = null,
    @SerialName("mana_cost") val manaCost: String? = null,
    val cmc: Double = 0.0,
    @SerialName("color_identity") val colorIdentity: List<String> = emptyList(),
    val rarity: String? = null,
    val layout: String? = null,
    @SerialName("oracle_text") val oracleText: String? = null,
    @SerialName("card_faces") val cardFaces: List<MoxCardFaceDto> = emptyList(),
    val prices: JsonObject? = null,
) {
    fun price(key: String): Double? = runCatching { prices?.get(key)?.jsonPrimitive?.doubleOrNull }.getOrNull()

    /** Layouts cuya cara trasera tiene imagen propia en Scryfall. */
    val hasBackImage: Boolean
        get() = layout in setOf("transform", "modal_dfc", "double_faced_token", "reversible_card", "art_series")
}

@Serializable
data class MoxBoardCardDto(
    val quantity: Int = 1,
    val boardType: String? = null,
    val finish: String? = null,
    val isFoil: Boolean = false,
    val isProxy: Boolean = false,
    val card: MoxCardDto = MoxCardDto(),
)

@Serializable
data class MoxBoardDto(
    val count: Int = 0,
    val cards: Map<String, MoxBoardCardDto> = emptyMap(),
)

@Serializable
data class MoxDeckDto(
    val id: String = "",
    val publicId: String = "",
    val name: String = "",
    val format: String = "",
    val description: String = "",
    val visibility: String = "public",
    val authors: List<MoxAuthorDto> = emptyList(),
    val createdByUser: MoxAuthorDto? = null,
    val createdAtUtc: String? = null,
    val lastUpdatedAtUtc: String? = null,
    val version: Int = 0,
    val likeCount: Int = 0,
    val viewCount: Int = 0,
    val colorIdentity: List<String> = emptyList(),
    val publicUrl: String? = null,
    /** Carta destacada (id de impresión de Moxfield). */
    val mainCardId: String? = null,
    val boards: Map<String, MoxBoardDto> = emptyMap(),
    // Números como Double: Moxfield puede mandarlos como 3 o 3.0.
    val bracket: Double? = null,
    val userBracket: Double? = null,
    val autoBracket: Double? = null,
    /** Hubs del mazo: objetos `{name, description}` (o texto). */
    val hubs: List<JsonElement> = emptyList(),
) {
    val effectiveBracket: Int? get() = (bracket ?: userBracket ?: autoBracket)?.toInt()?.takeIf { it in 1..5 }
    val hubNames: List<String> get() = hubs.mapNotNull(::nameOf)
}

/** Nombre de un elemento que puede ser `"texto"` u `{ "name": … }` (o `{ "card": { "name": … } }`). */
internal fun nameOf(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull
    is JsonObject -> (element["name"] as? JsonPrimitive)?.contentOrNull
        ?: (element["card"] as? JsonObject)?.let { (it["name"] as? JsonPrimitive)?.contentOrNull }
    else -> null
}?.takeIf { it.isNotBlank() }

@Serializable
data class MoxDeckSummaryDto(
    val id: String = "",
    val publicId: String = "",
    val name: String = "",
    val format: String = "",
    val visibility: String = "public",
    val createdByUser: MoxAuthorDto? = null,
    val authors: List<MoxAuthorDto> = emptyList(),
    val colorIdentity: List<String> = emptyList(),
    val likeCount: Int = 0,
    val viewCount: Int = 0,
    val mainboardCount: Int = 0,
    val lastUpdatedAtUtc: String? = null,
    val publicUrl: String? = null,
    /** Carta destacada: id de impresión de Moxfield (sirve para la miniatura). */
    val mainCardId: String? = null,
    val createdAtUtc: String? = null,
    val bracket: Double? = null,
    val userBracket: Double? = null,
    val autoBracket: Double? = null,
    val hubNames: List<String> = emptyList(),
    /** Forma no documentada: se sacan los nombres si los trae. */
    val commanders: List<JsonElement> = emptyList(),
) {
    val commanderNames: List<String> get() = commanders.mapNotNull(::nameOf)

    /** Bracket que muestra Moxfield: el que elige el autor y, si no, el calculado. */
    val effectiveBracket: Int? get() = (bracket ?: userBracket ?: autoBracket)?.toInt()?.takeIf { it in 1..5 }
}

@Serializable
data class MoxPage<T>(
    val pageNumber: Int = 1,
    val pageSize: Int = 0,
    val totalPages: Int = 0,
    val totalResults: Int = 0,
    val data: List<T> = emptyList(),
)

/** Orden de presentación de los boards de Moxfield. */
object Boards {
    const val COMMANDERS = "commanders"
    const val COMPANIONS = "companions"
    const val MAIN = "mainboard"
    const val SIDE = "sideboard"
    const val MAYBE = "maybeboard"

    val order = listOf(
        COMMANDERS, "partners", COMPANIONS, "signatureSpells", MAIN, SIDE, MAYBE,
        "attractions", "stickers", "contraptions", "planes", "schemes", "tokens",
    )

    val editable = listOf(COMMANDERS, MAIN, SIDE, MAYBE)

    fun label(board: String): String = I18n.strings.boardLabel(board)

    fun sortIndex(board: String): Int = order.indexOf(board).let { if (it < 0) order.size else it }
}

/**
 * Imágenes que sirve Moxfield a partir del id de impresión (`card.id`, `mainCardId`). Las
 * búsquedas de mazos solo traen `mainCardId`, no el id de Scryfall.
 */
object MoxfieldImages {
    fun artCrop(moxCardId: String) = "https://assets.moxfield.net/cards/card-$moxCardId-art_crop.webp"
    fun normal(moxCardId: String) = "https://assets.moxfield.net/cards/card-$moxCardId-normal.webp"
}
