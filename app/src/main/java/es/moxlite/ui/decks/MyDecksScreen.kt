package es.moxlite.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import es.moxlite.AppContainer
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.moxfield.MoxfieldIds
import es.moxlite.data.remote.userMessage
import es.moxlite.domain.DeckFilters
import es.moxlite.domain.DeckSort
import es.moxlite.i18n.I18n
import es.moxlite.ui.common.DeckFilterBar
import es.moxlite.ui.common.DeckRow
import es.moxlite.ui.common.FORMATS
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.MessageBox
import es.moxlite.ui.common.VISIBILITIES
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.common.toRow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MyDecksTab(val defaultSort: DeckSort) {
    /** Los de la cuenta y los creados en el móvil. */
    MINE(DeckSort.UPDATED),

    /** Mazos de otros guardados sin conexión. */
    SAVED(DeckSort.UPDATED),

    /** Abiertos hace poco (de cualquiera). */
    RECENT(DeckSort.VIEWED),
}

/** Contenido de una pestaña: los mazos que tocan antes y después de buscar/filtrar. */
data class TabContent(val total: Int = 0, val shown: List<DeckEntity> = emptyList())

class MyDecksViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.deckRepository

    private val _tab = MutableStateFlow(MyDecksTab.MINE)
    val tab = _tab.asStateFlow()

    /** Búsqueda y filtros (comunes a las pestañas); el orden va por pestaña. */
    private val _filters = MutableStateFlow(DeckFilters())
    private val _sorts = MutableStateFlow(MyDecksTab.entries.associateWith { it.defaultSort })

    val filters: StateFlow<DeckFilters> = combine(_filters, _sorts, _tab) { f, sorts, tab ->
        f.copy(sort = sorts.getValue(tab))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DeckFilters(sort = MyDecksTab.MINE.defaultSort))

    private val all: StateFlow<List<DeckEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val me = c.session.state.map { it.userName }.distinctUntilChanged()

    val tabs: StateFlow<Map<MyDecksTab, TabContent>> = combine(all, me, _filters, _sorts) { decks, me, f, sorts ->
        val mine = decks.filter { it.isLocalOnly || (me.isNotBlank() && it.authorUserName.equals(me, ignoreCase = true)) }
        val mineIds = mine.mapTo(HashSet()) { it.publicId }
        val lists = mapOf(
            MyDecksTab.MINE to mine,
            MyDecksTab.SAVED to decks.filter { it.isSaved && it.publicId !in mineIds },
            MyDecksTab.RECENT to decks.filter { it.viewedAt > 0 }.sortedByDescending { it.viewedAt }.take(RECENT_LIMIT),
        )
        lists.mapValues { (tab, list) -> TabContent(list.size, f.copy(sort = sorts.getValue(tab)).applyLocal(list)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Tipos de mazo que aparecen en el móvil, más los habituales. */
    val hubSuggestions: StateFlow<List<String>> = all.map { decks ->
        (decks.flatMap { it.hubList } + DeckFilters.COMMON_HUBS).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeckFilters.COMMON_HUBS)

    /** Comandantes de los mazos del móvil que contienen [text]. */
    fun commanderSuggestions(text: String): List<Pair<String, String>> =
        all.value.flatMap { it.commanderList }
            .filter { it.contains(text.trim(), ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .sorted()
            .map { it to it }

    fun selectTab(tab: MyDecksTab) {
        _tab.value = tab
    }

    fun setFilters(filters: DeckFilters) {
        _filters.value = filters.copy(sort = DeckSort.UPDATED)
        _sorts.update { it + (_tab.value to filters.sort) }
    }

    fun setText(text: String) = _filters.update { it.copy(text = text) }

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val _navigate = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigate = _navigate.asSharedFlow()
    private var lastAutoSync = 0L

    /** Al abrir la pantalla con sesión: traer la lista de mazos (como mucho cada 10 min). */
    fun autoSync() {
        if (!c.session.state.value.hasAccount || !c.connectivity.online.value) return
        if (System.currentTimeMillis() - lastAutoSync < 10 * 60_000) return
        lastAutoSync = System.currentTimeMillis()
        viewModelScope.launch { runCatching { repo.myRemoteDecks() } }
    }

    fun syncFromMoxfield() = viewModelScope.launch {
        val t = I18n.strings
        if (c.session.state.value.userName.isBlank()) {
            _messages.emit(t.loginToSeeYourDecks)
            return@launch
        }
        _refreshing.value = true
        lastAutoSync = System.currentTimeMillis()
        runCatching { repo.myRemoteDecks() }
            .onSuccess { _messages.emit(t.decksFound(it.size)) }
            .onFailure { _messages.emit(it.userMessage()) }
        _refreshing.value = false
    }

    fun openByLink(text: String) = viewModelScope.launch {
        val id = MoxfieldIds.parsePublicId(text)
        if (id == null) _messages.emit(I18n.strings.notAMoxfieldLink) else _navigate.emit(id)
    }

    fun createDeck(name: String, format: String, visibility: String) = viewModelScope.launch {
        _navigate.emit(repo.createDeck(name, format, visibility))
    }

    private companion object {
        const val RECENT_LIMIT = 50
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDecksScreen(openDeck: (String) -> Unit, openImport: () -> Unit, openLogin: () -> Unit, openAccount: () -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val vm: MyDecksViewModel = viewModel { MyDecksViewModel(container) }
    val tab by vm.tab.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val hubSuggestions by vm.hubSuggestions.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // "link" | "new"

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.navigate.collect { openDeck(it) } }
    LaunchedEffect(session.hasAccount) { vm.autoSync() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.myDecks) },
                actions = {
                    if (session.hasAccount) {
                        IconButton(onClick = { vm.syncFromMoxfield() }) { Icon(Icons.Outlined.Sync, t.syncMyDecks) }
                    }
                    IconButton(onClick = openAccount) {
                        if (session.userName.isNotBlank()) {
                            Box(
                                Modifier.size(30.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(session.userName.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        } else {
                            Icon(Icons.Outlined.AccountCircle, t.account)
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menu = true }) { Icon(Icons.Outlined.Add, t.add) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(t.newDeck) },
                        leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) },
                        onClick = { menu = false; dialog = "new" },
                    )
                    DropdownMenuItem(
                        text = { Text(t.importTextList) },
                        leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                        onClick = { menu = false; openImport() },
                    )
                    DropdownMenuItem(
                        text = { Text(t.openMoxfieldLink) },
                        leadingIcon = { Icon(Icons.Outlined.Link, null) },
                        onClick = { menu = false; dialog = "link" },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                MyDecksTab.entries.forEach { tb ->
                    val count = tabs[tb]?.total ?: 0
                    Tab(
                        selected = tab == tb,
                        onClick = { vm.selectTab(tb) },
                        text = {
                            val label = when (tb) {
                                MyDecksTab.MINE -> t.tabMine
                                MyDecksTab.SAVED -> t.tabSaved
                                MyDecksTab.RECENT -> t.tabRecent
                            }
                            Text(if (count > 0) "$label ($count)" else label, maxLines = 1)
                        },
                    )
                }
            }
            OutlinedTextField(
                value = filters.text,
                onValueChange = vm::setText,
                placeholder = { Text(t.searchMyDecksPlaceholder) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (filters.text.isNotEmpty()) {
                        IconButton(onClick = { vm.setText("") }) { Icon(Icons.Outlined.Close, t.clear) }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            DeckFilterBar(
                filters = filters,
                sorts = DeckSort.LOCAL,
                onChange = vm::setFilters,
                commanderSuggestions = { vm.commanderSuggestions(it) },
                hubSuggestions = hubSuggestions,
            )
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.syncFromMoxfield() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                val content = tabs[tab] ?: TabContent()
                LazyColumn(Modifier.fillMaxSize()) {
                    if (session.needsLogin && tab == MyDecksTab.MINE) {
                        item(key = "login") {
                            MessageBox(
                                text = t.sessionClosedByMoxfield,
                                actionLabel = t.loginAgain,
                                onAction = openLogin,
                            )
                        }
                    }
                    if (content.shown.isEmpty()) {
                        item(key = "empty") {
                            when {
                                content.total > 0 -> MessageBox(t.noDecksMatch, isError = false)
                                tab == MyDecksTab.SAVED -> MessageBox(t.noSavedDecks, isError = false)
                                tab == MyDecksTab.RECENT -> MessageBox(t.noRecentDecks, isError = false)
                                session.hasAccount -> MessageBox(t.noDecksYetLoggedIn, isError = false)
                                session.needsLogin -> Unit
                                else -> MessageBox(
                                    text = t.noOwnDecksNoAccount,
                                    isError = false,
                                    actionLabel = t.loginWithMoxfield,
                                    onAction = openLogin,
                                )
                            }
                        }
                    }
                    items(content.shown, key = { it.publicId }) { DeckRow(it.toRow()) { openDeck(it.publicId) } }
                }
            }
        }
    }

    when (dialog) {
        "link" -> TextInputDialog(
            title = t.openMoxfieldDeck,
            label = t.deckLinkOrId,
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.openByLink(it) },
        )
        "new" -> NewDeckDialog(
            onDismiss = { dialog = null },
            onConfirm = { name, format, visibility -> dialog = null; vm.createDeck(name, format, visibility) },
        )
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val t = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel ?: t.accept) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t.cancel) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDeckDialog(
    title: String? = null,
    initialName: String = "",
    initialFormat: String = "commander",
    onDismiss: () -> Unit,
    onConfirm: (name: String, format: String, visibility: String) -> Unit,
) {
    val t = LocalStrings.current
    var name by remember { mutableStateOf(initialName) }
    var format by remember { mutableStateOf(initialFormat) }
    var visibility by remember { mutableStateOf("public") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: t.newDeck) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(t.name) }, singleLine = true)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = t.formatLabel(format),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(t.format) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        FORMATS.forEach { f ->
                            DropdownMenuItem(text = { Text(t.formatLabel(f)) }, onClick = { format = f; expanded = false })
                        }
                    }
                }
                Text(t.visibility, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VISIBILITIES.forEach { v ->
                        FilterChip(selected = visibility == v, onClick = { visibility = v }, label = { Text(t.visibilityLabel(v)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, format, visibility) }, enabled = name.isNotBlank()) { Text(t.create) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t.cancel) } },
    )
}
