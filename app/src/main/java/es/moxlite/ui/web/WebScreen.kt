package es.moxlite.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.moxlite.data.remote.moxfield.MoxfieldIds
import es.moxlite.data.session.MoxfieldWebBridge
import es.moxlite.data.sync.SyncWorker
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.appContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class WebMode {
    /** Iniciar sesión: en cuanto se detecta la sesión se guarda y se vuelve a la app. */
    LOGIN,

    /** Navegar por Moxfield (también sirve para pasar el reto de Cloudflare). */
    BROWSE,
}

/** Páginas en las que todavía no se ha iniciado sesión. */
private val SIGN_IN_PAGES = listOf("/account/signin", "/account/register", "/account/forgot", "/account/reset")

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(mode: WebMode, startUrl: String?, onBack: () -> Unit, openDeck: (String) -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by container.session.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("Moxfield") }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var loggedIn by remember { mutableStateOf(false) }
    var probeJob by remember { mutableStateOf<Job?>(null) }

    /**
     * Comprueba si ya hay sesión pidiendo un token con las cookies del WebView. Se llama al salir
     * de la página de login y cuando la web manda un token propio.
     */
    fun probe(url: String?) {
        if (mode != WebMode.LOGIN || loggedIn) return
        if (url == null || !url.contains("moxfield.com") || SIGN_IN_PAGES.any { url.contains(it) }) return
        if (probeJob?.isActive == true) return
        probeJob = scope.launch {
            CookieManager.getInstance().flush()
            val ok = container.auth.refresh(force = true, probing = true)
            val state = container.session.state.value
            if (ok && state.hasAccount && !loggedIn) {
                loggedIn = true
                container.settings.setOnboarded(true)
                SyncWorker.runNow(context)
                snackbar.showSnackbar(t.loggedInAs(state.userName))
                delay(700)
                onBack()
            }
        }
    }

    // La web ha usado un token: buena señal de que ya ha entrado.
    LaunchedEffect(session.accessToken) {
        if (session.accessToken != null) probe(currentUrl ?: MoxfieldWebBridge.HOME_URL)
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    val deckInPage = currentUrl?.let(MoxfieldIds::parsePublicId)?.takeIf { currentUrl?.contains("/decks/") == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode == WebMode.LOGIN) t.loginTitle else title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t.close) } },
                actions = {
                    if (deckInPage != null && mode == WebMode.BROWSE) {
                        IconButton(onClick = { openDeck(deckInPage) }) { Icon(Icons.Outlined.Style, t.openDeckInApp) }
                    }
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, t.reload) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (mode == WebMode.LOGIN) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        t.loginHint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
            if (progress) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        container.webBridge.attach(this)
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                container.webBridge.onNativeRequest(request)
                                return null
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                progress = true
                                currentUrl = url
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                progress = false
                                currentUrl = url
                                title = view.title?.takeIf { it.isNotBlank() } ?: "Moxfield"
                                probe(url)
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                // Moxfield es una SPA: las rutas cambian sin recargar.
                                currentUrl = url
                                probe(url)
                            }
                        }
                        loadUrl(
                            startUrl ?: if (mode == WebMode.LOGIN) MoxfieldWebBridge.SIGN_IN_URL else MoxfieldWebBridge.HOME_URL
                        )
                        webView = this
                    }
                },
                onRelease = {
                    CookieManager.getInstance().flush()
                    it.destroy()
                },
            )
        }
    }
}
