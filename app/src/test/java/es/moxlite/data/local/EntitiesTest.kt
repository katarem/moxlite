package es.moxlite.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitiesTest {

    private fun card(typeLine: String) = DeckCardEntity(deckId = "d", board = "mainboard", cardKey = "k", name = "n", quantity = 1, typeLine = typeLine)

    @Test
    fun `primaryType elige el tipo principal por prioridad`() {
        assertEquals("Creature", card("Artifact Creature — Golem").primaryType)
        assertEquals("Land", card("Basic Land — Island").primaryType)
        assertEquals("Planeswalker", card("Legendary Planeswalker — Jace").primaryType)
        assertEquals("Instant", card("Instant // Sorcery").primaryType)
        assertEquals("Enchantment", card("Enchantment — Aura").primaryType)
        assertEquals("Other", card("Conspiracy").primaryType)
        assertEquals("Other", card("").primaryType)
    }

    @Test
    fun `primaryType mira solo la cara frontal y no los subtipos`() {
        // "Land" en la cara trasera no cuenta.
        assertEquals("Sorcery", card("Sorcery // Land").primaryType)
        // Subtipo con la palabra "Creature" no debería existir, pero lo de tras la raya no se mira.
        assertEquals("Artifact", card("Artifact — Creature Equipment").primaryType)
    }

    @Test
    fun `typeLabel traduce y cae a Otros`() {
        DeckCardEntity.TYPE_ORDER.forEach { assertFalse(DeckCardEntity.typeLabel(it) == "Otros") }
        assertEquals("Criaturas", DeckCardEntity.typeLabel("Creature"))
        assertEquals("Otros", DeckCardEntity.typeLabel("Other"))
    }

    @Test
    fun `isFullyLoaded si se descargo o es local`() {
        assertFalse(DeckEntity(publicId = "a", name = "a").isFullyLoaded)
        assertTrue(DeckEntity(publicId = "a", name = "a", fetchedAt = 1).isFullyLoaded)
        assertTrue(DeckEntity(publicId = "a", name = "a", isLocalOnly = true).isFullyLoaded)
    }
}
