package es.moxlite.data.remote

import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.scryfall.ScryFace
import es.moxlite.data.remote.scryfall.ScryImageUris
import es.moxlite.data.remote.scryfall.ScryList
import es.moxlite.data.remote.scryfall.ScryfallImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScryfallTest {

    @Test
    fun `construye la URL de imagen a partir del id`() {
        val id = "D0D33D52-3D28-4635-B985-51E126289259"
        assertEquals(
            "https://cards.scryfall.io/normal/front/d/0/d0d33d52-3d28-4635-b985-51e126289259.jpg",
            ScryfallImages.url(id),
        )
        assertEquals(
            "https://cards.scryfall.io/art_crop/back/d/0/d0d33d52-3d28-4635-b985-51e126289259.jpg",
            ScryfallImages.url(id, ScryfallImages.Size.ART_CROP, back = true),
        )
    }

    @Test
    fun `hasBackImage solo si las caras tienen imagen propia`() {
        val faces = listOf(ScryFace("A"), ScryFace("B"))
        assertTrue(ScryCard(cardFaces = faces).hasBackImage)
        // Split/adventure: varias caras pero una sola imagen.
        assertFalse(ScryCard(cardFaces = faces, imageUris = ScryImageUris(normal = "x")).hasBackImage)
        assertFalse(ScryCard().hasBackImage)
    }

    @Test
    fun `fullOracle une las caras si no hay texto principal`() {
        assertEquals("Texto", ScryCard(oracleText = "Texto", cardFaces = listOf(ScryFace("A", oracleText = "x"))).fullOracle)
        assertEquals("A\nuno\n//\nB\n", ScryCard(cardFaces = listOf(ScryFace("A", oracleText = "uno"), ScryFace("B"))).fullOracle)
        assertNull(ScryCard().fullOracle)
    }

    @Test
    fun `parsea una respuesta de collection con not_found`() {
        val json = """
            {"object":"list","not_found":[{"name":"Nada"}],
             "data":[{"id":"1","name":"Sol Ring","set":"c21","collector_number":"263","cmc":1.0,
                      "color_identity":[],"prices":{"eur":"1.10","usd":null},"legalities":{"vintage":"restricted"}}]}
        """.trimIndent()
        val list = Http.json.decodeFromString(ScryList.serializer(ScryCard.serializer()), json)
        val card = list.data.single()
        assertEquals("263", card.collectorNumber)
        assertEquals("1.10", card.prices.eur)
        assertNull(card.prices.usd)
        assertEquals("Nada", list.notFound.single().name)
        assertFalse(list.hasMore)
    }
}
