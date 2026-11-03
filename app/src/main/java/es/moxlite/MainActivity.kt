package es.moxlite

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import es.moxlite.data.remote.moxfield.MoxfieldIds
import es.moxlite.data.update.UpdateNotifications
import es.moxlite.domain.DeckSnapshot
import es.moxlite.domain.ShareLink
import es.moxlite.ui.AppNav
import es.moxlite.ui.common.ProvideStrings
import es.moxlite.ui.theme.MoxliteTheme

/** Lo que llega desde fuera: un enlace de mazo o un texto compartido para importar. */
sealed interface IncomingLink {
    data class Deck(val publicId: String) : IncomingLink
    data class ImportText(val text: String) : IncomingLink

    /** Enlace compartido desde Moxlite: lleva una copia del mazo. */
    data class Shared(val snapshot: DeckSnapshot) : IncomingLink
}

class MainActivity : ComponentActivity() {

    private val incoming = mutableStateOf<IncomingLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming.value = parse(intent)
        handleUpdateNotification(intent)
        setContent {
            MoxliteTheme {
                ProvideStrings {
                    AppNav(
                        incoming = incoming.value,
                        onIncomingHandled = { incoming.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = parse(intent)
        handleUpdateNotification(intent)
    }

    /** Al tocar la notificación de versión nueva: comprobar y mostrar el diálogo. */
    private fun handleUpdateNotification(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(UpdateNotifications.EXTRA_SHOW_UPDATE, false)) return
        intent.removeExtra(UpdateNotifications.EXTRA_SHOW_UPDATE) // no repetir al rotar la pantalla
        (application as MoxliteApp).container.updater.checkNow()
    }

    private fun parse(intent: Intent?): IncomingLink? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: return null
                ShareLink.parse(url)?.let(IncomingLink::Shared)
                    ?: MoxfieldIds.parsePublicId(url)?.let(IncomingLink::Deck)
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                ShareLink.parse(text)?.let(IncomingLink::Shared)
                    ?: if (text.contains("moxfield.com/decks/")) MoxfieldIds.parsePublicId(text)?.let(IncomingLink::Deck)
                    else text.takeIf { it.isNotBlank() }?.let(IncomingLink::ImportText)
            }
            else -> null
        }
    }
}
