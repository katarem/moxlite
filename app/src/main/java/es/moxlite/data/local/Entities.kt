package es.moxlite.data.local

import androidx.room.Entity
import es.moxlite.i18n.I18n
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
    /** Carta destacada como id de impresión de Moxfield (lo único que traen las búsquedas). */
    val featuredMoxCardId: String? = null,
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
    /** Bracket de Commander (1–5) según Moxfield. */
    val bracket: Int? = null,
    /** Tipos de mazo ("hubs" de Moxfield), separados por saltos de línea. */
    val hubs: String = "",
    /** Comandantes (nombres en inglés), separados por saltos de línea. */
    val commanders: String = "",
    /** Última vez que se abrió en la app (0 = nunca). */
    val viewedAt: Long = 0,
) {
    val isFullyLoaded: Boolean get() = fetchedAt > 0 || isLocalOnly
    val commanderList: List<String> get() = commanders.split('\n').filter { it.isNotBlank() }
    val hubList: List<String> get() = hubs.split('\n').filter { it.isNotBlank() }

    companion object {
        fun joinNames(names: List<String>) = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
    }
}

/** Un mazo creado en el móvil pasa a tener el publicId de Moxfield al subirse: aquí se apunta el cambio. */
@Entity(tableName = "deck_aliases")
data class DeckAliasEntity(
    @PrimaryKey val oldId: String,
    val newId: String,
)

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

        fun typeLabel(type: String) = I18n.strings.typeLabel(type)
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
    /** Moxfield avatar URL; null = not looked up yet (followed with an older version), blank = none. */
    val profileImageUrl: String? = null,
)

/**
 * Caché de la misma impresión en otro idioma (para mostrar imágenes y nombres en el idioma de
 * cartas elegido). [scryfallId] null = esa impresión no existe en ese idioma.
 */
@Entity(tableName = "card_localizations", primaryKeys = ["setCode", "collectorNumber", "lang"])
data class CardLocalizationEntity(
    val setCode: String,
    val collectorNumber: String,
    val lang: String,
    val scryfallId: String? = null,
    val printedName: String? = null,
    val checkedAt: Long = System.currentTimeMillis(),
)
