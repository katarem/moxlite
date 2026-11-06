package es.moxlite.data.remote.edhrec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.text.Normalizer

/*
 * EDHREC no tiene API pública documentada: estos son los JSON que carga su propia web.
 *
 * - https://json.edhrec.com/pages/commanders/<slug>[/<tema>|/<bracket>][/budget|/expensive].json
 *   Página del comandante: listas de cartas con sinergia e inclusión, temas, distribución media.
 * - https://json.edhrec.com/pages/average-decks/<slug>[/<tema>|/<bracket>][/budget|/expensive].json
 *   Mazo medio (100 cartas).
 * - POST https://edhrec.com/api/recs/  {"commanders": [...], "cards": [...]}
 *   Recomendaciones para un mazo concreto: qué añadir (inRecs) y qué sobra (outRecs).
 *
 * Una página que no existe responde 403 (S3). Si el orden de una pareja de comandantes no es el
 * suyo, responde {"redirect": "/commanders/<slug-bueno>"}.
 */

@Serializable
data class EdhPage(
    val header: String = "",
    val redirect: String? = null,
    /** Distribución media de tipos en los mazos de EDHREC. */
    val creature: Int? = null,
    val instant: Int? = null,
    val sorcery: Int? = null,
    val artifact: Int? = null,
    val enchantment: Int? = null,
    val planeswalker: Int? = null,
    val battle: Int? = null,
    val land: Int? = null,
    val basic: Int? = null,
    val nonbasic: Int? = null,
    @SerialName("tag_counts") val tagCounts: List<EdhTag> = emptyList(),
    @SerialName("budget_counts") val budgetCounts: Map<String, Int> = emptyMap(),
    @SerialName("bracket_counts") val bracketCounts: Map<String, Int> = emptyMap(),
    val panels: EdhPanels = EdhPanels(),
    val container: EdhContainer = EdhContainer(),
    /** Solo en average-decks. */
    val deck: EdhAverageDeck? = null,
    /** Solo en average-decks: el mazo medio con ids de Scryfall (c = comandante, m = mazo). */
    val archidekt: List<EdhArchidektCard> = emptyList(),
) {
    val cardLists: List<EdhCardList> get() = container.jsonDict.cardlists
    val commander: EdhCommanderCard? get() = container.jsonDict.card

    /** Número de mazos de EDHREC con este comandante (o pareja). */
    val numDecks: Int get() = commander?.numDecks ?: 0
}

@Serializable
data class EdhTag(val count: Int = 0, val slug: String = "", val value: String = "")

@Serializable
data class EdhPanels(@SerialName("mana_curve") val manaCurve: Map<String, Int> = emptyMap())

@Serializable
data class EdhContainer(@SerialName("json_dict") val jsonDict: EdhJsonDict = EdhJsonDict())

@Serializable
data class EdhJsonDict(
    val cardlists: List<EdhCardList> = emptyList(),
    val card: EdhCommanderCard? = null,
)

@Serializable
data class EdhCommanderCard(
    val name: String = "",
    val names: List<String> = emptyList(),
    val id: String? = null,
    @SerialName("num_decks") val numDecks: Int = 0,
    @SerialName("color_identity") val colorIdentity: List<String> = emptyList(),
)

@Serializable
data class EdhCardList(
    val header: String = "",
    val tag: String = "",
    val cardviews: List<EdhCardView> = emptyList(),
)

@Serializable
data class EdhCardView(
    /** Id de Scryfall de la impresión que usa EDHREC. */
    val id: String? = null,
    val name: String = "",
    val sanitized: String = "",
    /** Sinergia con el comandante: fracción (0,27 = +27 %). */
    val synergy: Double? = null,
    @SerialName("num_decks") val numDecks: Int? = null,
    @SerialName("potential_decks") val potentialDecks: Int? = null,
) {
    /** Fracción de los mazos del comandante que llevan la carta. */
    val inclusion: Double?
        get() {
            val n = numDecks ?: return null
            val total = potentialDecks?.takeIf { it > 0 } ?: return null
            return n.toDouble() / total
        }
}

@Serializable
data class EdhAverageDeck(
    val commander: List<String> = emptyList(),
    /** Por tipo: [["Sol Ring", 1], ["Forest", 4], …]. */
    val cards: Map<String, List<JsonArray>> = emptyMap(),
) {
    fun entries(): List<Pair<String, Int>> = cards.values.flatten().mapNotNull { pair ->
        val name = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val qty = (pair.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: 1
        name to qty
    }
}

@Serializable
data class EdhArchidektCard(
    /** "c" comandante, "m" mazo. */
    val c: String = "m",
    val q: Int = 1,
    /** Id de Scryfall. */
    val u: String = "",
)

@Serializable
data class EdhRecsRequest(
    val commanders: List<String>,
    val cards: List<String>,
    val name: String = "",
)

@Serializable
data class EdhRecs(
    val inRecs: List<EdhRec> = emptyList(),
    val outRecs: List<EdhRec> = emptyList(),
)

@Serializable
data class EdhRec(
    val name: String = "",
    @SerialName("primary_type") val primaryType: String = "",
    val salt: Double? = null,
    /** 0–100: cuánto encaja (inRecs) o cuánto sobra (outRecs). */
    val score: Double = 0.0,
)

/** Rutas de EDHREC a partir de nombres de carta. */
object EdhrecPaths {

    /**
     * Slug como lo genera EDHREC: cara frontal, sin tildes, sin signos, palabras unidas con guiones.
     * "Lim-Dûl the Necromancer" → "lim-dul-the-necromancer"; "Y'shtola, Night's Blessed" → "yshtola-nights-blessed".
     */
    fun slug(cardName: String): String {
        val front = cardName.substringBefore(" // ").trim()
        val ascii = Normalizer.normalize(front, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("æ", "ae").replace("Æ", "Ae")
            .lowercase()
        return ascii
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("[\\s-]+"), "-")
    }

    /**
     * Slug de un comandante o de una pareja, en el orden de EDHREC: por orden alfabético, salvo
     * los que acompañan ([seconds]: backgrounds y "doctor's companion"), que van al final.
     * Si el orden no es el suyo, EDHREC responde con una redirección (y se sigue).
     */
    fun commanders(names: List<String>, seconds: Set<String> = emptySet()): String {
        val (last, first) = names.filter { it.isNotBlank() }.partition { it in seconds }
        return (first.map(::slug).sorted() + last.map(::slug).sorted()).joinToString("-")
    }

    /** Variantes que tiene EDHREC (los temas se combinan con presupuesto pero no con bracket). */
    fun variant(theme: String?, bracket: Int?, budget: EdhBudget): String = buildString {
        when {
            !theme.isNullOrBlank() -> append('/').append(theme)
            bracket != null -> bracketSlug(bracket)?.let { append('/').append(it) }
        }
        budget.slug?.let { append('/').append(it) }
    }

    fun commanderPage(base: String, theme: String? = null, bracket: Int? = null, budget: EdhBudget = EdhBudget.ANY) =
        "commanders/$base" + variant(theme, bracket, budget)

    fun averageDeck(base: String, theme: String? = null, bracket: Int? = null, budget: EdhBudget = EdhBudget.ANY) =
        "average-decks/$base" + variant(theme, bracket, budget)

    fun bracketSlug(bracket: Int): String? = when (bracket) {
        1 -> "exhibition"
        2 -> "core"
        3 -> "upgraded"
        4 -> "optimized"
        5 -> "cedh"
        else -> null
    }

    /** Ruta de una redirección ("/commanders/x" → "commanders/x"). */
    fun fromRedirect(redirect: String): String = redirect.trim().trimStart('/').removeSuffix(".json")
}

@Serializable
enum class EdhBudget(val slug: String?) { ANY(null), BUDGET("budget"), EXPENSIVE("expensive") }
