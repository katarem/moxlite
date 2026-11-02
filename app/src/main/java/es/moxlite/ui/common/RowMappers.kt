package es.moxlite.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import es.moxlite.AppContainer
import es.moxlite.MoxliteApp
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import es.moxlite.data.repo.DeckMapper

fun DeckEntity.toRow() = DeckRowData(
    publicId = publicId,
    name = name,
    format = format,
    author = authorUserName,
    colors = colorIdentity,
    featuredScryfallId = featuredScryfallId,
    likes = likeCount,
    cards = mainboardCount,
    saved = isSaved,
    localOnly = isLocalOnly,
    localChanges = hasLocalChanges,
)

fun MoxDeckSummaryDto.toRow() = DeckRowData(
    publicId = publicId,
    name = name,
    format = format,
    author = (createdByUser ?: authors.firstOrNull())?.userName.orEmpty(),
    colors = DeckMapper.colors(colorIdentity),
    likes = likeCount,
    cards = mainboardCount,
)

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as MoxliteApp).container
