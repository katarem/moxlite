package es.moxlite.ui.deck

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.MoxError
import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.scryfall.ScryfallImages
import es.moxlite.data.remote.userMessage
import es.moxlite.ui.common.ColorPips
import es.moxlite.ui.common.MessageBox
import es.moxlite.ui.common.SectionHeader
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.decks.TextInputDialog
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(
    publicId: String,
    onBack: () -> Unit,
    openDeck: (String) -> Unit,
    openUser: (String) -> Unit,
    openWeb: (url: String) -> Unit,
) {
    val container = appContainer()
    val vm: DeckViewModel = viewModel(key = "deck-$publicId") { DeckViewModel(container, publicId) }
    val deck by vm.deck.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var editing by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // copy | rename | export | discard | remove | add
    var selected by remember { mutableStateOf<DeckCardEntity?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is DeckEvent.Message -> snackbar.showSnackbar(e.text)
                is DeckEvent.OpenDeck -> openDeck(e.publicId)
                is DeckEvent.Closed -> onBack()
                is DeckEvent.Share -> context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, e.title)
                            .putExtra(Intent.EXTRA_TEXT, e.text),
                        "Exportar ${e.title}",
                    )
                )
            }
        }
    }

    val d = deck
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.name ?: "Mazo", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Atrás") } },
                actions = {
                    if (d != null && !d.isLocalOnly) {
                        IconButton(onClick = { vm.toggleSaved() }) {
                            Icon(
                                if (d.isSaved) Icons.Filled.DownloadForOffline else Icons.Outlined.DownloadForOffline,
                                contentDescription = if (d.isSaved) "Guardado sin conexión" else "Guardar sin conexión",
                                tint = if (d.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, "Actualizar") }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem("Copiar mazo", Icons.Outlined.ContentCopy) { menu = false; dialog = "copy" }
                        MenuItem("Renombrar", Icons.Outlined.Edit) { menu = false; dialog = "rename" }
                        MenuItem("Exportar como texto", Icons.Outlined.Share) { menu = false; dialog = "export" }
                        if (d != null && !d.isLocalOnly) {
                            MenuItem("Ver en Moxfield (web)", Icons.Outlined.OpenInBrowser) {
                                menu = false; openWeb("https://moxfield.com/decks/${d.publicId}")
                            }
                        }
                        if (d?.authorUserName?.isNotBlank() == true) {
                            MenuItem("Más mazos de @${d.authorUserName}", Icons.Outlined.Person) { menu = false; openUser(d.authorUserName) }
                        }
                        if (d?.hasLocalChanges == true) {
                            MenuItem("Descartar cambios locales", Icons.Outlined.Restore) { menu = false; dialog = "discard" }
                        }
                        MenuItem("Quitar del móvil", Icons.Outlined.DeleteOutline) { menu = false; dialog = "remove" }
                    }
                },
            )
        },
        floatingActionButton = {
            if (d != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (editing) {
                        SmallFloatingActionButton(onClick = { dialog = "add" }) { Icon(Icons.Outlined.Add, "Añadir carta") }
                    }
                    FloatingActionButton(onClick = { editing = !editing }) {
                        Icon(if (editing) Icons.Outlined.Check else Icons.Outlined.Edit, if (editing) "Terminar edición" else "Editar")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                d != null && (cards.isNotEmpty() || d.isFullyLoaded) ->
                    DeckContent(d, cards, editing, onCardClick = { selected = it }, onQty = vm::setQuantity, openUser = openUser)
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> {
                    val e = error!!
                    MessageBox(
                        text = e.userMessage(),
                        actionLabel = if (e is MoxError.CloudflareBlocked) "Abrir Moxfield en la app" else "Reintentar",
                        onAction = {
                            if (e is MoxError.CloudflareBlocked) openWeb("https://moxfield.com/decks/$publicId") else vm.refresh()
                        },
                    )
                }
            }
        }
    }

    selected?.let { card ->
        // Siempre la versión actual de la fila (la cantidad puede haber cambiado).
        val current = cards.firstOrNull { it.board == card.board && it.cardKey == card.cardKey }
        if (current == null) selected = null
        else CardDetailDialog(
            card = current,
            editing = editing,
            onDismiss = { selected = null },
            onQty = { vm.setQuantity(current, it) },
            onMove = { board -> vm.move(current, board); selected = null },
        )
    }

    when (dialog) {
        "copy" -> TextInputDialog(
            title = "Copiar mazo",
            label = "Nombre de la copia",
            initial = "Copia de ${d?.name.orEmpty()}",
            confirmLabel = "Copiar",
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.copy(it) },
        )
        "rename" -> TextInputDialog(
            title = "Renombrar",
            label = "Nombre",
            initial = d?.name.orEmpty(),
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.rename(it) },
        )
        "export" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Exportar como texto") },
            text = { Text("¿Incluir edición y número de cada carta? Sirve para reimportar las mismas impresiones en Moxfield, Arena u otras webs.") },
            confirmButton = { TextButton(onClick = { dialog = null; vm.export(true) }) { Text("Con ediciones") } },
            dismissButton = { TextButton(onClick = { dialog = null; vm.export(false) }) { Text("Solo nombres") } },
        )
        "discard" -> ConfirmDialog(
            title = "Descartar cambios locales",
            text = if (d?.isLocalOnly == true) "Este mazo solo existe en el móvil: se borrará."
            else "Se perderán los cambios hechos en el móvil y se descargará la versión de Moxfield.",
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.discardLocalChanges() },
        )
        "remove" -> ConfirmDialog(
            title = "Quitar del móvil",
            text = "Se borra la copia local" + (if (d?.hasLocalChanges == true) " y sus cambios sin subir" else "") +
                ". En Moxfield no se toca nada.",
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.removeFromDevice() },
        )
        "add" -> AddCardDialog(vm = vm, onDismiss = { dialog = null })
    }
}

@Composable
private fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun DeckContent(
    deck: DeckEntity,
    cards: List<DeckCardEntity>,
    editing: Boolean,
    onCardClick: (DeckCardEntity) -> Unit,
    onQty: (DeckCardEntity, Int) -> Unit,
    openUser: (String) -> Unit,
) {
    val boards = (cards.map { it.board } + if (editing) Boards.editable else emptyList())
        .distinct()
        .sortedBy(Boards::sortIndex)
    // Por defecto, el mazo principal.
    var tab by rememberSaveable(deck.publicId) { mutableStateOf(Boards.MAIN) }
    val currentBoard = if (tab in boards) tab else boards.firstOrNull() ?: Boards.MAIN
    val boardCards = cards.filter { it.board == currentBoard }

    LazyColumn(Modifier.fillMaxSize()) {
        item { DeckHeader(deck, cards, openUser) }
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
        if (boardCards.isEmpty()) {
            item { MessageBox("Vacío. Pulsa + para añadir cartas.", isError = false) }
        }
        val groups = boardCards.groupBy { it.primaryType }
            .toSortedMap(compareBy<String> { DeckCardEntity.TYPE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } })
        groups.forEach { (type, list) ->
            item(key = "h-$currentBoard-$type") {
                SectionHeader(DeckCardEntity.typeLabel(type), "${list.sumOf { it.quantity }}")
            }
            items(list.sortedWith(compareBy<DeckCardEntity>({ it.cmc }, { it.name })), key = { "${it.board}|${it.cardKey}" }) { card ->
                CardRow(card, editing, onClick = { onCardClick(card) }, onQty = { onQty(card, it) })
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun DeckHeader(deck: DeckEntity, cards: List<DeckCardEntity>, openUser: (String) -> Unit) {
    val price = cards.sumOf { (it.priceEur ?: 0.0) * it.quantity }
    Column {
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            deck.featuredScryfallId?.let {
                AsyncImage(
                    model = ScryfallImages.url(it, ScryfallImages.Size.ART_CROP),
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
                    Text(deck.format, style = MaterialTheme.typography.labelLarge)
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
            Stat("Cartas", "${cards.filter { it.board == Boards.MAIN || it.board == Boards.COMMANDERS }.sumOf { it.quantity }}")
            if (price > 0) Stat("Precio", String.format(Locale("es", "ES"), "%.2f €", price))
            if (!deck.isLocalOnly) Stat("Me gusta", "${deck.likeCount}")
            Stat("Visibilidad", when (deck.visibility) {
                "private" -> "Privado"; "unlisted" -> "Oculto"; else -> if (deck.isLocalOnly) "Local" else "Público"
            })
        }
        StatusNotes(deck)
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StatusNotes(deck: DeckEntity) {
    val notes = buildList {
        if (deck.hasConflict) add("Moxfield tiene una versión más nueva y tú tienes cambios sin subir. Exporta tu versión si la quieres conservar y descarta los cambios locales para traer la de Moxfield.")
        else if (deck.isLocalOnly) add("Este mazo solo está en el móvil. Se creará en Moxfield cuando la sincronización de escritura esté disponible; mientras, puedes exportarlo e importarlo en la web.")
        else if (deck.hasLocalChanges) add("Hay cambios hechos en el móvil pendientes de subir a Moxfield.")
    }
    notes.forEach { note ->
        Surface(
            color = if (deck.hasConflict) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
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

@Composable
private fun CardRow(card: DeckCardEntity, editing: Boolean, onClick: () -> Unit, onQty: (Int) -> Unit) {
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
            card.scryfallId?.let {
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
            Text(card.name + if (card.isFoil) " ✦" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ManaText.plain(card.manaCost),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (editing) {
            IconButton(onClick = { onQty(card.quantity - 1) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
            IconButton(onClick = { onQty(card.quantity + 1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
        } else {
            card.priceEur?.let {
                Text(String.format(Locale("es", "ES"), "%.2f €", it), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun CardDetailDialog(
    card: DeckCardEntity,
    editing: Boolean,
    onDismiss: () -> Unit,
    onQty: (Int) -> Unit,
    onMove: (String) -> Unit,
) {
    var back by remember(card.cardKey) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.name) },
        text = {
            LazyColumn {
                item {
                    card.scryfallId?.let { id ->
                        AsyncImage(
                            model = ScryfallImages.url(id, ScryfallImages.Size.NORMAL, back = back),
                            contentDescription = card.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.716f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = card.hasBackImage) { back = !back },
                        )
                        if (card.hasBackImage) Text("Toca la carta para girarla", style = MaterialTheme.typography.labelSmall)
                    }
                }
                item {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text("${ManaText.plain(card.manaCost)}  ${card.typeLine}", style = MaterialTheme.typography.labelLarge)
                        card.oracleText?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                        Text(
                            listOfNotNull(
                                card.setCode.uppercase().ifBlank { null },
                                card.collectorNumber,
                                card.rarity,
                                card.priceEur?.let { String.format(Locale("es", "ES"), "%.2f €", it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                if (editing) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Cantidad: ${card.quantity}", modifier = Modifier.weight(1f))
                            TextButton(onClick = { onQty(card.quantity - 1) }) { Text("−1") }
                            TextButton(onClick = { onQty(card.quantity + 1) }) { Text("+1") }
                        }
                        Text("Mover a:", style = MaterialTheme.typography.labelMedium)
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
                if (editing) TextButton(onClick = { onQty(0); onDismiss() }) { Text("Quitar") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}

object ManaText {
    /** "{2}{U}{B}" → "2UB"; "{W/U}" → "W/U". */
    fun plain(cost: String): String = cost.replace("}{", " ").replace("{", "").replace("}", "")
        .split(" ").joinToString("") {
            when {
                it == "//" -> " // "
                it.length > 1 && it.any { c -> !c.isDigit() } -> "($it)"
                else -> it
            }
        }
}
