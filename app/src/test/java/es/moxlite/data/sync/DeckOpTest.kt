package es.moxlite.data.sync

import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.Http
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(listOf("create", "create", "set_qty", "set_qty", "move", "rename"), all.map { it.typeName })
    }

    @Test
    fun `describe genera textos legibles`() {
        assertEquals(
            listOf(
                "Crear mazo \"Nuevo\"",
                "Crear copia \"Copia\"",
                "Island × 4",
                "Quitar Duress",
                "Mover Negate a sideboard",
                "Renombrar a \"Otro nombre\"",
            ),
            all.map { it.describe() },
        )
    }

    @Test
    fun `el escritor provisional no sube nada`() = runTest {
        val writer = PendingDiscoveryWriter()
        assertFalse(writer.isAvailable)
        assertEquals(WriteResult.NotSupported, writer.apply(DeckEntity(publicId = "x", name = "x"), all.first()))
    }

    @Test
    fun `notSupported explica que se guarda en local`() {
        assertTrue(notSupported("Renombrar").message!!.startsWith("Renombrar todavía no está implementado"))
    }
}
