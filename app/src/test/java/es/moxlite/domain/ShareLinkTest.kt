package es.moxlite.domain

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
    fun `ida y vuelta con un enlace moxlite que no pasa por ninguna web`() {
        val snapshot = DeckSnapshot(id = "PUB123", n = "Atraxa", f = "commander", a = "yo", t = list)
        val link = ShareLink.build(snapshot)
        assertTrue(link, link.startsWith("moxlite://deck/"))
        assertFalse(link.contains("http"))
        assertEquals(snapshot, ShareLink.parse(link))
    }

    @Test
    fun `el codigo suelto tambien abre el mazo`() {
        val snapshot = DeckSnapshot(id = "PUB123", n = "Atraxa", t = list)
        val code = ShareLink.code(snapshot)
        assertEquals("moxlite://deck/$code", ShareLink.build(snapshot))
        assertEquals(snapshot, ShareLink.parse(code))
        assertEquals(snapshot, ShareLink.parse("  $code\n"))
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
    fun `los enlaces antiguos tambien se leen`() {
        val snapshot = DeckSnapshot(id = "PUB123", n = "Atraxa", t = list)
        val code = ShareLink.code(snapshot)
        assertEquals(snapshot, ShareLink.parse("https://moxfield.com/decks/PUB123#moxlite=$code"))
        assertEquals(snapshot, ShareLink.parse("https://moxfield.com/decks/${ShareLink.LOCAL_PATH}#moxlite=$code"))
        assertEquals(snapshot, ShareLink.parse("https://katarem.github.io/moxlite/d/#moxlite=$code"))
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
        assertNull(ShareLink.parse("moxlite://deck/esto-tampoco-vale"))
        assertNull(ShareLink.parse("un mensaje cualquiera sin mazo"))
        assertNull(ShareLink.parse("PUB123abcdefghijklmn"))
    }
}
