package es.moxlite.data.repo

import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.data.remote.moxfield.MoxAuthorDto
import es.moxlite.data.remote.moxfield.MoxBoardCardDto
import es.moxlite.data.remote.moxfield.MoxBoardDto
import es.moxlite.data.remote.moxfield.MoxCardDto
import es.moxlite.data.remote.moxfield.MoxCardFaceDto
import es.moxlite.data.remote.moxfield.MoxDeckDto
import es.moxlite.data.remote.moxfield.MoxDeckSummaryDto
import es.moxlite.data.remote.scryfall.ScryCard
import es.moxlite.data.remote.scryfall.ScryFace
import es.moxlite.data.remote.scryfall.ScryImageUris
import es.moxlite.data.remote.scryfall.ScryPrices
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckMapperTest {

    private fun entry(card: MoxCardDto, qty: Int = 1, foil: Boolean = false, finish: String? = null) =
        MoxBoardCardDto(quantity = qty, isFoil = foil, finish = finish, card = card)

    private fun eur(value: Double) = buildJsonObject { put("eur", JsonPrimitive(value)) }

    private fun deck(boards: Map<String, MoxBoardDto>, author: MoxAuthorDto? = MoxAuthorDto("yo", "Yo")) = MoxDeckDto(
        id = "remote-1",
        publicId = "PUB",
        name = "Mazo",
        format = "commander",
        createdByUser = author,
        version = 4,
        colorIdentity = listOf("b", "W", "G", "W"),
        lastUpdatedAtUtc = "2024-01-01T00:00:00Z",
        boards = boards,
    )

    @Test
    fun `colors ordena en WUBRG, en mayusculas y sin duplicados`() {
        assertEquals("WUBRG", DeckMapper.colors(listOf("g", "R", "b", "U", "w")))
        assertEquals("WBG", DeckMapper.colors(listOf("G", "b", "W", "W")))
        assertEquals("", DeckMapper.colors(emptyList()))
        // Valores desconocidos van al final.
        assertEquals("UC", DeckMapper.colors(listOf("C", "U")))
    }

    @Test
    fun `fromDto mapea cartas, cuenta el mainboard y elige al comandante como destacada`() {
        val dto = deck(
            mapOf(
                Boards.COMMANDERS to MoxBoardDto(
                    cards = mapOf("k1" to entry(MoxCardDto(id = "c1", uniqueCardId = "u1", scryfallId = "sf-cmdr", name = "Atraxa", set = "2x2", cn = "190", colorIdentity = listOf("U", "W")))),
                ),
                Boards.MAIN to MoxBoardDto(
                    cards = mapOf(
                        "k2" to entry(MoxCardDto(uniqueCardId = "u2", name = "Island", typeLine = "Basic Land — Island"), qty = 10),
                        "k3" to entry(MoxCardDto(uniqueCardId = "u3", name = "Sol Ring", typeLine = "Artifact"), qty = 1, finish = "foil"),
                    ),
                ),
            ),
        )

        val (entity, cards) = DeckMapper.fromDto(dto, existing = null)

        assertEquals("PUB", entity.publicId)
        assertEquals("remote-1", entity.remoteId)
        assertEquals("yo", entity.authorUserName)
        assertEquals("WBG", entity.colorIdentity)
        assertEquals(11, entity.mainboardCount)
        assertEquals(4, entity.remoteVersion)
        assertEquals("sf-cmdr", entity.featuredScryfallId)
        assertFalse(entity.isSaved)
        assertFalse(entity.hasLocalChanges)
        assertTrue(entity.fetchedAt > 0)
        assertTrue(entity.isFullyLoaded)

        assertEquals(3, cards.size)
        val atraxa = cards.single { it.name == "Atraxa" }
        assertEquals(Boards.COMMANDERS, atraxa.board)
        assertEquals("u1", atraxa.cardKey)
        assertEquals("c1", atraxa.moxCardId)
        assertEquals("WU", atraxa.colorIdentity)
        assertEquals("190", atraxa.collectorNumber)
        assertTrue(cards.single { it.name == "Sol Ring" }.isFoil)
        assertNull(cards.single { it.name == "Island" }.moxCardId)
    }

    @Test
    fun `fromDto sin comandante destaca la carta no basica mas cara del mainboard`() {
        val dto = deck(
            mapOf(
                Boards.MAIN to MoxBoardDto(
                    cards = mapOf(
                        "a" to entry(MoxCardDto(uniqueCardId = "a", scryfallId = "sf-basic", name = "Island", typeLine = "Basic Land — Island", prices = eur(100.0))),
                        "b" to entry(MoxCardDto(uniqueCardId = "b", scryfallId = "sf-cheap", name = "Opt", typeLine = "Instant", prices = eur(0.1))),
                        "c" to entry(MoxCardDto(uniqueCardId = "c", scryfallId = "sf-pricey", name = "Force of Will", typeLine = "Instant", prices = eur(80.0))),
                    ),
                ),
                Boards.SIDE to MoxBoardDto(
                    cards = mapOf("d" to entry(MoxCardDto(uniqueCardId = "d", scryfallId = "sf-side", name = "Black Lotus", typeLine = "Artifact", prices = eur(9999.0)))),
                ),
            ),
        )
        val (entity, _) = DeckMapper.fromDto(dto, existing = null)
        assertEquals("sf-pricey", entity.featuredScryfallId)
        assertEquals(3, entity.mainboardCount)
    }

    @Test
    fun `fromDto conserva el estado local del mazo existente`() {
        val existing = DeckEntity(
            publicId = "PUB",
            name = "Viejo",
            isSaved = true,
            hasLocalChanges = true,
            hasConflict = true,
            originalPublicId = "ORIG",
            imagesCachedAt = 1234L,
        )
        val (entity, _) = DeckMapper.fromDto(deck(emptyMap()), existing)
        assertTrue(entity.isSaved)
        assertEquals("ORIG", entity.originalPublicId)
        assertEquals(1234L, entity.imagesCachedAt)
        // Lo descargado sustituye a lo local: ya no hay cambios ni conflicto.
        assertFalse(entity.hasLocalChanges)
        assertFalse(entity.hasConflict)
        assertEquals("Mazo", entity.name)
    }

    @Test
    fun `fromDto usa el primer autor si no hay createdByUser`() {
        val dto = deck(emptyMap(), author = null).copy(authors = listOf(MoxAuthorDto("otro", "Otro")))
        assertEquals("otro", DeckMapper.fromDto(dto, null).first.authorUserName)
    }

    @Test
    fun `fromDto cae a id, a la clave de entrada y a las caras cuando faltan datos`() {
        val dfc = MoxCardDto(
            id = "mox-id",
            name = "Delver of Secrets // Insectile Aberration",
            layout = "transform",
            cardFaces = listOf(
                MoxCardFaceDto("Delver of Secrets", "{U}", "Creature — Human Wizard", "Look at the top card."),
                MoxCardFaceDto("Insectile Aberration", "", "Creature — Human Insect", "Flying"),
            ),
        )
        val bare = MoxCardDto(name = "Misterio")
        val dto = deck(mapOf(Boards.MAIN to MoxBoardDto(cards = mapOf("e1" to entry(dfc), "e2" to entry(bare)))))

        val cards = DeckMapper.fromDto(dto, null).second
        val delver = cards.single { it.name.startsWith("Delver") }
        assertEquals("mox-id", delver.cardKey)
        assertEquals("Creature — Human Wizard", delver.typeLine)
        assertEquals("{U}", delver.manaCost)
        assertTrue(delver.hasBackImage)
        assertEquals(
            "Delver of Secrets\nLook at the top card.\n//\nInsectile Aberration\nFlying",
            delver.oracleText,
        )
        val misterio = cards.single { it.name == "Misterio" }
        assertEquals("e2", misterio.cardKey)
        assertNull(misterio.oracleText)
        assertFalse(misterio.hasBackImage)
    }

    @Test
    fun `fromSummary crea una fila minima sin marcarla como descargada`() {
        val summary = MoxDeckSummaryDto(
            id = "r",
            publicId = "S1",
            name = "Burn",
            format = "modern",
            authors = listOf(MoxAuthorDto("autor")),
            colorIdentity = listOf("R"),
            likeCount = 5,
            mainboardCount = 60,
        )
        val entity = DeckMapper.fromSummary(summary)
        assertEquals("S1", entity.publicId)
        assertEquals("autor", entity.authorUserName)
        assertEquals("R", entity.colorIdentity)
        assertEquals(60, entity.mainboardCount)
        assertEquals(0L, entity.fetchedAt)
        assertFalse(entity.isFullyLoaded)
    }

    @Test
    fun `fromScryfall usa la clave sf y convierte precios`() {
        val card = ScryCard(
            id = "abc-123",
            name = "Lightning Bolt",
            set = "2x2",
            collectorNumber = "117",
            manaCost = "{R}",
            typeLine = "Instant",
            oracleText = "Deal 3.",
            cmc = 1.0,
            colorIdentity = listOf("R"),
            rarity = "uncommon",
            imageUris = ScryImageUris(normal = "x"),
            prices = ScryPrices(eur = "1.25", usd = "no"),
        )
        val row = DeckMapper.fromScryfall("deck", Boards.SIDE, card, 3)
        assertEquals("sf:abc-123", row.cardKey)
        assertEquals(Boards.SIDE, row.board)
        assertEquals(3, row.quantity)
        assertEquals("R", row.colorIdentity)
        assertEquals("117", row.collectorNumber)
        assertEquals(1.25, row.priceEur!!, 0.0001)
        assertNull(row.priceUsd)
        assertFalse(row.hasBackImage)
    }

    @Test
    fun `fromScryfall toma tipo y coste de la primera cara en cartas dobles`() {
        val card = ScryCard(
            id = "dfc",
            name = "A // B",
            cardFaces = listOf(ScryFace("A", "{1}{G}", "Creature — Elf", "uno"), ScryFace("B", null, "Land", "dos")),
        )
        val row = DeckMapper.fromScryfall("deck", Boards.MAIN, card, 1)
        assertEquals("Creature — Elf", row.typeLine)
        assertEquals("{1}{G}", row.manaCost)
        assertTrue(row.hasBackImage)
        assertEquals("A\nuno\n//\nB\ndos", row.oracleText)
    }

    @Test
    fun `la carta destacada del autor manda sobre el comandante y se guarda su id de Moxfield`() {
        val dto = deck(
            mapOf(
                Boards.COMMANDERS to MoxBoardDto(cards = mapOf("k1" to entry(MoxCardDto(id = "c1", uniqueCardId = "u1", scryfallId = "sf-cmdr", name = "Atraxa")))),
                Boards.MAIN to MoxBoardDto(cards = mapOf("k2" to entry(MoxCardDto(id = "m1", uniqueCardId = "u2", scryfallId = "sf-main", name = "Sol Ring")))),
            )
        ).copy(mainCardId = "m1")
        val (entity, _) = DeckMapper.fromDto(dto, null)
        assertEquals("sf-main", entity.featuredScryfallId)
        assertEquals("m1", entity.featuredMoxCardId)
    }

    @Test
    fun `fromSummary guarda mainCardId para la miniatura de las listas`() {
        val entity = DeckMapper.fromSummary(MoxDeckSummaryDto(publicId = "S1", name = "Burn", mainCardId = "abc12"))
        assertEquals("abc12", entity.featuredMoxCardId)
        assertNull(entity.featuredScryfallId)
    }
}
