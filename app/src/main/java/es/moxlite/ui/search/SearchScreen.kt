package es.moxlite.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import es.moxlite.data.remote.moxfield.DeckQuery
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import es.moxlite.data.remote.userMessage
import es.moxlite.data.repo.CardRepository
import es.moxlite.data.repo.DeckRepository
import es.moxlite.domain.DeckFilters
import es.moxlite.domain.DeckSort
import es.moxlite.i18n.I18n
import es.moxlite.ui.common.DeckFilterBar
import es.moxlite.ui.common.DeckRow
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.MessageBox
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.common.toRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeckListState(
    val filters: DeckFilters = DeckFilters(),
    val results: List<MoxDeckSummaryDto> = emptyList(),
    /** Última página pedida a Moxfield (0 = ninguna). */
    val page: Int = 0,
    val totalPages: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
) {
    val canLoadMore: Boolean get() = !loading && error == null && page < totalPages
}

/**
 * Búsqueda paginada de mazos con filtros; la reutiliza la pantalla de mazos de un usuario
 * ([base] lleva el autor).
 *
 * Los resultados se acumulan sin repetir `publicId`: con el orden "actualizados", un mazo puede
 * pasar de una página a la siguiente mientras se pagina, y un id repetido en la lista hacía que
 * Compose se cerrara ("Key was already used") al hacer scroll rápido.
 */
class DeckSearchViewModel(
    private val repo: DeckRepository,
    private val base: DeckQuery = DeckQuery(),
    initial: DeckFilters = DeckFilters(),
) : ViewModel() {
    private val _state = MutableStateFlow(DeckListState(filters = initial))
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun search(filters: DeckFilters = _state.value.filters) {
        job?.cancel()
        _state.value = DeckListState(filters = filters, loading = true, searched = true)
        job = viewModelScope.launch { load() }
    }

    fun loadMore() {
        if (!_state.value.canLoadMore || job?.isActive == true) return
        _state.update { it.copy(loading = true) }
        job = viewModelScope.launch { load() }
    }

    fun retry() {
        val s = _state.value
        if (s.results.isEmpty()) {
            search()
        } else {
            _state.update { it.copy(error = null) }
            loadMore()
        }
    }

    private suspend fun load() {
        try {
            val filters = _state.value.filters
            val commanderId = filters.commander?.let { name ->
                repo.moxfieldCardId(name) ?: error(I18n.strings.commanderNotFound(name))
            }
            // Los colores se filtran aquí: se piden páginas grandes y varias seguidas hasta tener
            // suficientes resultados (sin pasarse, que Moxfield limita las peticiones).
            val clientFilter = filters.needsClientFilter
            val pageSize = if (clientFilter) 100 else base.pageSize
            var fetched = 0
            var added = 0
            do {
                val page = _state.value.page + 1
                val result = repo.searchDecks(queryFor(filters, commanderId, page, pageSize))
                val matching = result.data.filter { it.publicId.isNotBlank() && filters.matchesSummary(it) }
                // Solo escribe este job (search() cancela el anterior antes de empezar).
                val before = _state.value
                val merged = (before.results + matching).distinctBy { it.publicId }
                added += merged.size - before.results.size
                val s = before.copy(results = merged, page = page, totalPages = result.totalPages)
                _state.value = s
                fetched++
            } while (clientFilter && added < MIN_NEW_RESULTS && s.page < s.totalPages && fetched < MAX_PAGES_PER_LOAD)
            _state.update { it.copy(loading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.userMessage()) }
        }
    }

    private fun queryFor(f: DeckFilters, commanderId: String?, page: Int, pageSize: Int) = base.copy(
        text = f.text,
        format = f.format ?: base.format,
        commanderCardId = commanderId,
        minBracket = f.bracket,
        maxBracket = f.bracket,
        hubName = f.hub,
        sortType = f.sort.remoteKey ?: base.sortType,
        sortDirection = "descending",
        pageNumber = page,
        pageSize = pageSize,
    )

    private companion object {
        const val MIN_NEW_RESULTS = 20
        const val MAX_PAGES_PER_LOAD = 5
    }
}

@Composable
fun DeckResultsList(
    state: DeckListState,
    openDeck: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
) {
    val t = LocalStrings.current
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    // `page` también: una página que no añade nada (todo filtrado) no cambia el tamaño.
    LaunchedEffect(nearEnd, state.results.size, state.page) {
        if (nearEnd && state.results.isNotEmpty()) onLoadMore()
    }

    Box(modifier.fillMaxSize()) {
        when {
            state.error != null && state.results.isEmpty() ->
                MessageBox(state.error, actionLabel = t.retry, onAction = onRetry)
            state.searched && !state.loading && state.results.isEmpty() ->
                MessageBox(emptyText ?: t.noResults, isError = false)
            state.loading && state.results.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.results, key = { it.publicId }) { deck ->
                    DeckRow(deck.toRow()) { openDeck(deck.publicId) }
                }
                when {
                    state.loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.error != null -> item(key = "error") {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text(t.retry) }
                        }
                    }
                }
            }
        }
    }
}

/** Sugerencias de comandante desde Scryfall (en cualquier idioma). */
suspend fun CardRepository.commanderSuggestionsSafe(text: String): List<Pair<String, String>> =
    runCatching { commanderSuggestions(text) }.getOrElse { if (it is CancellationException) throw it else emptyList() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(openDeck: (String) -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val vm: DeckSearchViewModel = viewModel { DeckSearchViewModel(container.deckRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(state.filters.text) }
    val filters = state.filters

    Scaffold(topBar = { TopAppBar(title = { Text(t.searchDecks) }) }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(t.searchDecksPlaceholder) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = ""; if (state.searched) vm.search(filters.copy(text = "")) }) {
                            Icon(Icons.Outlined.Close, t.clear)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(filters.copy(text = text)) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            DeckFilterBar(
                filters = filters,
                sorts = DeckSort.REMOTE,
                onChange = { vm.search(it.copy(text = text)) },
                commanderSuggestions = { container.cardRepository.commanderSuggestionsSafe(it) },
            )
            if (filters.needsClientFilter && state.searched) {
                Text(
                    t.searchDecksColorsHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (!state.searched) {
                MessageBox(t.searchDecksIntro, isError = false)
            } else {
                DeckResultsList(
                    state = state,
                    openDeck = openDeck,
                    onLoadMore = { vm.loadMore() },
                    onRetry = { vm.retry() },
                    emptyText = if (filters.activeCount > 0) t.noDecksMatch else null,
                )
            }
        }
    }
}
