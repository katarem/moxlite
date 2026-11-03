package es.moxlite.data.sync

import es.moxlite.data.remote.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckOpTest {

    private val all: List<DeckOp> = listOf(
        DeckOp.CreateDeck("Nuevo", "commander"),
        DeckOp.CreateDeck("Copia", "modern", copiedFrom = "PUB"),
        DeckOp.SetQuantity("mainboard", "u1", "Island", "sf", "mx", 4),
        DeckOp.SetQuantity("sideboard", "u2", "Duress", null, null, 0),
        DeckOp.MoveCard("mainboard", "sideboard", "u3", "Negate", 2),
        DeckOp.Rename("Otro nombre"),
        DeckOp.ChangePrinting("mainboard", "u4", "Sol Ring", "old", "sfOld", "sfNew", "c21", "263"),
    )

    @Test
    fun `todas las operaciones se serializan y deserializan sin perder datos`() {
        all.forEach { op ->
            val json = Http.json.encodeToString(DeckOp.serializer(), op)
            assertTrue("Falta discriminador en $json", json.contains("\"type\":\"${op.typeName}\""))
            assertEquals(op, Http.json.decodeFromString(DeckOp.serializer(), json))
        }
    }

    @Test
    fun `typeName coincide con el SerialName`() {
        assertEquals(listOf("create", "create", "set_qty", "set_qty", "move", "rename", "printing"), all.map { it.typeName })
    }

    @Test
    fun `describe genera textos legibles`() {
        assertEquals(
            listOf(
                "Crear mazo \"Nuevo\"",
                "Crear copia \"Copia\"",
                "Island × 4",
                "Quitar Duress",
                "Mover Negate a Banquillo",
                "Renombrar a \"Otro nombre\"",
                "Arte de Sol Ring: C21 263",
            ),
            all.map { it.describe() },
        )
    }

    @Test
    fun `lee colas guardadas por versiones anteriores (sin campos nuevos)`() {
        val old = """{"type":"set_qty","board":"mainboard","cardKey":"u1","name":"Island","scryfallId":null,"moxCardId":"mx","quantity":3}"""
        val op = Http.json.decodeFromString(DeckOp.serializer(), old) as DeckOp.SetQuantity
        assertEquals(3, op.quantity)
        assertEquals(null, op.setCode)
        val create = Http.json.decodeFromString(DeckOp.serializer(), """{"type":"create","name":"N","format":"modern"}""") as DeckOp.CreateDeck
        assertEquals("public", create.visibility)
    }
}
