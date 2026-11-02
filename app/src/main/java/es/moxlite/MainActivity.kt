package es.moxlite

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import es.moxlite.data.remote.moxfield.MoxfieldIds
import es.moxlite.data.update.UpdateNotifications
import es.moxlite.ui.AppNav
import es.moxlite.ui.theme.MoxliteTheme

/** Lo que llega desde fuera: un enlace de mazo o un texto compartido para importar. */
sealed interface IncomingLink {
    data class Deck(val publicId: String) : IncomingLink
    data class ImportText(val text: String) : IncomingLink
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
                AppNav(
                    incoming = incoming.value,
                    onIncomingHandled = { incoming.value = null },
                )
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
            Intent.ACTION_VIEW -> intent.dataString?.let(MoxfieldIds::parsePublicId)?.let(IncomingLink::Deck)
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                if (text.contains("moxfield.com/decks/")) MoxfieldIds.parsePublicId(text)?.let(IncomingLink::Deck)
                else text.takeIf { it.isNotBlank() }?.let(IncomingLink::ImportText)
            }
            else -> null
        }
    }
}
