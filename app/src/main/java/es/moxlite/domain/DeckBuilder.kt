package es.moxlite.domain

import kotlinx.serialization.Serializable
import kotlin.math.floor

/**
 * Lógica del asistente de construcción de mazos (sin Android ni red, para poder probarla).
 *
 * EDHREC clasifica por tipo de carta, no por función. Las funciones (ramp, robo, removal,
 * barridos) se deducen aquí del texto de reglas, con las plantillas habituales de Commander como
 * objetivo (36 tierras, 10 de ramp, 10 de robo, 8 de interacción, 3 barridos).
 */
enum class CardRole { LAND, RAMP, DRAW, REMOVAL, WIPE }

object CardRoles {

    fun of(name: String, typeLine: String, oracleText: String?): Set<CardRole> {
        val front = typeLine.substringBefore("//")
        if (front.contains("Land")) return setOf(CardRole.LAND)
        val text = normalize(name, oracleText ?: return emptySet())
        if (text.isBlank()) return emptySet()
        val roles = mutableSetOf<CardRole>()
        if (isRamp(text)) roles += CardRole.RAMP
        if (isDraw(text)) roles += CardRole.DRAW
        if (isRemoval(text)) roles += CardRole.REMOVAL
        if (isWipe(text)) roles += CardRole.WIPE
        return roles
    }

    /** Minúsculas, sin el texto recordatorio (entre paréntesis) y con el nombre de la carta como "~". */
    private fun normalize(name: String, oracle: String): String {
        var text = oracle.lowercase().replace(Regex("\\([^)]*\\)"), " ")
        name.split(" // ").filter { it.isNotBlank() }.forEach { face ->
            text = text.replace(face.lowercase(), "~")
            face.substringBefore(",").takeIf { it.length >= 4 && it != face }?.let { text = text.replace(it.lowercase(), "~") }
        }
        return text.replace(Regex("\\s+"), " ")
    }

    private val RAMP = listOf(
        Regex("\\badd \\{"),
        Regex("\\badd (one|two|three|x|that much|an amount of) mana"),
        Regex("\\badd (one|two|three|x) mana"),
        Regex("search your library for [^.]*\\b(lands?|forests?|plains|islands?|swamps?|mountains?)\\b[^.]*(battlefield|hand)"),
        Regex("put (a|up to \\w+|any number of) lands? cards? from your hand onto the battlefield"),
        Regex("put (a|up to \\w+) land cards? from your hand onto the battlefield"),
        Regex("you may play (an additional land|two additional lands|any number of additional lands)"),
        Regex("\\bcreates? (a|an|one|two|three|four|x|\\w+) (tapped )?treasure"),
    )

    private val DRAW = listOf(
        Regex("\\bdraw (a|an|one|two|three|four|five|six|seven|x|that many|\\w+) cards?"),
        Regex("\\bdraw cards equal to"),
        Regex("\\bdraw (a|an) additional card"),
        Regex("exile the top [^.]*of your library\\. [^.]*(you may (play|cast)|until)"),
        Regex("put (one|two|three|\\w+|any number|all) of (them|those cards)[^.]* into your hand"),
    )

    private val REMOVAL = listOf(
        Regex("(destroy|exile) (up to (one|two|three|\\w+) )?(other |another )?target (?!cards?\\b|player|opponent|spell)[^.]*"),
        Regex("counter target [^.]*(spell|ability)"),
        Regex("return (up to \\w+ )?(other |another )?target [^.]*to (its|their) owner's hand"),
        Regex("deals? [^.]*damage to (any target|up to \\w+ target|(another |other )?target (creature|planeswalker|permanent|attacking|blocking|tapped|battle|nonland))"),
        Regex("target (player|opponent) sacrifices"),
        Regex("each opponent sacrifices (a|an|one|two) (creature|permanent|nonland)"),
        Regex("\\bfights? (up to one |another )?target"),
        Regex("target creature [^.]*gets -\\d+/-\\d+"),
        Regex("target creature [^.]*gets -x/-x"),
    )

    private val WIPE = listOf(
        Regex("(destroy|exile) (all|each) (?!cards)[^.]*"),
        Regex("(all|each) (other )?(creatures?|non-?\\w+ creatures?)( your opponents control)? gets? -"),
        Regex("deals? [^.]*damage to each (creature|other creature|nonland permanent)"),
        Regex("return all [^.]*to (their|its) owners?'? hands?"),
        Regex("(each player|each opponent|all players) sacrifices? (all|each)"),
    )

    private fun isRamp(text: String) = RAMP.any { it.containsMatchIn(text) }

    private fun isDraw(text: String) = DRAW.any { it.containsMatchIn(text) }

    private fun isRemoval(text: String) = REMOVAL.any { regex ->
        regex.findAll(text).any { !it.value.contains("graveyard") && !it.value.contains("from your hand") }
    }

    private fun isWipe(text: String) = WIPE.any { regex ->
        regex.findAll(text).any { !it.value.contains("graveyard") && !it.value.contains("from your hand") && !it.value.contains("library") }
    }
}

/** Objetivos del mazo (ajustables). Las tierras salen de la media de EDHREC para el comandante. */
@Serializable
data class BuilderTargets(
    val lands: Int = 36,
    val ramp: Int = 10,
    val draw: Int = 10,
    val removal: Int = 8,
    val wipes: Int = 3,
) {
    fun of(role: CardRole): Int = when (role) {
        CardRole.LAND -> lands
        CardRole.RAMP -> ramp
        CardRole.DRAW -> draw
        CardRole.REMOVAL -> removal
        CardRole.WIPE -> wipes
    }

    fun with(role: CardRole, value: Int): BuilderTargets {
        val v = value.coerceIn(0, 99)
        return when (role) {
            CardRole.LAND -> copy(lands = v)
            CardRole.RAMP -> copy(ramp = v)
            CardRole.DRAW -> copy(draw = v)
            CardRole.REMOVAL -> copy(removal = v)
            CardRole.WIPE -> copy(wipes = v)
        }
    }
}

/** Carta del mazo en construcción, con lo necesario para las cuentas. */
data class BuildCard(
    val name: String,
    val typeLine: String,
    val manaCost: String,
    val cmc: Double,
    val quantity: Int,
    val roles: Set<CardRole>,
) {
    val isLand: Boolean get() = CardRole.LAND in roles
}

data class DeckStats(
    /** Cartas del mazo, comandantes incluidos. */
    val total: Int,
    val byRole: Map<CardRole, Int>,
    /** Cartas que no son tierra por valor de maná (7 = 7 o más). */
    val curve: Map<Int, Int>,
    val averageCmc: Double,
) {
    fun count(role: CardRole) = byRole[role] ?: 0

    companion object {
        fun of(commanders: Int, cards: List<BuildCard>): DeckStats {
            val byRole = CardRole.entries.associateWith { role -> cards.filter { role in it.roles }.sumOf { it.quantity } }
            val spells = cards.filterNot { it.isLand }
            val curve = spells.groupBy { it.cmc.toInt().coerceIn(0, 7) }.mapValues { (_, list) -> list.sumOf { it.quantity } }
            val count = spells.sumOf { it.quantity }
            val avg = if (count == 0) 0.0 else spells.sumOf { it.cmc * it.quantity } / count
            return DeckStats(commanders + cards.sumOf { it.quantity }, byRole, curve, avg)
        }
    }
}

/** Candidata a entrar en el mazo, en orden de preferencia. */
data class BuildCandidate(
    val name: String,
    val roles: Set<CardRole>,
    val isBasic: Boolean = false,
)

data class AutoFillPlan(
    /** Cartas elegidas (nombres de las candidatas), en el orden en que se eligieron. */
    val picks: List<String>,
    /** Tierras básicas que faltan para llegar al objetivo de tierras. */
    val basics: Int,
)

object AutoFill {

    /**
     * Rellena hasta [deckSize] cartas (comandantes incluidos): primero lo que falta de cada función,
     * luego las mejores candidatas, y las tierras hasta el objetivo (las no básicas mejor valoradas
     * hasta [nonbasicTarget], el resto básicas).
     */
    fun plan(
        commanders: Int,
        current: List<BuildCard>,
        candidates: List<BuildCandidate>,
        targets: BuilderTargets,
        nonbasicTarget: Int,
        deckSize: Int = 100,
    ): AutoFillPlan {
        val stats = DeckStats.of(commanders, current)
        val free = (deckSize - stats.total).coerceAtLeast(0)
        if (free == 0) return AutoFillPlan(emptyList(), 0)

        val inDeck = current.mapTo(HashSet()) { it.name.lowercase() }
        val pool = candidates.filter { !it.isBasic && it.name.lowercase() !in inDeck }.distinctBy { it.name.lowercase() }
        val landNeed = (targets.lands - stats.count(CardRole.LAND)).coerceIn(0, free)
        var spellSlots = free - landNeed
        val picks = LinkedHashSet<String>()

        // 1. Lo que falta de cada función, por orden de importancia.
        val counts = stats.byRole.toMutableMap()
        for (role in listOf(CardRole.RAMP, CardRole.DRAW, CardRole.REMOVAL, CardRole.WIPE)) {
            for (candidate in pool) {
                if (spellSlots == 0 || (counts[role] ?: 0) >= targets.of(role)) break
                if (CardRole.LAND in candidate.roles || role !in candidate.roles || candidate.name in picks) continue
                picks += candidate.name
                spellSlots--
                candidate.roles.forEach { r -> counts[r] = (counts[r] ?: 0) + 1 }
            }
        }
        // 2. El resto de huecos para hechizos: las mejores candidatas.
        for (candidate in pool) {
            if (spellSlots == 0) break
            if (CardRole.LAND in candidate.roles || candidate.name in picks) continue
            picks += candidate.name
            spellSlots--
        }
        // 3. Tierras no básicas hasta su objetivo; el resto, básicas.
        val currentNonbasic = current.filter { it.isLand && !BasicLands.isBasic(it.name) }.sumOf { it.quantity }
        var nonbasicNeed = (nonbasicTarget - currentNonbasic).coerceIn(0, landNeed)
        var landsAdded = 0
        for (candidate in pool) {
            if (nonbasicNeed == 0) break
            if (CardRole.LAND !in candidate.roles || candidate.name in picks) continue
            picks += candidate.name
            nonbasicNeed--
            landsAdded++
        }
        // Si no hay hechizos suficientes, los huecos que quedan también son básicas.
        return AutoFillPlan(picks.toList(), landNeed - landsAdded + spellSlots)
    }
}

object BasicLands {
    val BY_COLOR = linkedMapOf("W" to "Plains", "U" to "Island", "B" to "Swamp", "R" to "Mountain", "G" to "Forest")
    const val WASTES = "Wastes"

    private val NAMES = BY_COLOR.values.toSet() + WASTES +
        BY_COLOR.values.map { "Snow-Covered $it" } + "Snow-Covered Wastes"

    fun isBasic(name: String) = name in NAMES

    /**
     * Reparte [count] básicas entre los colores de la identidad según los símbolos de maná de los
     * hechizos ([manaCosts], con su cantidad). Sin colores: Páramos (Wastes).
     */
    fun distribute(count: Int, colorIdentity: String, manaCosts: List<Pair<String, Int>>): Map<String, Int> {
        if (count <= 0) return emptyMap()
        val colors = BY_COLOR.keys.filter { colorIdentity.uppercase().contains(it) }
        if (colors.isEmpty()) return mapOf(WASTES to count)

        val pips = colors.associateWith { 0.0 }.toMutableMap()
        manaCosts.forEach { (cost, qty) ->
            Regex("\\{([^}]+)\\}").findAll(cost.uppercase()).forEach { symbol ->
                val parts = symbol.groupValues[1].split('/').filter { it in pips }
                parts.forEach { pips[it] = pips.getValue(it) + qty.toDouble() / parts.size }
            }
        }
        val weights = if (pips.values.sum() == 0.0) colors.associateWith { 1.0 } else pips
        val total = weights.values.sum()

        // Reparto proporcional con restos mayores, y al menos una de cada color si caben.
        val exact = colors.associateWith { weights.getValue(it) / total * count }
        val result = exact.mapValues { floor(it.value).toInt() }.toMutableMap()
        var left = count - result.values.sum()
        exact.entries.sortedByDescending { it.value - floor(it.value) }.forEach { (color, _) ->
            if (left > 0) { result[color] = result.getValue(color) + 1; left-- }
        }
        if (count >= colors.size) {
            colors.filter { result.getValue(it) == 0 }.forEach { missing ->
                val donor = result.maxBy { it.value }.key
                result[donor] = result.getValue(donor) - 1
                result[missing] = 1
            }
        }
        return result.filterValues { it > 0 }.mapKeys { BY_COLOR.getValue(it.key) }
    }
}
