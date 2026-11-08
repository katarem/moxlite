package es.moxlite.data.sync

import es.moxlite.data.diagnostics.DiagnosticLog
import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.Http
import es.moxlite.data.remote.MoxError
import es.moxlite.data.remote.moxfield.AddCardBody
import es.moxlite.data.remote.moxfield.CreateDeckBody
import es.moxlite.data.remote.moxfield.DeckInfoBody
import es.moxlite.data.remote.moxfield.EditionBody
import es.moxlite.data.remote.moxfield.ImportBody
import es.moxlite.data.remote.moxfield.MoveBody
import es.moxlite.data.remote.moxfield.MoxBoardCardDto
import es.moxlite.data.remote.moxfield.MoxDeckDto
import es.moxlite.data.remote.moxfield.MoxfieldService
import es.moxlite.data.remote.moxfield.QuantityBody
import es.moxlite.data.session.SessionManager
import es.moxlite.domain.DeckLine
import es.moxlite.domain.DeckText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

/**
 * Sube la cola de cambios a Moxfield con las mismas llamadas que hace su web.
 *
 * Antes de cada operación se lee el mazo remoto: así se conoce la versión actual
 * (`X-Deck-Version`) y el id de cada carta, y las operaciones son idempotentes (fijan cantidades
 * absolutas, mover algo que ya no está no hace nada…), lo que permite reintentar sin duplicar.
 */
class MoxfieldApiWriter(
    private val api: MoxfieldService,
    private val session: SessionManager,
    private val log: DiagnosticLog,
) : MoxfieldWriter {

    override val isAvailable: Boolean get() = session.state.value.hasAccount

    override suspend fun apply(deck: DeckEntity, op: DeckOp, cards: List<DeckCardEntity>): WriteResult =
        withContext(Dispatchers.IO) {
            if (!isAvailable) {
                log.w(TAG, "${op.typeName} on ${deck.publicId} not sent: no Moxfield account")
                return@withContext WriteResult.NotSupported
            }
            if (op is DeckOp.CreateDeck) return@withContext create(deck, op, cards)
            // Un mazo local primero tiene que crearse (su CreateDeck va antes en la cola).
            if (deck.isLocalOnly) {
                log.w(TAG, "${op.typeName} on ${deck.publicId} waits: the deck has not been created in Moxfield yet")
                return@withContext WriteResult.NotSupported
            }

            val remote = api.deck(deck.publicId)
            if (!isMine(remote)) {
                log.w(TAG, "${deck.publicId} is not owned by the signed-in account (author=${remote.createdByUser?.userName}, authors=${remote.authors.size})")
                throw MoxError.NotOwner()
            }
            when (op) {
                is DeckOp.SetQuantity -> setQuantity(remote, op)
                is DeckOp.MoveCard -> move(remote, op)
                is DeckOp.Rename -> ok(
                    api.updateDeck(
                        remote.id, remote.publicId, remote.version.toString(),
                        DeckInfoBody(op.name, remote.format, remote.description, remote.visibility),
                    )
                )
                is DeckOp.ChangePrinting -> changePrinting(remote, op)
                is DeckOp.CreateDeck -> Unit // tratado arriba
            }
            WriteResult.Done
        }

    private fun isMine(remote: MoxDeckDto): Boolean {
        val me = session.state.value.userName
        if (me.isBlank()) return false
        return remote.createdByUser?.userName.equals(me, ignoreCase = true) ||
            remote.authors.any { it.userName.equals(me, ignoreCase = true) }
    }

    // ---------- Operaciones ----------

    private suspend fun create(deck: DeckEntity, op: DeckOp.CreateDeck, cards: List<DeckCardEntity>): WriteResult {
        log.i(TAG, "Creating ${deck.publicId} in Moxfield (format=${deck.format.ifBlank { op.format }}, cards=${cards.size}, copiedFrom=${op.copiedFrom})")
        val response = api.createDeck(
            CreateDeckBody(
                name = deck.name.ifBlank { op.name },
                format = deck.format.ifBlank { op.format }.ifBlank { "none" },
                visibility = deck.visibility.ifBlank { op.visibility },
                description = deck.description,
            )
        )
        val created = parseObject(ok(response)) ?: throw MoxError.WriteRejected(response.code(), "sin respuesta")
        val publicId = created.str("publicId") ?: throw MoxError.WriteRejected(response.code(), "sin publicId")
        val remoteId = created.str("id") ?: api.deck(publicId).id
        log.i(TAG, "${deck.publicId} created in Moxfield as $publicId; importing its cards")

        if (cards.isNotEmpty()) {
            val text = importText(cards)
            val version = (created["version"] as? JsonPrimitive)?.intOrNull
            val first = api.importText(remoteId, publicId, version?.toString(), ImportBody(text))
            if (!first.isSuccessful) {
                // Puede que la versión no fuera la esperada: se relee y se reintenta una vez.
                log.w(TAG, "Import into $publicId rejected with HTTP ${first.code()}: retrying once with a fresh version")
                first.errorBody()?.close()
                val fresh = api.deck(publicId)
                ok(api.importText(remoteId, publicId, fresh.version.toString(), ImportBody(text)))
            } else {
                first.body()?.close()
            }
        }
        log.i(TAG, "${deck.publicId} uploaded as $publicId")
        return WriteResult.Created(publicId, remoteId)
    }

    private suspend fun setQuantity(remote: MoxDeckDto, op: DeckOp.SetQuantity) {
        val entry = findEntry(remote, op.board, op.name, op.moxCardId, op.scryfallId)
        val version = remote.version.toString()
        when {
            op.quantity <= 0 -> if (entry != null) {
                try {
                    ok(api.removeCard(remote.id, op.board, entry.card.id, remote.publicId, version))
                } catch (_: MoxError.NotFound) {
                    // Ya no estaba.
                }
            }
            entry != null -> if (entry.quantity != op.quantity) {
                ok(api.setQuantity(remote.id, op.board, entry.card.id, remote.publicId, version, QuantityBody(op.quantity)))
            }
            else -> {
                val cardId = op.moxCardId ?: resolveCardId(op.name, op.setCode, op.collectorNumber)
                ok(api.addCard(remote.id, op.board, remote.publicId, version, AddCardBody(cardId, op.quantity)))
            }
        }
    }

    private suspend fun move(remote: MoxDeckDto, op: DeckOp.MoveCard) {
        if (op.fromBoard == op.toBoard) return
        val source = findEntry(remote, op.fromBoard, op.name, null, null) ?: return // ya movida
        val cardId = source.card.id
        val qty = source.quantity

        val moved = try {
            api.moveCard(remote.id, op.fromBoard, cardId, op.toBoard, qty, remote.publicId, remote.version.toString(), MoveBody())
                .also { if (!it.isSuccessful) it.errorBody()?.close() else it.body()?.close() }
                .isSuccessful
        } catch (_: MoxError.NotFound) {
            false
        } catch (_: HttpException) {
            false
        }
        if (moved) return

        // Plan B: añadir en el destino y quitar del origen.
        val fresh = api.deck(remote.publicId)
        val target = findEntry(fresh, op.toBoard, op.name, cardId, null)
        if (target != null) {
            ok(api.setQuantity(fresh.id, op.toBoard, target.card.id, fresh.publicId, fresh.version.toString(), QuantityBody(target.quantity + qty)))
        } else {
            ok(api.addCard(fresh.id, op.toBoard, fresh.publicId, fresh.version.toString(), AddCardBody(cardId, qty)))
        }
        val afterAdd = api.deck(remote.publicId)
        findEntry(afterAdd, op.fromBoard, op.name, cardId, null)?.let {
            ok(api.removeCard(afterAdd.id, op.fromBoard, it.card.id, afterAdd.publicId, afterAdd.version.toString()))
        }
    }

    private suspend fun changePrinting(remote: MoxDeckDto, op: DeckOp.ChangePrinting) {
        val entry = findEntry(remote, op.board, op.name, op.oldMoxCardId, op.oldScryfallId) ?: return
        if (entry.card.scryfallId == op.newScryfallId) return
        val newId = resolveCardId(op.name, op.setCode, op.collectorNumber)
        val finish = entry.finish ?: if (entry.isFoil || op.foil) "foil" else "nonFoil"
        ok(
            api.changeEdition(
                remote.id, entry.card.id, remote.publicId, remote.version.toString(),
                EditionBody(newEditionCardId = newId, finish = finish),
            )
        )
    }

    // ---------- Utilidades ----------

    /** Id de impresión de Moxfield para una carta (por nombre y, si se sabe, edición y número). */
    private suspend fun resolveCardId(name: String, set: String?, collectorNumber: String?): String {
        val attempts = buildList<suspend () -> JsonElement> {
            if (set != null && collectorNumber != null) {
                add { api.cardLookup(name, set, collectorNumber) }
                add { api.cardSearch("!\"$name\" set:$set cn:\"$collectorNumber\"") }
            }
            add { api.cardLookup(name, null, null) }
            add { api.cardSearch("!\"$name\"") }
        }
        for (attempt in attempts) {
            val id = try {
                WriteParsing.extractCardId(attempt(), set, collectorNumber)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: MoxError.Unauthorized) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (id != null) return id
        }
        throw MoxError.WriteRejected(404, name)
    }

    private fun findEntry(remote: MoxDeckDto, board: String, name: String, moxId: String?, scryfallId: String?): MoxBoardCardDto? {
        val entries = remote.boards[board]?.cards?.values.orEmpty()
        return entries.firstOrNull { moxId != null && it.card.id == moxId }
            ?: entries.firstOrNull { scryfallId != null && it.card.scryfallId == scryfallId }
            ?: entries.firstOrNull { WriteParsing.sameName(it.card.name, name) }
    }

    /** Lanza [MoxError.WriteRejected] si la respuesta no es 2xx; devuelve el cuerpo como texto. */
    private fun ok(response: Response<ResponseBody>): String {
        if (!response.isSuccessful) {
            val detail = response.errorBody()?.use { it.string() }.orEmpty()
            throw MoxError.WriteRejected(response.code(), WriteParsing.errorDetail(detail))
        }
        return response.body()?.use { it.string() }.orEmpty()
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { Http.json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun importText(cards: List<DeckCardEntity>): String = DeckText.format(
        cards.filter { it.quantity > 0 }.map {
            DeckLine(it.quantity, it.name, it.board, it.setCode.ifBlank { null }, it.collectorNumber, it.isFoil)
        },
        includePrintings = true,
    )
}

private const val TAG = "Writer"

/** Partes puras (testeables) del escritor. */
object WriteParsing {

    /**
     * Saca el id de impresión de una respuesta de búsqueda de cartas de Moxfield, sea una carta
     * suelta, `{card: …}`, una página `{data: […]}` o una lista. Si hay varias, prefiere la de la
     * edición y número pedidos.
     */
    fun extractCardId(element: JsonElement, set: String? = null, collectorNumber: String? = null): String? {
        val cards = mutableListOf<JsonObject>()
        collectCards(element, cards, depth = 0)
        if (cards.isEmpty()) return null
        fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        val exact = cards.firstOrNull {
            set != null && collectorNumber != null &&
                it.s("set").equals(set, ignoreCase = true) && it.s("cn") == collectorNumber
        }
        return (exact ?: cards.first()).s("id")
    }

    private fun collectCards(element: JsonElement, out: MutableList<JsonObject>, depth: Int) {
        if (depth > 4) return
        when (element) {
            is JsonArray -> element.forEach { collectCards(it, out, depth + 1) }
            is JsonObject -> {
                val id = (element["id"] as? JsonPrimitive)?.contentOrNull
                if (id != null && (element.containsKey("name") || element.containsKey("scryfall_id"))) {
                    out += element
                } else {
                    listOf("card", "data", "cards", "results").forEach { key ->
                        element[key]?.let { collectCards(it, out, depth + 1) }
                    }
                }
            }
            else -> Unit
        }
    }

    /** Mismo nombre, ignorando mayúsculas y comparando también solo la cara frontal. */
    fun sameName(a: String, b: String): Boolean {
        val x = a.trim().lowercase()
        val y = b.trim().lowercase()
        return x == y || x.substringBefore(" // ") == y.substringBefore(" // ")
    }

    /** Mensaje corto a partir del cuerpo de error de Moxfield. */
    fun errorDetail(body: String): String {
        val parsed = runCatching { Http.json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = parsed?.let { obj ->
            listOf("message", "title", "detail", "error").firstNotNullOfOrNull { key ->
                (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        }
        return (message ?: body).replace(Regex("\\s+"), " ").trim().take(160)
    }
}
