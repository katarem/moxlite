package es.moxlite.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "decks")
data class DeckEntity(
    /** publicId de Moxfield, o "local-<uuid>" para mazos creados/copiados sin conexión. */
    @PrimaryKey val publicId: String,
    val remoteId: String? = null,
    val name: String,
    val format: String = "",
    val description: String = "",
    val visibility: String = "public",
    val authorUserName: String = "",
    val authorDisplayName: String = "",
    /** Colores de identidad concatenados, p. ej. "WUB". */
    val colorIdentity: String = "",
    /** Carta destacada (comandante o primera del mazo) para la miniatura. */
    val featuredScryfallId: String? = null,
    val likeCount: Int = 0,
    val viewCount: Int = 0,
    val mainboardCount: Int = 0,
    val remoteVersion: Int = 0,
    val remoteUpdatedAt: String? = null,
    /** Guardado para uso sin conexión (cartas + imágenes en el móvil). */
    val isSaved: Boolean = false,
    /** Solo existe en el móvil (todavía no subido a Moxfield). */
    val isLocalOnly: Boolean = false,
    /** Hay cambios locales en la cola de sincronización. */
    val hasLocalChanges: Boolean = false,
    /** Moxfield cambió el mazo mientras había cambios locales sin subir. */
    val hasConflict: Boolean = false,
    /** Si es una copia, el mazo original. */
    val originalPublicId: String? = null,
    /** Última vez que se descargó de Moxfield (0 = nunca, solo resumen de búsqueda). */
    val fetchedAt: Long = 0,
    val imagesCachedAt: Long? = null,
) {
    val isFullyLoaded: Boolean get() = fetchedAt > 0 || isLocalOnly
}

@Entity(
    tableName = "deck_cards",
    primaryKeys = ["deckId", "board", "cardKey"],
    indices = [Index("deckId")],
)
data class DeckCardEntity(
    val deckId: String,
    val board: String,
    /** Clave estable de la carta dentro del board: uniqueCardId de Moxfield o "sf:<scryfallId>". */
    val cardKey: String,
    val name: String,
    val quantity: Int,
    val scryfallId: String? = null,
    val moxCardId: String? = null,
    val typeLine: String = "",
    val manaCost: String = "",
    val cmc: Double = 0.0,
    val colorIdentity: String = "",
    val setCode: String = "",
    val collectorNumber: String? = null,
    val rarity: String? = null,
    val oracleText: String? = null,
    val isFoil: Boolean = false,
    val isProxy: Boolean = false,
    val hasBackImage: Boolean = false,
    val priceEur: Double? = null,
    val priceUsd: Double? = null,
) {
    /** Tipo principal para agrupar (Criatura, Instantáneo…). */
    val primaryType: String
        get() {
            val front = typeLine.substringBefore("//").substringBefore("—")
            return TYPE_ORDER.firstOrNull { front.contains(it) } ?: "Other"
        }

    companion object {
        val TYPE_ORDER = listOf(
            "Creature", "Planeswalker", "Battle", "Instant", "Sorcery", "Artifact", "Enchantment", "Land",
        )

        fun typeLabel(type: String) = when (type) {
            "Creature" -> "Criaturas"
            "Planeswalker" -> "Planeswalkers"
            "Battle" -> "Batallas"
            "Instant" -> "Instantáneos"
            "Sorcery" -> "Conjuros"
            "Artifact" -> "Artefactos"
            "Enchantment" -> "Encantamientos"
            "Land" -> "Tierras"
            else -> "Otros"
        }
    }
}

@Entity(tableName = "pending_ops", indices = [Index("deckId")])
data class PendingOpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: String,
    /** Discriminador de [es.moxlite.data.sync.DeckOp] (para mostrar sin deserializar). */
    val type: String,
    /** Para fusionar operaciones sobre la misma carta ("board|cardKey"). */
    val targetKey: String? = null,
    /** JSON de [es.moxlite.data.sync.DeckOp]. */
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "followed_users")
data class FollowedUserEntity(
    @PrimaryKey val userName: String,
    val displayName: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)
