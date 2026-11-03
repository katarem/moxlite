package es.moxlite.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.moxlite.data.remote.Http
import es.moxlite.data.remote.userMessage
import es.moxlite.data.sync.DeckOp
import es.moxlite.data.sync.SyncWorker
import es.moxlite.data.sync.describe
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.SectionHeader
import es.moxlite.ui.common.appContainer
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Primera vez: iniciar sesión una vez y ya queda todo configurado (o seguir sin cuenta). */
@Composable
fun WelcomeScreen(openLogin: () -> Unit, onDone: () -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val session by container.session.state.collectAsStateWithLifecycle()

    LaunchedEffect(session.hasAccount) { if (session.hasAccount) onDone() }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
            Text("Moxlite", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(t.welcomeText, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Button(onClick = openLogin, modifier = Modifier.fillMaxWidth()) { Text(t.loginWithMoxfield) }
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(t.continueWithoutAccount) }
            Text(
                t.loginPrivacyNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** La cuenta de Moxfield: estado de la sesión, cambios pendientes y cerrar sesión. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    openLogin: () -> Unit,
    openDeck: (String) -> Unit,
    openUser: (String) -> Unit,
) {
    val container = appContainer()
    val t = LocalStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val session by container.session.state.collectAsStateWithLifecycle()
    val pending by container.deckRepository.observePendingOps().collectAsStateWithLifecycle(emptyList())
    var syncing by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.account) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t.back) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (session.userName.isNotBlank()) {
                            Text(
                                session.userName.take(1).uppercase(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (session.userName.isNotBlank()) "@${session.userName}" else t.notLoggedIn,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val status = when {
                        session.needsLogin -> t.sessionClosedByMoxfield
                        session.hasValidToken -> t.sessionActive + (session.expiresAt?.let {
                            " · " + t.tokenRenewsAt(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
                        } ?: "")
                        session.hasAccount -> t.sessionWillRenew
                        else -> t.loginExplanation
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (session.needsLogin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    if (!session.hasAccount) {
                        Button(onClick = openLogin) { Text(if (session.needsLogin) t.loginAgain else t.loginWithMoxfield) }
                    }
                }
            }

            if (session.hasAccount) {
                item {
                    ListItem(
                        headlineContent = { Text(t.syncMyDecks) },
                        supportingContent = { Text(t.syncMyDecksHint) },
                        leadingContent = {
                            if (syncing) CircularProgressIndicator(Modifier.size(24.dp)) else Icon(Icons.Outlined.Sync, null)
                        },
                        modifier = Modifier.clickable(enabled = !syncing) {
                            syncing = true
                            scope.launch {
                                runCatching { container.deckRepository.syncMine(download = container.settings.autoSaveMine.value) }
                                    .onSuccess { snackbar.showSnackbar(t.decksFound(it.size)) }
                                    .onFailure { snackbar.showSnackbar(it.userMessage()) }
                                syncing = false
                            }
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(t.viewPublicProfile) },
                        leadingContent = { Icon(Icons.Outlined.Person, null) },
                        modifier = Modifier.clickable { openUser(session.userName) },
                    )
                }
            }

            item { SectionHeader(t.pendingChanges, "${pending.size}") }
            if (pending.isEmpty()) {
                item { Text(t.noPendingChanges, modifier = Modifier.padding(horizontal = 16.dp)) }
            } else {
                item {
                    Text(
                        if (session.hasAccount) t.pendingAutoUpload else t.pendingNeedsLogin,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(pending, key = { it.id }) { op ->
                    val text = runCatching { Http.json.decodeFromString(DeckOp.serializer(), op.payload).describe() }
                        .getOrDefault(op.type)
                    ListItem(
                        headlineContent = { Text(text) },
                        supportingContent = { Text(op.deckId + (op.lastError?.let { " · ${t.error}: $it" } ?: "")) },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { container.deckRepository.deletePendingOp(op.id) } }) {
                                Icon(Icons.Outlined.Close, t.discardChange)
                            }
                        },
                        modifier = Modifier.clickable { openDeck(op.deckId) },
                    )
                }
                if (session.hasAccount) {
                    item {
                        OutlinedButton(onClick = { SyncWorker.runNow(context) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(t.syncNow)
                        }
                    }
                }
            }

            if (session.hasAccount || session.needsLogin) {
                item {
                    Surface(Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedButton(onClick = { confirmLogout = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Logout, null)
                            Spacer(Modifier.width(8.dp))
                            Text(t.logout)
                        }
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(t.logout) },
            text = { Text(if (pending.isEmpty()) t.logoutConfirm else t.logoutConfirmPending(pending.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    container.session.logout()
                }) { Text(t.logout) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(t.cancel) } },
        )
    }
}

/** Fila resumida de la cuenta para Ajustes y la cabecera de "Mis mazos". */
@Composable
fun AccountRow(onClick: () -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val session by container.session.state.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(if (session.hasAccount) "@${session.userName}" else t.notLoggedIn) },
        supportingContent = {
            Text(
                when {
                    session.needsLogin -> t.sessionClosedByMoxfield
                    session.hasAccount -> t.accountRowHint
                    else -> t.loginExplanation
                }
            )
        },
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (session.userName.isNotBlank()) {
                    Text(session.userName.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
