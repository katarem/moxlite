package es.moxlite.ui.deck

import es.moxlite.data.local.CardLocalizationEntity
import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.repo.PrintingKey
import es.moxlite.data.settings.DeckGrouping
import es.moxlite.data.settings.DeckSorting
import es.moxlite.i18n.En
import es.moxlite.i18n.Es
import org.junit.Assert.assertEquals
import org.junit.Test

class DeckLayoutTest {

    private fun card(name: String, type: String, cmc: Double, colors: String = "", price: Double? = null, qty: Int = 1) =
        DeckCardEntity(
            deckId = "d", board = "mainboard", cardKey = name, name = name, quantity = qty,
            typeLine = type, cmc = cmc, colorIdentity = colors, priceEur = price,
        )

    private val bolt = card("Lightning Bolt", "Instant", 1.0, "R", 2.0, qty = 4)
    private val goyf = card("Tarmogoyf", "Creature — Lhurgoyf", 2.0, "G", 10.0)
    private val sol = card("Sol Ring", "Artifact", 1.0, "", 1.0)
    private val island = card("Island", "Basic Land — Island", 0.0, "", 0.1, qty = 10)
    private val niv = card("Niv-Mizzet Reborn", "Legendary Creature — Dragon", 5.0, "WUBRG", 3.0)
    private val all = listOf(island, niv, bolt, goyf, sol)

    @Test
    fun `por tipo en el orden de Moxfield con etiquetas traducidas`() {
        val groups = DeckLayout.group(all, DeckGrouping.TYPE, DeckSorting.NAME, Es)
        assertEquals(listOf("Criaturas", "Instantáneos", "Artefactos", "Tierras"), groups.map { it.label })
        assertEquals(listOf("Niv-Mizzet Reborn", "Tarmogoyf"), groups.first().cards.map { it.name })
        assertEquals(4, groups[1].count)
        assertEquals("Creatures", DeckLayout.group(all, DeckGrouping.TYPE, DeckSorting.NAME, En).first().label)
    }

    @Test
    fun `por coste con las tierras al final`() {
        val groups = DeckLayout.group(all, DeckGrouping.CMC, DeckSorting.NAME, Es)
        assertEquals(listOf("Coste 1", "Coste 2", "Coste 5", "Tierras"), groups.map { it.label })
        assertEquals(listOf("Lightning Bolt", "Sol Ring"), groups.first().cards.map { it.name })
    }

    @Test
    fun `por color con multicolor e incoloro`() {
        val groups = DeckLayout.group(all, DeckGrouping.COLOR, DeckSorting.NAME, Es)
        assertEquals(listOf("Rojo", "Verde", "Multicolor", "Incoloro", "Tierras"), groups.map { it.label })
    }

    @Test
    fun `sin agrupar y orden por precio`() {
        val groups = DeckLayout.group(all, DeckGrouping.NONE, DeckSorting.PRICE, Es)
        assertEquals(1, groups.size)
        assertEquals(listOf("Tarmogoyf", "Niv-Mizzet Reborn", "Lightning Bolt", "Sol Ring", "Island"), groups.single().cards.map { it.name })
        assertEquals(emptyList<CardGroup>(), DeckLayout.group(emptyList(), DeckGrouping.NONE, DeckSorting.NAME, Es))
    }

    @Test
    fun `orden por coste desempata por nombre`() {
        assertEquals(listOf("Island", "Lightning Bolt", "Sol Ring", "Tarmogoyf", "Niv-Mizzet Reborn"), DeckLayout.sort(all, DeckSorting.CMC).map { it.name })
    }

    @Test
    fun `usa la impresion traducida si existe`() {
        val c = bolt.copy(setCode = "m10", collectorNumber = "146", scryfallId = "en-id")
        val loc = mapOf(PrintingKey("m10", "146") to CardLocalizationEntity("m10", "146", "es", "es-id", "Rayo"))
        assertEquals("es-id", c.imageId(loc))
        assertEquals("Rayo", c.localName(loc))
        val missing = mapOf(PrintingKey("m10", "146") to CardLocalizationEntity("m10", "146", "es", null, null))
        assertEquals("en-id", c.imageId(missing))
        assertEquals("Lightning Bolt", c.localName(missing))
        assertEquals("en-id", c.imageId(emptyMap()))
    }
}
