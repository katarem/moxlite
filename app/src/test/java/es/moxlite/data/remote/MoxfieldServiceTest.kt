package es.moxlite.data.remote

import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.moxfield.DeckQuery
import es.moxlite.data.remote.moxfield.MoxCardDto
import es.moxlite.data.remote.moxfield.MoxfieldIds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoxfieldServiceTest {

    @Test
    fun `DeckQuery por defecto solo manda paginacion y orden`() {
        assertEquals(
            mapOf("pageNumber" to "1", "pageSize" to "30", "sortType" to "updated", "sortDirection" to "descending"),
            DeckQuery().toParams(),
        )
    }

    @Test
    fun `DeckQuery incluye texto, formato y autor recortados y omite los vacios`() {
        val params = DeckQuery(text = "  atraxa ", format = "commander", authorUserName = " yo ", pageNumber = 3, pageSize = 100).toParams()
        assertEquals("atraxa", params["q"])
        assertEquals("commander", params["fmt"])
        assertEquals("yo", params["authorUserNames"])
        assertEquals("3", params["pageNumber"])
        assertEquals("100", params["pageSize"])

        val blank = DeckQuery(text = "   ", format = "", authorUserName = "  ").toParams()
        assertFalse(blank.containsKey("q"))
        assertFalse(blank.containsKey("fmt"))
        assertFalse(blank.containsKey("authorUserNames"))
    }

    @Test
    fun `DeckQuery manda comandante, bracket y tipo, y el texto tambien como filter`() {
        val params = DeckQuery(
            text = "atraxa",
            commanderCardId = "abc12",
            minBracket = 3,
            maxBracket = 3,
            hubName = " Tokens ",
            sortType = "likes",
        ).toParams()
        assertEquals("atraxa", params["filter"])
        assertEquals("abc12", params["commanderCardId"])
        assertEquals("3", params["minBracket"])
        assertEquals("3", params["maxBracket"])
        assertEquals("Tokens", params["hubName"])
        assertEquals("likes", params["sortType"])
        assertFalse(DeckQuery().toParams().containsKey("commanderCardId"))
        assertFalse(DeckQuery().toParams().containsKey("minBracket"))
        assertFalse(DeckQuery().toParams().containsKey("hubName"))
    }

    @Test
    fun `parsePublicId acepta enlaces con o sin esquema y rechaza textos cortos`() {
        assertEquals("ABCdef_12-3", MoxfieldIds.parsePublicId("moxfield.com/decks/ABCdef_12-3?foo=bar"))
        assertEquals("ABCdef", MoxfieldIds.parsePublicId("  ABCdef  "))
        assertNull(MoxfieldIds.parsePublicId("abc"))
        assertNull(MoxfieldIds.parsePublicId("https://moxfield.com/users/alguien"))
        assertNull(MoxfieldIds.parsePublicId(""))
    }

    @Test
    fun `Boards ordena y etiqueta los tableros`() {
        assertTrue(Boards.sortIndex(Boards.COMMANDERS) < Boards.sortIndex(Boards.MAIN))
        assertTrue(Boards.sortIndex(Boards.MAIN) < Boards.sortIndex(Boards.SIDE))
        assertTrue(Boards.sortIndex(Boards.SIDE) < Boards.sortIndex(Boards.MAYBE))
        assertEquals(Boards.order.size, Boards.sortIndex("inventado"))
        assertEquals("Banquillo", Boards.label(Boards.SIDE))
        assertEquals("Comandante", Boards.label(Boards.COMMANDERS))
        assertEquals("Inventado", Boards.label("inventado"))
    }

    @Test
    fun `price tolera precios numericos, texto numerico y basura`() {
        val card = MoxCardDto(
            prices = buildJsonObject {
                put("eur", JsonPrimitive(3.5))
                put("usd", JsonPrimitive("4.25"))
                put("tix", JsonPrimitive("n/a"))
            },
        )
        assertEquals(3.5, card.price("eur")!!, 0.0001)
        assertEquals(4.25, card.price("usd")!!, 0.0001)
        assertNull(card.price("tix"))
        assertNull(card.price("ausente"))
        assertNull(MoxCardDto().price("eur"))
    }

    @Test
    fun `hasBackImage solo para layouts de doble cara`() {
        assertTrue(MoxCardDto(layout = "transform").hasBackImage)
        assertTrue(MoxCardDto(layout = "modal_dfc").hasBackImage)
        assertFalse(MoxCardDto(layout = "split").hasBackImage)
        assertFalse(MoxCardDto(layout = null).hasBackImage)
    }

    @Test
    fun `la busqueda personal usa filter en vez de q`() {
        val params = DeckQuery(text = " goblins ", pageSize = 100).toPersonalParams()
        assertEquals("goblins", params["filter"])
        assertFalse(params.containsKey("q"))
        assertEquals("100", params["pageSize"])
    }

    @Test
    fun `urls de imagen de Moxfield a partir del id de impresion`() {
        assertEquals(
            "https://assets.moxfield.net/cards/card-abc12-art_crop.webp",
            es.moxlite.data.remote.moxfield.MoxfieldImages.artCrop("abc12"),
        )
        assertEquals("https://moxfield.com/decks/PUB", MoxfieldIds.deckUrl("PUB"))
    }

    @Test
    fun `parsea mainCardId de los resumenes de busqueda`() {
        val json = """{"data":[{"publicId":"A","name":"Uno","mainCardId":"zz9","visibility":"private"}]}"""
        val page = Http.json.decodeFromString(
            es.moxlite.data.remote.moxfield.MoxPage.serializer(es.moxlite.data.remote.moxfield.MoxDeckSummaryDto.serializer()), json,
        )
        assertEquals("zz9", page.data.single().mainCardId)
    }
}
