package es.moxlite.domain

import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import es.moxlite.data.repo.DeckMapper

/** Orden de las listas de mazos. Los cuatro primeros los entiende la búsqueda de Moxfield. */
enum class DeckSort(val remoteKey: String?) {
    UPDATED("updated"),
    CREATED("created"),
    LIKES("likes"),
    VIEWS("views"),

    /** Solo en el móvil: último abierto primero. */
    VIEWED(null),

    /** Solo en el móvil: alfabético. */
    NAME(null);

    companion object {
        /** Órdenes de la búsqueda de Moxfield (`sortType`; `bracket` responde 400). */
        val REMOTE = listOf(UPDATED, LIKES, VIEWS, CREATED)
        val LOCAL = listOf(VIEWED, UPDATED, NAME, LIKES, VIEWS)
    }
}

/** Cómo se comparan los colores elegidos con la identidad de color del mazo. */
enum class ColorMatch {
    /** Exactamente esos colores. */
    EXACT,

    /** Tiene al menos esos colores. */
    INCLUDES,

    /** No se sale de esos colores (cabe en un comandante de esos colores). */
    WITHIN,
}

/**
 * Filtros de mazos, comunes a la búsqueda de Moxfield y a "Mis mazos".
 *
 * En Moxfield, formato, comandante, bracket y tipo (hub) se filtran en el servidor; los colores
 * no (la búsqueda ignora `colors`/`colorIdentity`), así que se filtran aquí sobre cada página.
 */
data class DeckFilters(
    val text: String = "",
    val format: String? = null,
    /** "WUB"…, o "C" para incoloros. Vacío = cualquiera. */
    val colors: String = "",
    val colorMatch: ColorMatch = ColorMatch.EXACT,
    /** Nombre (en inglés) del comandante. */
    val commander: String? = null,
    /** Bracket de Commander (1–5). */
    val bracket: Int? = null,
    /** Tipo de mazo: "hub" de Moxfield (Tokens, Aristocrats, Budget…). */
    val hub: String? = null,
    val sort: DeckSort = DeckSort.UPDATED,
) {
    /** Filtros puestos (sin contar texto ni orden), para el indicador "Limpiar". */
    val activeCount: Int
        get() = listOf(format, colors.ifBlank { null }, commander, bracket, hub).count { it != null }

    /** Hay que filtrar en el móvil los resultados de Moxfield (pidiendo más páginas). */
    val needsClientFilter: Boolean get() = colors.isNotBlank()

    fun cleared() = DeckFilters(text = text, sort = sort)

    /** Cambia un color: "C" excluye a los demás y al revés. */
    fun toggleColor(c: Char): DeckFilters {
        val current = colors.toSet()
        val next = when {
            c == 'C' -> if ('C' in current) emptySet() else setOf('C')
            c in current -> current - c
            else -> current - 'C' + c
        }
        return copy(colors = DeckMapper.colors(next.map { it.toString() }))
    }

    fun matchesColors(deckColors: String): Boolean {
        if (colors.isBlank()) return true
        val deck = deckColors.uppercase().filter { it in WUBRG }.toSet()
        if (colors == "C") return deck.isEmpty()
        val wanted = colors.filter { it in WUBRG }.toSet()
        return when (colorMatch) {
            ColorMatch.EXACT -> deck == wanted
            ColorMatch.INCLUDES -> deck.containsAll(wanted)
            ColorMatch.WITHIN -> wanted.containsAll(deck)
        }
    }

    /**
     * Comprobación en el móvil de un resultado de Moxfield. Bracket y tipo ya los filtra el
     * servidor; aquí solo se descartan si el resultado trae el dato y no cuadra.
     */
    fun matchesSummary(s: MoxDeckSummaryDto): Boolean {
        if (!matchesColors(DeckMapper.colors(s.colorIdentity))) return false
        val b = s.effectiveBracket
        if (bracket != null && b != null && b != bracket) return false
        if (hub != null && s.hubNames.isNotEmpty() && s.hubNames.none { it.equals(hub, ignoreCase = true) }) return false
        return true
    }

    /** Filtro completo en el móvil (mazos guardados en Room). */
    fun matchesLocal(d: DeckEntity): Boolean {
        val q = text.trim()
        if (q.isNotEmpty()) {
            val inText = d.name.contains(q, true) || d.authorUserName.contains(q, true) ||
                d.authorDisplayName.contains(q, true) || d.commanders.contains(q, true)
            if (!inText) return false
        }
        if (format != null && normalizeFormat(d.format) != normalizeFormat(format)) return false
        if (!matchesColors(d.colorIdentity)) return false
        if (commander != null && d.commanderList.none { it.contains(commander, ignoreCase = true) }) return false
        if (bracket != null && d.bracket != bracket) return false
        if (hub != null && d.hubList.none { it.equals(hub, ignoreCase = true) }) return false
        return true
    }

    /** Filtra y ordena mazos guardados en el móvil. */
    fun applyLocal(decks: List<DeckEntity>): List<DeckEntity> {
        val filtered = decks.filter(::matchesLocal)
        val byName = compareBy<DeckEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        return when (sort) {
            DeckSort.NAME -> filtered.sortedWith(byName)
            DeckSort.LIKES -> filtered.sortedWith(compareByDescending<DeckEntity> { it.likeCount }.then(byName))
            DeckSort.VIEWS -> filtered.sortedWith(compareByDescending<DeckEntity> { it.viewCount }.then(byName))
            // Fecha ISO de Moxfield: se ordena como texto. Los creados en el móvil, arriba.
            DeckSort.UPDATED, DeckSort.CREATED -> filtered.sortedWith(
                compareByDescending<DeckEntity> { it.isLocalOnly }
                    .thenByDescending { it.remoteUpdatedAt.orEmpty() }
                    .thenByDescending { it.fetchedAt }
            )
            DeckSort.VIEWED -> filtered.sortedWith(
                compareByDescending<DeckEntity> { it.viewedAt }.thenByDescending { it.fetchedAt }.then(byName)
            )
        }
    }

    companion object {
        const val WUBRG = "WUBRG"

        /** Brackets de Commander (1 Exhibición … 5 cEDH). */
        val BRACKETS = 1..5

        /** Tipos (hubs de Moxfield) habituales; se puede escribir cualquier otro. */
        val COMMON_HUBS = listOf(
            "Aggro", "Aristocrats", "Artifacts", "Blink", "Budget", "Burn", "cEDH", "Combo", "Control",
            "Counters", "Dragons", "Elves", "Enchantress", "Group Hug", "Landfall", "Lifegain", "Mill",
            "Midrange", "Reanimator", "Spellslinger", "Stax", "Storm", "Superfriends", "Tokens",
            "Treasure", "Tribal", "Voltron", "Wheels", "Zombies",
        )

        /** "paupercommander" era como lo guardaban versiones antiguas de la app. */
        fun normalizeFormat(format: String): String = when (val f = format.lowercase()) {
            "paupercommander", "pdh" -> "pauperedh"
            "edh" -> "commander"
            else -> f
        }
    }
}
