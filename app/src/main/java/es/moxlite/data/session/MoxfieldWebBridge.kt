package es.moxlite.data.session

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import es.moxlite.data.remote.Http
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Conecta un WebView con moxfield.com para:
 *  1. Capturar el token Bearer que usa el frontend (sesión para la app).
 *  2. Registrar las llamadas a la API (descubrir endpoints de escritura).
 *  3. Compartir cookies (cf_clearance) con OkHttp vía [WebViewCookieJar].
 *
 * El puente JS está restringido por origen a moxfield.com: otra web cargada en el WebView
 * no puede llamarlo.
 */
class MoxfieldWebBridge(
    private val session: SessionManager,
    private val log: ApiTrafficLog,
) {
    private var jsHookActive = false

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    fun attach(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Mismo UA que OkHttp: no se toca, OkHttp copia el del WebView.
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        val canListen = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val canInject = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (canListen && canInject) {
            WebViewCompat.addWebMessageListener(
                webView, BRIDGE_NAME, ORIGINS,
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) = onJsMessage(message.data)
                },
            )
            WebViewCompat.addDocumentStartJavaScript(webView, HOOK_JS, ORIGINS)
            jsHookActive = true
        }
    }

    /** Llamar desde WebViewClient.shouldInterceptRequest (respaldo si el hook JS no está). */
    fun onNativeRequest(request: WebResourceRequest) {
        val url = request.url.toString()
        if (!ApiTrafficLog.isMoxfieldApi(url)) return
        request.requestHeaders.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value?.let(::captureBearer)
        if (!jsHookActive) log.record(request.method, url, null, "nativo")
    }

    private fun onJsMessage(data: String?) {
        val obj: JsonObject = runCatching { Http.json.parseToJsonElement(data ?: return).jsonObject }
            .getOrNull() ?: return
        fun str(key: String) = runCatching { obj[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

        val url = str("u") ?: return
        val absolute = if (url.startsWith("http")) url else "https://api2.moxfield.com$url"
        if (!ApiTrafficLog.isMoxfieldApi(absolute)) return

        str("a")?.let(::captureBearer)
        log.record(str("m") ?: "GET", absolute, str("b"), "web")
    }

    private fun captureBearer(header: String) {
        if (header.startsWith("Bearer ", ignoreCase = true)) {
            session.onTokenCaptured(header.substring(7).trim())
        }
    }

    companion object {
        private const val BRIDGE_NAME = "MoxliteBridge"
        val ORIGINS = setOf("https://moxfield.com", "https://www.moxfield.com")

        const val SIGN_IN_URL = "https://moxfield.com/account/signin?redirect=%2Fdecks%2Fpersonal"
        const val HOME_URL = "https://moxfield.com/"

        /**
         * Envuelve fetch y XMLHttpRequest al inicio del documento. Solo envía al puente
         * método, URL, cabecera Authorization y cuerpo (redactado después en Kotlin).
         */
        private val HOOK_JS = """
            (function () {
              if (window.__moxlite) return; window.__moxlite = true;
              function send(m, u, a, b) {
                try {
                  if (!u) return;
                  var body = (b == null) ? '' : (typeof b === 'string' ? b : '[cuerpo no textual]');
                  MoxliteBridge.postMessage(JSON.stringify({ m: String(m || 'GET'), u: String(u), a: a || null, b: body }));
                } catch (e) {}
              }
              function authOf(h) {
                if (!h) return null;
                try {
                  if (typeof Headers !== 'undefined' && h instanceof Headers) return h.get('Authorization');
                  if (Array.isArray(h)) { for (var i = 0; i < h.length; i++) if (String(h[i][0]).toLowerCase() === 'authorization') return h[i][1]; return null; }
                  for (var k in h) if (k.toLowerCase() === 'authorization') return h[k];
                } catch (e) {}
                return null;
              }
              var origFetch = window.fetch;
              if (origFetch) {
                window.fetch = function (input, init) {
                  try {
                    var url = (typeof input === 'string') ? input : (input && input.url);
                    var method = (init && init.method) || (input && input.method) || 'GET';
                    var auth = authOf(init && init.headers) || authOf(input && input.headers);
                    send(method, url, auth, init && init.body);
                  } catch (e) {}
                  return origFetch.apply(this, arguments);
                };
              }
              var P = XMLHttpRequest.prototype, open = P.open, setH = P.setRequestHeader, sendX = P.send;
              P.open = function (m, u) { this.__mx = { m: m, u: u, a: null }; return open.apply(this, arguments); };
              P.setRequestHeader = function (k, v) {
                try { if (this.__mx && String(k).toLowerCase() === 'authorization') this.__mx.a = v; } catch (e) {}
                return setH.apply(this, arguments);
              };
              P.send = function (b) {
                try { if (this.__mx) send(this.__mx.m, this.__mx.u, this.__mx.a, b); } catch (e) {}
                return sendX.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
