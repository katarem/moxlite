package es.moxlite.data.sync

import es.moxlite.data.local.DeckCardEntity
import es.moxlite.data.local.DeckEntity
import es.moxlite.data.remote.moxfield.Boards
import es.moxlite.i18n.I18n
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Operación de edición hecha en el móvil, pendiente de subir a Moxfield.
 * Se guarda como JSON en la tabla pending_ops y se reproduce en orden al sincronizar.
 * Los campos nuevos llevan valor por defecto para poder leer colas guardadas por versiones anteriores.
 */
@Serializable
sealed interface DeckOp {
    val typeName: String

    /** Crear el mazo en Moxfield (mazos nuevos, importados o copias hechas en el móvil). */
    @Serializable
    @SerialName("create")
    data class CreateDeck(
        val name: String,
        val format: String,
        val copiedFrom: String? = null,
        val visibility: String = "public",
    ) : DeckOp {
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
        val setCode: String? = null,
        val collectorNumber: String? = null,
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

    /** Cambiar la impresión (arte) de una carta. */
    @Serializable
    @SerialName("printing")
    data class ChangePrinting(
        val board: String,
        val cardKey: String,
        val name: String,
        val oldMoxCardId: String?,
        val oldScryfallId: String?,
        val newScryfallId: String,
        val setCode: String,
        val collectorNumber: String,
        val foil: Boolean = false,
    ) : DeckOp {
        override val typeName get() = "printing"
    }
}

fun DeckOp.describe(): String {
    val t = I18n.strings
    return when (this) {
        is DeckOp.CreateDeck -> if (copiedFrom != null) t.opCreateCopy(name) else t.opCreate(name)
        is DeckOp.SetQuantity -> if (quantity == 0) t.opRemove(name) else "$name × $quantity"
        is DeckOp.MoveCard -> t.opMove(name, Boards.label(toBoard))
        is DeckOp.Rename -> t.opRename(name)
        is DeckOp.ChangePrinting -> t.opPrinting(name, setCode.uppercase(), collectorNumber)
    }
}

sealed interface WriteResult {
    data object Done : WriteResult

    /**
     * El mazo local se ha creado en Moxfield con todo su contenido actual y ahora tiene este
     * publicId: el resto de operaciones pendientes de ese mazo ya están incluidas.
     */
    data class Created(val publicId: String, val remoteId: String) : WriteResult

    /** No se puede subir (sin sesión o mazo ajeno): la operación se queda en cola. */
    data object NotSupported : WriteResult
}

/** Aplica operaciones contra Moxfield. */
interface MoxfieldWriter {
    val isAvailable: Boolean

    /** [cards] = contenido local actual del mazo (para crear mazos con todo su contenido). */
    suspend fun apply(deck: DeckEntity, op: DeckOp, cards: List<DeckCardEntity>): WriteResult
}
