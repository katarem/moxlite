package es.moxlite.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import es.moxlite.domain.ColorMatch
import es.moxlite.domain.DeckFilters
import es.moxlite.domain.DeckSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Fila de filtros y orden para listas de mazos: orden, formato, colores, comandante, bracket y
 * tipo. La usan la búsqueda de Moxfield y "Mis mazos".
 *
 * @param commanderSuggestions sugerencias (nombre a mostrar, nombre en inglés) para un texto.
 */
@Composable
fun DeckFilterBar(
    filters: DeckFilters,
    sorts: List<DeckSort>,
    onChange: (DeckFilters) -> Unit,
    commanderSuggestions: suspend (String) -> List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    hubSuggestions: List<String> = DeckFilters.COMMON_HUBS,
) {
    val t = LocalStrings.current
    var dialog by remember { mutableStateOf<String?>(null) } // colors | commander | hub

    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropdownChip(
            label = t.deckSortLabel(filters.sort),
            selected = false,
            options = sorts.map { it to t.deckSortLabel(it) },
            current = filters.sort,
            onPick = { onChange(filters.copy(sort = it ?: filters.sort)) },
            anyLabel = null,
        )
        DropdownChip(
            label = filters.format?.let(t::formatLabel) ?: t.filterFormat,
            selected = filters.format != null,
            options = FORMATS.map { it to t.formatLabel(it) },
            current = filters.format,
            onPick = { onChange(filters.copy(format = it)) },
            anyLabel = t.anyOption,
        )
        FilterChip(
            selected = filters.colors.isNotBlank(),
            onClick = { dialog = "colors" },
            label = {
                if (filters.colors.isBlank()) Text(t.filterColors)
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorPips(filters.colors, size = 12)
                    if (filters.colors != "C") Text(t.colorMatchLabel(filters.colorMatch))
                }
            },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = filters.commander != null,
            onClick = { dialog = "commander" },
            label = { Text(filters.commander ?: t.filterCommander, maxLines = 1) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownChip(
            label = filters.bracket?.let(t::bracketLabel) ?: t.filterBracket,
            selected = filters.bracket != null,
            options = DeckFilters.BRACKETS.map { it to t.bracketLabel(it) },
            current = filters.bracket,
            onPick = { onChange(filters.copy(bracket = it)) },
            anyLabel = t.anyOption,
        )
        FilterChip(
            selected = filters.hub != null,
            onClick = { dialog = "hub" },
            label = { Text(filters.hub ?: t.filterHub, maxLines = 1) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        if (filters.activeCount > 0) {
            AssistChip(
                onClick = { onChange(filters.cleared()) },
                label = { Text(t.clearFilters) },
                leadingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(18.dp)) },
            )
        }
    }

    when (dialog) {
        "colors" -> ColorsDialog(
            filters = filters,
            onDismiss = { dialog = null },
            onApply = { dialog = null; onChange(it) },
        )
        "commander" -> CommanderDialog(
            initial = filters.commander.orEmpty(),
            suggestions = commanderSuggestions,
            onDismiss = { dialog = null },
            onApply = { dialog = null; onChange(filters.copy(commander = it?.trim()?.ifBlank { null })) },
        )
        "hub" -> HubDialog(
            initial = filters.hub.orEmpty(),
            suggestions = hubSuggestions,
            onDismiss = { dialog = null },
            onApply = { dialog = null; onChange(filters.copy(hub = it?.trim()?.ifBlank { null })) },
        )
    }
}

/** Chip con desplegable. [anyLabel] añade una opción para quitar el filtro (valor null). */
@Composable
private fun <T> DropdownChip(
    label: String,
    selected: Boolean,
    options: List<Pair<T, String>>,
    current: T?,
    onPick: (T?) -> Unit,
    anyLabel: String?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (anyLabel != null) {
                DropdownMenuItem(
                    text = { Text(anyLabel) },
                    onClick = { open = false; onPick(null) },
                    trailingIcon = { if (current == null) Icon(Icons.Outlined.Check, null) },
                )
            }
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { open = false; onPick(value) },
                    trailingIcon = { if (value == current) Icon(Icons.Outlined.Check, null) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorsDialog(filters: DeckFilters, onDismiss: () -> Unit, onApply: (DeckFilters) -> Unit) {
    val t = LocalStrings.current
    var draft by remember { mutableStateOf(filters) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.filterColors) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    "WUBRGC".forEach { c ->
                        val on = c in draft.colors
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (on) 3.dp else 0.dp,
                                    color = if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { draft = draft.toggleColor(c) }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ManaSymbol(c.toString(), Modifier.fillMaxSize().alpha(if (on) 1f else 0.35f))
                        }
                    }
                }
                if (draft.colors == "C") {
                    Text(t.colorless, style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ColorMatch.entries.forEach { m ->
                            FilterChip(
                                selected = draft.colorMatch == m,
                                onClick = { draft = draft.copy(colorMatch = m) },
                                label = { Text(t.colorMatchLabel(m)) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text(t.applyFilters) } },
        dismissButton = { TextButton(onClick = { onApply(draft.copy(colors = "")) }) { Text(t.clear) } },
    )
}

@Composable
private fun CommanderDialog(
    initial: String,
    suggestions: suspend (String) -> List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onApply: (String?) -> Unit,
) {
    val t = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    var results by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        if (text.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        results = try {
            suggestions(text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.filterCommander) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(t.commanderPlaceholder) },
                    singleLine = true,
                    trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 280.dp).padding(top = 4.dp)) {
                    items(results, key = { it.second }) { (display, english) ->
                        ListItem(
                            headlineContent = { Text(display) },
                            supportingContent = { if (display != english) Text(english) },
                            modifier = Modifier.clickable { onApply(english) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(text) }, enabled = text.isNotBlank()) { Text(t.applyFilters) } },
        dismissButton = { TextButton(onClick = { onApply(null) }) { Text(t.clear) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubDialog(initial: String, suggestions: List<String>, onDismiss: () -> Unit, onApply: (String?) -> Unit) {
    val t = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    val shown = remember(text, suggestions) {
        suggestions.filter { text.isBlank() || it.contains(text.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.filterHub) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(t.hubPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    Modifier.padding(top = 8.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    shown.forEach { hub ->
                        AssistChip(onClick = { onApply(hub) }, label = { Text(hub) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(text) }, enabled = text.isNotBlank()) { Text(t.applyFilters) } },
        dismissButton = { TextButton(onClick = { onApply(null) }) { Text(t.clear) } },
    )
}
