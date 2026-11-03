package es.moxlite.ui.update

import es.moxlite.ui.common.LocalStrings
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
    // Android 13+: pedir una vez el permiso para la notificación de versión nueva.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(updater) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && updater.shouldAskNotificationPermission()) {
            updater.markNotificationPermissionAsked()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LifecycleStartEffect(updater) {
        updater.checkIfDue()
        onStopOrDispose { }
    }

    val t = LocalStrings.current
    val state by updater.state.collectAsStateWithLifecycle()
    val visible by updater.dialogVisible.collectAsStateWithLifecycle()
    if (!visible) return

    when (val s = state) {
        is UpdateState.Available -> UpdateDialog(
            info = s.info,
            current = updater.currentVersion,
            onDismiss = updater::dismiss,
            confirm = { Button(onClick = { updater.download(s.info) }) { Text(t.updUpdate) } },
            dismiss = {
                Row {
                    TextButton(onClick = { updater.skip(s.info) }) { Text(t.updSkip) }
                    TextButton(onClick = updater::dismiss) { Text(t.later) }
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
                    Text(progress?.let { t.updDownloadingPercent((it * 100).toInt()) } ?: t.updDownloading)
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirm = { TextButton(onClick = updater::dismiss) { Text(t.hide) } },
            dismiss = { TextButton(onClick = updater::cancelDownload) { Text(t.cancel) } },
        )

        is UpdateState.ReadyToInstall -> UpdateDialog(
            info = s.info,
            current = updater.currentVersion,
            onDismiss = updater::dismiss,
            body = {
                Text(
                    t.updDownloadedHint,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirm = { Button(onClick = { updater.install(s.info, s.apk) }) { Text(t.updInstall) } },
            dismiss = { TextButton(onClick = updater::dismiss) { Text(t.later) } },
        )

        is UpdateState.Failed -> {
            val info = s.info
            AlertDialog(
                onDismissRequest = updater::dismiss,
                title = { Text(t.updTitle) },
                text = { Text(s.message) },
                confirmButton = {
                    if (info != null) {
                        Button(onClick = { updater.download(info) }) { Text(t.retry) }
                    } else {
                        TextButton(onClick = updater::dismiss) { Text(t.close) }
                    }
                },
                dismissButton = {
                    if (info != null) {
                        Row {
                            TextButton(onClick = { updater.openReleasePage(info) }) { Text(t.updOpenGithub) }
                            TextButton(onClick = updater::dismiss) { Text(t.close) }
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
    val t = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.updAvailableTitle(info.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t.updYouHave(current), style = MaterialTheme.typography.bodyMedium)
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
