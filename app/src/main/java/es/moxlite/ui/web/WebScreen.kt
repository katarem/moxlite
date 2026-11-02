package es.moxlite.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.moxlite.data.remote.moxfield.MoxfieldIds
import es.moxlite.data.session.MoxfieldWebBridge
import es.moxlite.ui.common.appContainer
import kotlinx.coroutines.delay

enum class WebMode {
    /** Iniciar sesión: se cierra solo al capturar el token. */
    LOGIN,

    /** Navegar por Moxfield (también sirve para pasar el reto de Cloudflare). */
    BROWSE,
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(mode: WebMode, startUrl: String?, onBack: () -> Unit, openDeck: (String) -> Unit) {
    val container = appContainer()
    val session by container.session.state.collectAsStateWithLifecycle()
    val wasLoggedIn = remember { session.isLoggedIn }
    val snackbar = remember { SnackbarHostState() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("Moxfield") }
    var currentUrl by remember { mutableStateOf<String?>(null) }

    // En modo login: cuando aparece el token, avisa y vuelve.
    LaunchedEffect(session.isLoggedIn) {
        if (mode == WebMode.LOGIN && session.isLoggedIn && !wasLoggedIn) {
            snackbar.showSnackbar("Sesión iniciada" + if (session.userName.isNotBlank()) " como @${session.userName}" else "")
            delay(800)
            onBack()
        }
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    val deckInPage = currentUrl?.let(MoxfieldIds::parsePublicId)?.takeIf { currentUrl?.contains("/decks/") == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode == WebMode.LOGIN) "Iniciar sesión en Moxfield" else title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Cerrar") } },
                actions = {
                    if (deckInPage != null) {
                        IconButton(onClick = { openDeck(deckInPage) }) { Icon(Icons.Outlined.Style, "Abrir este mazo en la app") }
                    }
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, "Recargar") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (mode == WebMode.LOGIN) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        "Entra con usuario y contraseña de Moxfield. El inicio con Google no funciona dentro de apps. " +
                            "La app no ve tu contraseña: solo guarda la sesión que Moxfield crea al entrar.",
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
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                // Moxfield es una SPA: las rutas cambian sin recargar.
                                currentUrl = url
                            }
                        }
                        loadUrl(
                            startUrl ?: if (mode == WebMode.LOGIN) MoxfieldWebBridge.SIGN_IN_URL else MoxfieldWebBridge.HOME_URL
                        )
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
