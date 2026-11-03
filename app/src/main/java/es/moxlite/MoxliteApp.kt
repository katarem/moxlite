package es.moxlite

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import coil.ImageLoader
import coil.ImageLoaderFactory
import es.moxlite.data.settings.ProxySettings
import es.moxlite.data.settings.ProxyType
import es.moxlite.data.sync.SyncWorker
import es.moxlite.data.update.UpdateNotifications
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

class MoxliteApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedulePeriodic(this)
        UpdateNotifications.createChannel(this)
        container.updater.scheduleBackgroundCheck()

        // Renovar la sesión al abrir (como hace la web) y subir lo que quedara pendiente.
        container.appScope.launch {
            val session = container.session.state.value
            if (session.hasAccount) {
                container.auth.refresh()
                SyncWorker.pushSoon(this@MoxliteApp)
            }
        }

        var first = true
        container.settings.proxy.onEach { proxy ->
            // Al arrancar solo se toca el WebView si hay proxy configurado.
            if (!first || proxy.isUsable) ContextCompat.getMainExecutor(this@MoxliteApp).execute { applyWebViewProxy(proxy) }
            first = false
        }.launchIn(container.appScope)
    }

    /** El WebView (login, Moxfield web) usa el mismo proxy que OkHttp, si el sistema lo permite. */
    private fun applyWebViewProxy(proxy: ProxySettings) {
        runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
            val executor = Executor { it.run() }
            val controller = ProxyController.getInstance()
            if (proxy.isUsable) {
                val host = "${proxy.host.trim()}:${proxy.port}"
                val rule = if (proxy.type == ProxyType.SOCKS) "socks://$host" else host
                controller.setProxyOverride(ProxyConfig.Builder().addProxyRule(rule).build(), executor) {}
            } else {
                controller.clearProxyOverride(executor) {}
            }
        }
    }

    /** Coil usa este cargador (caché en disco persistente) en toda la app. */
    override fun newImageLoader(): ImageLoader = container.imageLoader
}
