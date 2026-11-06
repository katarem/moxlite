package es.moxlite.ui.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.moxlite.AppContainer
import es.moxlite.data.builder.BuilderDraft
import es.moxlite.data.builder.BuilderRepository
import es.moxlite.data.builder.DraftCard
import es.moxlite.data.builder.toBuildCard
import es.moxlite.data.remote.edhrec.EdhBudget
import es.moxlite.data.remote.edhrec.EdhPage
import es.moxlite.data.remote.edhrec.EdhRecs
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.userMessage
import es.moxlite.domain.AutoFill
import es.moxlite.domain.BasicLands
import es.moxlite.domain.BuildCandidate
import es.moxlite.domain.CardRole
import es.moxlite.domain.DeckStats
import es.moxlite.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BuilderStep { COMMANDER, OPTIONS, BUILD }

/** Carta propuesta por EDHREC, con lo que se sabe de ella. */
data class Suggestion(
    val name: String,
    /** Id de Scryfall de la impresión que usa EDHREC (para la imagen). */
    val scryfallId: String?,
    val card: ScryCard?,
    /** Tipo principal en inglés ("Creature"…), vacío si aún no se sabe. */
    val primaryType: String,
    val roles: Set<CardRole>,
    /** Sinergia con el comandante (fracción). */
    val synergy: Double?,
    /** Fracción de mazos del comandante que la llevan. */
    val inclusion: Double?,
    /** Encaje con tu mazo según EDHREC (0–100), o cuánto sobra en "Sobran". */
    val score: Double?,
    /** Listas de EDHREC en las que aparece (topcards, creatures…). */
    val lists: Set<String>,
    val order: Int,
) {
    val imageId: String? get() = card?.id?.takeIf { it.isNotBlank() } ?: scryfallId
}

sealed interface SuggestionFilter {
    /** Recomendaciones de EDHREC para el mazo tal como está. */
    data object ForDeck : SuggestionFilter
    data class Role(val role: CardRole) : SuggestionFilter
    data class EdhList(val tag: String, val header: String) : SuggestionFilter
}

data class BuilderUi(
    val loadingDraft: Boolean = true,
    val draft: BuilderDraft = BuilderDraft(),
    /** Cambiando comandante o enfoque desde la construcción. */
    val editingOptions: Boolean = false,
    /** Página del comandante sin variantes (temas, brackets y presupuesto disponibles). */
    val basePage: EdhPage? = null,
    /** Página con el tema, bracket y presupuesto elegidos (sugerencias). */
    val page: EdhPage? = null,
    val pageLoading: Boolean = false,
    val pageError: String? = null,
    val recs: EdhRecs? = null,
    val recsLoading: Boolean = false,
    val recsError: String? = null,
    /** Trabajo largo en curso (mazo medio, autocompletar…), con su texto. */
    val busy: String? = null,
    /** Cambia cuando llegan cartas de Scryfall, para recalcular las sugerencias. */
    val resolvedVersion: Int = 0,
) {
    val step: BuilderStep
        get() = when {
            draft.commanders.isEmpty() -> BuilderStep.COMMANDER
            !draft.started || editingOptions -> BuilderStep.OPTIONS
            else -> BuilderStep.BUILD
        }

    /** Ya hay mazo (se está mejorando uno existente o se empezó a construir). */
    val isImprovingDeck: Boolean get() = draft.sourceDeckId != null
}

sealed interface BuilderEvent {
    data class Message(val text: String) : BuilderEvent
    data class OpenDeck(val deckId: String, val replace: Boolean) : BuilderEvent
}

/**
 * Asistente de construcción con EDHREC: comandante → enfoque (tema, presupuesto, bracket) →
 * construir con sugerencias, objetivos por función y autocompletado → crear el mazo.
 */
class BuilderViewModel(private val c: AppContainer, private val deckId: String?) : ViewModel() {
    private val repo = c.builderRepository

    private val _ui = MutableStateFlow(BuilderUi())
    val ui = _ui.asStateFlow()

    private val _filter = MutableStateFlow<SuggestionFilter>(SuggestionFilter.ForDeck)
    val filter = _filter.asStateFlow()

    private val _events = MutableSharedFlow<BuilderEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var saveJob: Job? = null
    private var recsJob: Job? = null
    private var pageJob: Job? = null

    val stats: StateFlow<DeckStats> = _ui.map { it.draft }.distinctUntilChanged()
        .map { DeckStats.of(it.commanders.size, it.buildCards()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, DeckStats.of(0, emptyList()))

    private val allSuggestions: StateFlow<List<Suggestion>> = _ui
        .map { Triple(it.page ?: it.basePage, it.recs, it.draft to it.resolvedVersion) }
        .distinctUntilChanged()
        .map { (page, recs, draftAndVersion) -> merge(page, recs, draftAndVersion.first) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val suggestions: StateFlow<List<Suggestion>> = combine(allSuggestions, _filter, _ui.map { it.recs != null }.distinctUntilChanged()) { all, f, hasRecs ->
        applyFilter(all, f, hasRecs)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Cartas del mazo que EDHREC ve menos habituales con este comandante. */
    val cuts: StateFlow<List<Suggestion>> = _ui.map { it.recs to it.draft }.distinctUntilChanged().map { (recs, draft) ->
        val inDeck = draft.cards.associateBy { it.card.name.lowercase() }
        recs?.outRecs.orEmpty()
            .filter { it.score > 0 && !BasicLands.isBasic(it.name) }
            .mapNotNull { rec ->
                val card = inDeck[rec.name.lowercase()]?.card ?: return@mapNotNull null
                Suggestion(
                    name = card.name, scryfallId = card.id, card = card, primaryType = rec.primaryType,
                    roles = card.toBuildCard().roles, synergy = null, inclusion = null, score = rec.score,
                    lists = emptySet(), order = 0,
                )
            }
            .sortedByDescending { it.score }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Filtros disponibles: para tu mazo, por función y las listas de EDHREC. */
    val filters: StateFlow<List<SuggestionFilter>> = _ui.map { it.page ?: it.basePage }.distinctUntilChanged().map { page ->
        listOf<SuggestionFilter>(SuggestionFilter.ForDeck) +
            listOf(CardRole.RAMP, CardRole.DRAW, CardRole.REMOVAL, CardRole.WIPE, CardRole.LAND).map { SuggestionFilter.Role(it) } +
            page?.cardLists.orEmpty().filter { it.cardviews.isNotEmpty() }.map { SuggestionFilter.EdhList(it.tag, it.header) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, listOf(SuggestionFilter.ForDeck))

    init {
        viewModelScope.launch {
            val draft = try {
                val key = deckId?.let { c.deckRepository.resolveId(it) }
                when {
                    key == null -> repo.loadDraft(BuilderDraft.NEW) ?: BuilderDraft()
                    else -> repo.draftForDeck(key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(e.userMessage())
                BuilderDraft()
            }
            _ui.update { it.copy(loadingDraft = false, draft = draft) }
            if (draft.commanders.isNotEmpty()) {
                loadPages()
                if (draft.started) refreshRecs(immediately = true)
            }
        }
    }

    // ---------- Comandante ----------

    suspend fun commanderResults(text: String): List<ScryCard> {
        val first = _ui.value.draft.commanders.firstOrNull()
        val filter = if (first == null) "is:commander" else partnerFilter(first) ?: return emptyList()
        if (first == null && text.trim().length < 2) return emptyList()
        return runCatching { repo.commanderSearch(text, filter) }
            .getOrElse { if (it is CancellationException) throw it else emptyList() }
            .filter { card -> _ui.value.draft.commanders.none { it.name == card.name } }
    }

    /** Si el comandante admite pareja, la condición de Scryfall para buscarla. */
    fun partnerFilter(card: ScryCard): String? = BuilderRepository.partnerFilter(card)

    fun chooseCommander(card: ScryCard) = launchSafe {
        val english = c.cardRepository.englishPrinting(card)
        updateDraft { d ->
            val commanders = (d.commanders + english).distinctBy { it.name }.take(2)
            // El nombre sigue a los comandantes mientras no se haya cambiado a mano.
            val autoName = autoName(d.commanders)
            d.copy(
                commanders = commanders,
                name = if (d.name.isBlank() || d.name == autoName || d.commanders.isEmpty()) autoName(commanders) else d.name,
                theme = null, themeLabel = null,
                cards = d.cards.filter { it.card.name != english.name },
            )
        }
        _ui.update { it.copy(basePage = null, page = null, recs = null) }
        loadPages()
    }

    fun removeCommander(name: String) {
        updateDraft { d ->
            val left = d.commanders.filter { it.name != name }
            d.copy(
                commanders = left,
                name = if (d.name == autoName(d.commanders)) autoName(left) else d.name,
                theme = null, themeLabel = null, started = d.started && left.isNotEmpty(),
            )
        }
        _ui.update { it.copy(basePage = null, page = null, recs = null, pageError = null) }
        if (_ui.value.draft.commanders.isNotEmpty()) loadPages()
    }

    // ---------- Enfoque ----------

    fun setName(name: String) = updateDraft { it.copy(name = name) }

    fun setTheme(slug: String?, label: String?) {
        updateDraft { d ->
            if (d.theme == slug || slug == null) d.copy(theme = null, themeLabel = null)
            else d.copy(theme = slug, themeLabel = label, bracket = null)
        }
        loadPage()
    }

    fun setBracket(bracket: Int?) {
        updateDraft { d -> d.copy(bracket = if (d.bracket == bracket) null else bracket, theme = if (bracket != null) null else d.theme, themeLabel = if (bracket != null) null else d.themeLabel) }
        loadPage()
    }

    fun setBudget(budget: EdhBudget) {
        updateDraft { it.copy(budget = budget) }
        loadPage()
    }

    /** Volver a pedir a EDHREC la página del comandante (tras un error). */
    fun retry() = loadPages()

    fun editOptions() = _ui.update { it.copy(editingOptions = true) }

    fun closeOptions() = _ui.update { it.copy(editingOptions = false) }

    /** Empezar a construir: con el mazo medio de EDHREC, o solo con el comandante (o el mazo que había). */
    fun start(withAverageDeck: Boolean) = launchSafe {
        val t = I18n.strings
        val page = _ui.value.page
        if (withAverageDeck) {
            _ui.update { it.copy(busy = t.edhLoadingAverage) }
            try {
                val cards = repo.averageDeck(_ui.value.draft)
                updateDraft { it.copy(cards = cards) }
                message(t.edhAverageLoaded(cards.sumOf { it.quantity }))
            } finally {
                _ui.update { it.copy(busy = null) }
            }
        }
        updateDraft { d ->
            d.copy(
                started = true,
                targets = if (d.started) d.targets else d.targets.copy(lands = page?.land?.takeIf { it in 20..45 } ?: d.targets.lands),
            )
        }
        _ui.update { it.copy(editingOptions = false) }
        refreshRecs(immediately = true)
    }

    // ---------- Construir ----------

    fun setFilter(filter: SuggestionFilter) {
        _filter.value = filter
    }

    fun add(suggestion: Suggestion) = launchSafe {
        val card = suggestion.card ?: repo.resolveNames(listOf(suggestion.name))[suggestion.name.lowercase()]
            ?: repo.namedCard(suggestion.name)
        addCards(listOf(card to 1))
        message(I18n.strings.added(card.name))
    }

    fun remove(name: String) = updateDraft { d -> d.copy(cards = d.cards.filterNot { it.card.name.equals(name, ignoreCase = true) }) }

    fun setQuantity(name: String, quantity: Int) = updateDraft { d ->
        if (quantity <= 0) d.copy(cards = d.cards.filterNot { it.card.name == name })
        else d.copy(cards = d.cards.map { if (it.card.name == name) it.copy(quantity = quantity) else it })
    }

    fun setTarget(role: CardRole, value: Int) = updateDraft { it.copy(targets = it.targets.with(role, value)) }

    /** Rellena hasta 100 con lo que falta de cada función, las mejores sugerencias y tierras. */
    fun autoFill() = launchSafe {
        val t = I18n.strings
        _ui.update { it.copy(busy = t.edhAutoFilling) }
        try {
            if (_ui.value.recs == null) refreshRecs(immediately = true)?.join()
            resolveSuggestions()
            val draft = _ui.value.draft
            val pool = withContext(Dispatchers.Default) { candidatesInOrder() }
            val plan = AutoFill.plan(
                commanders = draft.commanders.size,
                current = draft.buildCards(),
                candidates = pool.map { BuildCandidate(it.name, it.roles, BasicLands.isBasic(it.name)) },
                targets = draft.targets,
                nonbasicTarget = BuilderRepository.nonbasicTarget(_ui.value.page, draft.targets.lands),
            )
            val byName = pool.associateBy { it.name }
            val picked = plan.picks.mapNotNull { name -> byName[name]?.card ?: repo.cached(name) }.map { it to 1 }
            val basics = basicsFor(plan.basics, draft.colorIdentity, draft.cards.map { it.card } + picked.map { it.first })
            addCards(picked + basics)
            val added = picked.size + basics.sumOf { it.second }
            message(if (added == 0) t.edhNothingToAdd else t.edhAutoFilled(added))
        } finally {
            _ui.update { it.copy(busy = null) }
        }
    }

    /** Añade básicas hasta el objetivo de tierras (sin pasar de 100). */
    fun fillBasics() = launchSafe {
        val draft = _ui.value.draft
        val lands = stats.value.count(CardRole.LAND)
        val need = minOf(draft.targets.lands - lands, 100 - draft.total)
        if (need <= 0) {
            message(I18n.strings.edhLandsComplete)
            return@launchSafe
        }
        val basics = basicsFor(need, draft.colorIdentity, draft.cards.map { it.card })
        addCards(basics)
        message(I18n.strings.edhBasicsAdded(basics.sumOf { it.second }))
    }

    fun refreshRecsNow() {
        refreshRecs(immediately = true)
    }

    /** Crea el mazo (o guarda los cambios en el mazo que se mejoraba) y lo abre. */
    fun finish() = launchSafe {
        // Que un guardado pendiente no vuelva a crear el borrador después de terminar.
        saveJob?.cancel()
        val draft = _ui.value.draft
        val improving = draft.sourceDeckId != null
        val id = if (improving) repo.applyToDeck(draft) else repo.createDeck(draft)
        _events.emit(BuilderEvent.OpenDeck(id, replace = true))
        message(if (improving) I18n.strings.edhDeckUpdated else I18n.strings.edhDeckCreated)
    }

    /** Tira el borrador y vuelve a empezar (desde el mazo, si se mejoraba uno). */
    fun discard() = launchSafe {
        saveJob?.cancel()
        val key = _ui.value.draft.key
        repo.deleteDraft(key)
        val fresh = if (deckId != null) repo.draftFromDeck(c.deckRepository.resolveId(deckId)) else BuilderDraft()
        _filter.value = SuggestionFilter.ForDeck
        _ui.value = BuilderUi(loadingDraft = false, draft = fresh)
        if (fresh.commanders.isNotEmpty()) loadPages()
        if (fresh.started) refreshRecs(immediately = true)
    }

    // ---------- Internos ----------

    private fun updateDraft(transform: (BuilderDraft) -> BuilderDraft) {
        val before = _ui.value.draft
        _ui.update { it.copy(draft = transform(it.draft)) }
        val after = _ui.value.draft
        if (after == before) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            runCatching { repo.saveDraft(_ui.value.draft) }
        }
        if (after.started && after.cards != before.cards) refreshRecs()
    }

    private fun addCards(cards: List<Pair<ScryCard, Int>>) = updateDraft { d ->
        var list = d.cards
        cards.forEach { (card, qty) ->
            val index = list.indexOfFirst { it.card.name.equals(card.name, ignoreCase = true) }
            list = if (index >= 0) {
                // Singleton salvo básicas (y cartas que permiten más copias, que se ajustan a mano).
                if (BasicLands.isBasic(card.name)) list.toMutableList().also { it[index] = it[index].copy(quantity = it[index].quantity + qty) }
                else list
            } else list + DraftCard(card, qty)
        }
        d.copy(cards = list)
    }

    private suspend fun basicsFor(count: Int, identity: String, spells: List<ScryCard>): List<Pair<ScryCard, Int>> {
        if (count <= 0) return emptyList()
        val costs = spells.filterNot { it.toBuildCard().isLand }.map { (it.manaCost ?: it.cardFaces.firstOrNull()?.manaCost).orEmpty() to 1 }
        val split = BasicLands.distribute(count, identity, costs)
        repo.resolveNames(split.keys)
        return split.mapNotNull { (name, qty) -> repo.basicLand(name)?.let { it to qty } }
    }

    /** Candidatas para autocompletar: primero las de EDHREC para tu mazo, luego por inclusión. */
    private fun candidatesInOrder(): List<Suggestion> {
        // Calculado aquí (y no con allSuggestions) para usar las cartas recién resueltas.
        val ui = _ui.value
        val all = merge(ui.page ?: ui.basePage, ui.recs, ui.draft)
        val forDeck = all.filter { it.score != null }.sortedByDescending { it.score }
        val rest = all.filter { it.score == null }.sortedByDescending { it.inclusion ?: 0.0 }
        return (forDeck + rest).filter { it.card != null }
    }

    private fun loadPages() {
        val draft = _ui.value.draft
        if (draft.commanders.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(pageLoading = true, pageError = null) }
            try {
                val base = repo.commanderPage(draft, withOptions = false)
                _ui.update { it.copy(basePage = base) }
                if (draft.theme == null && draft.bracket == null && draft.budget == EdhBudget.ANY) {
                    _ui.update { it.copy(page = base, pageLoading = false) }
                    resolveSuggestions()
                } else {
                    loadPage()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(pageLoading = false, pageError = e.userMessage()) }
            }
        }
    }

    private fun loadPage() {
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            delay(150)
            _ui.update { it.copy(pageLoading = true, pageError = null) }
            try {
                val page = repo.commanderPage(_ui.value.draft)
                _ui.update { it.copy(page = page, pageLoading = false) }
                resolveSuggestions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(page = null, pageLoading = false, pageError = e.userMessage()) }
            }
        }
    }

    private fun refreshRecs(immediately: Boolean = false): Job? {
        val draft = _ui.value.draft
        if (draft.commanders.isEmpty()) return null
        recsJob?.cancel()
        return viewModelScope.launch {
            if (!immediately) delay(1_500)
            _ui.update { it.copy(recsLoading = true, recsError = null) }
            try {
                val recs = repo.recs(_ui.value.draft)
                _ui.update { it.copy(recs = recs, recsLoading = false) }
                resolveSuggestions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(recsLoading = false, recsError = e.userMessage()) }
            }
        }.also { recsJob = it }
    }

    /** Trae de Scryfall las cartas sugeridas (imagen, tipo y texto para saber su función). */
    private suspend fun resolveSuggestions() {
        val ui = _ui.value
        val ids = (ui.page ?: ui.basePage)?.cardLists.orEmpty().flatMap { it.cardviews }
            .filter { !it.id.isNullOrBlank() && repo.cached(it.name) == null }
            .associate { it.name to it.id!! }
        val names = ui.recs?.let { r -> (r.inRecs + r.outRecs).map { it.name } }.orEmpty()
            .filter { repo.cached(it) == null && it !in ids }
        if (ids.isEmpty() && names.isEmpty()) return
        try {
            if (ids.isNotEmpty()) repo.resolveIds(ids)
            if (names.isNotEmpty()) repo.resolveNames(names)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin Scryfall se ven igual (con la imagen de EDHREC), solo sin función.
        }
        _ui.update { it.copy(resolvedVersion = it.resolvedVersion + 1) }
    }

    private fun merge(page: EdhPage?, recs: EdhRecs?, draft: BuilderDraft): List<Suggestion> {
        val map = LinkedHashMap<String, Suggestion>()
        var order = 0
        page?.cardLists.orEmpty().forEach { list ->
            list.cardviews.forEach { view ->
                val key = view.name.lowercase()
                val existing = map[key]
                map[key] = existing?.copy(
                    lists = existing.lists + list.tag,
                    primaryType = existing.primaryType.ifBlank { typeForList(list.tag) },
                    synergy = existing.synergy ?: view.synergy,
                    inclusion = existing.inclusion ?: view.inclusion,
                ) ?: Suggestion(
                    name = view.name, scryfallId = view.id, card = null, primaryType = typeForList(list.tag),
                    roles = emptySet(), synergy = view.synergy, inclusion = view.inclusion, score = null,
                    lists = setOf(list.tag), order = order++,
                )
            }
        }
        recs?.inRecs.orEmpty().forEach { rec ->
            val key = rec.name.lowercase()
            val existing = map[key]
            map[key] = existing?.copy(score = rec.score, primaryType = existing.primaryType.ifBlank { rec.primaryType })
                ?: Suggestion(
                    name = rec.name, scryfallId = null, card = null, primaryType = rec.primaryType, roles = emptySet(),
                    synergy = null, inclusion = null, score = rec.score, lists = emptySet(), order = order++,
                )
        }
        return map.values
            .filter { !draft.has(it.name) && !BasicLands.isBasic(it.name) }
            .map { s ->
                val card = repo.cached(s.name)
                val roles = card?.toBuildCard()?.roles ?: if (s.primaryType == "Land") setOf(CardRole.LAND) else emptySet()
                s.copy(card = card, roles = roles, primaryType = s.primaryType.ifBlank { primaryTypeOf(card) })
            }
    }

    private fun applyFilter(all: List<Suggestion>, filter: SuggestionFilter, hasRecs: Boolean): List<Suggestion> {
        val byFit = compareByDescending<Suggestion> { it.score ?: -1.0 }.thenByDescending { it.inclusion ?: 0.0 }
        return when (filter) {
            SuggestionFilter.ForDeck ->
                if (hasRecs) all.filter { it.score != null }.sortedWith(byFit)
                else all.sortedByDescending { it.inclusion ?: 0.0 }
            is SuggestionFilter.Role -> all.filter { filter.role in it.roles }.sortedWith(byFit)
            is SuggestionFilter.EdhList -> all.filter { filter.tag in it.lists }.sortedBy { it.order }
        }
    }

    private fun message(text: String) {
        _events.tryEmit(BuilderEvent.Message(text))
    }

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(e.userMessage())
            }
        }
    }

    private companion object {
        fun shortName(name: String) = name.substringBefore(" // ").substringBefore(",")

        fun autoName(commanders: List<ScryCard>) = commanders.joinToString(" + ") { shortName(it.name) }

        fun typeForList(tag: String): String = when (tag) {
            "creatures" -> "Creature"
            "instants" -> "Instant"
            "sorceries" -> "Sorcery"
            "utilityartifacts", "manaartifacts" -> "Artifact"
            "enchantments" -> "Enchantment"
            "planeswalkers" -> "Planeswalker"
            "battles" -> "Battle"
            "utilitylands", "lands" -> "Land"
            else -> ""
        }

        fun primaryTypeOf(card: ScryCard?): String {
            val front = (card?.typeLine ?: card?.cardFaces?.firstOrNull()?.typeLine).orEmpty().substringBefore("//")
            return listOf("Land", "Creature", "Planeswalker", "Battle", "Instant", "Sorcery", "Artifact", "Enchantment")
                .firstOrNull { front.contains(it) }.orEmpty()
        }
    }
}
