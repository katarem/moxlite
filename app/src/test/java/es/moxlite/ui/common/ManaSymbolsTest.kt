package es.moxlite.ui.common

import es.moxlite.ui.common.ManaSymbolSpec.Fallback
import es.moxlite.ui.common.ManaSymbolSpec.Single
import es.moxlite.ui.common.ManaSymbolSpec.Split
import es.moxlite.ui.common.ManaSymbolSpec.Symbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManaSymbolsTest {

    @Test
    fun `parte un coste en simbolos`() {
        assertEquals(listOf(Symbol("2"), Symbol("U"), Symbol("B")), ManaSymbolSpec.split("{2}{U}{B}"))
    }

    @Test
    fun `respeta el texto alrededor de los simbolos`() {
        assertEquals(
            listOf(Symbol("T"), ": Add ", Symbol("G"), " or ", Symbol("W/U"), "."),
            ManaSymbolSpec.split("{T}: Add {G} or {W/U}."),
        )
        assertEquals(listOf(Symbol("1"), Symbol("R"), " // ", Symbol("U")), ManaSymbolSpec.split("{1}{R} // {U}"))
        assertEquals(listOf("Creature — Elf"), ManaSymbolSpec.split("Creature — Elf"))
        assertEquals(emptyList<Any>(), ManaSymbolSpec.split(""))
    }

    @Test
    fun `cada simbolo basico tiene su icono`() {
        listOf("W", "U", "B", "R", "G", "C", "S", "X", "Y", "Z", "T", "Q", "E", "∞", "½", "CHAOS", "100", "1000000")
            .plus((0..20).map { "$it" })
            .forEach { s ->
                val spec = ManaSymbolSpec.of(s)
                assertTrue("$s → $spec", spec is Single && spec.glyph in ManaGlyphs.all)
            }
    }

    @Test
    fun `colores y minusculas`() {
        val w = ManaSymbolSpec.of("w") as Single
        val generic = ManaSymbolSpec.of("3") as Single
        assertEquals("W", w.glyph)
        assertTrue(w.background != generic.background)
        assertEquals(ManaSymbolSpec.Generic, generic.background)
    }

    @Test
    fun `hibridos`() {
        val wu = ManaSymbolSpec.of("W/U") as Split
        assertEquals("W" to "U", wu.topGlyph to wu.bottomGlyph)
        assertEquals("2" to "G", (ManaSymbolSpec.of("2/G") as Split).let { it.topGlyph to it.bottomGlyph })
        assertEquals("C" to "R", (ManaSymbolSpec.of("C/R") as Split).let { it.topGlyph to it.bottomGlyph })
    }

    @Test
    fun `pirexianos`() {
        val gp = ManaSymbolSpec.of("G/P") as Single
        assertEquals("P", gp.glyph)
        assertEquals((ManaSymbolSpec.of("G") as Single).background, gp.background)
        val hybrid = ManaSymbolSpec.of("W/U/P") as Split
        assertEquals("P" to "P", hybrid.topGlyph to hybrid.bottomGlyph)
    }

    @Test
    fun `simbolos desconocidos se muestran como texto`() {
        assertEquals(Fallback("TK"), ManaSymbolSpec.of("TK"))
        assertEquals(Fallback("HW"), ManaSymbolSpec.of("HW"))
        assertEquals(Fallback("25"), ManaSymbolSpec.of("25"))
    }

    @Test
    fun `colorless glyph is drawn larger than the colors`() {
        val colorless = ManaSymbolSpec.of("C") as ManaSymbolSpec.Single
        val white = ManaSymbolSpec.of("W") as ManaSymbolSpec.Single
        assertEquals("C", colorless.glyph)
        assertEquals(ManaSymbolSpec.Generic, colorless.background)
        assertTrue(colorless.scale > white.scale)
    }
}
