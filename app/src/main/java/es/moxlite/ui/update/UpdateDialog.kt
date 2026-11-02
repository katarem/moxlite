package es.moxlite.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.moxlite.data.update.AppUpdater
import es.moxlite.data.update.UpdateInfo
import es.moxlite.data.update.UpdateState

/**
 * Comprueba si hay versión nueva cada vez que la app pasa a primer plano (con un mínimo de horas
 * entre comprobaciones) y muestra el aviso. Va en la raíz de la navegación.
 */
@Composable
fun UpdatePrompt(updater: AppUpdater) {
    LifecycleStartEffect(updater) {
        updater.checkIfDue()
        onStopOrDispose { }
    }

    val state by updater.state.collectAsStateWithLifecycle()
    val visible by updater.dialogVisible.collectAsStateWithLifecycle()
    if (!visible) return

    when (val s = state) {
        is UpdateState.Available -> UpdateDialog(
            info = s.info,
            current = updater.currentVersion,
            onDismiss = updater::dismiss,
            confirm = { Button(onClick = { updater.download(s.info) }) { Text("Actualizar") } },
            dismiss = {
                Row {
                    TextButton(onClick = { updater.skip(s.info) }) { Text("Omitir") }
                    TextButton(onClick = updater::dismiss) { Text("Más tarde") }
                }
            },
        )

        is UpdateState.Downloading -> UpdateDialog(
            info = s.info,
            current = updater.currentVersion,
            onDismiss = updater::dismiss,
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val progress = s.progress
                    Text(progress?.let { "Descargando… ${(it * 100).toInt()} %" } ?: "Descargando…")
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirm = { TextButton(onClick = updater::dismiss) { Text("Ocultar") } },
            dismiss = { TextButton(onClick = updater::cancelDownload) { Text("Cancelar") } },
        )

        is UpdateState.ReadyToInstall -> UpdateDialog(
            info = s.info,
            current = updater.currentVersion,
            onDismiss = updater::dismiss,
            body = {
                Text(
                    "Descargada. Si Android lo pide, permite instalar aplicaciones desde Moxlite y vuelve a pulsar Instalar.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirm = { Button(onClick = { updater.install(s.info, s.apk) }) { Text("Instalar") } },
            dismiss = { TextButton(onClick = updater::dismiss) { Text("Más tarde") } },
        )

        is UpdateState.Failed -> {
            val info = s.info
            AlertDialog(
                onDismissRequest = updater::dismiss,
                title = { Text("Actualización") },
                text = { Text(s.message) },
                confirmButton = {
                    if (info != null) {
                        Button(onClick = { updater.download(info) }) { Text("Reintentar") }
                    } else {
                        TextButton(onClick = updater::dismiss) { Text("Cerrar") }
                    }
                },
                dismissButton = {
                    if (info != null) {
                        Row {
                            TextButton(onClick = { updater.openReleasePage(info) }) { Text("Abrir en GitHub") }
                            TextButton(onClick = updater::dismiss) { Text("Cerrar") }
                        }
                    }
                },
            )
        }

        else -> Unit
    }
}

@Composable
private fun UpdateDialog(
    info: UpdateInfo,
    current: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: @Composable () -> Unit,
    body: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Moxlite ${info.version} disponible") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tienes la $current.", style = MaterialTheme.typography.bodyMedium)
                if (body != null) {
                    body()
                } else if (info.notes.isNotBlank()) {
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = confirm,
        dismissButton = dismiss,
    )
}
