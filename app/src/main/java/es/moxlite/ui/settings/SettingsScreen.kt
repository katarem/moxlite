package es.moxlite.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.moxlite.data.remote.Http
import es.moxlite.data.sync.DeckOp
import es.moxlite.data.sync.SyncWorker
import es.moxlite.data.sync.describe
import es.moxlite.ui.common.SectionHeader
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.web.WebMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(openWeb: (WebMode) -> Unit, openApiLog: () -> Unit, openDeck: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by container.session.state.collectAsStateWithLifecycle()
    val pending by container.deckRepository.observePendingOps().collectAsStateWithLifecycle(emptyList())
    var userName by remember(session.userName) { mutableStateOf(session.userName) }
    var cacheBytes by remember { mutableLongStateOf(container.imageLoader.diskCache?.size ?: 0L) }

    Scaffold(topBar = { TopAppBar(title = { Text("Ajustes") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader("Cuenta de Moxfield") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val status = when {
                        session.isLoggedIn -> "Sesión activa" + (session.expiresAt?.let {
                            " hasta " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                        } ?: "")
                        session.accessToken != null || session.isExpired -> "Sesión caducada"
                        else -> "Sin sesión (solo mazos públicos)"
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = userName,
                        onValueChange = { userName = it },
                        label = { Text("Tu usuario de Moxfield") },
                        supportingText = { Text("Se usa para listar tus mazos públicos en \"Mis mazos\"") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { container.session.setUserName(userName) }, enabled = userName != session.userName) {
                            Text("Guardar usuario")
                        }
                        Button(onClick = { openWeb(WebMode.LOGIN) }) {
                            Text(if (session.isLoggedIn) "Renovar sesión" else "Iniciar sesión")
                        }
                    }
                    if (session.accessToken != null) {
                        OutlinedButton(onClick = { container.session.logout() }) { Text("Cerrar sesión y borrar cookies") }
                    }
                }
            }

            item { SectionHeader("Moxfield web") }
            item {
                ListItem(
                    headlineContent = { Text("Abrir Moxfield dentro de la app") },
                    supportingContent = { Text("Para pasar la verificación de Cloudflare o usar funciones que la app aún no tiene") },
                    modifier = Modifier.clickable { openWeb(WebMode.BROWSE) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Registro de API") },
                    supportingContent = { Text("Llamadas que hace la web: sirve para descubrir los endpoints de escritura") },
                    modifier = Modifier.clickable(onClick = openApiLog),
                )
            }

            item {
                SectionHeader("Cambios pendientes de subir", "${pending.size}")
            }
            if (pending.isEmpty()) {
                item { Text("No hay cambios pendientes.", modifier = Modifier.padding(horizontal = 16.dp)) }
            } else {
                item {
                    Text(
                        if (container.deckRepository.canWriteRemote) "Se suben automáticamente con conexión."
                        else "La subida a Moxfield aún no está implementada: los cambios se conservan en el móvil y puedes exportar el mazo como texto.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(pending, key = { it.id }) { op ->
                    val text = runCatching { Http.json.decodeFromString(DeckOp.serializer(), op.payload).describe() }
                        .getOrDefault(op.type)
                    ListItem(
                        headlineContent = { Text(text) },
                        supportingContent = { Text(op.deckId + (op.lastError?.let { " · error: $it" } ?: "")) },
                        modifier = Modifier.clickable { openDeck(op.deckId) },
                    )
                }
                item {
                    OutlinedButton(onClick = { SyncWorker.runNow(context) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text("Sincronizar ahora")
                    }
                }
            }

            item { SectionHeader("Almacenamiento") }
            item {
                ListItem(
                    headlineContent = { Text("Imágenes de cartas guardadas") },
                    supportingContent = { Text("%.1f MB".format(cacheBytes / 1_048_576.0)) },
                    trailingContent = {
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { container.imageLoader.diskCache?.clear() }
                                cacheBytes = 0
                            }
                        }) { Icon(Icons.Outlined.Delete, "Vaciar") }
                    },
                )
            }

            item { SectionHeader("Acerca de") }
            item {
                Text(
                    "Cliente no oficial. Usa la API interna de Moxfield (puede cambiar sin aviso) y las imágenes y datos de cartas de Scryfall. " +
                        "Sin relación con Moxfield, Scryfall ni Wizards of the Coast.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiLogScreen(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val calls by container.apiLog.calls.collectAsStateWithLifecycle()
    var onlyWrites by remember { mutableStateOf(true) }
    val shown = if (onlyWrites) calls.filter { it.method != "GET" && it.method != "OPTIONS" } else calls

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registro de API") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Atrás") } },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, container.apiLog.export()),
                                "Compartir registro",
                            )
                        )
                    }) { Icon(Icons.Outlined.Share, "Compartir") }
                    IconButton(onClick = { container.apiLog.clear() }) { Icon(Icons.Outlined.Delete, "Vaciar") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "1. Abre Moxfield web desde Ajustes con la sesión iniciada.\n" +
                            "2. Haz una vez cada acción: crear mazo, añadir carta, cambiar cantidad, mover a banquillo, borrar carta, renombrar, copiar un mazo, borrarlo.\n" +
                            "3. Vuelve aquí y comparte el registro. No incluye tokens ni contraseñas.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { onlyWrites = !onlyWrites }) {
                            Text(if (onlyWrites) "Mostrar también lecturas (GET)" else "Solo escrituras")
                        }
                    }
                }
                HorizontalDivider()
            }
            items(shown.asReversed()) { call ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("${call.time}  ${call.method}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(call.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    if (call.body.isNotBlank()) {
                        Text(call.body.take(600), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
