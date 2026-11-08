package es.moxlite.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.scryfall.ScryfallImages
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.ManaText
import es.moxlite.ui.common.MessageBox

/**
 * Añadir carta buscando en Scryfall en cualquier idioma ("rayo" o "lightning bolt"), con la
 * imagen de cada resultado. La carta se añade en local al momento y se sube a Moxfield.
 */
@Composable
fun AddCardScreen(vm: DeckViewModel, onDismiss: () -> Unit) {
    val t = LocalStrings.current
    val search by vm.search.collectAsStateWithLifecycle()
    // El texto vive aquí y no en el ViewModel: escribir y borrar es inmediato aunque la búsqueda
    // tarde, y una búsqueda que llega tarde nunca cambia lo escrito.
    var text by rememberSaveable { mutableStateOf(search.query) }
    var chosen by remember { mutableStateOf<ScryCard?>(null) }
    var board by remember { mutableStateOf(Boards.MAIN) }
    var quantity by remember { mutableIntStateOf(1) }

    BackHandler(onBack = onDismiss)
    // Full screen inside the activity window (edge-to-edge), so the system bar and keyboard insets
    // are always the real ones. A Dialog window got them wrong on some phones and cut off "Add".
    // Material 3 Surface blocks touches, so nothing reaches the deck screen underneath.
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, t.close) }
                Text(t.addCard, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    chosen = null
                    vm.searchCards(it)
                },
                placeholder = { Text(t.searchCardPlaceholder) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = ""; chosen = null; vm.searchCards("") }) {
                            Icon(Icons.Outlined.Close, t.clear)
                        }
                    }
                },
                supportingText = { Text(t.searchCardHint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            // Mientras busca se siguen viendo los resultados anteriores, con una barra arriba.
            val searching = search.loading || (text.trim().length >= 2 && text != search.query)
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            Box(Modifier.weight(1f)) {
                when {
                    search.loading && search.results.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    search.error != null && !searching -> MessageBox(search.error.orEmpty())
                    !searching && search.query.trim().length >= 2 && search.results.isEmpty() ->
                        MessageBox(t.noResults, isError = false)
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(search.results, key = { it.id }) { card ->
                            SearchResultRow(card, selected = chosen?.id == card.id) { chosen = card }
                            HorizontalDivider()
                        }
                    }
                }
            }

            // Destino y cantidad de la carta elegida.
            chosen?.let { card ->
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(card.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Boards.editable.forEach { b ->
                                FilterChip(selected = board == b, onClick = { board = b }, label = { Text(Boards.label(b)) })
                            }
                        }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.quantity, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { if (quantity > 1) quantity-- }) { Text("−") }
                            Text("$quantity")
                            TextButton(onClick = { quantity++ }) { Text("+") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                vm.addCard(card, board, quantity)
                                chosen = null
                                quantity = 1
                            }) { Text(t.add) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(card: ScryCard, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 78.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = card.imageUrl(ScryfallImages.Size.SMALL),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card.displayName != card.name) {
                Text(card.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            ManaText(
                listOfNotNull(
                    card.manaCost?.ifBlank { null },
                    card.typeLine,
                ).joinToString("  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(card.set.uppercase(), card.lang.takeIf { it != "en" }?.uppercase()).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
