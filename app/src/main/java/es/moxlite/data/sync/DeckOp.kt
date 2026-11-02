package es.moxlite.data.sync

import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.MoxError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Operación de edición hecha en el móvil, pendiente de subir a Moxfield.
 * Se guarda como JSON en la tabla pending_ops y se reproduce en orden al sincronizar.
 */
@Serializable
sealed interface DeckOp {
    val typeName: String

    /** Crear el mazo en Moxfield (mazos nuevos o copias hechas offline). */
    @Serializable
    @SerialName("create")
    data class CreateDeck(val name: String, val format: String, val copiedFrom: String? = null) : DeckOp {
        override val typeName get() = "create"
    }

    /** Fijar la cantidad de una carta en un board (0 = quitarla). */
    @Serializable
    @SerialName("set_qty")
    data class SetQuantity(
        val board: String,
        val cardKey: String,
        val name: String,
        val scryfallId: String?,
        val moxCardId: String?,
        val quantity: Int,
    ) : DeckOp {
        override val typeName get() = "set_qty"
    }

    @Serializable
    @SerialName("move")
    data class MoveCard(
        val fromBoard: String,
        val toBoard: String,
        val cardKey: String,
        val name: String,
        val quantity: Int,
    ) : DeckOp {
        override val typeName get() = "move"
    }

    @Serializable
    @SerialName("rename")
    data class Rename(val name: String) : DeckOp {
        override val typeName get() = "rename"
    }
}

fun DeckOp.describe(): String = when (this) {
    is DeckOp.CreateDeck -> if (copiedFrom != null) "Crear copia \"$name\"" else "Crear mazo \"$name\""
    is DeckOp.SetQuantity -> if (quantity == 0) "Quitar $name" else "$name × $quantity"
    is DeckOp.MoveCard -> "Mover $name a $toBoard"
    is DeckOp.Rename -> "Renombrar a \"$name\""
}

sealed interface WriteResult {
    data object Done : WriteResult

    /** El mazo local se ha creado en Moxfield y ahora tiene este publicId. */
    data class Created(val publicId: String, val remoteId: String) : WriteResult

    /** Todavía no se conoce el endpoint: la operación se queda en cola. */
    data object NotSupported : WriteResult
}

/**
 * Aplica operaciones contra Moxfield. Los endpoints de escritura aún no están confirmados,
 * así que la implementación inicial no sube nada y la cola se conserva intacta.
 *
 * Cuando se capturen las rutas (pantalla "Registro de API"), se implementa otra clase
 * con las llamadas reales y se cambia en AppContainer.
 */
interface MoxfieldWriter {
    val isAvailable: Boolean
    suspend fun apply(deck: DeckEntity, op: DeckOp): WriteResult
}

class PendingDiscoveryWriter : MoxfieldWriter {
    override val isAvailable = false
    override suspend fun apply(deck: DeckEntity, op: DeckOp): WriteResult = WriteResult.NotSupported
}

fun notSupported(what: String) = MoxError.NotSupportedYet(what)
