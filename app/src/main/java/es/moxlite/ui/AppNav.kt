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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import es.moxlite.IncomingLink
import es.moxlite.ui.common.OfflineBanner
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.deck.DeckScreen
import es.moxlite.ui.decks.MyDecksScreen
import es.moxlite.ui.importer.ImportScreen
import es.moxlite.ui.search.SearchScreen
import es.moxlite.ui.settings.ApiLogScreen
import es.moxlite.ui.settings.SettingsScreen
import es.moxlite.ui.update.UpdatePrompt
import es.moxlite.ui.users.UserDecksScreen
import es.moxlite.ui.users.UsersScreen
import es.moxlite.ui.web.WebMode
import es.moxlite.ui.web.WebScreen

object Routes {
    const val DECKS = "decks"
    const val SEARCH = "search"
    const val USERS = "users"
    const val SETTINGS = "settings"
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

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DECKS, "Mis mazos", Icons.Outlined.Style),
    Tab(Routes.SEARCH, "Buscar", Icons.Outlined.Search),
    Tab(Routes.USERS, "Usuarios", Icons.Outlined.Group),
    Tab(Routes.SETTINGS, "Ajustes", Icons.Outlined.Settings),
)

@Composable
fun AppNav(incoming: IncomingLink?, onIncomingHandled: () -> Unit) {
    val nav = rememberNavController()
    val container = appContainer()
    val online by container.connectivity.online.collectAsStateWithLifecycle()
    var importDraft by remember { mutableStateOf("") }

    LaunchedEffect(incoming) {
        when (incoming) {
            is IncomingLink.Deck -> nav.navigate(Routes.deck(incoming.publicId))
            is IncomingLink.ImportText -> { importDraft = incoming.text; nav.navigate(Routes.IMPORT) }
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
                            label = { Text(tab.label) },
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
            NavHost(nav, startDestination = Routes.DECKS, modifier = Modifier.weight(1f)) {
                composable(Routes.DECKS) {
                    MyDecksScreen(
                        openDeck = { nav.navigate(Routes.deck(it)) },
                        openImport = { importDraft = ""; nav.navigate(Routes.IMPORT) },
                        openLogin = { nav.navigate(Routes.web(WebMode.LOGIN)) },
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
                        openDeck = { nav.navigate(Routes.deck(it)) },
                    )
                }
                composable(Routes.DECK, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    DeckScreen(
                        publicId = entry.arguments?.getString("id").orEmpty(),
                        onBack = { nav.popBackStack() },
                        openDeck = { nav.navigate(Routes.deck(it)) { popUpTo(Routes.DECK) { inclusive = true } } },
                        openUser = { nav.navigate(Routes.user(it)) },
                        openWeb = { url -> nav.navigate(Routes.web(WebMode.BROWSE, url)) },
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
                        initialText = importDraft,
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
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
