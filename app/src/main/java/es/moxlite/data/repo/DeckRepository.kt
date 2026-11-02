package es.moxlite.data.repo

import android.content.Context
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import es.moxlite.data.local.AppDatabase
import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.local.FollowedUserEntity
import es.moxlite.data.local.PendingOpEntity
import es.moxlite.data.remote.Http
import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.moxfield.DeckQuery
import es.moxlite.data.remote.moxfield.MoxAuthorDto
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import es.moxlite.data.remote.moxfield.MoxPage
import es.moxlite.data.remote.moxfield.MoxfieldService
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.scryfall.ScryfallImages
import es.moxlite.data.session.SessionManager
import es.moxlite.data.sync.DeckOp
import es.moxlite.data.sync.MoxfieldWriter
import es.moxlite.data.sync.SyncWorker
import es.moxlite.data.sync.WriteResult
import es.moxlite.domain.DeckLine
import es.moxlite.domain.DeckText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

data class ImportResult(val deckId: String, val total: Int, val unresolved: List<String>)

/**
 * Fuente de verdad de los mazos. La UI siempre lee de Room; la red solo actualiza Room.
 * Las ediciones se aplican en local al momento y se encolan para subirlas a Moxfield.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckRepository(
    private val context: Context,
    private val moxfield: MoxfieldService,
    private val cards: CardRepository,
    db: AppDatabase,
    private val session: SessionManager,
    private val writer: MoxfieldWriter,
    private val imageLoader: ImageLoader,
) {
    private val decks = db.decks()
    private val ops = db.pendingOps()
    private val users = db.users()

    val canWriteRemote: Boolean get() = writer.isAvailable

    // ---------- Lectura ----------

    fun observeMine(): Flow<List<DeckEntity>> =
        session.state.map { it.userName }.distinctUntilChanged().flatMapLatest { decks.observeMine(it) }

    fun observeRecent(): Flow<List<DeckEntity>> = decks.observeRecent()
    fun observeDeck(id: String): Flow<DeckEntity?> = decks.observe(id)
    fun observeCards(id: String): Flow<List<DeckCardEntity>> = decks.observeCards(id)
    fun observePendingOps(): Flow<List<PendingOpEntity>> = ops.observeAll()
    fun observeFollowedUsers(): Flow<List<FollowedUserEntity>> = users.observeAll()

    /**
     * Descarga el mazo de Moxfield. Si hay cambios locales sin subir no se pisan:
     * se marca conflicto si Moxfield tiene una versión distinta. [force] descarta lo local.
     */
    suspend fun refreshDeck(publicId: String, force: Boolean = false) = withContext(Dispatchers.IO) {
        val existing = decks.get(publicId)
        if (existing?.isLocalOnly == true) return@withContext
        val dto = moxfield.deck(publicId)
        if (existing?.hasLocalChanges == true && !force) {
            if (dto.version != existing.remoteVersion) decks.upsert(existing.copy(hasConflict = true))
            return@withContext
        }
        if (force) ops.deleteForDeck(publicId)
        val (deck, cardRows) = DeckMapper.fromDto(dto, existing)
        decks.replaceDeck(deck, cardRows)
    }

    /** Actualiza en segundo plano los mazos guardados que no tienen cambios pendientes. */
    suspend fun refreshSavedDecks() {
        decks.getSaved().filterNot { it.hasLocalChanges }.forEach { deck ->
            runCatching { refreshDeck(deck.publicId) }
            delay(300)
        }
    }

    suspend fun searchDecks(query: DeckQuery): MoxPage<MoxDeckSummaryDto> = withContext(Dispatchers.IO) {
        moxfield.searchDecks(query.toParams())
    }

    suspend fun myRemoteDecks(): List<MoxDeckSummaryDto> {
        val me = session.state.value.userName
        if (me.isBlank()) return emptyList()
        val page = searchDecks(DeckQuery(authorUserName = me, pageSize = 100))
        // Guarda el resumen para que aparezcan en "Mis mazos" aunque no se hayan abierto.
        page.data.forEach { summary ->
            if (decks.get(summary.publicId) == null) decks.upsert(DeckMapper.fromSummary(summary))
        }
        return page.data
    }

    suspend fun searchUsers(text: String): List<MoxAuthorDto> = withContext(Dispatchers.IO) {
        if (text.isBlank()) emptyList() else moxfield.searchUsers(text.trim()).data
    }

    suspend fun followUser(user: MoxAuthorDto) = users.upsert(FollowedUserEntity(user.userName, user.displayName))
    suspend fun unfollowUser(userName: String) = users.delete(userName)

    // ---------- Offline ----------

    suspend fun setSaved(deckId: String, saved: Boolean) {
        val deck = decks.get(deckId) ?: return
        decks.upsert(deck.copy(isSaved = saved))
        if (saved) precacheImages(deckId)
    }

    /** Descarga a disco las imágenes de todas las cartas del mazo. Devuelve cuántas se guardaron. */
    suspend fun precacheImages(deckId: String): Int = withContext(Dispatchers.IO) {
        val urls = decks.getCards(deckId).flatMap { card ->
            val id = card.scryfallId ?: return@flatMap emptyList()
            listOfNotNull(
                ScryfallImages.url(id),
                ScryfallImages.url(id, back = true).takeIf { card.hasBackImage },
            )
        }.distinct()

        val ok = coroutineScope {
            urls.chunked(6).sumOf { batch ->
                batch.map { url ->
                    async {
                        val request = ImageRequest.Builder(context)
                            .data(url)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                        imageLoader.execute(request) is SuccessResult
                    }
                }.awaitAll().count { it }
            }
        }
        decks.get(deckId)?.let { decks.upsert(it.copy(imagesCachedAt = System.currentTimeMillis())) }
        ok
    }

    suspend fun removeFromDevice(deckId: String) {
        ops.deleteForDeck(deckId)
        decks.deleteDeck(deckId)
    }

    // ---------- Edición (local + cola) ----------

    suspend fun setQuantity(deckId: String, board: String, cardKey: String, quantity: Int) {
        val card = decks.getCard(deckId, board, cardKey) ?: return
        val qty = quantity.coerceAtLeast(0)
        if (qty == 0) decks.deleteCard(deckId, board, cardKey)
        else decks.upsertCards(listOf(card.copy(quantity = qty)))
        enqueue(
            deckId,
            DeckOp.SetQuantity(board, cardKey, card.name, card.scryfallId, card.moxCardId, qty),
            targetKey = "$board|$cardKey",
        )
    }

    suspend fun moveCard(deckId: String, fromBoard: String, toBoard: String, cardKey: String) {
        if (fromBoard == toBoard) return
        val card = decks.getCard(deckId, fromBoard, cardKey) ?: return
        val target = decks.getCard(deckId, toBoard, cardKey)
        decks.deleteCard(deckId, fromBoard, cardKey)
        decks.upsertCards(listOf(card.copy(board = toBoard, quantity = card.quantity + (target?.quantity ?: 0))))
        enqueue(deckId, DeckOp.MoveCard(fromBoard, toBoard, cardKey, card.name, card.quantity))
    }

    suspend fun addCard(deckId: String, board: String, card: ScryCard, quantity: Int = 1) {
        // Si ya está (misma impresión o mismo nombre), suma cantidad.
        val existing = decks.getCards(deckId).firstOrNull {
            it.board == board && (it.scryfallId == card.id || it.name.equals(card.name, ignoreCase = true))
        }
        val row = existing?.copy(quantity = existing.quantity + quantity)
            ?: DeckMapper.fromScryfall(deckId, board, card, quantity)
        decks.upsertCards(listOf(row))
        enqueue(
            deckId,
            DeckOp.SetQuantity(board, row.cardKey, row.name, row.scryfallId, row.moxCardId, row.quantity),
            targetKey = "$board|${row.cardKey}",
        )
    }

    suspend fun rename(deckId: String, name: String) {
        val deck = decks.get(deckId) ?: return
        decks.upsert(deck.copy(name = name.trim()))
        enqueue(deckId, DeckOp.Rename(name.trim()), targetKey = "name")
    }

    suspend fun createDeck(name: String, format: String): String {
        val id = newLocalId()
        decks.upsert(newLocalDeck(id, name, format))
        enqueue(id, DeckOp.CreateDeck(name, format))
        return id
    }

    /** Copia cualquier mazo (propio o ajeno) como mazo nuevo, sin necesidad de conexión. */
    suspend fun copyDeck(sourceId: String, name: String): String {
        val source = decks.get(sourceId) ?: error("El mazo no está descargado")
        val id = newLocalId()
        decks.replaceDeck(
            newLocalDeck(id, name, source.format).copy(
                description = source.description,
                colorIdentity = source.colorIdentity,
                featuredScryfallId = source.featuredScryfallId,
                mainboardCount = source.mainboardCount,
                originalPublicId = source.publicId.takeUnless { source.isLocalOnly },
            ),
            decks.getCards(sourceId).map { it.copy(deckId = id) },
        )
        enqueue(id, DeckOp.CreateDeck(name, source.format, copiedFrom = source.publicId))
        return id
    }

    suspend fun importText(name: String, format: String, text: String): ImportResult {
        val lines = DeckText.parse(text)
        require(lines.isNotEmpty()) { "No se ha reconocido ninguna carta en el texto." }
        val resolved = withContext(Dispatchers.IO) { cards.resolve(lines) }
        val id = newLocalId()
        val rows = resolved.map { (line, card) ->
            if (card != null) {
                DeckMapper.fromScryfall(id, line.board, card, line.quantity).copy(isFoil = line.foil)
            } else {
                DeckCardEntity(deckId = id, board = line.board, cardKey = "name:${line.name.lowercase()}", name = line.name, quantity = line.quantity)
            }
        }.groupBy { it.board to it.cardKey }
            .map { (_, same) -> same.first().copy(quantity = same.sumOf { it.quantity }) }

        val featured = rows.firstOrNull { it.board == Boards.COMMANDERS } ?: rows.firstOrNull { it.board == Boards.MAIN }
        decks.replaceDeck(
            newLocalDeck(id, name, format).copy(
                featuredScryfallId = featured?.scryfallId,
                colorIdentity = DeckMapper.colors(rows.flatMap { r -> r.colorIdentity.map { it.toString() } }),
                mainboardCount = rows.filter { it.board == Boards.MAIN }.sumOf { it.quantity },
            ),
            rows,
        )
        enqueue(id, DeckOp.CreateDeck(name, format))
        return ImportResult(id, lines.size, resolved.filterValues { it == null }.keys.map { it.name })
    }

    suspend fun exportText(deckId: String, includePrintings: Boolean = true): String {
        val lines = decks.getCards(deckId).map {
            DeckLine(it.quantity, it.name, it.board, it.setCode.ifBlank { null }, it.collectorNumber, it.isFoil)
        }
        return DeckText.format(lines, includePrintings)
    }

    /** Tira los cambios locales y vuelve a la versión de Moxfield. */
    suspend fun discardLocalChanges(deckId: String) {
        val deck = decks.get(deckId) ?: return
        if (deck.isLocalOnly) {
            removeFromDevice(deckId)
        } else {
            refreshDeck(deckId, force = true)
        }
    }

    /** Sube la cola a Moxfield. Mientras no haya escritor real, no hace nada y conserva la cola. */
    suspend fun pushPendingOps() {
        if (!writer.isAvailable) return
        val blockedDecks = mutableSetOf<String>()
        for (entity in ops.getAll()) {
            if (entity.deckId in blockedDecks) continue
            val deck = decks.get(entity.deckId)
            if (deck == null) {
                ops.delete(entity.id)
                continue
            }
            val op = runCatching { Http.json.decodeFromString(DeckOp.serializer(), entity.payload) }.getOrNull()
            if (op == null) {
                ops.delete(entity.id)
                continue
            }
            try {
                when (val result = writer.apply(deck, op)) {
                    WriteResult.Done -> ops.delete(entity.id)
                    is WriteResult.Created -> {
                        ops.delete(entity.id)
                        decks.changeDeckId(deck, result.publicId, result.remoteId)
                    }
                    WriteResult.NotSupported -> blockedDecks += entity.deckId
                }
            } catch (e: Exception) {
                ops.update(entity.copy(attempts = entity.attempts + 1, lastError = e.message))
                blockedDecks += entity.deckId
            }
        }
        // Mazos sin operaciones pendientes: vuelven a estar limpios.
        decks.getWithLocalChanges().forEach { deck ->
            if (ops.countForDeck(deck.publicId) == 0) {
                decks.upsert(deck.copy(hasLocalChanges = false, hasConflict = false))
            }
        }
    }

    // ---------- Internos ----------

    private suspend fun enqueue(deckId: String, op: DeckOp, targetKey: String? = null) {
        ops.enqueueCoalesced(
            PendingOpEntity(
                deckId = deckId,
                type = op.typeName,
                targetKey = targetKey,
                payload = Http.json.encodeToString(DeckOp.serializer(), op),
            )
        )
        decks.get(deckId)?.let { if (!it.hasLocalChanges) decks.upsert(it.copy(hasLocalChanges = true)) }
        if (writer.isAvailable) SyncWorker.runNow(context)
    }

    private fun newLocalId() = "local-" + UUID.randomUUID().toString().take(13)

    private fun newLocalDeck(id: String, name: String, format: String) = DeckEntity(
        publicId = id,
        name = name.trim().ifBlank { "Mazo sin nombre" },
        format = format,
        authorUserName = session.state.value.userName,
        isSaved = true,
        isLocalOnly = true,
        hasLocalChanges = true,
        fetchedAt = System.currentTimeMillis(),
    )
}
