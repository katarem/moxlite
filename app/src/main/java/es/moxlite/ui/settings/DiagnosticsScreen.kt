package es.moxlite.ui.settings

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.moxlite.data.diagnostics.DiagnosticLog
import es.moxlite.data.remote.userMessage
import es.moxlite.data.sync.SyncWorker
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Live view of the [DiagnosticLog], with export (state + whole log as a file) and "sync now". */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val lines by container.diagnostics.recent.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()

    DiagnosticsContent(
        lines = lines,
        canSync = session.hasAccount,
        snackbar = snackbar,
        onBack = onBack,
        onExport = {
            scope.launch {
                try {
                    context.startActivity(container.diagnosticsCollector.exportIntent(t.shareLog))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    container.diagnostics.e(DiagnosticLog.TAG, "Report export failed", e)
                    snackbar.showSnackbar(e.userMessage())
                }
            }
        },
        onSyncNow = { SyncWorker.runNow(context) },
        onClear = container.diagnostics::clear,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsContent(
    lines: List<String>,
    canSync: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onSyncNow: () -> Unit,
    onClear: () -> Unit,
) {
    val t = LocalStrings.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.diagnostics) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t.back) } },
                actions = {
                    IconButton(onClick = onExport) { Icon(Icons.Outlined.Share, t.exportDiagnostics) }
                    IconButton(onClick = onClear) { Icon(Icons.Outlined.Delete, t.clear) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.diagnosticsInstructions, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onExport) { Text(t.exportDiagnostics) }
                        if (canSync) OutlinedButton(onClick = onSyncNow) { Text(t.syncNow) }
                    }
                }
                HorizontalDivider()
            }
            if (lines.isEmpty()) {
                item { Text(t.diagnosticsEmpty, modifier = Modifier.padding(16.dp)) }
            }
            // Newest first: what just happened is what matters when checking a sync.
            items(lines.asReversed()) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        line.contains(" E/") -> MaterialTheme.colorScheme.error
                        line.contains(" W/") -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                )
            }
        }
    }
}
