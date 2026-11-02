package es.moxlite.domain

import es.moxlite.data.remote.moxfield.Boards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTextFormatTest {

    @Test
    fun `ignora comentarios, lineas vacias y basura`() {
        val lines = DeckText.parse(
            """
            # comentario
            // otro comentario

            Esto no es una carta
            2 Opt
            """.trimIndent(),
        )
        assertEquals(listOf(DeckLine(2, "Opt")), lines)
    }

    @Test
    fun `reconoce cabeceras en espanol, con dos puntos, con barras y con conteo`() {
        val lines = DeckText.parse(
            """
            Comandante:
            1 Kenrith
            // Banquillo
            1 Duress
            Maybeboard (3)
            1 Opt
            Compañero
            1 Lurrus of the Dream-Den
            Mazo
            1 Sol Ring
            """.trimIndent(),
        )
        assertEquals(Boards.COMMANDERS, lines.single { it.name == "Kenrith" }.board)
        assertEquals(Boards.SIDE, lines.single { it.name == "Duress" }.board)
        assertEquals(Boards.MAYBE, lines.single { it.name == "Opt" }.board)
        assertEquals(Boards.COMPANIONS, lines.single { it.name.startsWith("Lurrus") }.board)
        assertEquals(Boards.MAIN, lines.single { it.name == "Sol Ring" }.board)
    }

    @Test
    fun `acepta formatos de cantidad 4x y 4 x`() {
        val lines = DeckText.parse("4x Opt\n3 x Duress\n1 Sol Ring")
        assertEquals(listOf(4, 3, 1), lines.map { it.quantity })
        assertEquals(listOf("Opt", "Duress", "Sol Ring"), lines.map { it.name })
    }

    @Test
    fun `acepta numeros de coleccionista especiales y etched`() {
        val line = DeckText.parse("1 Sol Ring (PLST) C21-263 *E*").single()
        assertEquals("plst", line.setCode)
        assertEquals("C21-263", line.collectorNumber)
        assertTrue(line.foil)
    }

    @Test
    fun `no fusiona impresiones distintas ni boards distintos`() {
        val lines = DeckText.parse("1 Opt (XLN) 65\n1 Opt (ELD) 59\nSB: 1 Opt (XLN) 65")
        assertEquals(3, lines.size)
    }

    @Test
    fun `format agrupa por board en orden y ordena por nombre`() {
        val text = DeckText.format(
            listOf(
                DeckLine(1, "Zur", Boards.MAIN),
                DeckLine(2, "abrade", Boards.MAIN),
                DeckLine(1, "Duress", Boards.SIDE),
                DeckLine(1, "Kenrith", Boards.COMMANDERS),
                DeckLine(1, "Treasure", "tokens"),
            ),
        )
        assertEquals(
            "Commander\n1 Kenrith\n\nDeck\n2 abrade\n1 Zur\n\nSideboard\n1 Duress\n\nTokens\n1 Treasure",
            text,
        )
    }

    @Test
    fun `format sin impresiones omite edicion pero mantiene foil`() {
        val text = DeckText.format(listOf(DeckLine(1, "Sol Ring", Boards.MAIN, "c21", "263", foil = true)), includePrintings = false)
        assertEquals("Deck\n1 Sol Ring *F*", text)
    }

    @Test
    fun `texto vacio da lista vacia`() {
        assertEquals(emptyList<DeckLine>(), DeckText.parse(""))
        assertEquals("", DeckText.format(emptyList()))
    }
}
