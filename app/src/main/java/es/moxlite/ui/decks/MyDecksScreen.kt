package es.moxlite.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import es.moxlite.i18n.I18n
import es.moxlite.ui.common.DeckRow
import es.moxlite.ui.common.FORMATS
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.VISIBILITIES
import es.moxlite.ui.common.MessageBox
import es.moxlite.ui.common.SectionHeader
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.common.toRow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MyDecksViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.deckRepository

    val mine: StateFlow<List<DeckEntity>> = repo.observeMine()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recent: StateFlow<List<DeckEntity>> = repo.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDecksScreen(openDeck: (String) -> Unit, openImport: () -> Unit, openLogin: () -> Unit, openAccount: () -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val vm: MyDecksViewModel = viewModel { MyDecksViewModel(container) }
    val mine by vm.mine.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
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
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.syncFromMoxfield() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (session.needsLogin) {
                    item {
                        MessageBox(
                            text = t.sessionClosedByMoxfield,
                            actionLabel = t.loginAgain,
                            onAction = openLogin,
                        )
                    }
                }
                if (mine.isEmpty() && recent.isEmpty()) {
                    item {
                        MessageBox(
                            text = if (session.hasAccount) t.noDecksYetLoggedIn else t.noDecksYet,
                            isError = false,
                            actionLabel = if (session.hasAccount || session.needsLogin) null else t.loginWithMoxfield,
                            onAction = if (session.hasAccount || session.needsLogin) null else openLogin,
                        )
                    }
                }
                if (mine.isNotEmpty()) {
                    item { SectionHeader(if (session.hasAccount) t.yoursAndSaved else t.savedOnPhone, "${mine.size}") }
                    items(mine, key = { "m" + it.publicId }) { DeckRow(it.toRow()) { openDeck(it.publicId) } }
                }
                if (recent.isNotEmpty()) {
                    item { SectionHeader(t.recentlyViewed) }
                    items(recent, key = { "r" + it.publicId }) { DeckRow(it.toRow()) { openDeck(it.publicId) } }
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
                        value = format,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(t.format) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        FORMATS.forEach { f ->
                            DropdownMenuItem(text = { Text(f) }, onClick = { format = f; expanded = false })
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
