package es.moxlite.data.builder

import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.remote.Http
import es.moxlite.data.remote.edhrec.EdhBudget
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.domain.BuilderTargets
import es.moxlite.domain.CardRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuilderDraftTest {

    private fun card(name: String, type: String, oracle: String = "", colors: List<String> = emptyList()) =
        ScryCard(id = "id-$name", name = name, typeLine = type, oracleText = oracle, colorIdentity = colors)

    @Test
    fun `parejas de comandante`() {
        val kraum = card("Kraum, Ludevic's Opus", "Legendary Creature — Zombie Horror", "Flying, haste\nWhenever an opponent casts their second spell each turn, draw a card.\nPartner (You can have two commanders if both have partner.)")
        assertEquals("is:commander keyword:partner -o:\"partner with\"", BuilderRepository.partnerFilter(kraum))

        val wilson = card("Wilson, Refined Grizzly", "Legendary Creature — Bear Warrior", "Choose a Background (You can have a Background as a second commander.)\nReach, trample, ward {2}")
        assertEquals("t:background", BuilderRepository.partnerFilter(wilson))

        val background = card("Raised by Giants", "Legendary Enchantment — Background", "Commander creatures you own have base power and toughness 10/10 and are Giants in addition to their other types.")
        assertEquals("is:commander o:\"choose a background\"", BuilderRepository.partnerFilter(background))

        val pir = card("Pir, Imaginative Rascal", "Legendary Creature — Human", "Partner with Toothy, Imaginary Friend (When this creature enters, target player may put Toothy into their hand from their library, then shuffle.)\nIf one or more counters would be put on a permanent your team controls, that many plus one of each of those kinds of counters are put on that permanent instead.")
        assertEquals("!\"Toothy, Imaginary Friend\"", BuilderRepository.partnerFilter(pir))

        assertNull(BuilderRepository.partnerFilter(card("Atraxa, Praetors' Voice", "Legendary Creature — Phyrexian Angel Horror", "Flying, vigilance, deathtouch, lifelink\nAt the beginning of your end step, proliferate.")))
    }

    @Test
    fun `el borrador se guarda y se lee igual`() {
        val draft = BuilderDraft(
            name = "Atraxa",
            commanders = listOf(card("Atraxa, Praetors' Voice", "Legendary Creature", colors = listOf("W", "U", "B", "G"))),
            theme = "infect",
            themeLabel = "Infect",
            budget = EdhBudget.BUDGET,
            cards = listOf(DraftCard(card("Sol Ring", "Artifact", "{T}: Add {C}{C}.")), DraftCard(card("Forest", "Basic Land — Forest"), 8)),
            targets = BuilderTargets(lands = 34),
            started = true,
        )
        val json = Http.json.encodeToString(BuilderDraft.serializer(), draft)
        val back = Http.json.decodeFromString(BuilderDraft.serializer(), json)
        assertEquals(draft, back)
        assertEquals("WUBG", back.colorIdentity)
        assertEquals(10, back.total)
        assertEquals("atraxa-praetors-voice", back.edhrecBase)
        assertTrue(back.has("sol ring"))
        assertEquals(setOf(CardRole.RAMP), back.buildCards().first().roles)
    }

    @Test
    fun `una carta guardada en el movil sirve para el asistente`() {
        val row = DeckCardEntity(
            deckId = "d", board = "mainboard", cardKey = "sf:x", name = "Cultivate", quantity = 1, scryfallId = "x",
            typeLine = "Sorcery", manaCost = "{2}{G}", cmc = 3.0, colorIdentity = "G",
            oracleText = "Search your library for up to two basic land cards, reveal those cards, put one onto the battlefield tapped and the other into your hand, then shuffle.",
        )
        val card = row.toScryCard()
        assertEquals(listOf("G"), card.colorIdentity)
        assertEquals(setOf(CardRole.RAMP), card.toBuildCard().roles)
    }
}
