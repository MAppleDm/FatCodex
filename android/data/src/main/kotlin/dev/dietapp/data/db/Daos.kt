package dev.dietapp.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE day = :day AND deleted = 0 ORDER BY eatenAtMs, position, id")
    fun observeDay(day: String): Flow<List<EntryRow>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun get(id: String): EntryRow?

    /** Today's live entries, in diary order: what the parser is told it may change (at most 100). */
    @Query("SELECT * FROM entries WHERE day = :day AND deleted = 0 ORDER BY eatenAtMs, position, id LIMIT 100")
    suspend fun forDay(day: String): List<EntryRow>

    /** The recorded diary for the export: everything counted, in diary order. [from] and [to] inclusive (ISO days). */
    @Query("SELECT * FROM entries WHERE deleted = 0 AND pending = 0 AND day >= :from AND day <= :to ORDER BY day, eatenAtMs, position, id")
    suspend fun recorded(from: String, to: String): List<EntryRow>

    /** Past meals with known nutrition, for "как обычно". [from] inclusive, [to] exclusive (ISO days). */
    @Query("SELECT * FROM entries WHERE deleted = 0 AND pending = 0 AND mealId IS NOT NULL AND kcal100 IS NOT NULL AND day >= :from AND day < :to")
    suspend fun history(from: String, to: String): List<EntryRow>

    @Upsert
    suspend fun upsert(rows: List<EntryRow>)

    @Upsert
    suspend fun upsert(row: EntryRow)

    @Query("SELECT * FROM entries WHERE dirty = 1 ORDER BY updatedAtMs, id")
    suspend fun dirty(): List<EntryRow>

    @Query("SELECT id FROM entries WHERE dirty = 1")
    suspend fun dirtyIds(): List<String>

    @Query("UPDATE entries SET deleted = 1, dirty = :dirty WHERE id = :id")
    suspend fun markDeleted(id: String, dirty: Boolean)

    @Query(
        "SELECT day, SUM(kcal) AS kcal, SUM(protein) AS protein, SUM(fat) AS fat, SUM(carbs) AS carbs " +
            "FROM entries WHERE deleted = 0 AND pending = 0 AND kcal IS NOT NULL GROUP BY day ORDER BY day DESC",
    )
    fun observeDaySummaries(): Flow<List<DaySummaryRow>>

    /** "записать": these entries are counted from now on. */
    @Query("UPDATE entries SET pending = 0, updatedAtMs = :now WHERE id IN (:ids) AND pending = 1")
    suspend fun confirm(ids: List<String>, now: Long)

    @Query("DELETE FROM entries")
    suspend fun clear()
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights WHERE deleted = 0 ORDER BY day, updatedAtMs")
    fun observeAll(): Flow<List<WeightRow>>

    @Query("SELECT * FROM weights WHERE deleted = 0 ORDER BY day, updatedAtMs")
    suspend fun all(): List<WeightRow>

    /** The weigh-in that counts for [day] (the latest one), if there is one. */
    @Query("SELECT * FROM weights WHERE day = :day AND deleted = 0 ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun latestForDay(day: String): WeightRow?

    @Query("SELECT * FROM weights WHERE id = :id")
    suspend fun get(id: String): WeightRow?

    @Upsert
    suspend fun upsert(rows: List<WeightRow>)

    @Upsert
    suspend fun upsert(row: WeightRow)

    @Query("SELECT * FROM weights WHERE dirty = 1 ORDER BY updatedAtMs, id")
    suspend fun dirty(): List<WeightRow>

    @Query("SELECT id FROM weights WHERE dirty = 1")
    suspend fun dirtyIds(): List<String>

    @Query("UPDATE weights SET deleted = 1, dirty = :dirty WHERE id = :id")
    suspend fun markDeleted(id: String, dirty: Boolean)

    @Query("DELETE FROM weights")
    suspend fun clear()
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAtMs, id")
    fun observeAll(): Flow<List<OutboxRow>>

    @Query("SELECT * FROM outbox WHERE state = 'queued' ORDER BY createdAtMs, id")
    suspend fun queued(): List<OutboxRow>

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: String): OutboxRow?

    @Upsert
    suspend fun upsert(row: OutboxRow)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE day = :day ORDER BY createdAtMs, id")
    fun observeDay(day: String): Flow<List<NoteRow>>

    @Query("SELECT * FROM notes WHERE kind = 'question' AND resolved = 0 ORDER BY createdAtMs DESC, id DESC LIMIT 1")
    suspend fun latestOpenQuestion(): NoteRow?

    @Insert
    suspend fun insert(row: NoteRow): Long

    @Query("UPDATE notes SET resolved = 1 WHERE id = :id")
    suspend fun resolve(id: Long)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteRow?

    @Query("SELECT * FROM notes WHERE day = :day ORDER BY createdAtMs, id")
    suspend fun forDay(day: String): List<NoteRow>

    @Query("UPDATE notes SET resolved = 1 WHERE targetEntryId = :entryId")
    suspend fun resolveForEntry(entryId: String)


    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    /** The lines of the first versions that said a message was "read without the model": the app never does that now. */
    @Query(
        "DELETE FROM notes WHERE kind = 'info' AND (text LIKE '%Разобрано без неё%' OR text LIKE '%Разобрано без модели%' " +
            "OR text LIKE '%Read without it%' OR text LIKE '%Read without the model%')",
    )
    suspend fun deleteLegacyFallback(): Int

    @Query("DELETE FROM notes")
    suspend fun clear()
}

@Dao
interface DayFactsDao {
    @Query("SELECT * FROM day_facts WHERE day = :day")
    fun observe(day: String): Flow<DayFactsRow?>

    @Query("SELECT * FROM day_facts WHERE day >= :from AND day <= :to ORDER BY day")
    suspend fun between(from: String, to: String): List<DayFactsRow>

    @Query("SELECT * FROM day_facts WHERE day = :day")
    suspend fun get(day: String): DayFactsRow?

    @Upsert
    suspend fun upsert(row: DayFactsRow)

    @Query("DELETE FROM day_facts")
    suspend fun clear()
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    fun observe(): Flow<ProfileRow?>

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun get(): ProfileRow?

    @Upsert
    suspend fun upsert(row: ProfileRow)

    @Query("DELETE FROM profile")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE day = :day ORDER BY atMs, createdAtMs, id")
    fun observeDay(day: String): Flow<List<MessageRow>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageRow?

    /** The day's messages once, for the agent: what was said before the message it reads. */
    @Query("SELECT * FROM messages WHERE day = :day ORDER BY atMs, createdAtMs, id")
    suspend fun forDay(day: String): List<MessageRow>

    @Upsert
    suspend fun upsert(row: MessageRow)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface FoodDao {
    @Query("SELECT * FROM foods ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<FoodRow>>

    @Query("SELECT * FROM foods ORDER BY name COLLATE NOCASE, id")
    suspend fun all(): List<FoodRow>

    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun get(id: Long): FoodRow?

    @Query("SELECT * FROM foods WHERE `key` = :key")
    suspend fun byKey(key: String): FoodRow?

    @Insert
    suspend fun insert(row: FoodRow): Long

    @Update
    suspend fun update(row: FoodRow)

    @Query("DELETE FROM foods WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM foods")
    suspend fun clear()
}

@Database(
    entities = [
        EntryRow::class, WeightRow::class, OutboxRow::class, NoteRow::class, ProfileRow::class,
        MessageRow::class, FoodRow::class, DayFactsRow::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun weights(): WeightDao
    abstract fun outbox(): OutboxDao
    abstract fun notes(): NoteDao
    abstract fun profile(): ProfileDao
    abstract fun messages(): MessageDao
    abstract fun foods(): FoodDao
    abstract fun dayFacts(): DayFactsDao

    companion object {
        /** Every migration, oldest first. In local mode the database is the only copy of the diary: never drop it. */
        val MIGRATIONS = arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7, Migration7To8, Migration8To9, Migration9To10)
    }
}

/**
 * v10: the calories burned are not read from the watch any more (what the watches put into Health Connect was not right), so the
 * two columns of v8 and v9 go. The table is rebuilt (SQLite before 3.35 cannot drop a column); sleep, steps and workouts stay.
 */
object Migration9To10 : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `day_facts_new` (`day` TEXT NOT NULL, `sleepMinutes` INTEGER, `steps` INTEGER, " +
                "`workouts` TEXT, `readAtMs` INTEGER NOT NULL, PRIMARY KEY(`day`))",
        )
        db.execSQL(
            "INSERT INTO `day_facts_new` (`day`, `sleepMinutes`, `steps`, `workouts`, `readAtMs`) " +
                "SELECT `day`, `sleepMinutes`, `steps`, `workouts`, `readAtMs` FROM `day_facts`",
        )
        db.execSQL("DROP TABLE `day_facts`")
        db.execSQL("ALTER TABLE `day_facts_new` RENAME TO `day_facts`")
    }
}

/** v9: everything the watch says was burned that day, the body at rest included (some watches give only that). */
object Migration8To9 : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `day_facts` ADD COLUMN `totalKcal` INTEGER")
    }
}

/** v8: the calories the watch says were burned that day, added to the day's allowance. A new nullable column, nothing else moves. */
object Migration7To8 : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `day_facts` ADD COLUMN `activeKcal` INTEGER")
    }
}

/**
 * v7: the watch's pulse and heart rate variability are not read any more, so the two columns go. A table is rebuilt instead of
 * altered (SQLite before 3.35, which is most phones, cannot drop a column); what was read about sleep, steps and workouts stays.
 */
object Migration6To7 : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `day_facts_new` (`day` TEXT NOT NULL, `sleepMinutes` INTEGER, `steps` INTEGER, " +
                "`workouts` TEXT, `readAtMs` INTEGER NOT NULL, PRIMARY KEY(`day`))",
        )
        db.execSQL(
            "INSERT INTO `day_facts_new` (`day`, `sleepMinutes`, `steps`, `workouts`, `readAtMs`) " +
                "SELECT `day`, `sleepMinutes`, `steps`, `workouts`, `readAtMs` FROM `day_facts`",
        )
        db.execSQL("DROP TABLE `day_facts`")
        db.execSQL("ALTER TABLE `day_facts_new` RENAME TO `day_facts`")
    }
}

/** v6: the ingredients of a dish (in the user's foods and in diary entries), and what the watch says about a day. */
object Migration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `foods` ADD COLUMN `parts` TEXT")
        db.execSQL("ALTER TABLE `entries` ADD COLUMN `parts` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `day_facts` (`day` TEXT NOT NULL, `sleepMinutes` INTEGER, `steps` INTEGER, `restingHr` INTEGER, " +
                "`hrvMs` REAL, `workouts` TEXT, `readAtMs` INTEGER NOT NULL, PRIMARY KEY(`day`))",
        )
    }
}

/** v5: an answer to a question about an entry knows that entry. */
object Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `aboutEntryId` TEXT")
    }
}

/** v4: entries waiting for the user's confirmation (auto-confirm switched off). */
object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `entries` ADD COLUMN `pending` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v3: food proposals (options in a note, to be confirmed), and where each food of the user's base came from. */
object Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `payload` TEXT")
        db.execSQL("ALTER TABLE `foods` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'user'")
        db.execSQL("ALTER TABLE `foods` ADD COLUMN `url` TEXT")
        // foods the model saved before v3 were estimates: that is what tells them apart
        db.execSQL("UPDATE `foods` SET `origin` = 'model' WHERE `estimated` = 1")
    }
}

/** v2: kept messages (the feed as a conversation), the user's own foods, and notes tied to their message. */
object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `day` TEXT NOT NULL, `text` TEXT, " +
                "`hasImage` INTEGER NOT NULL, `source` TEXT NOT NULL, `atMs` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_day` ON `messages` (`day`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `foods` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`key` TEXT NOT NULL, `aliases` TEXT NOT NULL, `kcal` REAL NOT NULL, `protein` REAL NOT NULL, " +
                "`fat` REAL NOT NULL, `carbs` REAL NOT NULL, `estimated` INTEGER NOT NULL, `note` TEXT, " +
                "`createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_foods_key` ON `foods` (`key`)")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `messageId` TEXT")
        // messages still waiting to be processed keep showing up as messages
        db.execSQL(
            "INSERT OR IGNORE INTO `messages` (`id`, `day`, `text`, `hasImage`, `source`, `atMs`, `createdAtMs`) " +
                "SELECT `id`, `day`, `text`, `hasImage`, `source`, `createdAtMs`, `createdAtMs` FROM `outbox`",
        )
    }
}
