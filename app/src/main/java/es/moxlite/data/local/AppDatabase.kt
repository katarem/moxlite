package es.moxlite.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DeckDao {

    @Query(
        """
        SELECT * FROM decks
        WHERE isSaved = 1 OR isLocalOnly = 1 OR (:me != '' AND lower(authorUserName) = lower(:me))
        ORDER BY hasLocalChanges DESC, name COLLATE NOCASE
        """
    )
    abstract fun observeMine(me: String): Flow<List<DeckEntity>>

    @Query("SELECT * FROM decks WHERE isSaved = 0 AND isLocalOnly = 0 AND fetchedAt > 0 ORDER BY fetchedAt DESC LIMIT 20")
    abstract fun observeRecent(): Flow<List<DeckEntity>>

    @Query("SELECT * FROM decks WHERE publicId = :id")
    abstract fun observe(id: String): Flow<DeckEntity?>

    @Query("SELECT * FROM decks WHERE publicId = :id")
    abstract suspend fun get(id: String): DeckEntity?

    @Query("SELECT * FROM decks WHERE isSaved = 1 AND isLocalOnly = 0")
    abstract suspend fun getSaved(): List<DeckEntity>

    @Query("SELECT * FROM decks WHERE hasLocalChanges = 1")
    abstract suspend fun getWithLocalChanges(): List<DeckEntity>

    @Upsert
    abstract suspend fun upsert(deck: DeckEntity)

    @Query("DELETE FROM decks WHERE publicId = :id")
    abstract suspend fun deleteDeckRow(id: String)

    @Query("SELECT * FROM deck_cards WHERE deckId = :id")
    abstract fun observeCards(id: String): Flow<List<DeckCardEntity>>

    @Query("SELECT * FROM deck_cards WHERE deckId = :id")
    abstract suspend fun getCards(id: String): List<DeckCardEntity>

    @Query("SELECT * FROM deck_cards WHERE deckId = :deckId AND board = :board AND cardKey = :cardKey")
    abstract suspend fun getCard(deckId: String, board: String, cardKey: String): DeckCardEntity?

    @Upsert
    abstract suspend fun upsertCards(cards: List<DeckCardEntity>)

    @Query("DELETE FROM deck_cards WHERE deckId = :deckId AND board = :board AND cardKey = :cardKey")
    abstract suspend fun deleteCard(deckId: String, board: String, cardKey: String)

    @Query("DELETE FROM deck_cards WHERE deckId = :id")
    abstract suspend fun deleteCards(id: String)

    @Transaction
    open suspend fun replaceDeck(deck: DeckEntity, cards: List<DeckCardEntity>) {
        upsert(deck)
        deleteCards(deck.publicId)
        upsertCards(cards)
    }

    @Transaction
    open suspend fun deleteDeck(id: String) {
        deleteCards(id)
        deleteDeckRow(id)
    }

    @Query("UPDATE deck_cards SET deckId = :newId WHERE deckId = :oldId")
    abstract suspend fun moveCardsToDeck(oldId: String, newId: String)

    @Query("UPDATE pending_ops SET deckId = :newId WHERE deckId = :oldId")
    abstract suspend fun moveOpsToDeck(oldId: String, newId: String)

    /** Cuando un mazo local se crea en Moxfield, pasa a usar el publicId real. */
    @Transaction
    open suspend fun changeDeckId(old: DeckEntity, newPublicId: String, remoteId: String) {
        upsert(old.copy(publicId = newPublicId, remoteId = remoteId, isLocalOnly = false))
        moveCardsToDeck(old.publicId, newPublicId)
        moveOpsToDeck(old.publicId, newPublicId)
        deleteDeckRow(old.publicId)
    }
}

@Dao
abstract class PendingOpDao {
    @Query("SELECT * FROM pending_ops ORDER BY id")
    abstract fun observeAll(): Flow<List<PendingOpEntity>>

    @Query("SELECT * FROM pending_ops ORDER BY id")
    abstract suspend fun getAll(): List<PendingOpEntity>

    @Query("SELECT COUNT(*) FROM pending_ops WHERE deckId = :deckId")
    abstract suspend fun countForDeck(deckId: String): Int

    @Insert
    abstract suspend fun insert(op: PendingOpEntity): Long

    @Upsert
    abstract suspend fun update(op: PendingOpEntity)

    @Query("DELETE FROM pending_ops WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM pending_ops WHERE deckId = :deckId")
    abstract suspend fun deleteForDeck(deckId: String)

    @Query("DELETE FROM pending_ops WHERE deckId = :deckId AND type = :type AND targetKey = :targetKey")
    abstract suspend fun deleteSameTarget(deckId: String, type: String, targetKey: String)

    /** Sustituye la operación anterior sobre la misma carta (solo cuenta el último valor). */
    @Transaction
    open suspend fun enqueueCoalesced(op: PendingOpEntity) {
        if (op.targetKey != null) deleteSameTarget(op.deckId, op.type, op.targetKey)
        insert(op)
    }
}

@Dao
abstract class UserDao {
    @Query("SELECT * FROM followed_users ORDER BY userName COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<FollowedUserEntity>>

    @Upsert
    abstract suspend fun upsert(user: FollowedUserEntity)

    @Query("DELETE FROM followed_users WHERE userName = :userName")
    abstract suspend fun delete(userName: String)
}

@Database(
    entities = [DeckEntity::class, DeckCardEntity::class, PendingOpEntity::class, FollowedUserEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun decks(): DeckDao
    abstract fun pendingOps(): PendingOpDao
    abstract fun users(): UserDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "moxlite.db").build()
    }
}
