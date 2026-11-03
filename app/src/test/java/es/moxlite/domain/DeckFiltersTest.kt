package es.moxlite.domain

import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.Http
import es.moxlite.data.remote.moxfield.MoxDeckDto
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import es.moxlite.data.remote.moxfield.MoxPage
import es.moxlite.data.repo.DeckMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckFiltersTest {

    private fun deck(
        id: String,
        name: String = id,
        colors: String = "",
        format: String = "commander",
        commanders: String = "",
        bracket: Int? = null,
        hubs: String = "",
        likes: Int = 0,
        viewedAt: Long = 0,
        updated: String? = null,
        author: String = "",
    ) = DeckEntity(
        publicId = id, name = name, colorIdentity = colors, format = format, commanders = commanders,
        bracket = bracket, hubs = hubs, likeCount = likes, viewedAt = viewedAt, remoteUpdatedAt = updated,
        authorUserName = author,
    )

    @Test
    fun `colores exactos, al menos y como maximo`() {
        val exact = DeckFilters(colors = "WU")
        assertTrue(exact.matchesColors("UW"))
        assertFalse(exact.matchesColors("WUB"))
        assertFalse(exact.matchesColors("W"))

        val atLeast = exact.copy(colorMatch = ColorMatch.INCLUDES)
        assertTrue(atLeast.matchesColors("WUB"))
        assertFalse(atLeast.matchesColors("W"))

        val atMost = exact.copy(colorMatch = ColorMatch.WITHIN)
        assertTrue(atMost.matchesColors("W"))
        assertTrue(atMost.matchesColors(""))
        assertFalse(atMost.matchesColors("WUB"))

        assertTrue(DeckFilters().matchesColors("WUBRG"))
    }

    @Test
    fun `incoloro excluye al resto de colores`() {
        val f = DeckFilters().toggleColor('W').toggleColor('U')
        assertEquals("WU", f.colors)
        val colorless = f.toggleColor('C')
        assertEquals("C", colorless.colors)
        assertTrue(colorless.matchesColors(""))
        assertFalse(colorless.matchesColors("R"))
        assertEquals("G", colorless.toggleColor('G').colors)
        assertEquals("", colorless.toggleColor('C').colors)
    }

    @Test
    fun `filtro local por texto, formato, comandante, bracket y tipo`() {
        val atraxa = deck("a", "Superfriends", colors = "WUBG", commanders = "Atraxa, Praetors' Voice", bracket = 3, hubs = "Superfriends\nCounters")
        val burn = deck("b", "Burn", colors = "R", format = "modern", author = "pepe")
        val pdh = deck("c", "PDH", format = "paupercommander")
        val all = listOf(atraxa, burn, pdh)

        assertEquals(listOf(atraxa), DeckFilters(text = "atraxa").applyLocal(all))
        assertEquals(listOf(burn), DeckFilters(text = "PEPE").applyLocal(all))
        assertEquals(listOf(burn), DeckFilters(format = "modern").applyLocal(all))
        assertEquals(listOf(pdh), DeckFilters(format = "pauperEdh").applyLocal(all))
        assertEquals(listOf(atraxa), DeckFilters(commander = "praetors").applyLocal(all))
        assertEquals(listOf(atraxa), DeckFilters(bracket = 3).applyLocal(all))
        assertEquals(listOf(atraxa), DeckFilters(hub = "counters").applyLocal(all))
        assertEquals(listOf(burn), DeckFilters(colors = "R").applyLocal(all))
        assertTrue(DeckFilters(bracket = 4).applyLocal(all).isEmpty())
    }

    @Test
    fun `orden local`() {
        val a = deck("a", "Beta", likes = 1, viewedAt = 10, updated = "2026-01-01T00:00:00Z")
        val b = deck("b", "alfa", likes = 9, viewedAt = 30, updated = "2025-01-01T00:00:00Z")
        val c = deck("c", "Gamma", likes = 5, viewedAt = 20, updated = "2026-06-01T00:00:00Z")
        val all = listOf(a, b, c)
        assertEquals(listOf("b", "a", "c"), DeckFilters(sort = DeckSort.NAME).applyLocal(all).map { it.publicId })
        assertEquals(listOf("b", "c", "a"), DeckFilters(sort = DeckSort.LIKES).applyLocal(all).map { it.publicId })
        assertEquals(listOf("b", "c", "a"), DeckFilters(sort = DeckSort.VIEWED).applyLocal(all).map { it.publicId })
        assertEquals(listOf("c", "a", "b"), DeckFilters(sort = DeckSort.UPDATED).applyLocal(all).map { it.publicId })
    }

    @Test
    fun `limpiar conserva texto y orden y cuenta los filtros puestos`() {
        val f = DeckFilters(text = "x", format = "modern", colors = "R", bracket = 2, sort = DeckSort.LIKES)
        assertEquals(3, f.activeCount)
        assertEquals(DeckFilters(text = "x", sort = DeckSort.LIKES), f.cleared())
        assertTrue(f.needsClientFilter)
        assertFalse(DeckFilters(format = "modern").needsClientFilter)
    }

    @Test
    fun `los resultados de Moxfield traen bracket, tipos y comandantes`() {
        val json = """
            {"pageNumber":1,"totalPages":3,"data":[
              {"publicId":"P1","name":"Atraxa","format":"commander","colorIdentity":["W","U","B","G"],
               "bracket":3.0,"autoBracket":2,"hubNames":["Superfriends"],
               "commanders":[{"name":"Atraxa, Praetors' Voice"}]},
              {"publicId":"P2","name":"Sin datos","colorIdentity":["R"],"userBracket":4,"commanders":["Krenko, Mob Boss"]}
            ]}
        """.trimIndent()
        val page = Http.json.decodeFromString(MoxPage.serializer(MoxDeckSummaryDto.serializer()), json)
        val (p1, p2) = page.data
        assertEquals(3, p1.effectiveBracket)
        assertEquals(listOf("Superfriends"), p1.hubNames)
        assertEquals(listOf("Atraxa, Praetors' Voice"), p1.commanderNames)
        assertEquals(4, p2.effectiveBracket)
        assertEquals(listOf("Krenko, Mob Boss"), p2.commanderNames)

        val row = DeckMapper.fromSummary(p1)
        assertEquals(3, row.bracket)
        assertEquals("Superfriends", row.hubs)
        assertEquals(listOf("Atraxa, Praetors' Voice"), row.commanderList)

        // Bracket y tipo los filtra Moxfield; aquí solo se descarta lo que no cuadra.
        assertTrue(DeckFilters(colors = "WUBG", bracket = 3, hub = "superfriends").matchesSummary(p1))
        assertFalse(DeckFilters(bracket = 2).matchesSummary(p1))
        assertTrue(DeckFilters(hub = "Tokens").matchesSummary(p2)) // no trae tipos: no se descarta
        assertFalse(DeckFilters(colors = "U").matchesSummary(p2))
    }

    @Test
    fun `el mazo completo guarda comandantes y conserva cuando se abrio`() {
        val json = """
            {"id":"r1","publicId":"P1","name":"Atraxa","bracket":4,"hubs":[{"name":"Counters","description":""}],
             "boards":{"commanders":{"count":1,"cards":{"k":{"quantity":1,"card":{"id":"m1","uniqueCardId":"u1","name":"Atraxa, Praetors' Voice"}}}},
                       "mainboard":{"count":1,"cards":{"k2":{"quantity":1,"card":{"id":"m2","uniqueCardId":"u2","name":"Sol Ring"}}}}}}
        """.trimIndent()
        val dto = Http.json.decodeFromString(MoxDeckDto.serializer(), json)
        val existing = deck("P1", viewedAt = 1234)
        val (entity, _) = DeckMapper.fromDto(dto, existing)
        assertEquals("Atraxa, Praetors' Voice", entity.commanders)
        assertEquals(4, entity.bracket)
        assertEquals("Counters", entity.hubs)
        assertEquals(1234L, entity.viewedAt)

        val (fresh, _) = DeckMapper.fromDto(dto.copy(bracket = null, hubs = emptyList()), null)
        assertNull(fresh.bracket)
        assertEquals(0L, fresh.viewedAt)
    }
}
