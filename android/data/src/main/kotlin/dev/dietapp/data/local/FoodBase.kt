package dev.dietapp.data.local

import dev.dietapp.data.domain.Lang.t
import androidx.room.withTransaction
import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.db.FoodRow
import dev.dietapp.data.db.toDomain
import dev.dietapp.data.domain.Food
import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.food.Text
import dev.dietapp.data.local.parse.LexEntry
import dev.dietapp.data.local.parse.Lexicon
import dev.dietapp.data.net.AppError
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A food found by [FoodBase.search]. The id says where it lives: `my:12` (the user's), `ru:борщ` (the table), `usda:01001`. */
data class FoodHit(val id: String, val name: String, val per100: Per100, val source: Source, val approximate: Boolean) {
    enum class Source { Mine, Table, Usda }
}

/** What a food is saved with, from the screen, the model or a file. Values per 100 g. */
data class FoodInput(
    val name: String,
    val per100: Per100,
    val aliases: List<String> = emptyList(),
    val estimated: Boolean = false,
    val note: String? = null,
    /** "user", "model", "web" or "import"; null keeps what an existing food has (a new one becomes "user"). */
    val origin: String? = null,
    val url: String? = null,
)

/** Who is saving: the model's numbers get a stricter check than a person copying a label. */
enum class Author { User, Model }

data class ImportResult(val added: Int, val updated: Int, val skipped: List<String>)

/** What the resolver needs from the food base: exact picks by id and the user's own foods by name. */
interface FoodLookup {
    suspend fun byId(id: String): FoodHit?
    suspend fun mine(name: String): FoodHit?
}

/**
 * Every food the app knows, in one place: the user's own foods (a Room table, editable by the user, by the model
 * and through a JSON file), the bundled table of typical Russian dishes and the bundled USDA catalog. The last two
 * are read-only.
 */
@Singleton
class FoodBase @Inject constructor(
    private val db: AppDatabase,
    private val catalog: CatalogSource,
    private val bundled: Lexicon,
    private val clock: Clock,
) : FoodLookup {
    private val lock = Mutex()

    /** The user's foods in memory (small), with a dictionary that knows them; null until first read or after a change. */
    @Volatile private var cache: Pair<List<FoodRow>, Lexicon>? = null

    val foods: Flow<List<Food>> = db.foods().observeAll().map { rows -> rows.map { it.toDomain() } }

    private suspend fun loaded(): Pair<List<FoodRow>, Lexicon> = cache ?: lock.withLock {
        cache ?: db.foods().all().let { rows -> rows to Lexicon(rows.map(::lexEntry) + bundled.entries) }.also { cache = it }
    }

    private fun invalidate() { cache = null }

    /** The bundled dictionary with the user's foods in front, so the offline parser recognises them by name. */
    suspend fun lexicon(): Lexicon = loaded().second

    // ---------- reading ----------

    /** The best matches for [query] (Russian or English) from all three sources, the user's own first. */
    suspend fun search(query: String, limit: Int = 10): List<FoodHit> {
        val tokens = Text.tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val hits = LinkedHashMap<String, FoodHit>()

        loaded().first
            .map { it to matched(tokens, it) }
            .filter { (_, n) -> n == tokens.size || (tokens.size >= 3 && n >= tokens.size - 1) }
            .sortedWith(compareBy({ -it.second }, { it.first.name.length }))
            .forEach { (row, _) -> mineHit(row).let { hits[it.id] = it } }

        // the dictionary: its table values, or the USDA row its query points at
        val queries = mutableListOf<String>(query)
        var i = 0
        while (i < tokens.size) {
            val (entry, len) = bundled.matchAt(tokens, i) ?: (null to 1)
            if (entry != null) {
                entry.per100?.let { hits.putIfAbsent("ru:${entry.name}", FoodHit("ru:${entry.name}", entry.name, it, FoodHit.Source.Table, true)) }
                entry.query?.let(queries::add)
            }
            i += len
        }
        val cat = catalog.get()
        for (q in queries) {
            cat.search(listOf(q), limit = if (q == query) 6 else 2)
                .filter { it.coverage >= 0.5 }
                .forEach { m -> hits.putIfAbsent("usda:${m.food.id}", usdaHit(m.food.id, m.food.name, Per100(m.food.kcal, m.food.protein, m.food.fat, m.food.carbs))) }
        }
        return hits.values.take(limit)
    }

    override suspend fun byId(id: String): FoodHit? {
        val (kind, rest) = id.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return null
        return when (kind) {
            "my" -> rest.toLongOrNull()?.let { db.foods().get(it) }?.let(::mineHit)
            "ru" -> bundled.entries.firstOrNull { it.name == rest && it.per100 != null }
                ?.let { FoodHit(id, it.name, it.per100!!, FoodHit.Source.Table, true) }
            "usda" -> catalog.get().byId(rest)?.let { usdaHit(it.id, it.name, Per100(it.kcal, it.protein, it.fat, it.carbs)) }
            else -> null
        }
    }

    /** One of the user's foods called exactly [name] (or one of its other names). */
    override suspend fun mine(name: String): FoodHit? {
        val key = keyOf(name).takeIf { it.isNotEmpty() } ?: return null
        return loaded().first.firstOrNull { row -> row.key == key || row.aliases.lines().any { keyOf(it) == key } }?.let(::mineHit)
    }

    suspend fun get(id: Long): Food? = db.foods().get(id)?.toDomain()

    // ---------- writing ----------

    /**
     * Creates a food, or updates [id], or (without an id) updates the food that already has this name.
     * Throws [AppError] with a message that can be shown (or handed back to the model) when the values cannot be right.
     */
    suspend fun save(input: FoodInput, id: Long? = null, author: Author = Author.User): Food {
        val clean = validate(input, author)
        val key = keyOf(clean.name)
        val now = clock.millis()
        val saved = db.withTransaction {
            val sameName = db.foods().byKey(key)
            val existing = if (id != null) {
                db.foods().get(id) ?: throw AppError(t("Такого продукта в базе нет.", "There is no such food in the base."), "food_missing")
            } else {
                sameName
            }
            if (existing != null && sameName != null && sameName.id != existing.id) {
                throw AppError(t("В базе уже есть «${sameName.name}».", "The base already has “${sameName.name}”."), "food_exists")
            }
            val row = FoodRow(
                id = existing?.id ?: 0, name = clean.name, key = key,
                aliases = clean.aliases.joinToString("\n"),
                kcal = clean.per100.kcal, protein = clean.per100.protein, fat = clean.per100.fat, carbs = clean.per100.carbs,
                estimated = clean.estimated, note = clean.note,
                createdAtMs = existing?.createdAtMs ?: now, updatedAtMs = now,
                origin = clean.origin ?: existing?.origin ?: "user",
                url = clean.url ?: existing?.url.takeIf { clean.origin == null },
            )
            if (existing == null) row.copy(id = db.foods().insert(row)) else row.also { db.foods().update(it) }
        }
        invalidate()
        return saved.toDomain()
    }

    suspend fun delete(id: Long): Food? {
        val row = db.foods().get(id) ?: return null
        db.foods().delete(id)
        invalidate()
        return row.toDomain()
    }

    // ---------- file ----------

    /** All of the user's foods as a JSON file anyone (or any tool) can read and edit, then import back. */
    suspend fun exportJson(): String = FILE_JSON.encodeToString(
        FoodsFile.serializer(),
        FoodsFile(
            exportedAt = Instant.ofEpochMilli(clock.millis()).toString(),
            foods = db.foods().all().map {
                FoodJson(it.name, it.aliases.lines().filter(String::isNotBlank), it.kcal, it.protein, it.fat, it.carbs, it.estimated, it.note, it.url)
            },
        ),
    )

    /** Adds new foods and updates the ones with the same name. Foods that are not in the file are kept. */
    suspend fun importJson(text: String): ImportResult {
        val file = try {
            FILE_JSON.decodeFromString(FoodsFile.serializer(), text)
        } catch (e: Exception) {
            throw AppError(t("Это не файл базы продуктов (нужен JSON, как при экспорте).", "This is not a food base file (a JSON file like the export is needed)."), "bad_file")
        }
        if (file.format != FORMAT) throw AppError(t("Это не файл базы продуктов (нужен JSON, как при экспорте).", "This is not a food base file (a JSON file like the export is needed)."), "bad_file")
        var added = 0
        var updated = 0
        val skipped = ArrayList<String>()
        for (f in file.foods) {
            val input = FoodInput(f.name, Per100(f.kcal, f.protein, f.fat, f.carbs), f.aliases, f.estimated, f.note, origin = "import", url = f.url)
            try {
                val existed = db.foods().byKey(keyOf(f.name)) != null
                save(input)
                if (existed) updated++ else added++
            } catch (e: AppError) {
                skipped += "${f.name.ifBlank { t("(без названия)", "(no name)") }}: ${e.message}"
            }
        }
        return ImportResult(added, updated, skipped)
    }

    // ---------- helpers ----------

    private fun matched(query: List<String>, row: FoodRow): Int {
        val words = (Text.tokenize(row.name) + row.aliases.lines().flatMap(Text::tokenize)).toSet()
        return query.count { q -> words.any { w -> Lexicon.similar(q, w) || (q.length >= 3 && w.startsWith(q)) } }
    }

    private fun mineHit(row: FoodRow) =
        FoodHit("my:${row.id}", row.name, Per100(row.kcal, row.protein, row.fat, row.carbs), FoodHit.Source.Mine, row.estimated)

    private fun usdaHit(id: String, name: String, per100: Per100) = FoodHit("usda:$id", name, per100, FoodHit.Source.Usda, false)

    private fun lexEntry(row: FoodRow) = LexEntry(
        name = row.name,
        phrases = (listOf(row.name) + row.aliases.lines()).map(Text::tokenize).filter { it.isNotEmpty() },
        query = null, per100 = null, defaultGrams = 100.0, units = emptyMap(), withGrams = null, variable = false, bareSpoon = null,
    )

    companion object {
        const val FORMAT = "dietapp-foods"

        private val FILE_JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Folded words of a name: "Казеиновый  Протеин" and "казеиновый протеин" are the same food. */
        fun keyOf(name: String): String = Text.tokenize(name).joinToString(" ")

        /**
         * Rejects values that cannot describe 100 g of anything. The model also has to make the energy add up
         * (4 kcal per g of protein and carbs, 9 per g of fat); a person copying a label is trusted, since alcohol
         * and fibre legitimately break that sum.
         */
        fun validate(input: FoodInput, author: Author): FoodInput {
            val name = input.name.trim().replace(Regex("\\s+"), " ")
            if (keyOf(name).isEmpty()) throw AppError(t("Нужно название.", "A name is needed."), "bad_food")
            if (name.length > 80) throw AppError(t("Название длиннее 80 знаков.", "The name is longer than 80 characters."), "bad_food")
            val p = input.per100
            val values = listOf(p.kcal, p.protein, p.fat, p.carbs)
            if (values.any { it.isNaN() || it < 0 }) throw AppError(t("Значения не могут быть отрицательными.", "Values cannot be negative."), "bad_food")
            if (p.kcal > 900) throw AppError(t("В 100 г не бывает больше 900 ккал.", "100 g cannot hold more than 900 kcal."), "bad_food")
            if (p.protein > 100 || p.fat > 100 || p.carbs > 100 || p.protein + p.fat + p.carbs > 105) {
                throw AppError(t("Белков, жиров и углеводов вместе больше 100 г на 100 г.", "Protein, fat and carbs add up to more than 100 g per 100 g."), "bad_food")
            }
            if (author == Author.Model) {
                val energy = 4 * p.protein + 9 * p.fat + 4 * p.carbs
                if (abs(energy - p.kcal) > max(40.0, 0.25 * p.kcal)) {
                    throw AppError(
                        t("Не сходится энергия: 4·Б + 9·Ж + 4·У = ${energy.toInt()} ккал, а указано ${p.kcal.toInt()}.", "The energy does not add up: 4·P + 9·F + 4·C = ${energy.toInt()} kcal, but ${p.kcal.toInt()} is given."),
                        "bad_food",
                    )
                }
            }
            val aliases = input.aliases.map { it.trim() }.filter { it.isNotEmpty() && keyOf(it) != keyOf(name) }.distinctBy(::keyOf)
            val url = input.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.take(500)
            return input.copy(name = name, aliases = aliases, note = input.note?.trim()?.takeIf { it.isNotEmpty() }?.take(1000), url = url)
        }
    }
}

@Serializable
private data class FoodsFile(
    val format: String = FoodBase.FORMAT,
    val version: Int = 1,
    @SerialName("exported_at") val exportedAt: String? = null,
    val foods: List<FoodJson> = emptyList(),
)

/** One food in the file. Values per 100 g. */
@Serializable
private data class FoodJson(
    val name: String,
    val aliases: List<String> = emptyList(),
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val estimated: Boolean = false,
    val note: String? = null,
    val url: String? = null,
)
