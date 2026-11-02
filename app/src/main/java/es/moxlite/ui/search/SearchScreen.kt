package es.moxlite.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import es.moxlite.data.repo.DeckRepository
import es.moxlite.ui.common.DeckRow
import es.moxlite.ui.common.FORMATS
import es.moxlite.ui.common.MessageBox
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.common.toRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeckListState(
    val query: DeckQuery = DeckQuery(),
    val results: List<MoxDeckSummaryDto> = emptyList(),
    val totalPages: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
) {
    val canLoadMore: Boolean get() = !loading && query.pageNumber < totalPages
}

/** Búsqueda paginada de mazos; la reutiliza la pantalla de mazos de un usuario. */
class DeckSearchViewModel(private val repo: DeckRepository, initial: DeckQuery = DeckQuery()) : ViewModel() {
    private val _state = MutableStateFlow(DeckListState(query = initial))
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun search(query: DeckQuery = _state.value.query) {
        job?.cancel()
        _state.update { it.copy(query = query.copy(pageNumber = 1), results = emptyList(), loading = true, error = null, searched = true) }
        job = viewModelScope.launch { load(append = false) }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.canLoadMore) return
        _state.update { it.copy(query = it.query.copy(pageNumber = it.query.pageNumber + 1), loading = true) }
        job = viewModelScope.launch { load(append = true) }
    }

    private suspend fun load(append: Boolean) {
        runCatching { repo.searchDecks(_state.value.query) }
            .onSuccess { page ->
                _state.update {
                    it.copy(
                        results = if (append) it.results + page.data else page.data,
                        totalPages = page.totalPages,
                        loading = false,
                    )
                }
            }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
    }
}

@Composable
fun DeckResultsList(
    state: DeckListState,
    openDeck: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "Sin resultados",
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, state.results.size) { if (nearEnd && state.results.isNotEmpty()) onLoadMore() }

    Box(modifier.fillMaxSize()) {
        when {
            state.error != null && state.results.isEmpty() ->
                MessageBox(state.error, actionLabel = "Reintentar", onAction = onRetry)
            state.searched && !state.loading && state.results.isEmpty() ->
                MessageBox(emptyText, isError = false)
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.results, key = { it.publicId }) { DeckRow(it.toRow()) { openDeck(it.publicId) } }
                if (state.loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

private val SORTS = listOf(
    "updated" to "Recientes",
    "likes" to "Más gustados",
    "views" to "Más vistos",
    "created" to "Nuevos",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(openDeck: (String) -> Unit) {
    val container = appContainer()
    val vm: DeckSearchViewModel = viewModel { DeckSearchViewModel(container.deckRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(state.query.text) }

    Scaffold(topBar = { TopAppBar(title = { Text("Buscar mazos") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Nombre, comandante, carta…") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(state.query.copy(text = text)) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SORTS.forEach { (key, label) ->
                    FilterChip(
                        selected = state.query.sortType == key,
                        onClick = { vm.search(state.query.copy(text = text, sortType = key)) },
                        label = { Text(label) },
                    )
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FORMATS.forEach { f ->
                    FilterChip(
                        selected = state.query.format == f,
                        onClick = {
                            val newFormat = if (state.query.format == f) null else f
                            vm.search(state.query.copy(text = text, format = newFormat))
                        },
                        label = { Text(f) },
                    )
                }
            }
            if (!state.searched) {
                MessageBox("Busca mazos públicos de Moxfield. Los que abras se guardan como recientes.", isError = false)
            } else {
                DeckResultsList(
                    state = state,
                    openDeck = openDeck,
                    onLoadMore = { vm.loadMore() },
                    onRetry = { vm.search() },
                )
            }
        }
    }
}
