package es.moxlite.data.repo

import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.scryfall.ScryCollectionRequest
import es.moxlite.data.remote.scryfall.ScryIdentifier
import es.moxlite.data.remote.scryfall.ScryfallService
import es.moxlite.domain.DeckLine
import kotlinx.coroutines.delay
import retrofit2.HttpException

/** Datos de carta desde Scryfall (búsqueda, autocompletado, resolución de listas). */
class CardRepository(private val api: ScryfallService) {

    suspend fun autocomplete(query: String): List<String> =
        if (query.trim().length < 2) emptyList() else api.autocomplete(query.trim()).data

    suspend fun named(name: String): ScryCard = api.named(name)

    /** Búsqueda con sintaxis de Scryfall (p. ej. "t:dragon c:r"). Sin resultados = lista vacía. */
    suspend fun search(query: String): List<ScryCard> = try {
        api.search(query).data
    } catch (e: HttpException) {
        if (e.code() == 404) emptyList() else throw e
    }

    /**
     * Resuelve líneas de texto a cartas de Scryfall en lotes de 75 (límite de /cards/collection).
     * Las que no se encuentran devuelven null.
     */
    suspend fun resolve(lines: List<DeckLine>): Map<DeckLine, ScryCard?> {
        val result = LinkedHashMap<DeckLine, ScryCard?>()
        lines.chunked(75).forEachIndexed { index, chunk ->
            if (index > 0) delay(120) // respetar el límite de ~10 peticiones/s
            val identifiers = chunk.map { line ->
                if (line.setCode != null && line.collectorNumber != null) {
                    ScryIdentifier(set = line.setCode, collectorNumber = line.collectorNumber)
                } else {
                    ScryIdentifier(name = line.name)
                }
            }
            val found = api.collection(ScryCollectionRequest(identifiers)).data
            chunk.forEach { line ->
                result[line] = found.firstOrNull { card ->
                    if (line.setCode != null && line.collectorNumber != null) {
                        card.set.equals(line.setCode, true) && card.collectorNumber.equals(line.collectorNumber, true)
                    } else {
                        nameMatches(card.name, line.name)
                    }
                } ?: found.firstOrNull { nameMatches(it.name, line.name) }
            }
        }
        return result
    }

    private fun nameMatches(cardName: String, wanted: String): Boolean {
        val a = cardName.lowercase()
        val b = wanted.lowercase()
        return a == b || a.substringBefore(" // ") == b || a == b.substringBefore(" // ")
    }
}
