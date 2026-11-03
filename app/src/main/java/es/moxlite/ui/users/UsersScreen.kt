package es.moxlite.ui.users

import es.moxlite.ui.common.LocalStrings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import es.moxlite.data.local.FollowedUserEntity
import es.moxlite.data.remote.moxfield.DeckQuery
import es.moxlite.data.remote.moxfield.MoxAuthorDto
import es.moxlite.data.remote.userMessage
import es.moxlite.data.repo.DeckRepository
import es.moxlite.ui.common.MessageBox
import es.moxlite.ui.common.SectionHeader
import es.moxlite.ui.common.appContainer
import es.moxlite.ui.search.DeckResultsList
import es.moxlite.ui.search.DeckSearchViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UsersViewModel(private val repo: DeckRepository) : ViewModel() {
    val followed: StateFlow<List<FollowedUserEntity>> = repo.observeFollowedUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _results = MutableStateFlow<List<MoxAuthorDto>>(emptyList())
    val results = _results.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var job: Job? = null

    fun search(text: String, debounce: Boolean = true) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(350)
            runCatching { repo.searchUsers(text) }
                .onSuccess { _results.value = it; _error.value = null }
                .onFailure { _error.value = it.userMessage() }
        }
    }

    fun follow(user: MoxAuthorDto) { viewModelScope.launch { repo.followUser(user) } }
    fun unfollow(userName: String) { viewModelScope.launch { repo.unfollowUser(userName) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(openUser: (String) -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val vm: UsersViewModel = viewModel { UsersViewModel(container.deckRepository) }
    val followed by vm.followed.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text(t.users) }) }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; if (it.length >= 2) vm.search(it) },
                placeholder = { Text(t.searchUserPlaceholder) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (text.isNotBlank()) openUser(text.trim())
                }),
                supportingText = { Text(t.searchUserHint) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            error?.let { MessageBox(it) }
            LazyColumn(Modifier.fillMaxSize()) {
                if (followed.isNotEmpty() && text.isBlank()) {
                    item { SectionHeader(t.following) }
                    items(followed, key = { "f" + it.userName }) { u ->
                        ListItem(
                            headlineContent = { Text(u.displayName.ifBlank { u.userName }) },
                            supportingContent = { Text("@${u.userName}") },
                            leadingContent = { Icon(Icons.Outlined.Person, null) },
                            trailingContent = {
                                IconButton(onClick = { vm.unfollow(u.userName) }) { Icon(Icons.Outlined.PersonRemove, t.unfollow) }
                            },
                            modifier = Modifier.clickable { openUser(u.userName) },
                        )
                    }
                }
                if (text.isNotBlank()) {
                    items(results, key = { "r" + it.userName }) { u ->
                        val isFollowed = followed.any { it.userName.equals(u.userName, true) }
                        ListItem(
                            headlineContent = { Text(u.displayName.ifBlank { u.userName }) },
                            supportingContent = { Text("@${u.userName}" + if (u.badges.isNotEmpty()) " · ${u.badges.joinToString()}" else "") },
                            leadingContent = { Avatar(u.profileImageUrl) },
                            trailingContent = {
                                IconButton(onClick = { if (isFollowed) vm.unfollow(u.userName) else vm.follow(u) }) {
                                    Icon(if (isFollowed) Icons.Outlined.PersonRemove else Icons.Outlined.PersonAdd, if (isFollowed) t.unfollow else t.follow)
                                }
                            },
                            modifier = Modifier.clickable { openUser(u.userName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Avatar(url: String?) {
    if (url.isNullOrBlank()) Icon(Icons.Outlined.Person, null)
    else AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
}

/** Mazos públicos de un usuario (búsqueda con authorUserNames). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDecksScreen(userName: String, onBack: () -> Unit, openDeck: (String) -> Unit) {
    val container = appContainer()
    val t = LocalStrings.current
    val vm: DeckSearchViewModel = viewModel(key = "user-$userName") {
        DeckSearchViewModel(container.deckRepository, DeckQuery(authorUserName = userName, pageSize = 50))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(userName) { if (!state.searched) vm.search() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("@$userName") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t.back) } },
            )
        },
    ) { padding ->
        DeckResultsList(
            state = state,
            openDeck = openDeck,
            onLoadMore = { vm.loadMore() },
            onRetry = { vm.search() },
            emptyText = t.userHasNoPublicDecks(userName),
            modifier = Modifier.padding(padding),
        )
    }
}
