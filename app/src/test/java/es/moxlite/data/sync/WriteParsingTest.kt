package es.moxlite.data.sync

import es.moxlite.data.remote.Http
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteParsingTest {

    private fun json(text: String) = Http.json.parseToJsonElement(text)

    @Test
    fun `saca el id de una carta suelta, envuelta o en una pagina`() {
        assertEquals("abc12", WriteParsing.extractCardId(json("""{"id":"abc12","name":"Sol Ring","set":"c21"}""")))
        assertEquals("abc12", WriteParsing.extractCardId(json("""{"card":{"id":"abc12","name":"Sol Ring"}}""")))
        assertEquals("p1", WriteParsing.extractCardId(json("""{"pageNumber":1,"data":[{"id":"p1","name":"A"},{"id":"p2","name":"A"}]}""")))
        assertEquals("p1", WriteParsing.extractCardId(json("""[{"id":"p1","scryfall_id":"x"}]""")))
    }

    @Test
    fun `prefiere la edicion y numero pedidos`() {
        val page = json("""{"data":[{"id":"a","name":"Bolt","set":"m10","cn":"146"},{"id":"b","name":"Bolt","set":"2x2","cn":"117"}]}""")
        assertEquals("b", WriteParsing.extractCardId(page, "2X2", "117"))
        assertEquals("a", WriteParsing.extractCardId(page, "zzz", "1"))
    }

    @Test
    fun `sin cartas devuelve null`() {
        assertNull(WriteParsing.extractCardId(json("""{"pageNumber":1,"data":[]}""")))
        assertNull(WriteParsing.extractCardId(json("""{"id":"deck","publicId":"x"}""")))
        assertNull(WriteParsing.extractCardId(json("\"texto\"")))
    }

    @Test
    fun `compara nombres sin mayusculas y por la cara frontal`() {
        assertTrue(WriteParsing.sameName("Sol Ring", "sol ring"))
        assertTrue(WriteParsing.sameName("Fire // Ice", "Fire"))
        assertTrue(WriteParsing.sameName("Fire", "Fire // Ice"))
        assertFalse(WriteParsing.sameName("Fire // Ice", "Ice"))
    }

    @Test
    fun `resume el cuerpo de error`() {
        assertEquals("Deck version mismatch", WriteParsing.errorDetail("""{"title":"Deck version mismatch","status":409}"""))
        assertEquals("texto plano", WriteParsing.errorDetail("  texto\n plano "))
        assertEquals(160, WriteParsing.errorDetail("x".repeat(500)).length)
    }

    @Test
    fun `reads the created deck at the top level or wrapped`() {
        assertEquals(CreatedDeck("pub1", "id1", 3), WriteParsing.createdDeck("""{"id":"id1","publicId":"pub1","version":3}"""))
        assertEquals(CreatedDeck("pub1", "id1", null), WriteParsing.createdDeck("""{"deck":{"id":"id1","publicId":"pub1"}}"""))
        assertEquals(CreatedDeck("pub1", null, null), WriteParsing.createdDeck("""{"data":{"result":{"publicId":"pub1"}}}"""))
    }

    @Test
    fun `no created deck in an answer without a publicId`() {
        assertNull(WriteParsing.createdDeck("""{"id":"id1","name":"Deck"}"""))
        assertNull(WriteParsing.createdDeck("""{"publicId":""}"""))
        assertNull(WriteParsing.createdDeck(""))
        assertNull(WriteParsing.createdDeck("not json"))
    }

    @Test
    fun `a copy of a precon gets a regular format`() {
        assertEquals("commander", WriteParsing.formatForNewDeck("commanderPrecons"))
        assertEquals("none", WriteParsing.formatForNewDeck("precons"))
        assertEquals("none", WriteParsing.formatForNewDeck(""))
        assertEquals("modern", WriteParsing.formatForNewDeck("modern"))
    }

    private val since = 1_762_600_000_000L // 2025-11-08T11:06:40Z

    private fun summary(publicId: String, name: String = "Destructor de tierras", createdAt: String? = "2025-11-08T11:10:00Z", cards: Int = 0) =
        MoxDeckSummaryDto(id = "id-$publicId", publicId = publicId, name = name, createdAtUtc = createdAt, mainboardCount = cards)

    @Test
    fun `reuses the newest empty deck with the same name created since the local one`() {
        val decks = listOf(
            summary("old", createdAt = "2025-11-08T09:00:00Z"),
            summary("first", createdAt = "2025-11-08T11:10:00Z"),
            summary("latest", name = " destructor de TIERRAS ", createdAt = "2025-11-08T16:05:56.29Z"),
            summary("other", name = "Otro mazo", createdAt = "2025-11-08T16:30:00Z"),
        )

        assertEquals("latest", WriteParsing.pickCreatedDeck(decks, "Destructor de tierras", since)?.publicId)
    }

    @Test
    fun `never reuses a deck with cards, without a creation date or older than the local one`() {
        val decks = listOf(
            summary("withCards", cards = 87),
            summary("noDate", createdAt = null),
            summary("older", createdAt = "2025-11-08T10:00:00Z"),
        )

        assertNull(WriteParsing.pickCreatedDeck(decks, "Destructor de tierras", since))
    }
}
