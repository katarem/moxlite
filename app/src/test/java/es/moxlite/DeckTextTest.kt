package es.moxlite

import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.moxfield.MoxfieldIds
import es.moxlite.domain.DeckLine
import es.moxlite.domain.DeckText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTextTest {

    @Test
    fun `parsea secciones, ediciones y foil`() {
        val text = """
            Commander
            1 Atraxa, Praetors' Voice (2X2) 190

            Deck
            1 Sol Ring (C21) 263
            4 Island
            1x Fire // Ice
            1 Lightning Bolt (2X2) 117 *F*

            Sideboard
            2 Pithing Needle
        """.trimIndent()

        val lines = DeckText.parse(text)

        assertEquals(6, lines.size)
        val atraxa = lines.first { it.name.startsWith("Atraxa") }
        assertEquals(Boards.COMMANDERS, atraxa.board)
        assertEquals("2x2", atraxa.setCode)
        assertEquals("190", atraxa.collectorNumber)

        val island = lines.first { it.name == "Island" }
        assertEquals(4, island.quantity)
        assertNull(island.setCode)

        assertTrue(lines.any { it.name == "Fire // Ice" })
        assertTrue(lines.first { it.name == "Lightning Bolt" }.foil)
        assertEquals(Boards.SIDE, lines.first { it.name == "Pithing Needle" }.board)
    }

    @Test
    fun `acepta formato MTGO con SB y marca de comandante`() {
        val lines = DeckText.parse("1 Kenrith, the Returned King *CMDR*\n60 Forest\nSB: 3 Naturalize")
        assertEquals(Boards.COMMANDERS, lines[0].board)
        assertEquals(Boards.MAIN, lines[1].board)
        assertEquals(Boards.SIDE, lines[2].board)
        assertEquals("Naturalize", lines[2].name)
    }

    @Test
    fun `no confunde parentesis del nombre con la edicion`() {
        val line = DeckText.parse("1 B.F.M. (Big Furry Monster)").single()
        assertEquals("B.F.M. (Big Furry Monster)", line.name)
        assertNull(line.setCode)
    }

    @Test
    fun `fusiona duplicados`() {
        val lines = DeckText.parse("2 Island\n3 Island")
        assertEquals(5, lines.single().quantity)
    }

    @Test
    fun `exportar y volver a importar conserva el mazo`() {
        val original = listOf(
            DeckLine(1, "Atraxa, Praetors' Voice", Boards.COMMANDERS, "2x2", "190"),
            DeckLine(1, "Sol Ring", Boards.MAIN, "c21", "263"),
            DeckLine(10, "Island", Boards.MAIN),
            DeckLine(1, "Swords to Plowshares", Boards.MAYBE, foil = true),
        )
        val text = DeckText.format(original)
        assertTrue(text.startsWith("Commander\n"))
        assertEquals(original.toSet(), DeckText.parse(text).toSet())
    }

    @Test
    fun `extrae el publicId de enlaces de Moxfield`() {
        assertEquals("oEWXWHM5eEGMmopExLWRCA", MoxfieldIds.parsePublicId("https://moxfield.com/decks/oEWXWHM5eEGMmopExLWRCA"))
        assertEquals("abc_123-XYZ", MoxfieldIds.parsePublicId("mira https://www.moxfield.com/decks/abc_123-XYZ/primer"))
        assertEquals("oEWXWHM5eEGMmopExLWRCA", MoxfieldIds.parsePublicId("oEWXWHM5eEGMmopExLWRCA"))
        assertNull(MoxfieldIds.parsePublicId("hola que tal"))
    }
}
