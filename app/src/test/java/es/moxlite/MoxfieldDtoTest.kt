package es.moxlite

import es.moxlite.data.remote.Http
import es.moxlite.data.remote.moxfield.MoxDeckDto
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import es.moxlite.data.remote.moxfield.MoxPage
import es.moxlite.data.session.ApiTrafficLog
import es.moxlite.data.sync.DeckOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoxfieldDtoTest {

    /** Respuesta recortada con la forma de /v3/decks/all/{id}, con campos extra y nulos. */
    private val deckJson = """
        {
          "id": "abc", "publicId": "PUB123", "name": "Atraxa Superfriends", "format": "commander",
          "description": null, "visibility": "public", "version": 7, "likeCount": 3, "viewCount": 99,
          "colorIdentity": ["G","W","U","B"], "unknownField": {"a": 1},
          "createdByUser": {"userName": "yo", "displayName": "Yo", "badges": []},
          "boards": {
            "commanders": { "count": 1, "cards": {
              "k1": { "quantity": 1, "boardType": "commanders", "finish": "nonFoil", "isFoil": false, "isProxy": false,
                      "card": { "id": "x1", "uniqueCardId": "u1", "scryfall_id": "d0d33d52-3d28-4635-b985-51e126289259",
                                "name": "Atraxa, Praetors' Voice", "set": "2x2", "cn": "190", "cmc": 4,
                                "mana_cost": "{G}{W}{U}{B}", "type_line": "Legendary Creature — Phyrexian Angel Horror",
                                "color_identity": ["G","W","U","B"], "prices": {"eur": 12.5, "usd": null, "ck_buy": "n/a"} } }
            } },
            "mainboard": { "count": 2, "cards": {
              "k2": { "quantity": 2, "card": { "uniqueCardId": "u2", "name": "Island", "set": "unf", "type_line": "Basic Land — Island" } }
            } }
          }
        }
    """.trimIndent()

    @Test
    fun `parsea el mazo ignorando campos desconocidos y nulos`() {
        val deck = Http.json.decodeFromString(MoxDeckDto.serializer(), deckJson)
        assertEquals("PUB123", deck.publicId)
        assertEquals("", deck.description)
        assertEquals(7, deck.version)
        val atraxa = deck.boards.getValue("commanders").cards.getValue("k1").card
        assertEquals("d0d33d52-3d28-4635-b985-51e126289259", atraxa.scryfallId)
        assertEquals(12.5, atraxa.price("eur")!!, 0.001)
        assertEquals(null, atraxa.price("usd"))
        assertEquals(null, atraxa.price("ck_buy"))
        assertEquals(2, deck.boards.getValue("mainboard").cards.getValue("k2").quantity)
    }

    @Test
    fun `parsea una pagina de busqueda`() {
        val json = """{"pageNumber":1,"pageSize":2,"totalPages":5,"totalResults":9,
            "data":[{"publicId":"A","name":"Uno","format":"modern","colorIdentity":["R"],"createdByUser":{"userName":"u"}}]}"""
        val page = Http.json.decodeFromString(MoxPage.serializer(MoxDeckSummaryDto.serializer()), json)
        assertEquals(5, page.totalPages)
        assertEquals("u", page.data.single().createdByUser?.userName)
    }

    @Test
    fun `las operaciones se serializan con discriminador`() {
        val op: DeckOp = DeckOp.SetQuantity("mainboard", "u2", "Island", null, null, 3)
        val json = Http.json.encodeToString(DeckOp.serializer(), op)
        assertTrue(json.contains("\"type\":\"set_qty\""))
        assertEquals(op, Http.json.decodeFromString(DeckOp.serializer(), json))
    }

    @Test
    fun `el registro de API redacta secretos`() {
        val body = """{"userName":"yo","password":"secreta","token":"abc.def"}"""
        val redacted = ApiTrafficLog.redactBody(body)
        assertFalse(redacted.contains("secreta"))
        assertFalse(redacted.contains("abc.def"))
        assertTrue(redacted.contains("\"userName\":\"yo\""))
        assertFalse(ApiTrafficLog.redactUrl("https://api2.moxfield.com/x?token=zzz&a=1").contains("zzz"))
    }
}
