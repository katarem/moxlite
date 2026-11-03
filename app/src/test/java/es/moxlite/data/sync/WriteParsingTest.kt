package es.moxlite.data.sync

import es.moxlite.data.remote.Http
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
}
