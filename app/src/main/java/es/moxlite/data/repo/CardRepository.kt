package es.moxlite.data.repo

import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.scryfall.ScryCollectionRequest
import es.moxlite.data.remote.scryfall.ScryIdentifier
import es.moxlite.data.remote.scryfall.ScryfallService
import es.moxlite.domain.DeckLine
import kotlinx.coroutines.delay
import retrofit2.HttpException

/** Clave de una impresión: edición (en minúsculas) y número de coleccionista. */
data class PrintingKey(val set: String, val collectorNumber: String) {
    companion object {
        fun of(set: String, collectorNumber: String?) =
            collectorNumber?.takeIf { set.isNotBlank() && it.isNotBlank() }?.let { PrintingKey(set.lowercase(), it) }
    }
}

/** Datos de carta desde Scryfall (búsqueda, autocompletado, resolución de listas, idiomas). */
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
     * Búsqueda que no depende del idioma: "rayo" encuentra la impresión en español de Lightning
     * Bolt y "lightning bolt" la inglesa. Admite también sintaxis de Scryfall.
     */
    suspend fun searchAnyLanguage(text: String): List<ScryCard> {
        val q = text.trim()
        if (q.length < 2) return emptyList()
        return searchOrEmpty(
            mapOf("q" to q, "include_multilingual" to "true", "unique" to "cards", "order" to "name")
        ).take(MAX_RESULTS)
    }

    /**
     * Cartas que pueden ser comandante cuyo nombre (en cualquier idioma) contiene [text].
     * Devuelve pares (nombre a mostrar, nombre en inglés), sin repetir.
     */
    suspend fun commanderSuggestions(text: String): List<Pair<String, String>> {
        val q = text.trim().replace("\"", "")
        if (q.length < 2) return emptyList()
        return searchOrEmpty(
            mapOf("q" to "is:commander \"$q\"", "include_multilingual" to "true", "unique" to "cards", "order" to "edhrec")
        ).map { it.displayName to it.name }.distinctBy { it.second }.take(MAX_RESULTS)
    }

    /**
     * Como [commanderSuggestions], pero con una condición de Scryfall propia ([filter]), para
     * buscar la pareja de un comandante (partner, background…). Devuelve las cartas.
     */
    suspend fun commanderSearch(text: String, filter: String): List<ScryCard> {
        val q = text.trim().replace("\"", "")
        val query = if (q.isEmpty()) filter else "$filter \"$q\""
        return searchOrEmpty(
            mapOf("q" to query, "include_multilingual" to "true", "unique" to "cards", "order" to "edhrec")
        ).distinctBy { it.oracleId ?: it.name }.take(MAX_RESULTS)
    }

    /** Cartas por id de Scryfall, en lotes de 75. Las que no existen no aparecen. */
    suspend fun byIds(ids: List<String>): Map<String, ScryCard> {
        val result = LinkedHashMap<String, ScryCard>()
        ids.distinct().chunked(75).forEachIndexed { index, chunk ->
            if (index > 0) delay(120)
            api.collection(ScryCollectionRequest(chunk.map { ScryIdentifier(id = it) })).data.forEach { result[it.id] = it }
        }
        return result
    }

    /** Todas las impresiones (artes) de una carta, de la más nueva a la más antigua. */
    suspend fun printings(cardName: String): List<ScryCard> = searchOrEmpty(
        mapOf(
            "q" to "!\"${cardName.replace("\"", "")}\"",
            "unique" to "prints",
            "order" to "released",
            "dir" to "desc",
            "include_extras" to "true",
        )
    )

    /** La impresión inglesa equivalente (mismo set y número) de una carta en otro idioma. */
    suspend fun englishPrinting(card: ScryCard): ScryCard =
        if (card.lang == "en") card
        else runCatching { api.printing(card.set, card.collectorNumber) }.getOrDefault(card)

    /**
     * Busca las mismas impresiones en otro idioma, por lotes. Las que no existen en ese idioma
     * vuelven como null (se muestran en inglés).
     */
    suspend fun localize(keys: Collection<PrintingKey>, lang: String): Map<PrintingKey, ScryCard?> {
        val result = LinkedHashMap<PrintingKey, ScryCard?>()
        if (lang == "en") return result
        keys.distinct().chunked(LOCALIZE_BATCH).forEachIndexed { index, chunk ->
            if (index > 0) delay(120) // ~10 peticiones/s como máximo
            val query = "lang:$lang (" + chunk.joinToString(" or ") {
                "(set:${it.set} cn:\"${it.collectorNumber.replace("\"", "")}\")"
            } + ")"
            val found = searchOrEmpty(
                mapOf("q" to query, "unique" to "prints", "include_multilingual" to "true")
            ).filter { it.lang == lang }
            chunk.forEach { key ->
                result[key] = found.firstOrNull { it.set.equals(key.set, true) && it.collectorNumber == key.collectorNumber }
            }
        }
        return result
    }

    private suspend fun searchOrEmpty(params: Map<String, String>): List<ScryCard> = try {
        api.searchAdvanced(params).data
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

    private companion object {
        const val MAX_RESULTS = 60
        const val LOCALIZE_BATCH = 15
    }
}
