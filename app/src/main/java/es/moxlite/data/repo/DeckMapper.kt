package es.moxlite.data.repo

import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.moxfield.MoxDeckDto
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import es.moxlite.data.remote.scryfall.ScryCard

object DeckMapper {

    private const val WUBRG = "WUBRG"

    fun colors(list: List<String>): String =
        list.map { it.uppercase() }.distinct().sortedBy { WUBRG.indexOf(it).let { i -> if (i < 0) 9 else i } }
            .joinToString("")

    /** Mazo completo de Moxfield → filas de Room, conservando el estado local (guardado, etc.). */
    fun fromDto(dto: MoxDeckDto, existing: DeckEntity?): Pair<DeckEntity, List<DeckCardEntity>> {
        val publicId = dto.publicId
        val cards = dto.boards.flatMap { (board, content) ->
            content.cards.map { (entryKey, entry) ->
                val c = entry.card
                DeckCardEntity(
                    deckId = publicId,
                    board = board,
                    cardKey = c.uniqueCardId.ifBlank { c.id }.ifBlank { entryKey },
                    name = c.name,
                    quantity = entry.quantity,
                    scryfallId = c.scryfallId,
                    moxCardId = c.id.ifBlank { null },
                    typeLine = c.typeLine ?: c.cardFaces.firstOrNull()?.typeLine.orEmpty(),
                    manaCost = c.manaCost?.ifBlank { null } ?: c.cardFaces.firstOrNull()?.manaCost.orEmpty(),
                    cmc = c.cmc,
                    colorIdentity = colors(c.colorIdentity),
                    setCode = c.set,
                    collectorNumber = c.cn,
                    rarity = c.rarity,
                    oracleText = c.oracleText ?: c.cardFaces.takeIf { it.isNotEmpty() }
                        ?.joinToString("\n//\n") { "${it.name}\n${it.oracleText.orEmpty()}" },
                    isFoil = entry.isFoil || entry.finish == "foil",
                    isProxy = entry.isProxy,
                    hasBackImage = c.hasBackImage,
                    priceEur = c.price("eur"),
                    priceUsd = c.price("usd"),
                )
            }
        }
        val author = dto.createdByUser ?: dto.authors.firstOrNull()
        // La carta que el autor eligió como destacada; si no, comandante o la más cara.
        val featured = dto.mainCardId?.let { main -> cards.firstOrNull { it.moxCardId == main } }
            ?: cards.firstOrNull { it.board == Boards.COMMANDERS }
            ?: cards.filter { it.board == Boards.MAIN && !it.typeLine.contains("Basic") }.maxByOrNull { it.priceEur ?: 0.0 }

        val deck = DeckEntity(
            publicId = publicId,
            remoteId = dto.id,
            name = dto.name,
            format = dto.format,
            description = dto.description,
            visibility = dto.visibility,
            authorUserName = author?.userName.orEmpty(),
            authorDisplayName = author?.displayName.orEmpty(),
            colorIdentity = colors(dto.colorIdentity),
            featuredScryfallId = featured?.scryfallId,
            featuredMoxCardId = dto.mainCardId ?: featured?.moxCardId,
            likeCount = dto.likeCount,
            viewCount = dto.viewCount,
            mainboardCount = cards.filter { it.board == Boards.MAIN }.sumOf { it.quantity },
            remoteVersion = dto.version,
            remoteUpdatedAt = dto.lastUpdatedAtUtc,
            isSaved = existing?.isSaved ?: false,
            isLocalOnly = false,
            hasLocalChanges = false,
            hasConflict = false,
            originalPublicId = existing?.originalPublicId,
            fetchedAt = System.currentTimeMillis(),
            imagesCachedAt = existing?.imagesCachedAt,
        )
        return deck to cards
    }

    /** Resumen de búsqueda → fila mínima (no pisa un mazo ya descargado). */
    fun fromSummary(s: MoxDeckSummaryDto): DeckEntity {
        val author = s.createdByUser ?: s.authors.firstOrNull()
        return DeckEntity(
            publicId = s.publicId,
            remoteId = s.id,
            name = s.name,
            format = s.format,
            visibility = s.visibility,
            authorUserName = author?.userName.orEmpty(),
            authorDisplayName = author?.displayName.orEmpty(),
            colorIdentity = colors(s.colorIdentity),
            featuredMoxCardId = s.mainCardId,
            likeCount = s.likeCount,
            viewCount = s.viewCount,
            mainboardCount = s.mainboardCount,
            remoteUpdatedAt = s.lastUpdatedAtUtc,
        )
    }

    fun fromScryfall(deckId: String, board: String, card: ScryCard, quantity: Int) = DeckCardEntity(
        deckId = deckId,
        board = board,
        cardKey = "sf:${card.id}",
        name = card.name,
        quantity = quantity,
        scryfallId = card.id,
        typeLine = card.typeLine ?: card.cardFaces.firstOrNull()?.typeLine.orEmpty(),
        manaCost = card.manaCost?.ifBlank { null } ?: card.cardFaces.firstOrNull()?.manaCost.orEmpty(),
        cmc = card.cmc,
        colorIdentity = colors(card.colorIdentity),
        setCode = card.set,
        collectorNumber = card.collectorNumber,
        rarity = card.rarity,
        oracleText = card.fullOracle,
        hasBackImage = card.hasBackImage,
        priceEur = card.prices.eur?.toDoubleOrNull(),
        priceUsd = card.prices.usd?.toDoubleOrNull(),
    )
}
