package es.moxlite.ui.decks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.FloatingActionButton
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
import es.moxlite.ui.common.DeckRow
import es.moxlite.ui.common.FORMATS
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MyDecksViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.deckRepository

    val mine: StateFlow<List<DeckEntity>> = repo.observeMine()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recent: StateFlow<List<DeckEntity>> = repo.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val userName: StateFlow<String> = c.session.state.map { it.userName }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.session.state.value.userName)

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val _navigate = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigate = _navigate.asSharedFlow()

    fun syncFromMoxfield() = viewModelScope.launch {
        if (userName.value.isBlank()) {
            _messages.emit("Indica tu usuario de Moxfield en Ajustes o inicia sesión.")
            return@launch
        }
        _refreshing.value = true
        runCatching { repo.myRemoteDecks() }
            .onSuccess { _messages.emit("${it.size} mazos públicos encontrados") }
            .onFailure { _messages.emit(it.userMessage()) }
        _refreshing.value = false
    }

    fun openByLink(text: String) = viewModelScope.launch {
        val id = MoxfieldIds.parsePublicId(text)
        if (id == null) _messages.emit("No parece un enlace de Moxfield") else _navigate.emit(id)
    }

    fun createDeck(name: String, format: String) = viewModelScope.launch {
        _navigate.emit(repo.createDeck(name, format))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDecksScreen(openDeck: (String) -> Unit, openImport: () -> Unit, openLogin: () -> Unit) {
    val container = appContainer()
    val vm: MyDecksViewModel = viewModel { MyDecksViewModel(container) }
    val mine by vm.mine.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val user by vm.userName.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // "link" | "new"

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.navigate.collect { openDeck(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (user.isBlank()) "Mis mazos" else "Mis mazos · @$user") },
                actions = {
                    IconButton(onClick = { vm.syncFromMoxfield() }) { Icon(Icons.Outlined.Sync, "Traer mis mazos de Moxfield") }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menu = true }) { Icon(Icons.Outlined.Add, "Añadir") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Nuevo mazo") },
                        leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) },
                        onClick = { menu = false; dialog = "new" },
                    )
                    DropdownMenuItem(
                        text = { Text("Importar lista de texto") },
                        leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                        onClick = { menu = false; openImport() },
                    )
                    DropdownMenuItem(
                        text = { Text("Abrir enlace de Moxfield") },
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
            if (mine.isEmpty() && recent.isEmpty()) {
                MessageBox(
                    text = "Aún no hay mazos en el móvil.\nInicia sesión para traer los tuyos, busca mazos o abre un enlace de Moxfield.",
                    isError = false,
                    actionLabel = "Iniciar sesión en Moxfield",
                    onAction = openLogin,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (mine.isNotEmpty()) {
                        item { SectionHeader("En el móvil y tuyos", "${mine.size}") }
                        items(mine, key = { "m" + it.publicId }) { DeckRow(it.toRow()) { openDeck(it.publicId) } }
                    }
                    if (recent.isNotEmpty()) {
                        item { SectionHeader("Vistos recientemente") }
                        items(recent, key = { "r" + it.publicId }) { DeckRow(it.toRow()) { openDeck(it.publicId) } }
                    }
                }
            }
        }
    }

    when (dialog) {
        "link" -> TextInputDialog(
            title = "Abrir mazo de Moxfield",
            label = "Enlace o id del mazo",
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.openByLink(it) },
        )
        "new" -> NewDeckDialog(
            onDismiss = { dialog = null },
            onConfirm = { name, format -> dialog = null; vm.createDeck(name, format) },
        )
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "Aceptar",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDeckDialog(
    title: String = "Nuevo mazo",
    initialName: String = "",
    initialFormat: String = "commander",
    onDismiss: () -> Unit,
    onConfirm: (name: String, format: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var format by remember { mutableStateOf(initialFormat) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = format,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Formato") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        FORMATS.forEach { f ->
                            DropdownMenuItem(text = { Text(f) }, onClick = { format = f; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, format) }, enabled = name.isNotBlank()) { Text("Crear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
