package es.moxlite.data.builder

import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.remote.Http
import es.moxlite.data.remote.edhrec.EdhBudget
import es.moxlite.data.remote.edhrec.EdhPage
import es.moxlite.data.remote.edhrec.EdhRecs
import es.moxlite.data.remote.edhrec.EdhrecClient
import es.moxlite.data.remote.edhrec.EdhrecPaths
import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.repo.CardRepository
import es.moxlite.data.repo.DeckMapper
import es.moxlite.data.repo.DeckRepository
import es.moxlite.domain.BasicLands
import es.moxlite.domain.BuildCard
import es.moxlite.domain.BuilderTargets
import es.moxlite.domain.CardRole
import es.moxlite.domain.CardRoles
import es.moxlite.domain.DeckLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class DraftCard(val card: ScryCard, val quantity: Int = 1)

/**
 * Mazo a medio construir con el asistente. Se guarda en disco a cada cambio, así que se puede
 * cerrar la app y seguir luego. Hay uno para "mazo nuevo" y uno por cada mazo que se mejora.
 */
@Serializable
data class BuilderDraft(
    /** "new" o el id del mazo que se está mejorando. */
    val key: String = NEW,
    val sourceDeckId: String? = null,
    /** Huella del mazo al empezar a mejorarlo: si cambia luego, el borrador ya no vale. */
    val sourceSignature: String? = null,
    val name: String = "",
    val commanders: List<ScryCard> = emptyList(),
    /** Tema de EDHREC (slug) y su nombre. */
    val theme: String? = null,
    val themeLabel: String? = null,
    val bracket: Int? = null,
    val budget: EdhBudget = EdhBudget.ANY,
    /** El mazo, sin los comandantes. */
    val cards: List<DraftCard> = emptyList(),
    val targets: BuilderTargets = BuilderTargets(),
    /** Ya se ha pasado de elegir comandante y enfoque a construir. */
    val started: Boolean = false,
    val updatedAt: Long = 0,
) {
    val commanderNames: List<String> get() = commanders.map { it.name }
    val colorIdentity: String get() = DeckMapper.colors(commanders.flatMap { it.colorIdentity })
    val total: Int get() = commanders.size + cards.sumOf { it.quantity }

    /** Ruta base de EDHREC para los comandantes ("kraum-ludevics-opus-tymna-the-weaver"). */
    val edhrecBase: String
        get() = EdhrecPaths.commanders(
            commanderNames,
            seconds = commanders.filter { c ->
                (c.typeLine ?: "").contains("Background") || c.fullOracle.orEmpty().contains("Doctor's companion", ignoreCase = true)
            }.mapTo(HashSet()) { it.name },
        )

    fun has(name: String) = cards.any { it.card.name.equals(name, ignoreCase = true) } ||
        commanders.any { it.name.equals(name, ignoreCase = true) }

    fun buildCards(): List<BuildCard> = cards.map { it.card.toBuildCard(it.quantity) }

    companion object {
        const val NEW = "new"
    }
}

fun ScryCard.toBuildCard(quantity: Int = 1): BuildCard {
    val type = typeLine ?: cardFaces.firstOrNull()?.typeLine.orEmpty()
    return BuildCard(
        name = name,
        typeLine = type,
        manaCost = manaCost?.ifBlank { null } ?: cardFaces.firstOrNull()?.manaCost.orEmpty(),
        cmc = cmc,
        quantity = quantity,
        roles = CardRoles.of(name, type, fullOracle),
    )
}

/**
 * Asistente de construcción: datos de EDHREC + cartas de Scryfall + borradores en disco.
 * Al terminar, el mazo se crea (o se actualiza) con [DeckRepository], que lo sube a Moxfield.
 */
class BuilderRepository(
    private val edhrec: EdhrecClient,
    private val cards: CardRepository,
    private val decks: DeckRepository,
    private val dir: File,
) {
    /** Cartas ya resueltas en Scryfall, por nombre en minúsculas (dura lo que el proceso). */
    private val resolved = ConcurrentHashMap<String, ScryCard>()

    // ---------- Borradores ----------

    suspend fun loadDraft(key: String): BuilderDraft? = withContext(Dispatchers.IO) {
        val file = draftFile(key)
        if (!file.isFile) return@withContext null
        runCatching { Http.json.decodeFromString(BuilderDraft.serializer(), file.readText()) }.getOrNull()
            ?.also { draft -> (draft.cards.map { it.card } + draft.commanders).forEach { remember(it) } }
    }

    suspend fun saveDraft(draft: BuilderDraft) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = draftFile(draft.key)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(Http.json.encodeToString(BuilderDraft.serializer(), draft.copy(updatedAt = System.currentTimeMillis())))
        tmp.renameTo(file)
    }

    suspend fun deleteDraft(key: String) = withContext(Dispatchers.IO) { draftFile(key).delete() }

    suspend fun hasNewDraft(): Boolean = withContext(Dispatchers.IO) { draftFile(BuilderDraft.NEW).isFile }

    /** Borrador para mejorar un mazo del móvil: sus comandantes y su mazo principal. */
    suspend fun draftFromDeck(deckId: String): BuilderDraft {
        val deck = decks.deckOf(deckId)
        val rows = decks.cardsOf(deckId)
        val commanders = rows.filter { it.board == Boards.COMMANDERS }.map { it.toScryCard() }
        val main = rows.filter { it.board == Boards.MAIN }.map { DraftCard(it.toScryCard(), it.quantity) }
        return BuilderDraft(
            key = deckId,
            sourceDeckId = deckId,
            sourceSignature = signature(rows),
            name = deck?.name.orEmpty(),
            commanders = commanders,
            cards = main,
            started = commanders.isNotEmpty(),
        )
    }

    /**
     * Borrador para mejorar un mazo: el guardado si el mazo no ha cambiado desde que se empezó,
     * o uno nuevo a partir del mazo tal como está ahora.
     */
    suspend fun draftForDeck(deckId: String): BuilderDraft {
        val saved = loadDraft(deckId)
        if (saved != null && saved.sourceSignature == signature(decks.cardsOf(deckId))) return saved
        return draftFromDeck(deckId)
    }

    private fun signature(rows: List<DeckCardEntity>): String =
        rows.filter { it.board == Boards.COMMANDERS || it.board == Boards.MAIN }
            .map { "${it.board}|${it.name.lowercase()}|${it.quantity}" }
            .sorted()
            .joinToString("\n")
            .hashCode()
            .toString()

    // ---------- EDHREC ----------

    suspend fun commanderPage(draft: BuilderDraft, withOptions: Boolean = true): EdhPage =
        edhrec.page(
            if (withOptions) EdhrecPaths.commanderPage(draft.edhrecBase, draft.theme, draft.bracket, draft.budget)
            else EdhrecPaths.commanderPage(draft.edhrecBase)
        )

    suspend fun recs(draft: BuilderDraft): EdhRecs =
        edhrec.recs(draft.commanderNames, draft.cards.map { it.card.name })

    /** Mazo medio de EDHREC para el comandante y el enfoque elegidos, sin los comandantes. */
    suspend fun averageDeck(draft: BuilderDraft): List<DraftCard> {
        val page = edhrec.page(EdhrecPaths.averageDeck(draft.edhrecBase, draft.theme, draft.bracket, draft.budget))
        val commanders = draft.commanderNames.map { it.lowercase() }.toSet()
        if (page.archidekt.isNotEmpty()) {
            val main = page.archidekt.filter { it.c != "c" && it.u.isNotBlank() }
            val byId = withContext(Dispatchers.IO) { cards.byIds(main.map { it.u }) }
            byId.values.forEach(::remember)
            val found = main.mapNotNull { entry -> byId[entry.u]?.let { DraftCard(it, entry.q) } }
                .filter { it.card.name.lowercase() !in commanders }
            if (found.isNotEmpty()) return merge(found)
        }
        val entries = page.deck?.entries().orEmpty().filter { it.first.lowercase() !in commanders }
        val byName = resolveNames(entries.map { it.first })
        return merge(entries.mapNotNull { (name, qty) -> byName[name.lowercase()]?.let { DraftCard(it, qty) } })
    }

    // ---------- Scryfall ----------

    fun cached(name: String): ScryCard? = resolved[name.lowercase()]

    /** Resuelve nombres de carta (los que aún no estén en caché), en lotes de 75. */
    suspend fun resolveNames(names: Collection<String>): Map<String, ScryCard> {
        val missing = names.map { it.trim() }.filter { it.isNotEmpty() && cached(it) == null }.distinctBy { it.lowercase() }
        if (missing.isNotEmpty()) {
            val found = withContext(Dispatchers.IO) { cards.resolve(missing.map { DeckLine(1, it) }) }
            found.forEach { (line, card) ->
                if (card != null) {
                    remember(card)
                    resolved[line.name.lowercase()] = card
                }
            }
        }
        return names.mapNotNull { name -> cached(name)?.let { name.lowercase() to it } }.toMap()
    }

    /** Resuelve por id de Scryfall (las listas de EDHREC traen el id de su impresión). */
    suspend fun resolveIds(byName: Map<String, String>) {
        val missing = byName.filterKeys { cached(it) == null }
        if (missing.isEmpty()) return
        val found = withContext(Dispatchers.IO) { cards.byIds(missing.values.toList()) }
        missing.forEach { (name, id) -> found[id]?.let { resolved[name.lowercase()] = it; remember(it) } }
    }

    suspend fun basicLand(name: String): ScryCard? = cached(name) ?: resolveNames(listOf(name))[name.lowercase()]

    suspend fun namedCard(name: String): ScryCard = cached(name) ?: withContext(Dispatchers.IO) { cards.named(name) }.also(::remember)

    suspend fun commanderSearch(text: String, filter: String) = withContext(Dispatchers.IO) { cards.commanderSearch(text, filter) }

    suspend fun commanderSuggestions(text: String) = withContext(Dispatchers.IO) { cards.commanderSuggestions(text) }

    // ---------- Terminar ----------

    /** Crea el mazo en el móvil (y lo sube a Moxfield). Devuelve su id. */
    suspend fun createDeck(draft: BuilderDraft): String {
        val rows = draft.commanders.map { Triple(Boards.COMMANDERS, it, 1) } +
            draft.cards.map { Triple(Boards.MAIN, it.card, it.quantity) }
        val name = draft.name.ifBlank { draft.commanders.firstOrNull()?.name.orEmpty() }
        return decks.createFromCards(name, "commander", rows).also { deleteDraft(draft.key) }
    }

    /** Aplica el borrador a un mazo existente: añade, quita y cambia cantidades. */
    suspend fun applyToDeck(draft: BuilderDraft): String {
        val deckId = decks.resolveId(draft.sourceDeckId ?: error("sin mazo"))
        val rows = decks.cardsOf(deckId)
        applyBoard(deckId, Boards.COMMANDERS, rows, draft.commanders.map { DraftCard(it, 1) })
        applyBoard(deckId, Boards.MAIN, decks.cardsOf(deckId), draft.cards)
        if (draft.name.isNotBlank() && draft.name != decks.deckOf(deckId)?.name) decks.rename(deckId, draft.name)
        deleteDraft(draft.key)
        return deckId
    }

    private suspend fun applyBoard(deckId: String, board: String, rows: List<DeckCardEntity>, wanted: List<DraftCard>) {
        val current = rows.filter { it.board == board }
        val wantedByName = merge(wanted).associateBy { it.card.name.lowercase() }
        current.forEach { row ->
            val want = wantedByName[row.name.lowercase()]
            when {
                want == null -> decks.setQuantity(deckId, board, row.cardKey, 0)
                want.quantity != row.quantity -> decks.setQuantity(deckId, board, row.cardKey, want.quantity)
            }
        }
        val have = current.mapTo(HashSet()) { it.name.lowercase() }
        wantedByName.values.filter { it.card.name.lowercase() !in have }.forEach { add ->
            decks.addCard(deckId, board, add.card, add.quantity)
        }
    }

    // ---------- Internos ----------

    private fun remember(card: ScryCard) {
        if (card.name.isBlank()) return
        resolved.putIfAbsent(card.name.lowercase(), card)
        card.name.substringBefore(" // ").takeIf { it != card.name }?.let { resolved.putIfAbsent(it.lowercase(), card) }
    }

    private fun draftFile(key: String) = File(dir, "draft-" + key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun merge(list: List<DraftCard>): List<DraftCard> =
        list.groupBy { it.card.name.lowercase() }.map { (_, same) -> same.first().copy(quantity = same.sumOf { it.quantity }) }

    companion object {
        /** Cuántas no básicas pone EDHREC de media, si la página no lo dice. */
        fun nonbasicTarget(page: EdhPage?, lands: Int): Int =
            page?.nonbasic?.takeIf { it > 0 }?.coerceAtMost(lands) ?: (lands * 0.55).toInt()

        /**
         * Si [card] admite un segundo comandante, la condición de Scryfall para buscarlo
         * (partner, "partner with", background, friends forever, doctor's companion). Si no, null.
         */
        fun partnerFilter(card: ScryCard): String? {
            val oracle = card.fullOracle.orEmpty()
            val text = oracle.lowercase().replace(Regex("\\([^)]*\\)"), " ")
            val type = (card.typeLine ?: card.cardFaces.firstOrNull()?.typeLine).orEmpty()
            Regex("partner with ([^(\\n]+)", RegexOption.IGNORE_CASE).find(oracle)?.let { match ->
                val name = match.groupValues[1].trim().trimEnd('.').replace("\"", "")
                if (name.isNotBlank()) return "!\"$name\""
            }
            return when {
                "choose a background" in text -> "t:background"
                type.contains("Background") -> "is:commander o:\"choose a background\""
                "friends forever" in text -> "keyword:\"friends forever\""
                "doctor's companion" in text -> "t:\"time lord\" t:doctor"
                type.contains("Time Lord") && type.contains("Doctor") -> "o:\"doctor's companion\""
                Regex("\\bpartner\\b").containsMatchIn(text) -> "is:commander keyword:partner -o:\"partner with\""
                else -> null
            }
        }
    }
}

/** Carta guardada en Room → ScryCard (lo justo para el asistente; sin red). */
fun DeckCardEntity.toScryCard(): ScryCard = ScryCard(
    id = scryfallId.orEmpty(),
    name = name,
    set = setCode,
    collectorNumber = collectorNumber.orEmpty(),
    manaCost = manaCost,
    typeLine = typeLine,
    oracleText = oracleText,
    cmc = cmc,
    colorIdentity = colorIdentity.map { it.toString() },
    rarity = rarity,
)
