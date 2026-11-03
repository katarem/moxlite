package es.moxlite.data.repo

import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.scryfall.ScryCollectionRequest
import es.moxlite.data.remote.scryfall.ScryList
import es.moxlite.data.remote.scryfall.ScryNames
import es.moxlite.data.remote.scryfall.ScryfallService
import es.moxlite.domain.DeckLine
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CardRepositoryTest {

    /** Scryfall falso: responde con las cartas de [catalog] que coincidan con cada identificador. */
    private class FakeScryfall(
        private val catalog: List<ScryCard> = emptyList(),
        private val searchError: Int? = null,
    ) : ScryfallService {
        val collectionCalls = mutableListOf<ScryCollectionRequest>()
        val autocompleteCalls = mutableListOf<String>()
        val advancedCalls = mutableListOf<Map<String, String>>()

        /** Respuesta de searchAdvanced (por defecto, todo el catálogo). */
        var advancedResult: (Map<String, String>) -> List<ScryCard> = { catalog }

        override suspend fun autocomplete(q: String): ScryNames {
            autocompleteCalls += q
            return ScryNames(listOf("$q 1", "$q 2"))
        }

        override suspend fun search(q: String, unique: String, order: String, page: Int): ScryList<ScryCard> {
            searchError?.let { throw HttpException(Response.error<Any>(it, "{}".toResponseBody())) }
            return ScryList(catalog.filter { it.name.contains(q, ignoreCase = true) })
        }

        override suspend fun searchAdvanced(params: Map<String, String>): ScryList<ScryCard> {
            advancedCalls += params
            searchError?.let { throw HttpException(Response.error<Any>(it, "{}".toResponseBody())) }
            return ScryList(advancedResult(params))
        }

        override suspend fun printing(set: String, number: String): ScryCard =
            catalog.first { it.set == set && it.collectorNumber == number && it.lang == "en" }

        override suspend fun named(exact: String): ScryCard = catalog.first { it.name == exact }
        override suspend fun byId(id: String): ScryCard = catalog.first { it.id == id }

        override suspend fun collection(body: ScryCollectionRequest): ScryList<ScryCard> {
            collectionCalls += body
            val found = body.identifiers.mapNotNull { ident ->
                catalog.firstOrNull { card ->
                    if (ident.set != null) card.set == ident.set && card.collectorNumber == ident.collectorNumber
                    else card.name.lowercase().split(" // ").any { it == ident.name!!.lowercase() } ||
                        card.name.equals(ident.name, ignoreCase = true)
                }
            }
            return ScryList(found.distinct())
        }
    }

    private val bolt2x2 = ScryCard(id = "b1", name = "Lightning Bolt", set = "2x2", collectorNumber = "117")
    private val boltM10 = ScryCard(id = "b2", name = "Lightning Bolt", set = "m10", collectorNumber = "146")
    private val fireIce = ScryCard(id = "fi", name = "Fire // Ice", set = "mh2", collectorNumber = "290")
    private val island = ScryCard(id = "is", name = "Island", set = "unf", collectorNumber = "235")

    @Test
    fun `autocompletar ignora consultas de menos de dos letras`() = runTest {
        val api = FakeScryfall()
        val repo = CardRepository(api)
        assertEquals(emptyList<String>(), repo.autocomplete(" a "))
        assertEquals(listOf("sol 1", "sol 2"), repo.autocomplete("  sol "))
        assertEquals(listOf("sol"), api.autocompleteCalls)
    }

    @Test
    fun `buscar sin resultados (404) devuelve lista vacia`() = runTest {
        assertEquals(emptyList<ScryCard>(), CardRepository(FakeScryfall(searchError = 404)).search("zzz"))
    }

    @Test
    fun `buscar con otros errores los propaga`() = runTest {
        try {
            CardRepository(FakeScryfall(searchError = 500)).search("bolt")
            fail("Debería lanzar HttpException")
        } catch (e: HttpException) {
            assertEquals(500, e.code())
        }
    }

    @Test
    fun `resolve respeta edicion y numero, y si no cae al nombre`() = runTest {
        val api = FakeScryfall(listOf(bolt2x2, boltM10, fireIce, island))
        val withPrinting = DeckLine(1, "Lightning Bolt", setCode = "m10", collectorNumber = "146")
        val byName = DeckLine(4, "island")
        val splitHalf = DeckLine(1, "Fire")
        val splitFull = DeckLine(1, "Fire // Ice", Boards.SIDE)
        val unknown = DeckLine(1, "Carta Inventada")

        val result = CardRepository(api).resolve(listOf(withPrinting, byName, splitHalf, splitFull, unknown))

        assertSame(boltM10, result[withPrinting])
        assertSame(island, result[byName])
        assertSame(fireIce, result[splitHalf])
        assertSame(fireIce, result[splitFull])
        assertNull(result[unknown])
        assertTrue(result.containsKey(unknown))
        // Conserva el orden de entrada.
        assertEquals(listOf(withPrinting, byName, splitHalf, splitFull, unknown), result.keys.toList())

        val idents = api.collectionCalls.single().identifiers
        assertEquals("m10", idents[0].set)
        assertEquals("146", idents[0].collectorNumber)
        assertNull(idents[0].name)
        assertEquals("island", idents[1].name)
        assertNull(idents[1].set)
    }

    @Test
    fun `resolve cae al nombre si la impresion pedida no existe pero la carta vino en el lote`() = runTest {
        val api = FakeScryfall(listOf(bolt2x2))
        val wrongPrinting = DeckLine(1, "Lightning Bolt", setCode = "xxx", collectorNumber = "1")
        val byName = DeckLine(1, "Lightning Bolt", Boards.SIDE)
        val result = CardRepository(api).resolve(listOf(wrongPrinting, byName))
        assertSame(bolt2x2, result[wrongPrinting])
        assertSame(bolt2x2, result[byName])
    }

    @Test
    fun `resolve devuelve null si ni la impresion ni el nombre aparecen`() = runTest {
        val line = DeckLine(1, "Lightning Bolt", setCode = "xxx", collectorNumber = "1")
        assertNull(CardRepository(FakeScryfall(listOf(bolt2x2))).resolve(listOf(line))[line])
    }

    @Test
    fun `resolve trocea en lotes de 75`() = runTest {
        val api = FakeScryfall(listOf(island))
        val lines = (1..160).map { DeckLine(1, "Carta $it") }
        val result = CardRepository(api).resolve(lines)
        assertEquals(listOf(75, 75, 10), api.collectionCalls.map { it.identifiers.size })
        assertEquals(160, result.size)
    }

    @Test
    fun `buscar en cualquier idioma pide cartas multilingues y no busca con menos de dos letras`() = runTest {
        val rayo = ScryCard(id = "es1", name = "Lightning Bolt", printedName = "Rayo", lang = "es", set = "m10", collectorNumber = "146")
        val api = FakeScryfall(listOf(rayo))
        val repo = CardRepository(api)

        assertTrue(repo.searchAnyLanguage("r").isEmpty())
        assertTrue(api.advancedCalls.isEmpty())

        val found = repo.searchAnyLanguage(" rayo ")
        assertEquals(listOf(rayo), found)
        val params = api.advancedCalls.single()
        assertEquals("rayo", params["q"])
        assertEquals("true", params["include_multilingual"])
        assertEquals("Rayo", found.single().displayName)
    }

    @Test
    fun `buscar en cualquier idioma sin resultados (404) devuelve lista vacia`() = runTest {
        assertTrue(CardRepository(FakeScryfall(searchError = 404)).searchAnyLanguage("nada").isEmpty())
    }

    @Test
    fun `printings pide todas las impresiones por nombre exacto`() = runTest {
        val api = FakeScryfall(listOf(bolt2x2, boltM10))
        CardRepository(api).printings("Lightning Bolt")
        val params = api.advancedCalls.single()
        assertEquals("!\"Lightning Bolt\"", params["q"])
        assertEquals("prints", params["unique"])
    }

    @Test
    fun `englishPrinting cambia una carta en otro idioma por la inglesa de la misma edicion`() = runTest {
        val rayo = ScryCard(id = "es1", name = "Lightning Bolt", printedName = "Rayo", lang = "es", set = "m10", collectorNumber = "146")
        val repo = CardRepository(FakeScryfall(listOf(bolt2x2, boltM10, rayo)))
        assertSame(boltM10, repo.englishPrinting(rayo))
        assertSame(bolt2x2, repo.englishPrinting(bolt2x2))
    }

    @Test
    fun `localize busca por lotes en el idioma pedido y marca las que no existen`() = runTest {
        val rayoM10 = ScryCard(id = "es1", name = "Lightning Bolt", printedName = "Rayo", lang = "es", set = "m10", collectorNumber = "146")
        val api = FakeScryfall().apply { advancedResult = { listOf(rayoM10) } }
        val repo = CardRepository(api)
        val keys = (1..20).map { PrintingKey("set$it", "$it") } + PrintingKey("m10", "146")

        val result = repo.localize(keys, "es")

        assertEquals(2, api.advancedCalls.size) // 21 claves en lotes de 15
        assertTrue(api.advancedCalls.first()["q"]!!.startsWith("lang:es ("))
        assertTrue(api.advancedCalls.first()["q"]!!.contains("(set:set1 cn:\"1\")"))
        assertEquals(rayoM10, result[PrintingKey("m10", "146")])
        assertNull(result[PrintingKey("set1", "1")])
        assertEquals(21, result.size)
    }

    @Test
    fun `localize en ingles no hace nada`() = runTest {
        val api = FakeScryfall()
        assertTrue(CardRepository(api).localize(listOf(PrintingKey("m10", "146")), "en").isEmpty())
        assertTrue(api.advancedCalls.isEmpty())
    }

    @Test
    fun `PrintingKey normaliza la edicion y descarta claves incompletas`() {
        assertEquals(PrintingKey("m10", "146"), PrintingKey.of("M10", "146"))
        assertNull(PrintingKey.of("", "146"))
        assertNull(PrintingKey.of("m10", null))
        assertNull(PrintingKey.of("m10", ""))
    }
}
