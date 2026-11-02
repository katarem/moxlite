package es.moxlite.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTrafficLogTest {

    @Test
    fun `solo registra llamadas a la API de Moxfield`() {
        val log = ApiTrafficLog()
        log.record("get", "https://www.moxfield.com/decks/abc", null, "fetch")
        log.record("get", "https://api.scryfall.com/cards/x", null, "fetch")
        log.record("post", "https://api2.moxfield.com/v2/decks", """{"name":"x"}""", "xhr")
        val call = log.calls.value.single()
        assertEquals("POST", call.method)
        assertEquals("xhr", call.source)
        assertEquals("""{"name":"x"}""", call.body)
    }

    @Test
    fun `redacta tokens en cuerpo y URL`() {
        val log = ApiTrafficLog()
        log.record(
            "POST",
            "https://api2.moxfield.com/v1/account/token?access_token=SECRETO&x=1",
            """{"refreshToken":"R1","accessToken":"A1","captchaToken":"C1","keep":"ok"}""",
            "fetch",
        )
        val call = log.calls.value.single()
        listOf("SECRETO", "R1", "A1", "C1").forEach { assertFalse("Se ha filtrado $it", (call.url + call.body).contains(it)) }
        assertTrue(call.url.endsWith("access_token=***&x=1"))
        assertTrue(call.body.contains("\"keep\":\"ok\""))
    }

    @Test
    fun `evita duplicados seguidos y limita el tamano`() {
        val log = ApiTrafficLog(maxEntries = 3)
        log.record("GET", "https://api2.moxfield.com/a", null, "fetch")
        log.record("GET", "https://api2.moxfield.com/a", null, "xhr")
        assertEquals(1, log.calls.value.size)

        (1..5).forEach { log.record("GET", "https://api2.moxfield.com/n$it", null, "fetch") }
        assertEquals(listOf("n3", "n4", "n5"), log.calls.value.map { it.url.substringAfterLast('/') })
    }

    @Test
    fun `recorta cuerpos enormes`() {
        val log = ApiTrafficLog()
        log.record("PUT", "https://api2.moxfield.com/x", "a".repeat(10_000), "fetch")
        assertEquals(4_000, log.calls.value.single().body.length)
    }

    @Test
    fun `exporta y limpia`() {
        val log = ApiTrafficLog()
        log.record("GET", "https://api2.moxfield.com/a", null, "fetch")
        log.record("POST", "https://api2.moxfield.com/b", "{}", "fetch")
        val text = log.export()
        assertTrue(text.contains("GET https://api2.moxfield.com/a"))
        assertTrue(text.contains("POST https://api2.moxfield.com/b\n{}"))
        assertEquals(2, text.split("\n\n").size)

        log.clear()
        assertEquals("", log.export())
    }

    @Test
    fun `reconoce los dominios de la API`() {
        assertTrue(ApiTrafficLog.isMoxfieldApi("https://api2.moxfield.com/v3/decks"))
        assertTrue(ApiTrafficLog.isMoxfieldApi("https://api.moxfield.com/v2/x"))
        assertFalse(ApiTrafficLog.isMoxfieldApi("https://moxfield.com/decks/x"))
    }
}
