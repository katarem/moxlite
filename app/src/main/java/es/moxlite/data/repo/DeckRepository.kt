package es.moxlite.data.repo

import android.content.Context
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import es.moxlite.data.local.AppDatabase
import es.moxlite.data.local.CardLocalizationEntity
import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.local.FollowedUserEntity
import es.moxlite.data.local.PendingOpEntity
import es.moxlite.data.remote.Http
import es.moxlite.data.remote.MoxError
import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.moxfield.DeckQuery
import es.moxlite.data.remote.moxfield.MoxAuthorDto
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import es.moxlite.data.remote.moxfield.MoxPage
import es.moxlite.data.remote.moxfield.MoxfieldService
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.scryfall.ScryfallImages
import es.moxlite.data.remote.userMessage
import es.moxlite.data.session.SessionManager
import es.moxlite.data.settings.AppSettings
import es.moxlite.data.sync.DeckOp
import es.moxlite.data.sync.MoxfieldWriter
import es.moxlite.data.sync.SyncWorker
import es.moxlite.data.sync.WriteParsing
import es.moxlite.data.sync.WriteResult
import es.moxlite.domain.DeckLine
import es.moxlite.domain.DeckSnapshot
import es.moxlite.domain.DeckText
import es.moxlite.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
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
    private val settings: AppSettings,
) {
    private val decks = db.decks()
    private val ops = db.pendingOps()
    private val users = db.users()
    private val localizations = db.localizations()
    private val pushMutex = Mutex()

    /** Hay sesión: los cambios se suben a Moxfield. */
    val canWriteRemote: Boolean get() = writer.isAvailable

    // ---------- Lectura ----------

    fun observeAll(): Flow<List<DeckEntity>> = decks.observeAll()

    /** Id nuevo de un mazo creado en el móvil cuando ya se ha subido a Moxfield (null mientras no). */
    fun observeAlias(id: String): Flow<String?> = decks.observeAlias(id)

    /** Sigue los cambios de id (mazo local subido a Moxfield) hasta el id actual. */
    suspend fun resolveId(id: String): String {
        var current = id
        repeat(4) { current = decks.getAlias(current) ?: return current }
        return current
    }

    /** Apunta que se ha abierto el mazo (pestaña "Recientes"). */
    suspend fun markViewed(id: String) = decks.markViewed(id, System.currentTimeMillis())
    fun observeDeck(id: String): Flow<DeckEntity?> = decks.observe(id)
    fun observeCards(id: String): Flow<List<DeckCardEntity>> = decks.observeCards(id)
    fun observePendingOps(): Flow<List<PendingOpEntity>> = ops.observeAll()
    fun observeFollowedUsers(): Flow<List<FollowedUserEntity>> = users.observeAll()

    /** Traducciones conocidas de impresiones al idioma [lang]. */
    fun observeLocalizations(lang: String): Flow<Map<PrintingKey, CardLocalizationEntity>> =
        localizations.observe(lang).map { rows -> rows.associateBy { PrintingKey(it.setCode, it.collectorNumber) } }

    /** El mazo es de la cuenta con sesión iniciada. */
    fun isMine(deck: DeckEntity): Boolean {
        val me = session.state.value.userName
        return me.isNotBlank() && deck.authorUserName.equals(me, ignoreCase = true)
    }

    /** Se puede editar: mazos propios o creados en el móvil. Los ajenos se copian primero. */
    fun canEdit(deck: DeckEntity): Boolean = deck.isLocalOnly || isMine(deck)

    /**
     * Descarga el mazo de Moxfield. Si hay cambios locales sin subir no se pisan:
     * se marca conflicto si Moxfield tiene una versión distinta. [force] descarta lo local.
     */
    suspend fun refreshDeck(publicId: String, force: Boolean = false) = withContext(Dispatchers.IO) {
        val existing = decks.get(publicId)
        if (existing?.isLocalOnly == true) return@withContext
        val dto = moxfield.deck(publicId)
        val current = decks.get(publicId)
        if (current?.hasLocalChanges == true && !force) {
            if (dto.version != current.remoteVersion) decks.upsert(current.copy(hasConflict = true))
            return@withContext
        }
        if (force) ops.deleteForDeck(publicId)
        val (deck, cardRows) = DeckMapper.fromDto(dto, current ?: existing)
        decks.replaceDeck(deck, cardRows)
    }

    /** Actualiza en segundo plano los mazos guardados que no tienen cambios pendientes. */
    suspend fun refreshSavedDecks() {
        decks.getSaved().filterNot { it.hasLocalChanges }.forEach { deck ->
            safely { refreshDeck(deck.publicId) }
            delay(300)
        }
    }

    suspend fun searchDecks(query: DeckQuery): MoxPage<MoxDeckSummaryDto> = withContext(Dispatchers.IO) {
        moxfield.searchDecks(query.toParams())
    }

    private val cardIdCache = mutableMapOf<String, String>()

    /**
     * Id de impresión de Moxfield de una carta por su nombre en inglés (lo que pide el filtro de
     * comandante de la búsqueda). Null si Moxfield no la encuentra.
     */
    suspend fun moxfieldCardId(name: String): String? = withContext(Dispatchers.IO) {
        val key = name.trim().lowercase()
        cardIdCache[key]?.let { return@withContext it }
        val attempts = listOf<suspend () -> kotlinx.serialization.json.JsonElement>(
            { moxfield.cardLookup(name.trim(), null, null) },
            { moxfield.cardSearch("!\"${name.trim().replace("\"", "")}\"") },
        )
        for (attempt in attempts) {
            val id = try {
                WriteParsing.extractCardId(attempt())
            } catch (e: CancellationException) {
                throw e
            } catch (_: MoxError.NotFound) {
                null
            } catch (e: IOException) {
                throw e // sin red, bloqueo, Cloudflare…: que se vea el motivo real
            } catch (_: Exception) {
                null
            }
            if (id != null) {
                synchronized(cardIdCache) { cardIdCache[key] = id }
                return@withContext id
            }
        }
        null
    }

    /**
     * Mazos de la cuenta. Con sesión se usan los personales (incluidos privados y ocultos); si
     * falla, o sin sesión, los públicos del usuario. Se guardan los resúmenes para que salgan en
     * "Mis mazos" aunque no se hayan abierto.
     */
    suspend fun myRemoteDecks(): List<MoxDeckSummaryDto> = withContext(Dispatchers.IO) {
        val s = session.state.value
        val me = s.userName
        if (me.isBlank()) return@withContext emptyList()

        val personal: List<MoxDeckSummaryDto>? = if (s.hasAccount) {
            safely {
                val all = mutableListOf<MoxDeckSummaryDto>()
                var page = 1
                do {
                    val result = moxfield.personalDecks(DeckQuery(pageNumber = page, pageSize = 100).toPersonalParams())
                    all += result.data
                    page++
                } while (page <= result.totalPages && page <= 5)
                all
            }
        } else null
        val list = personal ?: searchDecks(DeckQuery(authorUserName = me, pageSize = 100)).data

        list.forEach { summary ->
            val existing = decks.get(summary.publicId)
            val row = DeckMapper.fromSummary(summary).let { r ->
                if (r.authorUserName.isBlank()) r.copy(authorUserName = me) else r
            }
            if (existing == null || !existing.isFullyLoaded) {
                decks.upsert(row.copy(isSaved = existing?.isSaved ?: false, viewedAt = existing?.viewedAt ?: 0))
            } else {
                // Ya descargado: solo se completan los datos que trae el resumen.
                val updated = existing.copy(
                    featuredMoxCardId = existing.featuredMoxCardId ?: summary.mainCardId,
                    bracket = summary.effectiveBracket ?: existing.bracket,
                    hubs = row.hubs.ifBlank { existing.hubs },
                )
                if (updated != existing) decks.upsert(updated)
            }
        }

        // Con la lista personal completa: quitar resúmenes de mazos que ya no existen.
        if (personal != null) {
            val ids = list.map { it.publicId }.toSet()
            decks.getOwnedBy(me)
                .filter { it.publicId !in ids && it.fetchedAt == 0L && !it.isSaved && !it.hasLocalChanges }
                .forEach { decks.deleteDeck(it.publicId) }
        }
        list
    }

    /** Trae los mazos de la cuenta y, si [download], los guarda enteros (con imágenes) sin conexión. */
    suspend fun syncMine(download: Boolean): List<MoxDeckSummaryDto> {
        val summaries = myRemoteDecks()
        if (download) {
            summaries.forEach { s ->
                val existing = decks.get(s.publicId)
                if (existing?.hasLocalChanges == true) return@forEach
                val stale = existing == null || !existing.isFullyLoaded || !existing.isSaved ||
                    existing.remoteUpdatedAt != s.lastUpdatedAtUtc || existing.imagesCachedAt == null
                if (stale) {
                    safely {
                        refreshDeck(s.publicId)
                        decks.get(s.publicId)?.let { if (!it.isSaved) decks.upsert(it.copy(isSaved = true)) }
                        safely { ensureLocalized(s.publicId) }
                        precacheImages(s.publicId)
                    }
                    delay(300)
                }
            }
        }
        return summaries
    }

    suspend fun searchUsers(text: String): List<MoxAuthorDto> = withContext(Dispatchers.IO) {
        if (text.isBlank()) emptyList() else moxfield.searchUsers(text.trim()).data
    }

    suspend fun followUser(user: MoxAuthorDto) = users.upsert(FollowedUserEntity(user.userName, user.displayName))
    suspend fun unfollowUser(userName: String) = users.delete(userName)

    // ---------- Offline e idiomas ----------

    suspend fun setSaved(deckId: String, saved: Boolean) {
        val deck = decks.get(deckId) ?: return
        decks.upsert(deck.copy(isSaved = saved))
        if (saved) {
            safely { ensureLocalized(deckId) }
            precacheImages(deckId)
        }
    }

    /**
     * Busca en Scryfall las cartas del mazo en el idioma de cartas elegido (una vez: queda en
     * caché). Las que no existen en ese idioma se siguen viendo en inglés.
     */
    suspend fun ensureLocalized(deckId: String) {
        val lang = settings.cardLanguage.value
        if (lang == "en") return
        val cached = localizations.getAll(lang).associateBy { PrintingKey(it.setCode, it.collectorNumber) }
        val now = System.currentTimeMillis()
        val missing = decks.getCards(deckId)
            .mapNotNull { PrintingKey.of(it.setCode, it.collectorNumber) }
            .distinct()
            .filter { key ->
                val row = cached[key]
                // Las que no existían se vuelven a mirar cada mes (salen traducciones nuevas).
                row == null || (row.scryfallId == null && now - row.checkedAt > RECHECK_MS)
            }
        if (missing.isEmpty()) return
        val found = withContext(Dispatchers.IO) { cards.localize(missing, lang) }
        localizations.upsert(
            missing.map { key ->
                val card = found[key]
                CardLocalizationEntity(key.set, key.collectorNumber, lang, card?.id, card?.displayName)
            }
        )
    }

    /** Descarga a disco las imágenes de todas las cartas del mazo. Devuelve cuántas se guardaron. */
    suspend fun precacheImages(deckId: String): Int = withContext(Dispatchers.IO) {
        val lang = settings.cardLanguage.value
        val localized = if (lang == "en") emptyMap()
        else localizations.getAll(lang).associateBy { PrintingKey(it.setCode, it.collectorNumber) }

        val urls = decks.getCards(deckId).flatMap { card ->
            val id = PrintingKey.of(card.setCode, card.collectorNumber)?.let { localized[it]?.scryfallId }
                ?: card.scryfallId ?: return@flatMap emptyList()
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

    private suspend fun requireEditable(deckId: String): DeckEntity {
        val deck = decks.get(deckId) ?: error(I18n.strings.errDeckNotDownloaded)
        if (!canEdit(deck)) throw MoxError.NotOwner()
        return deck
    }

    suspend fun setQuantity(deckId: String, board: String, cardKey: String, quantity: Int) {
        requireEditable(deckId)
        val card = decks.getCard(deckId, board, cardKey) ?: return
        val qty = quantity.coerceAtLeast(0)
        if (qty == 0) decks.deleteCard(deckId, board, cardKey)
        else decks.upsertCards(listOf(card.copy(quantity = qty)))
        if (board == Boards.COMMANDERS) updateCommanders(deckId)
        enqueue(
            deckId,
            DeckOp.SetQuantity(
                board, cardKey, card.name, card.scryfallId, card.moxCardId, qty,
                card.setCode.ifBlank { null }, card.collectorNumber,
            ),
            targetKey = "$board|$cardKey",
        )
    }

    suspend fun moveCard(deckId: String, fromBoard: String, toBoard: String, cardKey: String) {
        if (fromBoard == toBoard) return
        requireEditable(deckId)
        val card = decks.getCard(deckId, fromBoard, cardKey) ?: return
        val target = decks.getCard(deckId, toBoard, cardKey)
        decks.deleteCard(deckId, fromBoard, cardKey)
        decks.upsertCards(listOf(card.copy(board = toBoard, quantity = card.quantity + (target?.quantity ?: 0))))
        if (Boards.COMMANDERS == fromBoard || Boards.COMMANDERS == toBoard) updateCommanders(deckId)
        enqueue(deckId, DeckOp.MoveCard(fromBoard, toBoard, cardKey, card.name, card.quantity))
    }

    /**
     * Añade una carta. [card] es la impresión inglesa; si se buscó en otro idioma, [localized] es
     * la impresión en ese idioma y se recuerda para mostrarla así.
     */
    suspend fun addCard(deckId: String, board: String, card: ScryCard, quantity: Int = 1, localized: ScryCard? = null) {
        requireEditable(deckId)
        if (localized != null && localized.lang != "en") {
            PrintingKey.of(card.set, card.collectorNumber)?.let { key ->
                localizations.upsert(
                    listOf(CardLocalizationEntity(key.set, key.collectorNumber, localized.lang, localized.id, localized.displayName))
                )
            }
        }
        // Si ya está (misma impresión o mismo nombre), suma cantidad.
        val existing = decks.getCards(deckId).firstOrNull {
            it.board == board && (it.scryfallId == card.id || it.name.equals(card.name, ignoreCase = true))
        }
        val row = existing?.copy(quantity = existing.quantity + quantity)
            ?: DeckMapper.fromScryfall(deckId, board, card, quantity)
        decks.upsertCards(listOf(row))
        decks.get(deckId)?.let { deck ->
            if (deck.featuredScryfallId == null) decks.upsert(deck.copy(featuredScryfallId = row.scryfallId))
        }
        if (board == Boards.COMMANDERS) updateCommanders(deckId)
        enqueue(
            deckId,
            DeckOp.SetQuantity(
                board, row.cardKey, row.name, row.scryfallId, row.moxCardId, row.quantity,
                row.setCode.ifBlank { null }, row.collectorNumber,
            ),
            targetKey = "$board|${row.cardKey}",
        )
    }

    /** Cambia el arte/edición de una carta del mazo por [printing]. */
    suspend fun changePrinting(deckId: String, board: String, cardKey: String, printing: ScryCard) {
        val deck = requireEditable(deckId)
        val card = decks.getCard(deckId, board, cardKey) ?: return
        if (card.scryfallId == printing.id) return
        decks.upsertCards(
            listOf(
                card.copy(
                    scryfallId = printing.id,
                    moxCardId = null,
                    setCode = printing.set,
                    collectorNumber = printing.collectorNumber,
                    rarity = printing.rarity ?: card.rarity,
                    hasBackImage = printing.hasBackImage,
                    priceEur = printing.prices.eur?.toDoubleOrNull(),
                    priceUsd = printing.prices.usd?.toDoubleOrNull(),
                )
            )
        )
        if (deck.featuredScryfallId == card.scryfallId) {
            decks.get(deckId)?.let { decks.upsert(it.copy(featuredScryfallId = printing.id)) }
        }
        enqueue(
            deckId,
            DeckOp.ChangePrinting(
                board = board,
                cardKey = cardKey,
                name = card.name,
                oldMoxCardId = card.moxCardId,
                oldScryfallId = card.scryfallId,
                newScryfallId = printing.id,
                setCode = printing.set,
                collectorNumber = printing.collectorNumber,
                foil = card.isFoil,
            ),
            targetKey = "print|$board|$cardKey",
        )
    }

    suspend fun rename(deckId: String, name: String) {
        val deck = requireEditable(deckId)
        decks.upsert(deck.copy(name = name.trim()))
        enqueue(deckId, DeckOp.Rename(name.trim()), targetKey = "name")
    }

    suspend fun createDeck(name: String, format: String, visibility: String = "public"): String {
        val id = newLocalId()
        decks.upsert(newLocalDeck(id, name, format).copy(visibility = visibility))
        enqueue(id, DeckOp.CreateDeck(name, format, visibility = visibility))
        return id
    }

    /** Copia cualquier mazo (propio o ajeno) como mazo nuevo, sin necesidad de conexión. */
    suspend fun copyDeck(sourceId: String, name: String): String {
        val source = decks.get(sourceId) ?: error(I18n.strings.errDeckNotDownloaded)
        val id = newLocalId()
        decks.replaceDeck(
            newLocalDeck(id, name, source.format).copy(
                description = source.description,
                colorIdentity = source.colorIdentity,
                featuredScryfallId = source.featuredScryfallId,
                featuredMoxCardId = source.featuredMoxCardId,
                mainboardCount = source.mainboardCount,
                originalPublicId = source.publicId.takeUnless { source.isLocalOnly },
                commanders = source.commanders,
                bracket = source.bracket,
                hubs = source.hubs,
                viewedAt = System.currentTimeMillis(),
            ),
            decks.getCards(sourceId).map { it.copy(deckId = id) },
        )
        enqueue(id, DeckOp.CreateDeck(name, source.format, copiedFrom = source.publicId))
        return id
    }

    suspend fun importText(name: String, format: String, text: String): ImportResult {
        val lines = DeckText.parse(text)
        require(lines.isNotEmpty()) { I18n.strings.errNothingRecognized }
        val resolved = withContext(Dispatchers.IO) { cards.resolve(lines) }
        val id = newLocalId()
        val rows = rowsFor(id, resolved)
        val featured = rows.firstOrNull { it.board == Boards.COMMANDERS } ?: rows.firstOrNull { it.board == Boards.MAIN }
        decks.replaceDeck(
            newLocalDeck(id, name, format).copy(
                featuredScryfallId = featured?.scryfallId,
                colorIdentity = DeckMapper.colors(rows.flatMap { r -> r.colorIdentity.map { it.toString() } }),
                mainboardCount = rows.filter { it.board == Boards.MAIN }.sumOf { it.quantity },
                commanders = DeckMapper.commanderNames(rows),
                viewedAt = System.currentTimeMillis(),
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

    /** Copia del mazo para meterla en un enlace compartido. */
    suspend fun snapshot(deckId: String): DeckSnapshot {
        val deck = decks.get(deckId) ?: error(I18n.strings.errDeckNotDownloaded)
        return DeckSnapshot(
            id = deck.publicId.takeUnless { deck.isLocalOnly },
            n = deck.name,
            f = deck.format,
            a = deck.authorUserName,
            t = exportText(deckId, includePrintings = true),
        )
    }

    /**
     * Mazo recibido por enlace: si aún no está en el móvil, se crea a partir de la copia del
     * enlace (por si Moxfield no responde). Al abrirlo con conexión se actualiza desde Moxfield.
     */
    suspend fun seedFromSnapshot(snapshot: DeckSnapshot) {
        val publicId = snapshot.id ?: return
        if (decks.get(publicId)?.isFullyLoaded == true) return
        val lines = DeckText.parse(snapshot.t)
        if (lines.isEmpty()) return
        val resolved = withContext(Dispatchers.IO) { cards.resolve(lines) }
        val rows = rowsFor(publicId, resolved)
        // Puede que mientras tanto se haya descargado de Moxfield: entonces no se toca.
        val current = decks.get(publicId)
        if (current?.isFullyLoaded == true) return
        val featured = rows.firstOrNull { it.board == Boards.COMMANDERS } ?: rows.firstOrNull { it.board == Boards.MAIN }
        decks.replaceDeck(
            DeckEntity(
                publicId = publicId,
                name = snapshot.n,
                format = snapshot.f,
                authorUserName = snapshot.a,
                colorIdentity = DeckMapper.colors(rows.flatMap { r -> r.colorIdentity.map { it.toString() } }),
                featuredScryfallId = featured?.scryfallId,
                featuredMoxCardId = current?.featuredMoxCardId,
                mainboardCount = rows.filter { it.board == Boards.MAIN }.sumOf { it.quantity },
                isSaved = current?.isSaved ?: false,
                fetchedAt = System.currentTimeMillis(),
                commanders = DeckMapper.commanderNames(rows),
                bracket = current?.bracket,
                hubs = current?.hubs.orEmpty(),
                viewedAt = System.currentTimeMillis(),
            ),
            rows,
        )
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

    suspend fun deletePendingOp(id: Long) {
        ops.delete(id)
        cleanLocalChangeFlags()
    }

    // ---------- Subida ----------

    /**
     * Sube la cola a Moxfield. Devuelve false si hay que reintentar más tarde (sin red, Moxfield
     * bloqueado o caído, sesión caducada…). Repasa la cola hasta vaciarla, así recoge también los
     * cambios hechos mientras subía.
     */
    suspend fun pushPendingOps(): Boolean = pushMutex.withLock {
        if (!writer.isAvailable) return@withLock true
        val touched = mutableSetOf<String>()
        var complete = true
        var pass = 0
        while (pass++ < MAX_PASSES) {
            val pending = ops.getAll()
            if (pending.isEmpty()) break
            val blocked = mutableSetOf<String>()
            var progressed = false
            for (entity in pending) {
                if (entity.deckId in blocked) continue
                val deck = decks.get(entity.deckId)
                val op = runCatching { Http.json.decodeFromString(DeckOp.serializer(), entity.payload) }.getOrNull()
                if (deck == null || op == null) {
                    ops.delete(entity.id)
                    continue
                }
                try {
                    when (val result = writer.apply(deck, op, decks.getCards(deck.publicId))) {
                        WriteResult.Done -> {
                            ops.delete(entity.id)
                            touched += deck.publicId
                            progressed = true
                        }
                        is WriteResult.Created -> {
                            // Se creó con todo su contenido actual: el resto de su cola sobra.
                            ops.deleteForDeck(deck.publicId)
                            decks.changeDeckId(deck, result.publicId, result.remoteId)
                            touched += result.publicId
                            blocked += deck.publicId
                            progressed = true
                        }
                        WriteResult.NotSupported -> blocked += deck.publicId
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ops.update(entity.copy(attempts = entity.attempts + 1, lastError = e.userMessage()))
                    if (e.isTransient()) {
                        // Sin red, bloqueo, límite de peticiones o sesión: parar y reintentar luego.
                        complete = false
                        break
                    }
                    // Error de esta operación (Moxfield la rechaza): se deja en cola con el error.
                    blocked += deck.publicId
                }
            }
            if (!complete || !progressed) break
        }
        cleanLocalChangeFlags()
        // Traer la versión canónica de los mazos subidos (ids de cartas, versión…).
        touched.forEach { id ->
            if (ops.countForDeck(id) == 0) safely { refreshDeck(id, force = true) }
        }
        complete
    }

    private fun Exception.isTransient(): Boolean = when (this) {
        is MoxError.WriteRejected, is MoxError.NotOwner, is MoxError.NotFound -> false
        is MoxError -> true // bloqueo, Cloudflare, 429, 5xx, sesión
        is IOException -> true
        else -> false
    }

    private suspend fun cleanLocalChangeFlags() {
        decks.getWithLocalChanges().forEach { deck ->
            if (!deck.isLocalOnly && ops.countForDeck(deck.publicId) == 0) {
                decks.upsert(deck.copy(hasLocalChanges = false, hasConflict = false))
            }
        }
    }

    // ---------- Internos ----------

    private fun rowsFor(deckId: String, resolved: Map<DeckLine, ScryCard?>): List<DeckCardEntity> =
        resolved.map { (line, card) ->
            if (card != null) {
                DeckMapper.fromScryfall(deckId, line.board, card, line.quantity).copy(isFoil = line.foil)
            } else {
                DeckCardEntity(
                    deckId = deckId, board = line.board, cardKey = "name:${line.name.lowercase()}",
                    name = line.name, quantity = line.quantity,
                    setCode = line.setCode.orEmpty(), collectorNumber = line.collectorNumber,
                )
            }
        }.groupBy { it.board to it.cardKey }
            .map { (_, same) -> same.first().copy(quantity = same.sumOf { it.quantity }) }

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
        if (writer.isAvailable) SyncWorker.pushSoon(context)
    }

    private suspend fun updateCommanders(deckId: String) =
        decks.setCommanders(deckId, DeckMapper.commanderNames(decks.getCards(deckId)))

    private fun newLocalId() = "local-" + UUID.randomUUID().toString().take(13)

    private fun newLocalDeck(id: String, name: String, format: String) = DeckEntity(
        publicId = id,
        name = name.trim().ifBlank { I18n.strings.untitledDeck },
        format = format,
        authorUserName = session.state.value.userName,
        isSaved = true,
        isLocalOnly = true,
        hasLocalChanges = true,
        fetchedAt = System.currentTimeMillis(),
    )

    /** Como runCatching, pero sin tragarse la cancelación de la corrutina. */
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MAX_PASSES = 5
        const val RECHECK_MS = 30L * 24 * 60 * 60 * 1000
    }
}
