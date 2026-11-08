package es.moxlite.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.moxlite.BuildConfig
import es.moxlite.data.settings.CardLanguages
import es.moxlite.data.settings.ProxySettings
import es.moxlite.data.settings.ProxyType
import es.moxlite.data.update.UpdateNotifications
import es.moxlite.data.update.UpdateState
import es.moxlite.i18n.AppLanguage
import es.moxlite.ui.account.AccountRow
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.SectionHeader
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.web.WebMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    openWeb: (WebMode) -> Unit,
    openApiLog: () -> Unit,
    openDiagnostics: () -> Unit,
    openAccount: () -> Unit,
) {
    val container = appContainer()
    val t = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = container.settings
    val appLanguage by settings.appLanguage.collectAsStateWithLifecycle()
    val cardLanguage by settings.cardLanguage.collectAsStateWithLifecycle()
    val autoSaveMine by settings.autoSaveMine.collectAsStateWithLifecycle()
    val proxy by settings.proxy.collectAsStateWithLifecycle()
    var cacheBytes by remember { mutableLongStateOf(container.imageLoader.diskCache?.size ?: 0L) }
    val update by container.updater.state.collectAsStateWithLifecycle()
    val notifyUpdates by container.updater.backgroundEnabled.collectAsStateWithLifecycle()
    var canNotify by remember { mutableStateOf(UpdateNotifications.canNotify(context)) }
    var cardLanguageMenu by remember { mutableStateOf(false) }
    var proxyDialog by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        canNotify = UpdateNotifications.canNotify(context)
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = granted && UpdateNotifications.canNotify(context)
        // Si Android ya no muestra el diálogo (denegado antes), llevar a los ajustes de la app.
        if (!granted) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
    }
    val askNotifications = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(t.settings) }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader(t.account) }
            item { AccountRow(onClick = openAccount) }

            item { SectionHeader(t.languages) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(t.appLanguage, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = appLanguage == lang,
                                onClick = { settings.setAppLanguage(lang) },
                                label = { Text(t.appLanguageLabel(lang)) },
                            )
                        }
                    }
                }
            }
            item {
                Box {
                    ListItem(
                        headlineContent = { Text(t.cardLanguage) },
                        supportingContent = { Text(t.cardLanguageName(cardLanguage) + " · " + t.cardLanguageHint) },
                        modifier = Modifier.clickable { cardLanguageMenu = true },
                    )
                    DropdownMenu(expanded = cardLanguageMenu, onDismissRequest = { cardLanguageMenu = false }) {
                        CardLanguages.all.forEach { code ->
                            DropdownMenuItem(
                                text = { Text(t.cardLanguageName(code)) },
                                onClick = { settings.setCardLanguage(code); cardLanguageMenu = false },
                            )
                        }
                    }
                }
            }

            item { SectionHeader(t.offlineAndConnection) }
            item {
                ListItem(
                    headlineContent = { Text(t.autoSaveMine) },
                    supportingContent = { Text(t.autoSaveMineHint) },
                    trailingContent = { Switch(checked = autoSaveMine, onCheckedChange = settings::setAutoSaveMine) },
                    modifier = Modifier.clickable { settings.setAutoSaveMine(!autoSaveMine) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t.proxy) },
                    supportingContent = {
                        Text(if (proxy.isUsable) "${proxy.type.name} ${proxy.host}:${proxy.port}" else t.proxyHint)
                    },
                    trailingContent = {
                        Switch(
                            checked = proxy.enabled,
                            onCheckedChange = { on ->
                                if (on && proxy.host.isBlank()) proxyDialog = true
                                else settings.setProxy(proxy.copy(enabled = on))
                            },
                        )
                    },
                    modifier = Modifier.clickable { proxyDialog = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t.openLinksWithApp) },
                    supportingContent = { Text(t.openLinksWithAppHint) },
                    modifier = Modifier.clickable { openLinkSettings(context) },
                )
            }

            item { SectionHeader(t.moxfieldWeb) }
            item {
                ListItem(
                    headlineContent = { Text(t.openMoxfieldInApp) },
                    supportingContent = { Text(t.openMoxfieldInAppHint) },
                    modifier = Modifier.clickable { openWeb(WebMode.BROWSE) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t.apiLog) },
                    supportingContent = { Text(t.apiLogHint) },
                    modifier = Modifier.clickable(onClick = openApiLog),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t.diagnostics) },
                    supportingContent = { Text(t.diagnosticsHint) },
                    modifier = Modifier.clickable(onClick = openDiagnostics),
                )
            }

            item { SectionHeader(t.storage) }
            item {
                ListItem(
                    headlineContent = { Text(t.savedImages) },
                    supportingContent = { Text("%.1f MB".format(cacheBytes / 1_048_576.0)) },
                    trailingContent = {
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { container.imageLoader.diskCache?.clear() }
                                cacheBytes = 0
                            }
                        }) { Icon(Icons.Outlined.Delete, t.clear) }
                    },
                )
            }

            item { SectionHeader(t.about) }
            item {
                val status = when (val u = update) {
                    UpdateState.Checking -> t.updChecking
                    UpdateState.UpToDate -> t.updUpToDate
                    is UpdateState.Available -> t.updAvailable(u.info.version)
                    is UpdateState.Downloading -> t.updDownloadingVersion(u.info.version)
                    is UpdateState.ReadyToInstall -> t.updReady(u.info.version)
                    is UpdateState.Failed -> u.message
                    UpdateState.Idle -> t.updTapToCheck
                }
                ListItem(
                    headlineContent = { Text(t.version(BuildConfig.VERSION_NAME)) },
                    supportingContent = { Text(status) },
                    modifier = Modifier.clickable(enabled = update !is UpdateState.Checking) {
                        when (update) {
                            is UpdateState.Available, is UpdateState.Downloading, is UpdateState.ReadyToInstall ->
                                container.updater.showDialog()
                            else -> container.updater.checkNow()
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t.notifyUpdates) },
                    supportingContent = {
                        Text(
                            when {
                                !notifyUpdates -> t.notifyUpdatesOff
                                !canNotify -> t.notifyUpdatesBlocked
                                else -> t.notifyUpdatesOn
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = notifyUpdates,
                            onCheckedChange = { enabled ->
                                container.updater.setBackgroundEnabled(enabled)
                                if (enabled && !canNotify) askNotifications()
                            },
                        )
                    },
                    modifier = Modifier.clickable {
                        if (notifyUpdates && !canNotify) askNotifications()
                        else container.updater.setBackgroundEnabled(!notifyUpdates)
                    },
                )
            }
            item {
                Text(
                    t.disclaimer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (proxyDialog) {
        ProxyDialog(
            initial = proxy,
            onDismiss = { proxyDialog = false },
            onSave = { settings.setProxy(it); proxyDialog = false },
        )
    }
}

/** Android 12+: los enlaces https sin verificar no abren la app hasta activarlo a mano. */
private fun openLinkSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun ProxyDialog(initial: ProxySettings, onDismiss: () -> Unit, onSave: (ProxySettings) -> Unit) {
    val t = LocalStrings.current
    var type by remember { mutableStateOf(initial.type) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(if (initial.port > 0) initial.port.toString() else "") }
    val portNumber = port.toIntOrNull()
    val valid = host.isNotBlank() && portNumber != null && portNumber in 1..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.proxy) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.proxyExplanation, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProxyType.entries.forEach { pt ->
                        FilterChip(selected = type == pt, onClick = { type = pt }, label = { Text(pt.name) })
                    }
                }
                OutlinedTextField(value = host, onValueChange = { host = it.trim() }, label = { Text(t.proxyHost) }, singleLine = true)
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(t.proxyPort) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(ProxySettings(true, type, host, portNumber ?: 0)) }) { Text(t.saveAndEnable) }
        },
        dismissButton = {
            Row {
                if (initial.enabled) TextButton(onClick = { onSave(initial.copy(enabled = false)) }) { Text(t.disable) }
                TextButton(onClick = onDismiss) { Text(t.cancel) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiLogScreen(onBack: () -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val context = LocalContext.current
    val calls by container.apiLog.calls.collectAsStateWithLifecycle()
    var onlyWrites by remember { mutableStateOf(true) }
    val shown = if (onlyWrites) calls.filter { it.method != "GET" && it.method != "OPTIONS" } else calls

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.apiLog) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t.back) } },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, container.apiLog.export()),
                                t.shareLog,
                            )
                        )
                    }) { Icon(Icons.Outlined.Share, t.share) }
                    IconButton(onClick = { container.apiLog.clear() }) { Icon(Icons.Outlined.Delete, t.clear) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(t.apiLogInstructions, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { onlyWrites = !onlyWrites }) {
                            Text(if (onlyWrites) t.showReadsToo else t.onlyWrites)
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
