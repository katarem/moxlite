package es.moxlite.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import es.moxlite.IncomingLink
import es.moxlite.domain.DeckSnapshot
import es.moxlite.i18n.Strings
import es.moxlite.ui.account.AccountScreen
import es.moxlite.ui.account.WelcomeScreen
import es.moxlite.ui.common.LocalStrings
import es.moxlite.ui.common.OfflineBanner
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.deck.DeckScreen
import es.moxlite.ui.decks.MyDecksScreen
import es.moxlite.ui.importer.ImportDraft
import es.moxlite.ui.importer.ImportScreen
import es.moxlite.ui.search.SearchScreen
import es.moxlite.ui.settings.ApiLogScreen
import es.moxlite.ui.settings.SettingsScreen
import es.moxlite.ui.update.UpdatePrompt
import es.moxlite.ui.users.UserDecksScreen
import es.moxlite.ui.users.UsersScreen
import es.moxlite.ui.web.WebMode
import es.moxlite.ui.web.WebScreen
import kotlinx.coroutines.launch

object Routes {
    const val WELCOME = "welcome"
    const val DECKS = "decks"
    const val SEARCH = "search"
    const val USERS = "users"
    const val SETTINGS = "settings"
    const val ACCOUNT = "account"
    const val DECK = "deck/{id}"
    const val USER = "user/{name}"
    const val WEB = "web/{mode}?url={url}"
    const val IMPORT = "import"
    const val API_LOG = "apilog"

    fun deck(id: String) = "deck/${Uri.encode(id)}"
    fun user(name: String) = "user/${Uri.encode(name)}"
    fun web(mode: WebMode, url: String? = null) =
        "web/${mode.name}" + (url?.let { "?url=${Uri.encode(it)}" } ?: "")
}

private data class Tab(val route: String, val label: (Strings) -> String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DECKS, { it.tabMyDecks }, Icons.Outlined.Style),
    Tab(Routes.SEARCH, { it.tabSearch }, Icons.Outlined.Search),
    Tab(Routes.USERS, { it.tabUsers }, Icons.Outlined.Group),
    Tab(Routes.SETTINGS, { it.tabSettings }, Icons.Outlined.Settings),
)

@Composable
fun AppNav(incoming: IncomingLink?, onIncomingHandled: () -> Unit) {
    val nav = rememberNavController()
    val container = appContainer()
    val t = LocalStrings.current
    val online by container.connectivity.online.collectAsStateWithLifecycle()
    var importDraft by remember { mutableStateOf(ImportDraft()) }

    // Primera vez y sin cuenta: pantalla de bienvenida para iniciar sesión.
    val start = remember {
        if (!container.settings.onboarded.value && !container.session.state.value.hasAccount) Routes.WELCOME else Routes.DECKS
    }

    /** Mazo recibido por enlace o código de Moxlite. */
    fun openShared(snapshot: DeckSnapshot) {
        val id = snapshot.id
        if (id == null) {
            // Mazo que solo existía en el móvil de quien lo comparte: se ofrece importarlo.
            importDraft = ImportDraft(name = snapshot.n, format = snapshot.f.ifBlank { "commander" }, text = snapshot.t)
            nav.navigate(Routes.IMPORT)
        } else {
            // Copia del enlace por si Moxfield no responde; si responde, se actualiza al abrir.
            container.appScope.launch { runCatching { container.deckRepository.seedFromSnapshot(snapshot) } }
            nav.navigate(Routes.deck(id))
        }
    }

    LaunchedEffect(incoming) {
        when (incoming) {
            is IncomingLink.Deck -> nav.navigate(Routes.deck(incoming.publicId))
            is IncomingLink.ImportText -> { importDraft = ImportDraft(text = incoming.text); nav.navigate(Routes.IMPORT) }
            is IncomingLink.Shared -> openShared(incoming.snapshot)
            null -> return@LaunchedEffect
        }
        onIncomingHandled()
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showTabs = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label(t)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(bottom = padding.calculateBottomPadding())
                .consumeWindowInsets(PaddingValues(bottom = padding.calculateBottomPadding())),
        ) {
            NavHost(nav, startDestination = start, modifier = Modifier.weight(1f)) {
                composable(Routes.WELCOME) {
                    WelcomeScreen(
                        openLogin = { nav.navigate(Routes.web(WebMode.LOGIN)) },
                        onDone = {
                            container.settings.setOnboarded(true)
                            nav.navigate(Routes.DECKS) { popUpTo(Routes.WELCOME) { inclusive = true } }
                        },
                    )
                }
                composable(Routes.DECKS) {
                    MyDecksScreen(
                        openDeck = { nav.navigate(Routes.deck(it)) },
                        openShared = ::openShared,
                        openImport = { importDraft = ImportDraft(); nav.navigate(Routes.IMPORT) },
                        openLogin = { nav.navigate(Routes.web(WebMode.LOGIN)) },
                        openAccount = { nav.navigate(Routes.ACCOUNT) },
                    )
                }
                composable(Routes.SEARCH) {
                    SearchScreen(openDeck = { nav.navigate(Routes.deck(it)) })
                }
                composable(Routes.USERS) {
                    UsersScreen(openUser = { nav.navigate(Routes.user(it)) })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        openWeb = { nav.navigate(Routes.web(it)) },
                        openApiLog = { nav.navigate(Routes.API_LOG) },
                        openAccount = { nav.navigate(Routes.ACCOUNT) },
                    )
                }
                composable(Routes.ACCOUNT) {
                    AccountScreen(
                        onBack = { nav.popBackStack() },
                        openLogin = { nav.navigate(Routes.web(WebMode.LOGIN)) },
                        openDeck = { nav.navigate(Routes.deck(it)) },
                        openUser = { nav.navigate(Routes.user(it)) },
                    )
                }
                composable(Routes.DECK, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    DeckScreen(
                        publicId = entry.arguments?.getString("id").orEmpty(),
                        onBack = { nav.popBackStack() },
                        openDeck = { nav.navigate(Routes.deck(it)) { popUpTo(Routes.DECK) { inclusive = true } } },
                        openUser = { nav.navigate(Routes.user(it)) },
                        openWeb = { url -> nav.navigate(Routes.web(WebMode.BROWSE, url)) },
                        openLogin = { nav.navigate(Routes.web(WebMode.LOGIN)) },
                    )
                }
                composable(Routes.USER, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
                    UserDecksScreen(
                        userName = entry.arguments?.getString("name").orEmpty(),
                        onBack = { nav.popBackStack() },
                        openDeck = { nav.navigate(Routes.deck(it)) },
                    )
                }
                composable(
                    Routes.WEB,
                    arguments = listOf(
                        navArgument("mode") { type = NavType.StringType },
                        navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    WebScreen(
                        mode = WebMode.valueOf(entry.arguments?.getString("mode") ?: WebMode.BROWSE.name),
                        startUrl = entry.arguments?.getString("url"),
                        onBack = { nav.popBackStack() },
                        openDeck = { nav.navigate(Routes.deck(it)) },
                    )
                }
                composable(Routes.IMPORT) {
                    ImportScreen(
                        draft = importDraft,
                        onBack = { nav.popBackStack() },
                        onImported = { nav.navigate(Routes.deck(it)) { popUpTo(Routes.IMPORT) { inclusive = true } } },
                    )
                }
                composable(Routes.API_LOG) {
                    ApiLogScreen(onBack = { nav.popBackStack() })
                }
            }
            OfflineBanner(!online)
        }
    }

    UpdatePrompt(container.updater)
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    // "Mis mazos" es siempre la raíz (la bienvenida se quita al terminarla).
    popUpTo(Routes.DECKS) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
