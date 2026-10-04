package dev.dietapp.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.local.TestFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In local mode the database is the only copy of the diary, so an update must carry it over, never rebuild it.
 * Builds a real version-1 database from the exported schema, opens it with the current Room (which migrates and
 * validates the result) and checks the data and the new tables.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A database exactly as [version] of the app created it, from the schema Room exported at the time. */
    private fun createVersion(version: Int): SQLiteDatabase {
        val schema = Json.parseToJsonElement(
            TestFiles.repoFile("android/data/schemas/dev.dietapp.data.db.AppDatabase/$version.json").readText(),
        ).jsonObject["database"]!!.jsonObject
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs(); it.delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
            val table = entity["tableName"]!!.jsonPrimitive.content
            db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    @Test fun `version 1 data survives the move to the current version`() {
        createVersion(1).apply {
            execSQL(
                "INSERT INTO entries VALUES ('e1', 'm1', '2026-09-30', 1000, 0, 'гречка', 200.0, 184.0, 6.8, 1.2, 39.9, " +
                    "92.0, 3.38, 0.62, 19.94, 'Buckwheat', 'ok', 0.9, 'text', 1000, 0, 0)",
            )
            execSQL("INSERT INTO notes (day, kind, text, targetEntryId, resolved, createdAtMs) VALUES ('2026-09-30', 'question', 'Какой суп?', 'e1', 0, 2000)")
            execSQL(
                "INSERT INTO outbox VALUES ('m2', 'суп', 0, 'image/jpeg', '2026-09-30', '2026-09-30T10:00:00+03:00', 'text', " +
                    "NULL, NULL, 'queued', NULL, 0, 3000)",
            )
            execSQL("INSERT INTO profile VALUES (1, NULL, 1900)")
            close()
        }

        // Room checks every table, column and index against the version-2 schema after migrating, and throws if the
        // migration left anything different (MigrationTestHelper cannot open its file under Robolectric)
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            assertEquals("гречка", db.entries().get("e1")!!.name)
            assertEquals(1900, db.profile().get()!!.calorieGoal)
            val note = db.notes().latestOpenQuestion()!!
            assertEquals("Какой суп?", note.text)
            assertNull(note.messageId)
            // a message still waiting in the outbox shows up as a message
            val message = db.messages().get("m2")!!
            assertEquals("суп", message.text)
            assertEquals("2026-09-30", message.day)
            // the new food table works, with its unique name
            val id = db.foods().insert(FoodRow(name = "казеин", key = "казеин", aliases = "", kcal = 360.0, protein = 80.0,
                fat = 1.5, carbs = 8.0, estimated = true, note = null, createdAtMs = 1, updatedAtMs = 1))
            assertEquals("казеин", db.foods().get(id)!!.name)
        }
        db.close()
    }

    @Test fun `version 2 foods learn where they came from`() {
        createVersion(2).apply {
            execSQL("INSERT INTO foods (name, `key`, aliases, kcal, protein, fat, carbs, estimated, note, createdAtMs, updatedAtMs) " +
                "VALUES ('казеин', 'казеин', '', 360, 80, 1.5, 8, 1, NULL, 1, 1)")
            execSQL("INSERT INTO foods (name, `key`, aliases, kcal, protein, fat, carbs, estimated, note, createdAtMs, updatedAtMs) " +
                "VALUES ('мой хлеб', 'мои хлеб', '', 231, 9, 3, 41, 0, 'этикетка', 1, 1)")
            execSQL("INSERT INTO notes (day, kind, text, targetEntryId, resolved, createdAtMs, messageId) VALUES ('2026-09-30', 'info', 'x', NULL, 1, 2, 'm1')")
            close()
        }
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val foods = db.foods().all().associateBy { it.name }
            assertEquals("the model's guesses were the estimated ones", "model", foods.getValue("казеин").origin)
            assertEquals("user", foods.getValue("мой хлеб").origin)
            assertNull(foods.getValue("мой хлеб").url)
            assertNull(db.notes().get(1)!!.payload)
        }
        db.close()
    }

    @Test fun `version 3 entries are all recorded ones`() {
        createVersion(3).apply {
            execSQL(
                "INSERT INTO entries VALUES ('e1', 'm1', '2026-10-02', 1000, 0, 'макароны вареные', 200.0, 316.0, 11.6, 1.8, 61.8, " +
                    "158.0, 5.8, 0.9, 30.9, 'Pasta', 'ok', 0.9, 'text', 1000, 0, 0)",
            )
            close()
        }
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            assertEquals(false, db.entries().get("e1")!!.pending)
            assertEquals(316.0, db.entries().observeDaySummaries().first().single().kcal, 0.0)
        }
        db.close()
    }

    @Test fun `version 4 messages answer nothing in particular`() {
        createVersion(4).apply {
            execSQL("INSERT INTO messages (id, day, text, hasImage, source, atMs, createdAtMs) VALUES ('m1', '2026-10-03', 'суп', 0, 'text', 1, 1)")
            close()
        }
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        runBlocking { assertNull(db.messages().get("m1")!!.aboutEntryId) }
        db.close()
    }
}
