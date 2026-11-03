package es.moxlite.ui.deck

import es.moxlite.data.local.CardLocalizationEntity
import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.repo.PrintingKey
import es.moxlite.data.settings.DeckGrouping
import es.moxlite.data.settings.DeckSorting
import es.moxlite.i18n.I18n
import es.moxlite.i18n.Strings

/** Un grupo de cartas de la vista del mazo ("Criaturas", "Coste 3"…). */
data class CardGroup(val key: String, val label: String, val cards: List<DeckCardEntity>) {
    val count: Int get() = cards.sumOf { it.quantity }
}

/** Agrupa y ordena las cartas de un board como en la web de Moxfield. */
object DeckLayout {

    private const val LAND = "Land"
    private val COLOR_ORDER = listOf("W", "U", "B", "R", "G", "M", "C", LAND)

    fun group(
        cards: List<DeckCardEntity>,
        grouping: DeckGrouping,
        sorting: DeckSorting,
        t: Strings = I18n.strings,
    ): List<CardGroup> {
        val sorted = sort(cards, sorting)
        return when (grouping) {
            DeckGrouping.NONE -> if (sorted.isEmpty()) emptyList() else listOf(CardGroup("all", t.allCards, sorted))

            DeckGrouping.TYPE -> sorted.groupBy { it.primaryType }
                .toSortedMap(compareBy<String> { DeckCardEntity.TYPE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } })
                .map { (type, list) -> CardGroup(type, t.typeLabel(type), list) }

            DeckGrouping.CMC -> sorted.groupBy { if (it.isLand) LAND else it.cmc.toInt().coerceAtLeast(0).toString() }
                .toSortedMap(compareBy<String> { if (it == LAND) Int.MAX_VALUE else it.toInt() })
                .map { (key, list) -> CardGroup(key, if (key == LAND) t.typeLabel(LAND) else t.manaValueGroup(key.toInt()), list) }

            DeckGrouping.COLOR -> sorted.groupBy { colorKey(it) }
                .toSortedMap(compareBy<String> { COLOR_ORDER.indexOf(it) })
                .map { (key, list) -> CardGroup(key, if (key == LAND) t.typeLabel(LAND) else t.colorGroup(key), list) }
        }
    }

    fun sort(cards: List<DeckCardEntity>, sorting: DeckSorting): List<DeckCardEntity> = when (sorting) {
        DeckSorting.NAME -> cards.sortedBy { it.name.lowercase() }
        DeckSorting.CMC -> cards.sortedWith(compareBy<DeckCardEntity>({ it.cmc }, { it.name.lowercase() }))
        DeckSorting.PRICE -> cards.sortedWith(compareByDescending<DeckCardEntity> { it.priceEur ?: -1.0 }.thenBy { it.name.lowercase() })
    }

    /** W, U, B, R, G, M (multicolor), C (incolora) o Land. */
    fun colorKey(card: DeckCardEntity): String = when {
        card.isLand -> LAND
        card.colorIdentity.length > 1 -> "M"
        card.colorIdentity.length == 1 -> card.colorIdentity.uppercase()
        else -> "C"
    }

    private val DeckCardEntity.isLand: Boolean get() = primaryType == LAND
}

/** Id de Scryfall para la imagen: la impresión en el idioma de cartas elegido si existe. */
fun DeckCardEntity.imageId(localized: Map<PrintingKey, CardLocalizationEntity>): String? =
    PrintingKey.of(setCode, collectorNumber)?.let { localized[it]?.scryfallId } ?: scryfallId

/** Nombre en el idioma de cartas elegido si se conoce. */
fun DeckCardEntity.localName(localized: Map<PrintingKey, CardLocalizationEntity>): String =
    PrintingKey.of(setCode, collectorNumber)?.let { localized[it]?.printedName }?.takeIf { it.isNotBlank() } ?: name
