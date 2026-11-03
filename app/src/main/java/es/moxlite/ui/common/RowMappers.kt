package es.moxlite.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import es.moxlite.AppContainer
import es.moxlite.MoxliteApp
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import es.moxlite.data.remote.moxfield.MoxfieldImages
import es.moxlite.data.remote.scryfall.ScryfallImages
import es.moxlite.data.repo.DeckMapper

fun DeckEntity.toRow() = DeckRowData(
    publicId = publicId,
    name = name,
    format = format,
    author = authorUserName,
    colors = colorIdentity,
    imageUrl = featuredImageUrl(),
    likes = likeCount,
    cards = mainboardCount,
    saved = isSaved,
    localOnly = isLocalOnly,
    localChanges = hasLocalChanges,
    isPrivate = visibility == "private",
    bracket = bracket,
)

/** Recorte del arte de la carta destacada: Scryfall si se conoce su id; si no, el de Moxfield. */
fun DeckEntity.featuredImageUrl(): String? =
    featuredScryfallId?.let { ScryfallImages.url(it, ScryfallImages.Size.ART_CROP) }
        ?: featuredMoxCardId?.let(MoxfieldImages::artCrop)

fun MoxDeckSummaryDto.toRow() = DeckRowData(
    publicId = publicId,
    name = name,
    format = format,
    author = (createdByUser ?: authors.firstOrNull())?.userName.orEmpty(),
    colors = DeckMapper.colors(colorIdentity),
    imageUrl = mainCardId?.let(MoxfieldImages::artCrop),
    likes = likeCount,
    cards = mainboardCount,
    isPrivate = visibility == "private",
    bracket = effectiveBracket,
)

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as MoxliteApp).container
