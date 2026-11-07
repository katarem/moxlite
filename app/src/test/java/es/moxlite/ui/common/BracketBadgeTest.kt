package es.moxlite.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BracketBadgeTest {

    private val inlineTag = "androidx.compose.foundation.text.inlineContent"

    @Test
    fun `bracket is drawn as the gauge icon followed by its number`() {
        val text = deckMetaText("Commander", 3, "@kat", "Bracket")

        assertEquals("Commander · Bracket 3 · @kat", text.text)
        val icons = text.getStringAnnotations(inlineTag, 0, text.length)
        assertEquals(1, icons.size)
        val icon = icons.single()
        assertEquals(BRACKET_ICON_ID, icon.item)
        assertEquals("Bracket ", text.text.substring(icon.start, icon.end))
        assertEquals('3', text.text[icon.end])
    }

    @Test
    fun `missing parts are skipped without extra separators`() {
        assertEquals("Commander", deckMetaText("Commander", null, null, "Bracket").text)
        assertEquals("Bracket 4", deckMetaText("", 4, null, "Bracket").text)
        assertEquals("Bracket 2 · @kat", deckMetaText(null, 2, "@kat", "Bracket").text)
        assertEquals("", deckMetaText(null, null, " ", "Bracket").text)
    }

    @Test
    fun `no icon without bracket`() {
        val text = deckMetaText("Modern", null, "@kat", "Bracket")
        assertTrue(text.getStringAnnotations(inlineTag, 0, text.length).isEmpty())
    }
}
