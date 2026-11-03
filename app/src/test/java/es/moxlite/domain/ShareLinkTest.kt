package es.moxlite.domain

import es.moxlite.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkTest {

    private val list = """
        Commander
        1 Atraxa, Praetors' Voice (2X2) 190

        Deck
        1 Sol Ring (C21) 263
        1 Ñoño, el Ávido (XYZ) 1 *F*
        35 Island (UNF) 235
    """.trimIndent()

    @Test
    fun `ida y vuelta de un mazo de Moxfield con un enlace propio, no de Moxfield`() {
        val snapshot = DeckSnapshot(id = "PUB123", n = "Atraxa", f = "commander", a = "yo", t = list)
        val link = ShareLink.build(snapshot, baseUrl = "https://katarem.github.io/moxlite/d/")
        assertTrue(link, link.startsWith("https://katarem.github.io/moxlite/d/#moxlite="))
        assertFalse(link.contains("moxfield.com"))
        assertEquals(snapshot, ShareLink.parse(link))
    }

    @Test
    fun `por defecto usa la pagina del repositorio`() {
        val link = ShareLink.build(DeckSnapshot(id = "PUB123", n = "X", t = "1 Sol Ring"))
        assertTrue(link, link.startsWith(BuildConfig.SHARE_BASE_URL + "#moxlite="))
        assertTrue(link, link.startsWith("https://") && link.contains(".github.io/"))
    }

    @Test
    fun `un mazo local conserva acentos y no tiene id`() {
        val snapshot = DeckSnapshot(n = "Mazo raro ñ", t = list)
        val parsed = ShareLink.parse(ShareLink.build(snapshot))!!
        assertNull(parsed.id)
        assertEquals("Mazo raro ñ", parsed.n)
        assertTrue(parsed.t.contains("Ñoño, el Ávido"))
    }

    @Test
    fun `el enlace de la app y los enlaces antiguos de Moxfield tambien se leen`() {
        val snapshot = DeckSnapshot(id = "PUB123", n = "Atraxa", t = list)
        val app = ShareLink.appLink(snapshot)
        assertTrue(app, app.startsWith("moxlite://d?moxlite="))
        assertEquals(snapshot, ShareLink.parse(app))

        val payload = ShareLink.build(snapshot).substringAfter("#moxlite=")
        assertEquals(snapshot, ShareLink.parse("https://moxfield.com/decks/PUB123#moxlite=$payload"))
        assertEquals(snapshot, ShareLink.parse("https://moxfield.com/decks/${ShareLink.LOCAL_PATH}#moxlite=$payload"))
    }

    @Test
    fun `encuentra el enlace dentro de un mensaje`() {
        val link = ShareLink.build(DeckSnapshot(id = "ABCDEF", n = "X", t = "1 Sol Ring"))
        val message = "Mira este mazo:\n$link\n(Ábrelo con Moxlite)"
        assertEquals("ABCDEF", ShareLink.parse(message)?.id)
    }

    @Test
    fun `el enlace es corto aunque el mazo sea grande`() {
        val big = (1..100).joinToString("\n") { "1 Carta número $it (SET) $it" }
        val link = ShareLink.build(DeckSnapshot(id = "PUB", n = "Grande", t = big))
        assertTrue("Enlace de ${link.length} caracteres", link.length < big.length)
    }

    @Test
    fun `ignora enlaces normales y datos corruptos`() {
        assertNull(ShareLink.parse("https://moxfield.com/decks/PUB123"))
        assertNull(ShareLink.parse("https://moxfield.com/decks/PUB123#moxlite=esto-no-es-valido"))
        assertNull(ShareLink.parse("https://moxfield.com/decks/PUB123#moxlite="))
    }
}
