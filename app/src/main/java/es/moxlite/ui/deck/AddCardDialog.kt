package es.moxlite.ui.deck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.moxlite.data.remote.moxfield.Boards

/**
 * Añadir carta buscando en Scryfall (necesita conexión). La carta se añade en local
 * al momento y queda en la cola para subirla a Moxfield.
 */
@Composable
fun AddCardDialog(vm: DeckViewModel, onDismiss: () -> Unit) {
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    var board by remember { mutableStateOf(Boards.MAIN) }
    var quantity by remember { mutableIntStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir carta") },
        text = {
            Column {
                OutlinedTextField(
                    value = chosen ?: query,
                    onValueChange = { query = it; chosen = null; vm.suggest(it) },
                    label = { Text("Nombre (en inglés)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (chosen == null && suggestions.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(suggestions) { name ->
                            Text(
                                name,
                                modifier = Modifier.fillMaxWidth().clickable { chosen = name }.padding(vertical = 10.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Boards.editable.forEach { b ->
                        FilterChip(selected = board == b, onClick = { board = b }, label = { Text(Boards.label(b)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cantidad", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (quantity > 1) quantity-- }) { Text("−") }
                    Text("$quantity")
                    TextButton(onClick = { quantity++ }) { Text("+") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen != null,
                onClick = { chosen?.let { vm.addCardByName(it, board, quantity) }; onDismiss() },
            ) { Text("Añadir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
