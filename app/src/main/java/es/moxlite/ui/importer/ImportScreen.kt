package es.moxlite.ui.importer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import es.moxlite.data.remote.userMessage
import es.moxlite.domain.DeckText
import es.moxlite.ui.common.FORMATS
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.appContainer
import kotlinx.coroutines.launch

/** Datos con los que se abre la pantalla (texto compartido, enlace de Moxlite…). */
data class ImportDraft(val name: String = "", val format: String = "commander", val text: String = "")

/** Crea un mazo a partir de una lista en texto (export de Moxfield, Arena, MTGO…) y lo sube a Moxfield. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(draft: ImportDraft, onBack: () -> Unit, onImported: (String) -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(draft.name) }
    var format by remember { mutableStateOf(draft.format) }
    var text by remember { mutableStateOf(draft.text) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val parsedCount = remember(text) { DeckText.parse(text).sumOf { it.quantity } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.importList) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t.back) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(t.deckName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FORMATS.forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f) }) }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(t.list) },
                placeholder = { Text("Commander\n1 Atraxa, Praetors' Voice\n\nDeck\n1 Sol Ring (C21) 263\n4 Island") },
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(t.cardsRecognized(parsedCount), style = MaterialTheme.typography.labelMedium)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && parsedCount > 0 && name.isNotBlank(),
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        runCatching { container.deckRepository.importText(name, format, text) }
                            .onSuccess { r ->
                                if (r.unresolved.isNotEmpty()) {
                                    message = t.notFoundInScryfall(r.unresolved.joinToString())
                                }
                                onImported(r.deckId)
                            }
                            .onFailure { message = it.userMessage() }
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp)) else Text(t.importAction)
            }
        }
    }
}
