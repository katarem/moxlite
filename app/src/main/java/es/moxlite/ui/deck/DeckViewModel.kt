package es.moxlite.ui.deck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.moxlite.AppContainer
import es.moxlite.data.local.CardLocalizationEntity
import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.userMessage
import es.moxlite.data.repo.PrintingKey
import es.moxlite.domain.ShareLink
import es.moxlite.i18n.I18n
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DeckEvent {
    data class Message(val text: String) : DeckEvent
    data class OpenDeck(val publicId: String) : DeckEvent
    data class Share(val text: String, val title: String) : DeckEvent
    data object Closed : DeckEvent
}

/** Estado de la lista de impresiones para elegir arte. */
data class PrintingsState(
    val cardName: String = "",
    val loading: Boolean = false,
    val printings: List<ScryCard> = emptyList(),
    val error: String? = null,
)

/** Estado del buscador de cartas para añadir. */
data class CardSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<ScryCard> = emptyList(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DeckViewModel(private val c: AppContainer, val publicId: String) : ViewModel() {
    private val repo = c.deckRepository

    val deck: StateFlow<DeckEntity?> = repo.observeDeck(publicId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val cards: StateFlow<List<DeckCardEntity>> = repo.observeCards(publicId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Cartas en el idioma elegido en Ajustes (imágenes y nombres). */
    val localized: StateFlow<Map<PrintingKey, CardLocalizationEntity>> = c.settings.cardLanguage
        .flatMapLatest { lang -> repo.observeLocalizations(lang) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Se puede editar (propio o local). Los mazos ajenos se copian para editarlos. */
    val canEdit: StateFlow<Boolean> = combine(deck, c.session.state) { d, _ -> d != null && repo.canEdit(d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<Throwable?>(null)
    val error = _error.asStateFlow()
    private val _events = MutableSharedFlow<DeckEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val _search = MutableStateFlow(CardSearchState())
    val search = _search.asStateFlow()
    private var searchJob: Job? = null

    private val _printings = MutableStateFlow<PrintingsState?>(null)
    val printings = _printings.asStateFlow()

    private var warnedNoSession = false

    init {
        viewModelScope.launch {
            val local = repo.observeDeck(publicId).first()
            // Al abrir: si hay red, se actualiza (sin pisar cambios locales).
            if (local?.isLocalOnly != true && c.connectivity.online.value) refresh()
            else if (local == null) _error.value = java.net.UnknownHostException()
        }
        // Traducir las cartas al idioma elegido (una vez por carta: queda en caché).
        viewModelScope.launch {
            combine(c.settings.cardLanguage, cards.map { it.size }.distinctUntilChanged()) { lang, size -> lang to size }
                .collectLatest { (lang, size) ->
                    if (lang != "en" && size > 0 && c.connectivity.online.value) {
                        runCatching { repo.ensureLocalized(publicId) }
                    }
                }
        }
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            _loading.value = true
            runCatching { repo.refreshDeck(publicId, force) }
                .onSuccess { _error.value = null }
                .onFailure { e ->
                    _error.value = e
                    if (deck.value != null) emit(DeckEvent.Message(e.userMessage()))
                }
            _loading.value = false
        }
    }

    fun toggleSaved() = launchSafe {
        val d = deck.value ?: return@launchSafe
        if (d.isLocalOnly) return@launchSafe
        val saving = !d.isSaved
        val t = I18n.strings
        if (saving) emit(DeckEvent.Message(t.savingOffline))
        repo.setSaved(publicId, saving)
        emit(DeckEvent.Message(if (saving) t.availableOffline else t.noLongerOffline))
    }

    fun setQuantity(card: DeckCardEntity, qty: Int) = launchSafe {
        repo.setQuantity(publicId, card.board, card.cardKey, qty)
        warnIfNoSession()
    }

    fun move(card: DeckCardEntity, toBoard: String) = launchSafe {
        repo.moveCard(publicId, card.board, toBoard, card.cardKey)
        warnIfNoSession()
    }

    fun rename(name: String) = launchSafe { repo.rename(publicId, name); warnIfNoSession() }

    fun copy(name: String) = launchSafe {
        val id = repo.copyDeck(publicId, name)
        warnIfNoSession()
        emit(DeckEvent.OpenDeck(id))
    }

    fun export(includePrintings: Boolean) = launchSafe {
        emit(DeckEvent.Share(repo.exportText(publicId, includePrintings), deck.value?.name ?: I18n.strings.deck))
    }

    /** Enlace para que otra persona con Moxlite vea el mazo en su app (aunque Moxfield no cargue). */
    fun shareLink() = launchSafe {
        val snapshot = repo.snapshot(publicId)
        val link = ShareLink.build(snapshot)
        emit(DeckEvent.Share(I18n.strings.shareMessage(snapshot.n, link), snapshot.n))
    }

    fun discardLocalChanges() = launchSafe {
        val wasLocal = deck.value?.isLocalOnly == true
        repo.discardLocalChanges(publicId)
        if (wasLocal) emit(DeckEvent.Closed) else emit(DeckEvent.Message(I18n.strings.restoredFromMoxfield))
    }

    fun removeFromDevice() = launchSafe {
        repo.removeFromDevice(publicId)
        emit(DeckEvent.Closed)
    }

    // ---------- Añadir cartas ----------

    /** Busca en cualquier idioma, con imagen de cada resultado. */
    fun searchCards(query: String) {
        _search.value = _search.value.copy(query = query)
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _search.value = CardSearchState(query = query)
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            _search.value = _search.value.copy(loading = true, error = null)
            runCatching { c.cardRepository.searchAnyLanguage(query) }
                .onSuccess { _search.value = CardSearchState(query = query, results = it) }
                .onFailure { _search.value = CardSearchState(query = query, error = it.userMessage()) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = CardSearchState()
    }

    fun addCard(result: ScryCard, board: String, quantity: Int) = launchSafe {
        // En el mazo se guarda la impresión inglesa (la que entiende Moxfield); la traducción se
        // recuerda para verla en el idioma buscado.
        val english = c.cardRepository.englishPrinting(result)
        repo.addCard(publicId, board, english, quantity, localized = result.takeIf { it.lang != "en" })
        warnIfNoSession()
        emit(DeckEvent.Message(I18n.strings.added(result.displayName)))
    }

    // ---------- Arte / edición ----------

    fun loadPrintings(card: DeckCardEntity) {
        _printings.value = PrintingsState(cardName = card.name, loading = true)
        viewModelScope.launch {
            runCatching { c.cardRepository.printings(card.name) }
                .onSuccess { _printings.value = PrintingsState(cardName = card.name, printings = it) }
                .onFailure { _printings.value = PrintingsState(cardName = card.name, error = it.userMessage()) }
        }
    }

    fun closePrintings() {
        _printings.value = null
    }

    fun changePrinting(card: DeckCardEntity, printing: ScryCard) = launchSafe {
        repo.changePrinting(publicId, card.board, card.cardKey, printing)
        _printings.value = null
        warnIfNoSession()
        emit(DeckEvent.Message(I18n.strings.printingChanged(printing.setName.ifBlank { printing.set.uppercase() })))
    }

    // ---------- Internos ----------

    private suspend fun warnIfNoSession() {
        if (!repo.canWriteRemote && !warnedNoSession) {
            warnedNoSession = true
            emit(DeckEvent.Message(I18n.strings.changesKeptLoginToUpload))
        }
    }

    private suspend fun emit(event: DeckEvent) = _events.emit(event)

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { emit(DeckEvent.Message(it.userMessage())) }
        }
    }
}
