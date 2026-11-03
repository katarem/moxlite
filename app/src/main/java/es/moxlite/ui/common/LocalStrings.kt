package es.moxlite.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.moxlite.i18n.Es
import es.moxlite.i18n.I18n
import es.moxlite.i18n.Strings

/** Textos del idioma elegido en Ajustes. Uso: `val t = LocalStrings.current; Text(t.myDecks)`. */
val LocalStrings = staticCompositionLocalOf<Strings> { Es }

@Composable
fun ProvideStrings(content: @Composable () -> Unit) {
    val container = appContainer()
    val language by container.settings.appLanguage.collectAsStateWithLifecycle()
    val strings = remember(language) { I18n.resolve(language) }
    CompositionLocalProvider(LocalStrings provides strings, content = content)
}
