package es.moxlite.ui.builder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import es.moxlite.data.builder.DraftCard
import es.moxlite.data.builder.toBuildCard
import es.moxlite.data.remote.edhrec.EdhBudget
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.scryfall.ScryfallImages
import es.moxlite.domain.BasicLands
import es.moxlite.domain.CardRole
import es.moxlite.domain.DeckStats
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.MessageBox
import es.moxlite.ui.common.SectionHeader
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.deck.ConfirmDialog
import es.moxlite.ui.deck.ManaText
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class BuildTab { SUGGESTIONS, DECK, CUTS }

/**
 * Asistente de construcción con EDHREC. [deckId] null = mazo nuevo; si no, se mejora ese mazo
 * (los cambios se guardan en él al terminar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuilderScreen(deckId: String?, onBack: () -> Unit, openDeck: (String) -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val vm: BuilderViewModel = viewModel(key = "builder-${deckId ?: "new"}") { BuilderViewModel(container, deckId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) } // targets | discard | finish | average | partner

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is BuilderEvent.Message -> snackbar.showSnackbar(event.text)
                is BuilderEvent.OpenDeck -> openDeck(event.deckId)
            }
        }
    }

    val step = ui.step
    // Atrás del sistema en "Comandante y enfoque" (desde la construcción): volver a construir.
    BackHandler(enabled = ui.editingOptions && ui.draft.started) { vm.closeOptions() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (step == BuilderStep.BUILD) ui.draft.name.ifBlank { t.edhBuilder } else t.edhBuilder,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (step == BuilderStep.BUILD) {
                            Text(t.edhCardsCount(ui.draft.total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (ui.editingOptions && ui.draft.started) vm.closeOptions() else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, t.back)
                    }
                },
                actions = {
                    if (step == BuilderStep.BUILD) {
                        IconButton(onClick = { vm.editOptions() }) { Icon(Icons.Outlined.Tune, t.edhOptions) }
                        IconButton(onClick = { dialog = "targets" }) { Icon(Icons.Outlined.Flag, t.edhTargets) }
                    }
                    if (!ui.loadingDraft && (ui.draft.commanders.isNotEmpty() || ui.draft.cards.isNotEmpty())) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t.more) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(t.edhDiscard) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                onClick = { menu = false; dialog = "discard" },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loadingDraft -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                step == BuilderStep.COMMANDER -> CommanderStep(vm)
                step == BuilderStep.OPTIONS -> OptionsStep(
                    vm = vm,
                    ui = ui,
                    onAddPartner = { dialog = "partner" },
                    onReplaceWithAverage = { dialog = "average" },
                )
                else -> BuildStep(vm, ui, onFinish = { if (ui.draft.total == 100) vm.finish() else dialog = "finish" })
            }
        }
    }

    when (dialog) {
        "targets" -> TargetsDialog(vm, onDismiss = { dialog = null })
        "discard" -> ConfirmDialog(
            title = t.edhDiscard,
            text = t.edhDiscardConfirm,
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.discard() },
        )
        "average" -> ConfirmDialog(
            title = t.edhReplaceWithAverage,
            text = t.edhReplaceConfirm,
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.start(withAverageDeck = true) },
        )
        "finish" -> ConfirmDialog(
            title = if (ui.isImprovingDeck) t.edhSaveToDeck else t.edhCreateDeck,
            text = t.edhNot100(ui.draft.total),
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.finish() },
        )
        "partner" -> Dialog(onDismissRequest = { dialog = null }) {
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(560.dp)) {
                Column(Modifier.padding(top = 8.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.edhAddPartner, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { dialog = null }) { Icon(Icons.Outlined.Close, t.close) }
                    }
                    CommanderPicker(vm, partner = true, onPicked = { dialog = null })
                }
            }
        }
    }
}

// ---------- Paso 1: comandante ----------

@Composable
private fun CommanderStep(vm: BuilderViewModel) {
    val t = LocalStrings.current
    Column(Modifier.fillMaxSize()) {
        Text(
            t.edhIntro,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        CommanderPicker(vm, partner = false, onPicked = {})
    }
}

/** Buscador de comandantes (o de la pareja del elegido), con imagen. */
@Composable
private fun CommanderPicker(vm: BuilderViewModel, partner: Boolean, onPicked: () -> Unit) {
    val t = LocalStrings.current
    var text by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ScryCard>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (!partner && text.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        results = vm.commanderResults(text)
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(if (partner) t.edhSearchPartner else t.edhSearchCommander) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Outlined.Close, t.clear) }
            },
            supportingText = { Text(t.searchCardHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        if (!loading && results.isEmpty() && (partner || text.trim().length >= 2)) {
            MessageBox(t.noResults, isError = false)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { card ->
                CardRow(
                    imageId = card.id,
                    imageUrl = card.imageUrl(ScryfallImages.Size.SMALL),
                    title = card.displayName,
                    subtitle = listOfNotNull(card.name.takeIf { it != card.displayName }, card.typeLine).joinToString(" · "),
                    detail = null,
                    onClick = { vm.chooseCommander(card); onPicked() },
                )
                HorizontalDivider()
            }
        }
    }
}

// ---------- Paso 2: enfoque ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsStep(vm: BuilderViewModel, ui: BuilderUi, onAddPartner: () -> Unit, onReplaceWithAverage: () -> Unit) {
    val t = LocalStrings.current
    val draft = ui.draft
    val base = ui.basePage
    Column(Modifier.fillMaxSize()) {
        if (ui.pageLoading || ui.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader(t.edhCommanders, base?.numDecks?.takeIf { it > 0 }?.let { t.edhDecksOnEdhrec(it) })
            draft.commanders.forEach { commander ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CardThumb(commander.id.takeIf { it.isNotBlank() }, commander.imageUrl(ScryfallImages.Size.SMALL))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(commander.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(commander.typeLine.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!ui.isImprovingDeck || draft.commanders.size > 1) {
                        IconButton(onClick = { vm.removeCommander(commander.name) }) { Icon(Icons.Outlined.Close, t.remove) }
                    }
                }
            }
            val first = draft.commanders.firstOrNull()
            if (draft.commanders.size == 1 && first != null && vm.partnerFilter(first) != null) {
                OutlinedButton(onClick = onAddPartner, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(t.edhAddPartner)
                }
            }
            ui.pageError?.let { MessageBox(it, actionLabel = t.retry, onAction = { vm.retry() }) }

            OutlinedTextField(
                value = draft.name,
                onValueChange = vm::setName,
                label = { Text(t.deckName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val tags = base?.tagCounts.orEmpty().filter { it.slug.isNotBlank() }.take(MAX_THEMES)
            if (tags.isNotEmpty()) {
                SectionHeader(t.edhTheme, draft.themeLabel)
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = draft.theme == tag.slug,
                            onClick = { vm.setTheme(tag.slug, tag.value) },
                            label = { Text("${tag.value} · ${tag.count}") },
                        )
                    }
                }
            }

            SectionHeader(t.edhBudget)
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EdhBudget.entries.forEach { budget ->
                    val count = budget.slug?.let { base?.budgetCounts?.get(it) }
                    FilterChip(
                        selected = draft.budget == budget,
                        onClick = { vm.setBudget(budget) },
                        label = { Text(t.edhBudgetLabel(budget) + (count?.let { " · $it" } ?: "")) },
                    )
                }
            }

            SectionHeader(t.edhBracket)
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = draft.bracket == null, onClick = { vm.setBracket(null) }, label = { Text(t.anyOption) })
                (1..5).forEach { bracket ->
                    val count = base?.bracketCounts?.get(bracket.toString())
                    FilterChip(
                        selected = draft.bracket == bracket,
                        enabled = draft.theme == null,
                        onClick = { vm.setBracket(bracket) },
                        label = { Text(t.bracketLabel(bracket) + (count?.let { " · $it" } ?: "")) },
                    )
                }
            }
            if (draft.theme != null) {
                Text(
                    t.edhBracketThemeHint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            ui.page?.let { page ->
                Text(
                    listOfNotNull(
                        page.numDecks.takeIf { it > 0 }?.let { t.edhDecksOnEdhrec(it) },
                        page.land?.let { t.edhAverageLands(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.busy?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                val enabled = ui.busy == null
                when {
                    draft.started -> {
                        Button(onClick = { vm.closeOptions(); vm.refreshRecsNow() }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Text(t.edhStartImprove)
                        }
                        OutlinedButton(onClick = onReplaceWithAverage, enabled = enabled && ui.pageError == null, modifier = Modifier.fillMaxWidth()) {
                            Text(t.edhReplaceWithAverage)
                        }
                    }
                    ui.isImprovingDeck -> Button(onClick = { vm.start(withAverageDeck = false) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(t.edhStartImprove)
                    }
                    else -> {
                        Button(onClick = { vm.start(withAverageDeck = true) }, enabled = enabled && ui.pageError == null, modifier = Modifier.fillMaxWidth()) {
                            Text(t.edhStartAverage)
                        }
                        Text(t.edhStartAverageHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { vm.start(withAverageDeck = false) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Text(t.edhStartEmpty)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Paso 3: construir ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BuildStep(vm: BuilderViewModel, ui: BuilderUi, onFinish: () -> Unit) {
    val t = LocalStrings.current
    val stats by vm.stats.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val cuts by vm.cuts.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(BuildTab.SUGGESTIONS) }
    var preview by remember { mutableStateOf<Suggestion?>(null) }
    // Carta del mazo abierta en grande, y si se puede quitar (los comandantes no, desde aquí).
    var previewInDeck by remember { mutableStateOf<Pair<ScryCard, Boolean>?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (ui.busy != null || ui.recsLoading || ui.pageLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        StatsBar(stats, ui.draft.targets, onRole = { role ->
            vm.setFilter(SuggestionFilter.Role(role))
            tab = BuildTab.SUGGESTIONS
        })
        CurveRow(stats)
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            Tab(selected = tab == BuildTab.SUGGESTIONS, onClick = { tab = BuildTab.SUGGESTIONS }, text = { Text(t.edhSuggestions, maxLines = 1) })
            Tab(selected = tab == BuildTab.DECK, onClick = { tab = BuildTab.DECK }, text = { Text("${t.deck} (${ui.draft.total})", maxLines = 1) })
            Tab(
                selected = tab == BuildTab.CUTS,
                onClick = { tab = BuildTab.CUTS },
                text = { Text(if (cuts.isEmpty()) t.edhCuts else "${t.edhCuts} (${cuts.size})", maxLines = 1) },
            )
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                BuildTab.SUGGESTIONS -> SuggestionsTab(
                    vm = vm,
                    ui = ui,
                    filters = filters,
                    filter = filter,
                    suggestions = suggestions,
                    onPreview = { preview = it },
                )
                BuildTab.DECK -> DeckTab(vm, ui, onPreview = { card, removable -> previewInDeck = card to removable })
                BuildTab.CUTS -> CutsTab(vm, cuts, onPreview = { s -> s.card?.let { previewInDeck = it to true } })
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val enabled = ui.busy == null
                OutlinedButton(onClick = { vm.autoFill() }, enabled = enabled && ui.draft.total < 100, modifier = Modifier.weight(1f)) {
                    Text(t.edhAutoFill, maxLines = 1)
                }
                OutlinedButton(onClick = { vm.fillBasics() }, enabled = enabled && ui.draft.total < 100) {
                    Text(t.edhBasics, maxLines = 1)
                }
                Button(onClick = onFinish, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(if (ui.isImprovingDeck) t.edhSaveToDeck else t.edhCreateDeck, maxLines = 1)
                }
            }
        }
    }

    preview?.let { s ->
        CardPreviewDialog(
            imageId = s.imageId,
            card = s.card,
            name = s.name,
            details = suggestionDetail(s),
            actionLabel = t.add,
            onAction = { vm.add(s); preview = null },
            onDismiss = { preview = null },
        )
    }
    previewInDeck?.let { (card, removable) ->
        CardPreviewDialog(
            imageId = card.id.takeIf { it.isNotBlank() },
            card = card,
            name = card.name,
            details = card.toBuildCard().roles.filter { it != CardRole.LAND }.takeIf { it.isNotEmpty() }?.joinToString(", ") { t.edhRoleLabel(it) },
            actionLabel = if (removable) t.remove else null,
            onAction = { vm.remove(card.name); previewInDeck = null },
            onDismiss = { previewInDeck = null },
        )
    }
}

@Composable
private fun StatsBar(stats: DeckStats, targets: es.moxlite.domain.BuilderTargets, onRole: (CardRole) -> Unit) {
    val t = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardRole.entries.forEach { role ->
            val have = stats.count(role)
            val want = targets.of(role)
            val done = have >= want
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { onRole(role) },
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$have/$want", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(t.edhRoleLabel(role), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Curva de maná de los hechizos (0–7+) y coste medio. */
@Composable
private fun CurveRow(stats: DeckStats) {
    val t = LocalStrings.current
    val max = (stats.curve.values.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        (0..7).forEach { cmc ->
            val n = stats.curve[cmc] ?: 0
            Column(Modifier.width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (n > 0) "$n" else "", style = MaterialTheme.typography.labelSmall)
                Box(
                    Modifier.width(16.dp).height((4 + 28 * n / max).dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (n > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                )
                Text(if (cmc == 7) "7+" else "$cmc", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            t.edhAvgCmc(String.format(java.util.Locale.getDefault(), "%.2f", stats.averageCmc)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuggestionsTab(
    vm: BuilderViewModel,
    ui: BuilderUi,
    filters: List<SuggestionFilter>,
    filter: SuggestionFilter,
    suggestions: List<Suggestion>,
    onPreview: (Suggestion) -> Unit,
) {
    val t = LocalStrings.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            filters.forEach { f ->
                FilterChip(
                    selected = f == filter,
                    onClick = { vm.setFilter(f) },
                    label = {
                        Text(
                            when (f) {
                                SuggestionFilter.ForDeck -> t.edhForDeck
                                is SuggestionFilter.Role -> t.edhRoleLabel(f.role)
                                is SuggestionFilter.EdhList -> t.edhListLabel(f.tag, f.header)
                            }
                        )
                    },
                )
            }
        }
        ui.recsError?.let { error ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.edhRecsFailed(error), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.refreshRecsNow() }) { Icon(Icons.Outlined.Refresh, t.retry) }
            }
        }
        if (ui.pageError != null && suggestions.isEmpty()) {
            MessageBox(ui.pageError, actionLabel = t.retry, onAction = { vm.retry() })
        } else if (suggestions.isEmpty() && !ui.recsLoading && !ui.pageLoading) {
            MessageBox(t.edhNoSuggestions, isError = false)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(suggestions, key = { it.name }) { s ->
                CardRow(
                    imageId = s.imageId,
                    imageUrl = null,
                    title = s.name,
                    subtitle = listOfNotNull(
                        s.card?.let { ManaText.plain(it.manaCost ?: it.cardFaces.firstOrNull()?.manaCost.orEmpty()).ifBlank { null } },
                        s.card?.typeLine ?: s.primaryType.takeIf { it.isNotBlank() }?.let { t.typeLabel(it) },
                    ).joinToString("  "),
                    detail = suggestionDetail(s),
                    onClick = { onPreview(s) },
                    trailing = {
                        IconButton(onClick = { vm.add(s) }, enabled = ui.busy == null) { Icon(Icons.Outlined.AddCircleOutline, t.add) }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun suggestionDetail(s: Suggestion): String {
    val t = LocalStrings.current
    return listOfNotNull(
        s.score?.let { t.edhFit(it.roundToInt()) },
        s.synergy?.let { t.edhSynergy((it * 100).roundToInt()) },
        s.inclusion?.let { t.edhInclusion((it * 100).roundToInt()) },
        s.roles.filter { it != CardRole.LAND }.takeIf { it.isNotEmpty() }?.joinToString(", ") { t.edhRoleLabel(it) },
    ).joinToString(" · ")
}

@Composable
private fun DeckTab(vm: BuilderViewModel, ui: BuilderUi, onPreview: (ScryCard, removable: Boolean) -> Unit) {
    val t = LocalStrings.current
    val groups = ui.draft.cards
        .groupBy { primaryType(it) }
        .toSortedMap(compareBy { TYPE_ORDER.indexOf(it).let { i -> if (i < 0) TYPE_ORDER.size else i } })
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "commanders") { SectionHeader(t.edhCommanders, "${ui.draft.commanders.size}") }
        items(ui.draft.commanders, key = { "c-" + it.name }) { card ->
            DeckLine(card, quantity = 1, onPreview = { onPreview(card, false) }, trailing = {})
        }
        if (ui.draft.cards.isEmpty()) {
            item(key = "empty") { MessageBox(t.edhEmptyDeck, isError = false) }
        }
        groups.forEach { (type, cards) ->
            item(key = "h-$type") { SectionHeader(t.typeLabel(type), "${cards.sumOf { it.quantity }}") }
            items(cards.sortedBy { it.card.cmc }, key = { "d-" + it.card.name }) { entry ->
                DeckLine(entry.card, entry.quantity, onPreview = { onPreview(entry.card, true) }) {
                    if (BasicLands.isBasic(entry.card.name)) {
                        TextButton(onClick = { vm.setQuantity(entry.card.name, entry.quantity - 1) }) { Text("−") }
                        TextButton(onClick = { vm.setQuantity(entry.card.name, entry.quantity + 1) }) { Text("+") }
                    } else {
                        IconButton(onClick = { vm.remove(entry.card.name) }) { Icon(Icons.Outlined.RemoveCircleOutline, t.remove) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckLine(card: ScryCard, quantity: Int, onPreview: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPreview).padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$quantity", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(28.dp))
        Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            ManaText.plain(card.manaCost ?: card.cardFaces.firstOrNull()?.manaCost.orEmpty()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
private fun CutsTab(vm: BuilderViewModel, cuts: List<Suggestion>, onPreview: (Suggestion) -> Unit) {
    val t = LocalStrings.current
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "hint") {
            Text(
                t.edhCutsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (cuts.isEmpty()) item(key = "empty") { MessageBox(t.edhNoCuts, isError = false) }
        items(cuts, key = { it.name }) { s ->
            CardRow(
                imageId = s.imageId,
                imageUrl = null,
                title = s.name,
                subtitle = s.card?.typeLine.orEmpty(),
                detail = s.score?.let { t.edhCutScore(it.roundToInt()) },
                onClick = { onPreview(s) },
                trailing = {
                    IconButton(onClick = { vm.remove(s.name) }) { Icon(Icons.Outlined.RemoveCircleOutline, t.remove) }
                },
            )
            HorizontalDivider()
        }
    }
}

// ---------- Objetivos ----------

@Composable
private fun TargetsDialog(vm: BuilderViewModel, onDismiss: () -> Unit) {
    val t = LocalStrings.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.edhTargets) },
        text = {
            Column {
                Text(t.edhTargetsHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                CardRole.entries.forEach { role ->
                    val value = ui.draft.targets.of(role)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.edhRoleLabel(role), modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.setTarget(role, value - 1) }) { Text("−") }
                        Text("$value", modifier = Modifier.width(28.dp), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { vm.setTarget(role, value + 1) }) { Text("+") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t.accept) } },
    )
}

// ---------- Comunes ----------

@Composable
private fun CardThumb(imageId: String?, imageUrl: String?) {
    Box(
        Modifier.size(width = 52.dp, height = 72.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val url = imageUrl ?: imageId?.let { ScryfallImages.url(it, ScryfallImages.Size.SMALL) }
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CardRow(
    imageId: String?,
    imageUrl: String?,
    title: String,
    subtitle: String,
    detail: String?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardThumb(imageId, imageUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
private fun CardPreviewDialog(
    imageId: String?,
    card: ScryCard?,
    name: String,
    details: String?,
    actionLabel: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                val url = card?.takeIf { it.id.isNotBlank() }?.imageUrl(ScryfallImages.Size.NORMAL)
                    ?: imageId?.let { ScryfallImages.url(it, ScryfallImages.Size.NORMAL) }
                if (url != null) {
                    AsyncImage(
                        model = url,
                        contentDescription = name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.716f).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                card?.typeLine?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                card?.fullOracle?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                if (!details.isNullOrBlank()) {
                    Text(details, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(t.close) }
                    if (actionLabel != null) Button(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

private val TYPE_ORDER = listOf("Creature", "Planeswalker", "Battle", "Instant", "Sorcery", "Artifact", "Enchantment", "Land", "Other")

private fun primaryType(entry: DraftCard): String {
    val front = (entry.card.typeLine ?: entry.card.cardFaces.firstOrNull()?.typeLine).orEmpty().substringBefore("//").substringBefore("—")
    if (entry.card.toBuildCard().isLand) return "Land"
    return TYPE_ORDER.firstOrNull { front.contains(it) } ?: "Other"
}

private const val MAX_THEMES = 24
