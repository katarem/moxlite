package es.moxlite.domain

import es.moxlite.data.remote.moxfield.Boards

/** Línea de una lista de mazo en texto plano. */
data class DeckLine(
    val quantity: Int,
    val name: String,
    val board: String = Boards.MAIN,
    val setCode: String? = null,
    val collectorNumber: String? = null,
    val foil: Boolean = false,
)

/**
 * Importación/exportación en texto plano, compatible con lo que aceptan Moxfield, Arena y MTGO:
 *
 * ```
 * Commander
 * 1 Atraxa, Praetors' Voice (2X2) 190
 *
 * Deck
 * 1 Sol Ring (C21) 263
 * 4 Island
 *
 * Sideboard
 * 1 Pithing Needle
 * ```
 */
object DeckText {

    private val LINE = Regex(
        """^(?:SB:\s*)?(\d+)\s*x?\s+(.+?)(?:\s+\(([A-Za-z0-9]{2,8})\)(?:\s+([A-Za-z0-9★†\-]+))?)?(\s+\*[FE]\*)?(\s+\*CMDR\*)?\s*$""",
    )

    private val HEADERS: Map<String, String> = mapOf(
        "commander" to Boards.COMMANDERS,
        "commanders" to Boards.COMMANDERS,
        "comandante" to Boards.COMMANDERS,
        "companion" to Boards.COMPANIONS,
        "compañero" to Boards.COMPANIONS,
        "deck" to Boards.MAIN,
        "main" to Boards.MAIN,
        "mainboard" to Boards.MAIN,
        "mazo" to Boards.MAIN,
        "sideboard" to Boards.SIDE,
        "banquillo" to Boards.SIDE,
        "maybeboard" to Boards.MAYBE,
        "considering" to Boards.MAYBE,
        "quizás" to Boards.MAYBE,
        "tokens" to "tokens",
    )

    fun parse(text: String): List<DeckLine> {
        var board = Boards.MAIN
        val result = mutableListOf<DeckLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val header = line.removePrefix("//").trim().removeSuffix(":").trim().lowercase()
            val headerBoard = HEADERS[header.substringBefore(" (")]
            if (headerBoard != null) {
                board = headerBoard
                continue
            }
            if (line.startsWith("//")) continue

            val match = LINE.matchEntire(line) ?: continue
            val (qty, name, set, number, foil, cmdr) = match.destructured
            val lineBoard = when {
                cmdr.isNotBlank() -> Boards.COMMANDERS
                line.startsWith("SB:", ignoreCase = true) -> Boards.SIDE
                else -> board
            }
            result += DeckLine(
                quantity = qty.toInt(),
                name = name.trim(),
                board = lineBoard,
                setCode = set.ifBlank { null }?.lowercase(),
                collectorNumber = number.ifBlank { null },
                foil = foil.isNotBlank(),
            )
        }
        return mergeDuplicates(result)
    }

    fun format(lines: List<DeckLine>, includePrintings: Boolean = true): String =
        lines.groupBy { it.board }
            .toSortedMap(compareBy<String>({ Boards.sortIndex(it) }, { it }))
            .entries
            .joinToString("\n\n") { (board, cards) ->
                val header = when (board) {
                    Boards.COMMANDERS -> "Commander"
                    Boards.COMPANIONS -> "Companion"
                    Boards.MAIN -> "Deck"
                    Boards.SIDE -> "Sideboard"
                    Boards.MAYBE -> "Maybeboard"
                    else -> board.replaceFirstChar { it.uppercase() }
                }
                val body = cards.sortedBy { it.name.lowercase() }.joinToString("\n") { line ->
                    buildString {
                        append(line.quantity).append(' ').append(line.name)
                        if (includePrintings && line.setCode != null) {
                            append(" (").append(line.setCode.uppercase()).append(')')
                            line.collectorNumber?.let { append(' ').append(it) }
                        }
                        if (line.foil) append(" *F*")
                    }
                }
                "$header\n$body"
            }

    private fun mergeDuplicates(lines: List<DeckLine>): List<DeckLine> =
        lines.groupBy { Triple(it.board, it.name.lowercase(), it.setCode to it.collectorNumber) }
            .map { (_, same) -> same.first().copy(quantity = same.sumOf { it.quantity }) }
}
