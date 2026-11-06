package es.moxlite.data.remote

import es.moxlite.data.remote.edhrec.EdhBudget
import es.moxlite.data.remote.edhrec.EdhPage
import es.moxlite.data.remote.edhrec.EdhRecs
import es.moxlite.data.remote.edhrec.EdhrecPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formato de EDHREC con extractos de respuestas reales (test/resources/edhrec). */
class EdhrecTest {

    private fun resource(name: String) = javaClass.getResource("/edhrec/$name")!!.readText()

    @Test
    fun `slugs como los de EDHREC`() {
        assertEquals("atraxa-praetors-voice", EdhrecPaths.slug("Atraxa, Praetors' Voice"))
        assertEquals("lim-dul-the-necromancer", EdhrecPaths.slug("Lim-Dûl the Necromancer"))
        assertEquals("yshtola-nights-blessed", EdhrecPaths.slug("Y'shtola, Night's Blessed"))
        assertEquals("borborygmos-and-fblthp", EdhrecPaths.slug("Borborygmos and Fblthp"))
        assertEquals("esika-god-of-the-tree", EdhrecPaths.slug("Esika, God of the Tree // The Prismatic Bridge"))
        assertEquals("ratonhnhaketon", EdhrecPaths.slug("Ratonhnhaké:ton"))
        assertEquals("aethersnipe", EdhrecPaths.slug("Æthersnipe"))
    }

    @Test
    fun `las parejas de comandantes van en orden alfabetico`() {
        assertEquals(
            "kraum-ludevics-opus-tymna-the-weaver",
            EdhrecPaths.commanders(listOf("Tymna the Weaver", "Kraum, Ludevic's Opus")),
        )
        // Background y "doctor's companion" van detrás aunque el orden alfabético diga otra cosa.
        assertEquals(
            "wilson-refined-grizzly-raised-by-giants",
            EdhrecPaths.commanders(listOf("Raised by Giants", "Wilson, Refined Grizzly"), seconds = setOf("Raised by Giants")),
        )
        assertEquals(
            "the-fourteenth-doctor-rose-tyler",
            EdhrecPaths.commanders(listOf("Rose Tyler", "The Fourteenth Doctor"), seconds = setOf("Rose Tyler")),
        )
    }

    @Test
    fun `rutas con tema, bracket y presupuesto`() {
        val base = "atraxa-praetors-voice"
        assertEquals("commanders/$base", EdhrecPaths.commanderPage(base))
        assertEquals("commanders/$base/infect/budget", EdhrecPaths.commanderPage(base, theme = "infect", budget = EdhBudget.BUDGET))
        assertEquals("commanders/$base/core/expensive", EdhrecPaths.commanderPage(base, bracket = 2, budget = EdhBudget.EXPENSIVE))
        assertEquals("commanders/$base/cedh", EdhrecPaths.commanderPage(base, bracket = 5))
        // EDHREC no tiene tema + bracket: manda el tema.
        assertEquals("average-decks/$base/infect", EdhrecPaths.averageDeck(base, theme = "infect", bracket = 3))
        assertEquals("commanders/kraum-tymna", EdhrecPaths.fromRedirect("/commanders/kraum-tymna"))
    }

    @Test
    fun `lee la pagina del comandante`() {
        val page = Http.json.decodeFromString(EdhPage.serializer(), resource("commander.json"))
        assertEquals(46181, page.numDecks)
        assertEquals(36, page.land)
        assertEquals(23, page.nonbasic)
        assertEquals("infect", page.tagCounts.first().slug)
        assertEquals(listOf("topcards", "creatures", "lands"), page.cardLists.map { it.tag })
        val top = page.cardLists.first().cardviews.first()
        assertEquals("Tekuthal, Inquiry Dominus", top.name)
        assertEquals("2d389264-e2d2-4589-9636-faa11fe46710", top.id)
        assertEquals(30927.0 / 46181, top.inclusion!!, 1e-9)
        assertEquals(0.27, top.synergy!!, 0.01)
        assertNull(page.redirect)
    }

    @Test
    fun `lee el mazo medio`() {
        val page = Http.json.decodeFromString(EdhPage.serializer(), resource("average.json"))
        assertEquals("c", page.archidekt.first().c)
        assertEquals(3, page.archidekt.count { it.c == "m" })
        assertEquals(
            listOf("Arcane Signet" to 1, "Astral Cornucopia" to 1, "Forest" to 4, "Island" to 3),
            page.deck!!.entries(),
        )
    }

    @Test
    fun `lee las recomendaciones y las redirecciones`() {
        val recs = Http.json.decodeFromString(EdhRecs.serializer(), resource("recs.json"))
        assertEquals("Three Visits", recs.inRecs.first().name)
        assertEquals("Sorcery", recs.inRecs.first().primaryType)
        assertEquals(40.0, recs.inRecs.first().score, 0.0)
        assertEquals("Oko, Thief of Crowns", recs.outRecs.first().name)

        val redirect = Http.json.decodeFromString(EdhPage.serializer(), """{"redirect":"/commanders/kraum-ludevics-opus-tymna-the-weaver"}""")
        assertEquals("/commanders/kraum-ludevics-opus-tymna-the-weaver", redirect.redirect)
        assertTrue(redirect.cardLists.isEmpty())
    }
}
