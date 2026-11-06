package es.moxlite.domain

import es.moxlite.domain.CardRole.DRAW
import es.moxlite.domain.CardRole.LAND
import es.moxlite.domain.CardRole.RAMP
import es.moxlite.domain.CardRole.REMOVAL
import es.moxlite.domain.CardRole.WIPE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckBuilderTest {

    private fun roles(name: String, type: String, oracle: String) = CardRoles.of(name, type, oracle)

    @Test
    fun `ramp`() {
        assertEquals(setOf(RAMP), roles("Sol Ring", "Artifact", "{T}: Add {C}{C}."))
        assertEquals(setOf(RAMP), roles("Arcane Signet", "Artifact", "{T}: Add one mana of any color in your commander's color identity."))
        assertEquals(setOf(RAMP), roles("Birds of Paradise", "Creature — Bird", "Flying\n{T}: Add one mana of any color."))
        assertEquals(
            setOf(RAMP),
            roles(
                "Cultivate", "Sorcery",
                "Search your library for up to two basic land cards, reveal those cards, put one onto the battlefield tapped and the other into your hand, then shuffle.",
            ),
        )
        assertEquals(
            setOf(RAMP),
            roles("Three Visits", "Sorcery", "Search your library for a Forest card, put that card onto the battlefield, then shuffle."),
        )
        assertEquals(
            setOf(RAMP),
            roles("Smothering Tithe", "Enchantment", "Whenever an opponent draws a card, that player may pay {2}. If the player doesn't, you create a Treasure token."),
        )
        assertEquals(
            setOf(RAMP, DRAW),
            roles(
                "Growth Spiral", "Instant",
                "Draw a card. You may put a land card from your hand onto the battlefield.",
            ),
        )
    }

    @Test
    fun `robo`() {
        assertEquals(
            setOf(DRAW),
            roles("Rhystic Study", "Enchantment", "Whenever an opponent casts a spell, you may draw a card unless that player pays {1}."),
        )
        assertEquals(setOf(DRAW), roles("Harmonize", "Sorcery", "Draw three cards."))
        assertEquals(setOf(DRAW), roles("Phyrexian Arena", "Enchantment", "At the beginning of your upkeep, you draw a card and you lose 1 life."))
        assertEquals(
            setOf(DRAW),
            roles(
                "Ponder", "Sorcery",
                "Look at the top three cards of your library, then put them back in any order. You may shuffle.\nDraw a card.",
            ),
        )
    }

    @Test
    fun `interaccion`() {
        assertEquals(
            setOf(REMOVAL),
            roles("Swords to Plowshares", "Instant", "Exile target creature. Its controller gains life equal to its power."),
        )
        // Path to Exile da una tierra al rival: no es ramp propio.
        assertEquals(
            setOf(REMOVAL),
            roles(
                "Path to Exile", "Instant",
                "Exile target creature. Its controller may search their library for a basic land card, put that card onto the battlefield tapped, then shuffle.",
            ),
        )
        assertEquals(setOf(REMOVAL), roles("Counterspell", "Instant", "Counter target spell."))
        assertEquals(setOf(REMOVAL), roles("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target."))
        assertEquals(
            setOf(REMOVAL),
            roles("Beast Within", "Instant", "Destroy target permanent. Its controller creates a 3/3 green Beast creature token."),
        )
        assertEquals(
            setOf(REMOVAL),
            roles(
                "Cyclonic Rift", "Instant",
                "Return target nonland permanent you don't control to its owner's hand.\nOverload {6}{U} (You may cast this spell for its overload cost. If you do, change its text by replacing all instances of \"target\" with \"each.\")",
            ),
        )
        // Recuperar del cementerio no es quitar nada.
        assertEquals(
            emptySet<CardRole>(),
            roles("Eternal Witness", "Creature — Human Shaman", "When Eternal Witness enters, return target card from your graveyard to your hand."),
        )
    }

    @Test
    fun `barridos y tierras`() {
        assertEquals(
            setOf(WIPE),
            roles("Wrath of God", "Sorcery", "Destroy all creatures. They can't be regenerated."),
        )
        assertEquals(
            setOf(WIPE),
            roles("Toxic Deluge", "Sorcery", "As an additional cost to cast this spell, pay X life.\nAll creatures get -X/-X until end of turn."),
        )
        assertEquals(
            setOf(WIPE),
            roles("Blasphemous Act", "Sorcery", "This spell costs {1} less to cast for each creature on the battlefield.\nBlasphemous Act deals 13 damage to each creature."),
        )
        assertEquals(setOf(LAND), roles("Command Tower", "Land", "{T}: Add one mana of any color in your commander's color identity."))
        assertEquals(
            setOf(LAND),
            roles("Evolving Wilds", "Land", "{T}, Sacrifice Evolving Wilds: Search your library for a basic land card, put it onto the battlefield tapped, then shuffle."),
        )
        // Cara frontal hechizo y trasera tierra: cuenta como hechizo.
        assertTrue(LAND !in roles("Fell the Profane // Fell Mire", "Instant // Land", "Destroy target creature or planeswalker.\n//\nFell Mire\n{T}: Add {B}."))
    }

    @Test
    fun `estadisticas del mazo`() {
        val cards = listOf(
            BuildCard("Sol Ring", "Artifact", "{1}", 1.0, 1, setOf(RAMP)),
            BuildCard("Harmonize", "Sorcery", "{2}{G}{G}", 4.0, 1, setOf(DRAW)),
            BuildCard("Forest", "Basic Land — Forest", "", 0.0, 10, setOf(LAND)),
            BuildCard("Craterhoof Behemoth", "Creature", "{5}{G}{G}{G}", 8.0, 1, emptySet()),
        )
        val stats = DeckStats.of(commanders = 1, cards = cards)
        assertEquals(14, stats.total)
        assertEquals(10, stats.count(LAND))
        assertEquals(1, stats.count(RAMP))
        assertEquals(mapOf(1 to 1, 4 to 1, 7 to 1), stats.curve)
        assertEquals((1.0 + 4.0 + 8.0) / 3, stats.averageCmc, 1e-9)
    }

    @Test
    fun `basicas segun los simbolos de mana`() {
        val split = BasicLands.distribute(
            count = 10,
            colorIdentity = "WG",
            manaCosts = listOf("{1}{G}{G}" to 1, "{G}" to 2, "{W}" to 1, "{G/W}" to 2),
        )
        // G: 2 + 2 + 1 = 5 símbolos; W: 1 + 1 = 2.
        assertEquals(mapOf("Forest" to 7, "Plains" to 3), split)
        assertEquals(10, split.values.sum())

        assertEquals(mapOf("Wastes" to 5), BasicLands.distribute(5, "", emptyList()))
        // Sin símbolos de color: a partes iguales; y al menos una de cada color.
        assertEquals(mapOf("Island" to 2, "Swamp" to 2), BasicLands.distribute(4, "UB", emptyList()))
        val tiny = BasicLands.distribute(3, "WUBRG".take(3), listOf("{W}{W}{W}{W}{W}{W}" to 1))
        assertEquals(mapOf("Plains" to 1, "Island" to 1, "Swamp" to 1), tiny)
    }

    @Test
    fun `autocompletar cubre funciones y tierras`() {
        val current = listOf(
            BuildCard("Forest", "Basic Land — Forest", "", 0.0, 30, setOf(LAND)),
            BuildCard("Sol Ring", "Artifact", "{1}", 1.0, 1, setOf(RAMP)),
        )
        // Mazo de 1 comandante + 31 cartas: quedan 68 huecos. Objetivo 36 tierras → 6 más.
        val candidates = buildList {
            repeat(70) { add(BuildCandidate("Bicho $it", emptySet())) }
            repeat(5) { add(BuildCandidate("Ramp $it", setOf(RAMP))) }
            repeat(15) { add(BuildCandidate("Robo $it", setOf(DRAW))) }
            add(BuildCandidate("Command Tower", setOf(LAND)))
            add(BuildCandidate("Exotic Orchard", setOf(LAND)))
            add(BuildCandidate("Forest", setOf(LAND), isBasic = true))
        }
        val plan = AutoFill.plan(
            commanders = 1,
            current = current,
            candidates = candidates,
            targets = BuilderTargets(lands = 36, ramp = 10, draw = 10, removal = 8, wipes = 3),
            nonbasicTarget = 20,
        )
        val picks = plan.picks
        assertEquals(68, picks.size + plan.basics)
        // Primero las funciones que faltan (todo el ramp que hay y 10 de robo)…
        assertEquals(5, picks.count { it.startsWith("Ramp") })
        assertEquals(10, picks.count { it.startsWith("Robo") })
        // …las no básicas, y el resto de tierras como básicas.
        assertTrue("Command Tower" in picks && "Exotic Orchard" in picks)
        assertTrue("Forest" !in picks)
        assertEquals(4, plan.basics)
        assertEquals(62 - 15, picks.count { it.startsWith("Bicho") })

        // Con el mazo lleno no hace nada.
        val full = AutoFill.plan(1, listOf(BuildCard("Forest", "Basic Land", "", 0.0, 99, setOf(LAND))), candidates, BuilderTargets(), 20)
        assertEquals(AutoFillPlan(emptyList(), 0), full)
    }
}
