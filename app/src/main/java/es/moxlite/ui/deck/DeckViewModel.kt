package es.moxlite.ui.deck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.moxlite.AppContainer
import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.MoxError
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DeckEvent {
    data class Message(val text: String) : DeckEvent
    data class OpenDeck(val publicId: String) : DeckEvent
    data class Share(val text: String, val title: String) : DeckEvent
    data object Closed : DeckEvent
}

class DeckViewModel(private val c: AppContainer, val publicId: String) : ViewModel() {
    private val repo = c.deckRepository

    val deck: StateFlow<DeckEntity?> = repo.observeDeck(publicId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val cards: StateFlow<List<DeckCardEntity>> = repo.observeCards(publicId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<Throwable?>(null)
    val error = _error.asStateFlow()
    private val _events = MutableSharedFlow<DeckEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    // Añadir carta (Scryfall)
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions = _suggestions.asStateFlow()
    private var suggestJob: Job? = null

    val canWriteRemote get() = repo.canWriteRemote

    init {
        viewModelScope.launch {
            val local = repo.observeDeck(publicId).first()
            // Al abrir: si hay red, se actualiza (sin pisar cambios locales).
            if (local?.isLocalOnly != true && c.connectivity.online.value) refresh()
            else if (local == null) _error.value = java.net.UnknownHostException()
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
        if (saving) emit(DeckEvent.Message("Guardando cartas e imágenes para usar sin conexión…"))
        repo.setSaved(publicId, saving)
        emit(DeckEvent.Message(if (saving) "Disponible sin conexión" else "Ya no se guarda sin conexión"))
    }

    fun setQuantity(card: DeckCardEntity, qty: Int) = launchSafe {
        repo.setQuantity(publicId, card.board, card.cardKey, qty)
        markUnsaved()
    }

    fun move(card: DeckCardEntity, toBoard: String) = launchSafe {
        repo.moveCard(publicId, card.board, toBoard, card.cardKey)
        markUnsaved()
    }

    fun rename(name: String) = launchSafe { repo.rename(publicId, name); markUnsaved() }

    fun copy(name: String) = launchSafe {
        val id = repo.copyDeck(publicId, name)
        emit(DeckEvent.OpenDeck(id))
    }

    fun export(includePrintings: Boolean) = launchSafe {
        emit(DeckEvent.Share(repo.exportText(publicId, includePrintings), deck.value?.name ?: "Mazo"))
    }

    fun discardLocalChanges() = launchSafe {
        val wasLocal = deck.value?.isLocalOnly == true
        repo.discardLocalChanges(publicId)
        if (wasLocal) emit(DeckEvent.Closed) else emit(DeckEvent.Message("Recuperada la versión de Moxfield"))
    }

    fun removeFromDevice() = launchSafe {
        repo.removeFromDevice(publicId)
        emit(DeckEvent.Closed)
    }

    fun suggest(query: String) {
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            delay(250)
            _suggestions.value = runCatching { c.cardRepository.autocomplete(query) }.getOrDefault(emptyList())
        }
    }

    fun addCardByName(name: String, board: String, quantity: Int) = launchSafe {
        val card: ScryCard = c.cardRepository.named(name)
        repo.addCard(publicId, board, card, quantity)
        markUnsaved()
        emit(DeckEvent.Message("Añadida: ${card.name}"))
    }

    private suspend fun markUnsaved() {
        if (!repo.canWriteRemote) {
            emit(DeckEvent.Message(MoxError.NotSupportedYet("Subir cambios a Moxfield").message.orEmpty()))
        }
    }

    private var warnedNotSupported = false

    private suspend fun emit(event: DeckEvent) {
        // El aviso de "todavía no se sube" solo una vez por pantalla.
        if (event is DeckEvent.Message && event.text.contains("todavía no está implementado")) {
            if (warnedNotSupported) return
            warnedNotSupported = true
        }
        _events.emit(event)
    }

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { emit(DeckEvent.Message(it.userMessage())) }
        }
    }
}
