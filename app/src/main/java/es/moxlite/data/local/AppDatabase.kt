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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DeckDao {

    /** Todos los mazos del móvil (para "Mis mazos": pestañas, búsqueda y filtros se aplican en memoria). */
    @Query("SELECT * FROM decks")
    abstract fun observeAll(): Flow<List<DeckEntity>>

    @Query("UPDATE decks SET viewedAt = :at WHERE publicId = :id")
    abstract suspend fun markViewed(id: String, at: Long)

    @Query("UPDATE decks SET commanders = :commanders WHERE publicId = :id")
    abstract suspend fun setCommanders(id: String, commanders: String)

    @Upsert
    abstract suspend fun upsertAlias(alias: DeckAliasEntity)

    @Query("SELECT newId FROM deck_aliases WHERE oldId = :oldId")
    abstract fun observeAlias(oldId: String): Flow<String?>

    @Query("SELECT newId FROM deck_aliases WHERE oldId = :oldId")
    abstract suspend fun getAlias(oldId: String): String?

    @Query("SELECT * FROM decks WHERE publicId = :id")
    abstract fun observe(id: String): Flow<DeckEntity?>

    @Query("SELECT * FROM decks WHERE publicId = :id")
    abstract suspend fun get(id: String): DeckEntity?

    @Query("SELECT * FROM decks WHERE isSaved = 1 AND isLocalOnly = 0")
    abstract suspend fun getSaved(): List<DeckEntity>

    @Query("SELECT * FROM decks WHERE :me != '' AND lower(authorUserName) = lower(:me) AND isLocalOnly = 0")
    abstract suspend fun getOwnedBy(me: String): List<DeckEntity>

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
        // Se lee de nuevo: puede haber cambiado (p. ej. se abrió) mientras se subía.
        val current = get(old.publicId) ?: old
        upsert(current.copy(publicId = newPublicId, remoteId = remoteId, isLocalOnly = false))
        moveCardsToDeck(old.publicId, newPublicId)
        moveOpsToDeck(old.publicId, newPublicId)
        deleteDeckRow(old.publicId)
        // La pantalla que lo tenga abierto (y los enlaces viejos) siguen al id nuevo.
        upsertAlias(DeckAliasEntity(old.publicId, newPublicId))
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

    /** Users whose avatar has never been fetched (followed before avatars were stored). */
    @Query("SELECT * FROM followed_users WHERE profileImageUrl IS NULL")
    abstract suspend fun getWithoutAvatar(): List<FollowedUserEntity>

    @Query("UPDATE followed_users SET profileImageUrl = :url WHERE userName = :userName")
    abstract suspend fun setAvatar(userName: String, url: String)
}

@Dao
abstract class LocalizationDao {
    @Query("SELECT * FROM card_localizations WHERE lang = :lang")
    abstract fun observe(lang: String): Flow<List<CardLocalizationEntity>>

    @Query("SELECT * FROM card_localizations WHERE lang = :lang")
    abstract suspend fun getAll(lang: String): List<CardLocalizationEntity>

    @Upsert
    abstract suspend fun upsert(rows: List<CardLocalizationEntity>)
}

@Database(
    entities = [
        DeckEntity::class, DeckCardEntity::class, PendingOpEntity::class, FollowedUserEntity::class,
        CardLocalizationEntity::class, DeckAliasEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun decks(): DeckDao
    abstract fun pendingOps(): PendingOpDao
    abstract fun users(): UserDao
    abstract fun localizations(): LocalizationDao

    companion object {
        /** v2: miniatura por id de Moxfield y caché de cartas en otros idiomas. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE decks ADD COLUMN featuredMoxCardId TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS card_localizations (" +
                        "setCode TEXT NOT NULL, collectorNumber TEXT NOT NULL, lang TEXT NOT NULL, " +
                        "scryfallId TEXT, printedName TEXT, checkedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(setCode, collectorNumber, lang))"
                )
            }
        }

        /**
         * v3: bracket, tipos (hubs), comandantes y "visto" para filtrar y ordenar en "Mis mazos";
         * alias de ids para seguir a un mazo creado en el móvil cuando se sube a Moxfield.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE decks ADD COLUMN bracket INTEGER")
                db.execSQL("ALTER TABLE decks ADD COLUMN hubs TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE decks ADD COLUMN commanders TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE decks ADD COLUMN viewedAt INTEGER NOT NULL DEFAULT 0")
                // Los que salían en "Vistos recientemente" siguen saliendo.
                db.execSQL("UPDATE decks SET viewedAt = fetchedAt WHERE isSaved = 0 AND isLocalOnly = 0 AND fetchedAt > 0")
                db.execSQL(
                    "UPDATE decks SET commanders = COALESCE((SELECT group_concat(name, char(10)) FROM deck_cards " +
                        "WHERE deck_cards.deckId = decks.publicId AND deck_cards.board = 'commanders'), '')"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS deck_aliases (oldId TEXT NOT NULL, newId TEXT NOT NULL, PRIMARY KEY(oldId))"
                )
            }
        }

        /** v4: avatar of followed users. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE followed_users ADD COLUMN profileImageUrl TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "moxlite.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
