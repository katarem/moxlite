package es.moxlite.ui.deck

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import es.moxlite.data.local.CardLocalizationEntity
import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.MoxError
import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.moxfield.MoxfieldIds
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.scryfall.ScryfallImages
import es.moxlite.data.remote.userMessage
import es.moxlite.data.repo.PrintingKey
import es.moxlite.data.settings.DeckGrouping
import es.moxlite.data.settings.DeckSorting
import es.moxlite.data.settings.DeckViewMode
import es.moxlite.ui.common.ColorPips
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.ManaText
import es.moxlite.ui.common.MessageBox
import es.moxlite.ui.common.SectionHeader
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.common.featuredImageUrl
import es.moxlite.ui.decks.TextInputDialog
import java.util.Locale

private typealias Localized = Map<PrintingKey, CardLocalizationEntity>

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(
    publicId: String,
    onBack: () -> Unit,
    openDeck: (String) -> Unit,
    openUser: (String) -> Unit,
    openWeb: (url: String) -> Unit,
    openLogin: () -> Unit,
    openBuilder: (String) -> Unit,
) {
    val container = appContainer()
    val t = LocalStrings.current
    val vm: DeckViewModel = viewModel(key = "deck-$publicId") { DeckViewModel(container, publicId) }
    val deck by vm.deck.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val localized by vm.localized.collectAsStateWithLifecycle()
    val canEdit by vm.canEdit.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val printings by vm.printings.collectAsStateWithLifecycle()
    val viewMode by container.settings.viewMode.collectAsStateWithLifecycle()
    val grouping by container.settings.grouping.collectAsStateWithLifecycle()
    val sorting by container.settings.sorting.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var editingRequested by rememberSaveable { mutableStateOf(false) }
    val editing = editingRequested && canEdit
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // copy | rename | export | discard | remove | add
    var selected by remember { mutableStateOf<DeckCardEntity?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is DeckEvent.Message -> snackbar.showSnackbar(e.text)
                is DeckEvent.OpenDeck -> openDeck(e.publicId)
                is DeckEvent.Closed -> onBack()
                is DeckEvent.CopyCode -> {
                    clipboard.setText(AnnotatedString(e.code))
                    snackbar.showSnackbar(t.codeCopied)
                }
                is DeckEvent.Share -> context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, e.title)
                            .putExtra(Intent.EXTRA_TEXT, e.text),
                        t.shareTitle(e.title),
                    )
                )
            }
        }
    }

    val d = deck
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.name ?: t.deck, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t.back) } },
                actions = {
                    if (d != null && !d.isLocalOnly) {
                        IconButton(onClick = { vm.toggleSaved() }) {
                            Icon(
                                if (d.isSaved) Icons.Filled.DownloadForOffline else Icons.Outlined.DownloadForOffline,
                                contentDescription = if (d.isSaved) t.savedOffline else t.saveOffline,
                                tint = if (d.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, t.refresh) }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t.more) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (d != null) {
                            MenuItem(t.shareLink, Icons.Outlined.Link) { menu = false; vm.shareLink() }
                            MenuItem(t.copyCode, Icons.Outlined.QrCode) { menu = false; vm.copyCode() }
                            MenuItem(t.exportText, Icons.Outlined.Share) { menu = false; dialog = "export" }
                            MenuItem(t.copyDeck, Icons.Outlined.ContentCopy) { menu = false; dialog = "copy" }
                            if (canEdit) MenuItem(t.rename, Icons.Outlined.Edit) { menu = false; dialog = "rename" }
                            if (canEdit && d.format.equals("commander", ignoreCase = true)) {
                                MenuItem(t.edhImproveDeck, Icons.Outlined.AutoAwesome) { menu = false; openBuilder(d.publicId) }
                            }
                        }
                        if (d != null && !d.isLocalOnly) {
                            MenuItem(t.viewOnMoxfieldWeb, Icons.Outlined.OpenInBrowser) {
                                menu = false; openWeb(MoxfieldIds.deckUrl(d.publicId))
                            }
                        }
                        if (d?.authorUserName?.isNotBlank() == true) {
                            MenuItem(t.moreDecksBy(d.authorUserName), Icons.Outlined.Person) { menu = false; openUser(d.authorUserName) }
                        }
                        if (d?.hasLocalChanges == true && !d.isLocalOnly) {
                            MenuItem(t.discardLocalChanges, Icons.Outlined.Restore) { menu = false; dialog = "discard" }
                        }
                        MenuItem(t.removeFromPhone, Icons.Outlined.DeleteOutline) { menu = false; dialog = "remove" }
                    }
                },
            )
        },
        floatingActionButton = {
            if (d != null && (cards.isNotEmpty() || d.isFullyLoaded)) {
                if (canEdit) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (editing) {
                            SmallFloatingActionButton(onClick = { dialog = "add" }) { Icon(Icons.Outlined.Add, t.addCard) }
                        }
                        FloatingActionButton(onClick = { editingRequested = !editingRequested }) {
                            Icon(if (editing) Icons.Outlined.Check else Icons.Outlined.Edit, if (editing) t.finishEditing else t.edit)
                        }
                    }
                } else {
                    // Mazo ajeno: como en Moxfield, se copia para editarlo.
                    ExtendedFloatingActionButton(
                        onClick = { dialog = "copy" },
                        icon = { Icon(Icons.Outlined.ContentCopy, null) },
                        text = { Text(t.copyToEdit) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                d != null && (cards.isNotEmpty() || d.isFullyLoaded) -> DeckContent(
                    deck = d,
                    cards = cards,
                    localized = localized,
                    editing = editing,
                    viewMode = viewMode,
                    grouping = grouping,
                    sorting = sorting,
                    onViewMode = container.settings::setViewMode,
                    onGrouping = container.settings::setGrouping,
                    onSorting = container.settings::setSorting,
                    onCardClick = { selected = it },
                    onQty = vm::setQuantity,
                    openUser = openUser,
                    needsLogin = d.hasLocalChanges && !session.hasAccount,
                    openLogin = openLogin,
                )
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> {
                    val e = error!!
                    MessageBox(
                        text = e.userMessage(),
                        actionLabel = if (e is MoxError.CloudflareBlocked) t.openMoxfieldInApp else t.retry,
                        onAction = {
                            if (e is MoxError.CloudflareBlocked) openWeb(MoxfieldIds.deckUrl(publicId)) else vm.refresh()
                        },
                    )
                }
                // Mientras Room responde o el mazo cambia de id (recién subido a Moxfield):
                // nunca una pantalla en blanco.
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }

    selected?.let { card ->
        // Siempre la versión actual de la fila (la cantidad o el arte pueden haber cambiado).
        val current = cards.firstOrNull { it.board == card.board && it.cardKey == card.cardKey }
        if (current == null) selected = null
        else CardDetailDialog(
            card = current,
            localized = localized,
            editing = editing,
            canChangeArt = canEdit,
            onDismiss = { selected = null },
            onQty = { vm.setQuantity(current, it) },
            onMove = { board -> vm.move(current, board); selected = null },
            onChangeArt = { vm.loadPrintings(current) },
        )
    }

    printings?.let { state ->
        val card = selected?.let { s -> cards.firstOrNull { it.board == s.board && it.cardKey == s.cardKey } }
        PrintingsDialog(
            state = state,
            currentScryfallId = card?.scryfallId,
            onDismiss = vm::closePrintings,
            onPick = { printing -> card?.let { vm.changePrinting(it, printing) } },
        )
    }

    when (dialog) {
        "copy" -> TextInputDialog(
            title = t.copyDeck,
            label = t.copyName,
            initial = t.copyOf(d?.name.orEmpty()),
            confirmLabel = t.copy,
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.copy(it) },
        )
        "rename" -> TextInputDialog(
            title = t.rename,
            label = t.name,
            initial = d?.name.orEmpty(),
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.rename(it) },
        )
        "export" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(t.exportText) },
            text = { Text(t.exportQuestion) },
            confirmButton = { TextButton(onClick = { dialog = null; vm.export(true) }) { Text(t.withPrintings) } },
            dismissButton = { TextButton(onClick = { dialog = null; vm.export(false) }) { Text(t.namesOnly) } },
        )
        "discard" -> ConfirmDialog(
            title = t.discardLocalChanges,
            text = t.discardConfirm,
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.discardLocalChanges() },
        )
        "remove" -> ConfirmDialog(
            title = t.removeFromPhone,
            text = when {
                d?.isLocalOnly == true -> t.removeLocalOnlyConfirm
                d?.hasLocalChanges == true -> t.removeWithChangesConfirm
                else -> t.removeConfirm
            },
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.removeFromDevice() },
        )
        "add" -> AddCardDialog(vm = vm, onDismiss = { dialog = null; vm.clearSearch() })
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val t = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(t.accept) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t.cancel) } },
    )
}

@Composable
private fun DeckContent(
    deck: DeckEntity,
    cards: List<DeckCardEntity>,
    localized: Localized,
    editing: Boolean,
    viewMode: DeckViewMode,
    grouping: DeckGrouping,
    sorting: DeckSorting,
    onViewMode: (DeckViewMode) -> Unit,
    onGrouping: (DeckGrouping) -> Unit,
    onSorting: (DeckSorting) -> Unit,
    onCardClick: (DeckCardEntity) -> Unit,
    onQty: (DeckCardEntity, Int) -> Unit,
    openUser: (String) -> Unit,
    needsLogin: Boolean,
    openLogin: () -> Unit,
) {
    val t = LocalStrings.current
    val boards = (cards.map { it.board } + if (editing) Boards.editable else emptyList())
        .distinct()
        .sortedBy(Boards::sortIndex)
    // Por defecto, el mazo principal.
    var tab by rememberSaveable(deck.publicId) { mutableStateOf(Boards.MAIN) }
    val currentBoard = if (tab in boards) tab else boards.firstOrNull() ?: Boards.MAIN
    val boardCards = cards.filter { it.board == currentBoard }
    val groups = remember(boardCards, grouping, sorting, t) { DeckLayout.group(boardCards, grouping, sorting, t) }
    val columns = if (viewMode == DeckViewMode.GRID) 3 else 1

    LazyColumn(Modifier.fillMaxSize()) {
        item { DeckHeader(deck, cards, openUser, needsLogin, openLogin) }
        if (boards.size > 1) {
            item {
                ScrollableTabRow(selectedTabIndex = boards.indexOf(currentBoard).coerceAtLeast(0), edgePadding = 8.dp) {
                    boards.forEach { b ->
                        val count = cards.filter { it.board == b }.sumOf { it.quantity }
                        Tab(selected = b == currentBoard, onClick = { tab = b }, text = { Text("${Boards.label(b)} ($count)") })
                    }
                }
            }
        }
        item { ViewBar(viewMode, grouping, sorting, onViewMode, onGrouping, onSorting) }
        if (boardCards.isEmpty()) {
            item { MessageBox(t.emptyBoard, isError = false) }
        }
        groups.forEach { group ->
            item(key = "h-$currentBoard-${group.key}") { SectionHeader(group.label, "${group.count}") }
            if (columns > 1) {
                val rows = group.cards.chunked(columns)
                itemsIndexed(rows, key = { i, _ -> "r-$currentBoard-${group.key}-$i" }) { _, row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { card ->
                            CardTile(card, localized, Modifier.weight(1f)) { onCardClick(card) }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else {
                items(group.cards, key = { "${it.board}|${it.cardKey}" }) { card ->
                    when (viewMode) {
                        DeckViewMode.TEXT -> TextRow(card, localized, editing, { onCardClick(card) }, { onQty(card, it) })
                        DeckViewMode.FULL -> FullCardRow(card, localized, editing, { onCardClick(card) }, { onQty(card, it) })
                        else -> CardRow(card, localized, editing, { onCardClick(card) }, { onQty(card, it) })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** Selector de vista (texto, lista, cuadrícula, carta completa) y de agrupación/orden. */
@Composable
private fun ViewBar(
    viewMode: DeckViewMode,
    grouping: DeckGrouping,
    sorting: DeckSorting,
    onViewMode: (DeckViewMode) -> Unit,
    onGrouping: (DeckGrouping) -> Unit,
    onSorting: (DeckSorting) -> Unit,
) {
    val t = LocalStrings.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        DeckViewMode.entries.forEach { mode ->
            IconToggleButton(checked = viewMode == mode, onCheckedChange = { onViewMode(mode) }) {
                Icon(
                    when (mode) {
                        DeckViewMode.TEXT -> Icons.AutoMirrored.Outlined.Subject
                        DeckViewMode.LIST -> Icons.AutoMirrored.Outlined.ViewList
                        DeckViewMode.GRID -> Icons.Outlined.GridView
                        DeckViewMode.FULL -> Icons.Outlined.Image
                    },
                    contentDescription = t.viewModeLabel(mode),
                    tint = if (viewMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            t.viewModeLabel(viewMode),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            TextButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(t.groupingLabel(grouping))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text(t.groupBy, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                DeckGrouping.entries.forEach { g ->
                    DropdownMenuItem(
                        text = { Text(t.groupingLabel(g)) },
                        leadingIcon = { if (g == grouping) Icon(Icons.Outlined.Check, null) },
                        onClick = { onGrouping(g); menu = false },
                    )
                }
                HorizontalDivider()
                Text(t.sortBy, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                DeckSorting.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(t.sortingLabel(s)) },
                        leadingIcon = { if (s == sorting) Icon(Icons.Outlined.Check, null) },
                        onClick = { onSorting(s); menu = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckHeader(
    deck: DeckEntity,
    cards: List<DeckCardEntity>,
    openUser: (String) -> Unit,
    needsLogin: Boolean,
    openLogin: () -> Unit,
) {
    val t = LocalStrings.current
    val price = cards.sumOf { (it.priceEur ?: 0.0) * it.quantity }
    Column {
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            deck.featuredImageUrl()?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))
                )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorPips(deck.colorIdentity, size = 14)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOfNotNull(
                            LocalStrings.current.formatLabel(deck.format).ifBlank { null },
                            deck.bracket?.let { "Bracket $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (deck.authorUserName.isNotBlank()) {
                    Text(
                        "@${deck.authorUserName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { openUser(deck.authorUserName) },
                    )
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat(t.cards, "${cards.filter { it.board == Boards.MAIN || it.board == Boards.COMMANDERS }.sumOf { it.quantity }}")
            if (price > 0) Stat(t.price, formatEur(price))
            if (!deck.isLocalOnly) Stat(t.likes, "${deck.likeCount}")
            Stat(t.visibility, if (deck.isLocalOnly) t.visibilityLocal else t.visibilityLabel(deck.visibility))
        }
        StatusNotes(deck, needsLogin, openLogin)
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StatusNotes(deck: DeckEntity, needsLogin: Boolean, openLogin: () -> Unit) {
    val t = LocalStrings.current
    val note = when {
        deck.hasConflict -> t.noteConflict
        needsLogin -> t.noteNeedsLogin
        deck.isLocalOnly -> t.noteLocalOnly
        deck.hasLocalChanges -> t.notePending
        else -> null
    } ?: return
    Surface(
        color = if (deck.hasConflict) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(enabled = needsLogin, onClick = openLogin),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Warning, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Vistas de carta ----------

/** Vista "Texto": como una lista impresa, sin imágenes. */
@Composable
private fun TextRow(card: DeckCardEntity, localized: Localized, editing: Boolean, onClick: () -> Unit, onQty: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = if (editing) 0.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${card.quantity}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(28.dp))
        Text(
            card.localName(localized) + if (card.isFoil) " ✦" else "",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (editing) QtyButtons(card.quantity, onQty)
        else ManaText(card.manaCost, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Vista "Lista": miniatura, nombre, coste y precio. */
@Composable
private fun CardRow(card: DeckCardEntity, localized: Localized, editing: Boolean, onClick: () -> Unit, onQty: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${card.quantity}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(28.dp))
        Box(
            Modifier.size(width = 36.dp, height = 50.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            card.imageId(localized)?.let {
                AsyncImage(
                    model = ScryfallImages.url(it, ScryfallImages.Size.SMALL),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(card.localName(localized) + if (card.isFoil) " ✦" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            ManaText(
                card.manaCost,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (editing) {
            QtyButtons(card.quantity, onQty)
        } else {
            card.priceEur?.let { Text(formatEur(it), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** Vista "Cuadrícula": la carta entera, con la cantidad encima. */
@Composable
private fun CardTile(card: DeckCardEntity, localized: Localized, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(0.716f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        val id = card.imageId(localized)
        if (id != null) {
            AsyncImage(
                model = ScryfallImages.url(id, ScryfallImages.Size.NORMAL),
                contentDescription = card.localName(localized),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(card.localName(localized), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp))
        }
        if (card.quantity > 1) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(bottomEnd = 8.dp),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Text(
                    "×${card.quantity}",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Vista "Carta completa": imagen grande con su texto al lado. */
@Composable
private fun FullCardRow(card: DeckCardEntity, localized: Localized, editing: Boolean, onClick: () -> Unit, onQty: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.weight(0.45f).aspectRatio(0.716f).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            card.imageId(localized)?.let {
                AsyncImage(
                    model = ScryfallImages.url(it, ScryfallImages.Size.NORMAL),
                    contentDescription = card.localName(localized),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(0.55f)) {
            Text(
                "${card.quantity}× ${card.localName(localized)}" + if (card.isFoil) " ✦" else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            ManaText(
                listOf(card.manaCost, card.typeLine).filter { it.isNotBlank() }.joinToString("  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            card.oracleText?.let {
                ManaText(it, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                listOfNotNull(card.setCode.uppercase().ifBlank { null }, card.collectorNumber, card.priceEur?.let(::formatEur)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (editing) QtyButtons(card.quantity, onQty)
        }
    }
}

@Composable
private fun QtyButtons(quantity: Int, onQty: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onQty(quantity - 1) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        IconButton(onClick = { onQty(quantity + 1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun CardDetailDialog(
    card: DeckCardEntity,
    localized: Localized,
    editing: Boolean,
    canChangeArt: Boolean,
    onDismiss: () -> Unit,
    onQty: (Int) -> Unit,
    onMove: (String) -> Unit,
    onChangeArt: () -> Unit,
) {
    val t = LocalStrings.current
    var back by remember(card.cardKey) { mutableStateOf(false) }
    val localName = card.localName(localized)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(localName)
                if (localName != card.name) Text(card.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            LazyColumn {
                item {
                    card.imageId(localized)?.let { id ->
                        AsyncImage(
                            model = ScryfallImages.url(id, ScryfallImages.Size.NORMAL, back = back),
                            contentDescription = localName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.716f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = card.hasBackImage) { back = !back },
                        )
                        if (card.hasBackImage) Text(t.tapToFlip, style = MaterialTheme.typography.labelSmall)
                    }
                }
                item {
                    Column(Modifier.padding(top = 8.dp)) {
                        ManaText("${card.manaCost}  ${card.typeLine}".trim(), style = MaterialTheme.typography.labelLarge)
                        card.oracleText?.let { ManaText(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                        Text(
                            listOfNotNull(
                                card.setCode.uppercase().ifBlank { null },
                                card.collectorNumber,
                                card.rarity,
                                card.priceEur?.let(::formatEur),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                if (canChangeArt) {
                    item {
                        TextButton(onClick = onChangeArt, modifier = Modifier.padding(top = 4.dp)) {
                            Icon(Icons.Outlined.Palette, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t.changeArt)
                        }
                    }
                }
                if (editing) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Text(t.quantityN(card.quantity), modifier = Modifier.weight(1f))
                            TextButton(onClick = { onQty(card.quantity - 1) }) { Text("−1") }
                            TextButton(onClick = { onQty(card.quantity + 1) }) { Text("+1") }
                        }
                        Text(t.moveTo, style = MaterialTheme.typography.labelMedium)
                        Row {
                            Boards.editable.filter { it != card.board }.forEach { b ->
                                TextButton(onClick = { onMove(b) }) { Text(Boards.label(b)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (editing) TextButton(onClick = { onQty(0); onDismiss() }) { Text(t.remove) }
                TextButton(onClick = onDismiss) { Text(t.close) }
            }
        },
    )
}

/** Elegir arte: todas las impresiones de la carta, como en Moxfield. */
@Composable
private fun PrintingsDialog(
    state: PrintingsState,
    currentScryfallId: String?,
    onDismiss: () -> Unit,
    onPick: (ScryCard) -> Unit,
) {
    val t = LocalStrings.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.chooseArt, style = MaterialTheme.typography.titleMedium)
                        Text(state.cardName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text(t.close) }
                }
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.error != null -> MessageBox(state.error)
                    state.printings.isEmpty() -> MessageBox(t.noResults, isError = false)
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.printings, key = { it.id }) { p ->
                            val current = p.id == currentScryfallId
                            Column(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(if (current) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                                    .clickable(enabled = !current) { onPick(p) }
                                    .padding(2.dp),
                            ) {
                                AsyncImage(
                                    model = p.imageUrl(ScryfallImages.Size.NORMAL),
                                    contentDescription = p.setName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.716f).clip(RoundedCornerShape(6.dp)),
                                )
                                Text(
                                    "${p.set.uppercase()} · ${p.collectorNumber}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    listOfNotNull(p.setName.ifBlank { null }, p.prices.eur?.let { "$it €" }).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatEur(value: Double): String = String.format(Locale("es", "ES"), "%.2f €", value)
