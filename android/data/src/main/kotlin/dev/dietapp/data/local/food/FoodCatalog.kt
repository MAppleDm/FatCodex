package dev.dietapp.data.local.food

import java.io.InputStream
import java.util.zip.GZIPInputStream

/** One food with nutrients per 100 g. */
data class CatalogFood(
    val id: String,
    val name: String,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
)

data class FoodMatch(val food: CatalogFood, val score: Double, val coverage: Double)

/**
 * The food database that ships inside the app (USDA SR Legacy, ~7 800 foods), searched entirely in memory.
 * Same ranking as the nutrition service, so local mode picks the same food the server would.
 */
class FoodCatalog(private val foods: List<CatalogFood>) {
    private val infos: List<Ranking.NameInfo> = foods.map { Ranking.analyse(it.name) }
    private val postings: Map<String, IntArray>

    init {
        val tmp = HashMap<String, MutableList<Int>>()
        infos.forEachIndexed { i, info -> for (stem in info.all) tmp.getOrPut(stem) { ArrayList() }.add(i) }
        postings = tmp.mapValues { it.value.toIntArray() }
    }

    val size: Int get() = foods.size

    private val byIds: Map<String, CatalogFood> by lazy { foods.associateBy { it.id } }

    fun byId(id: String): CatalogFood? = byIds[id]

    /** Best matches for any of [queries] (for example the model's English query and the user's own words), best first. */
    fun search(queries: List<String?>, limit: Int = 5): List<FoodMatch> {
        val querySets = queries.filter { !it.isNullOrBlank() }
            .map { Text.stemSet(Ranking.expandQuery(it!!)) }
            .filter { it.isNotEmpty() }
        if (querySets.isEmpty()) return emptyList()

        val candidates = LinkedHashSet<Int>()
        for (qs in querySets) for (stem in qs) postings[stem]?.forEach { candidates.add(it) }

        class Ranked(val index: Int, val score: Double, val coverage: Double, val size: Int)

        val ranked = ArrayList<Ranked>()
        for (i in candidates) {
            var score = 0.0
            var coverage = 0.0
            for (qs in querySets) {
                val (s, c) = Ranking.scoreMatch(qs, infos[i])
                if (s > score || (s == score && c > coverage)) {
                    score = s
                    coverage = c
                }
            }
            if (coverage == 0.0) continue
            ranked += Ranked(i, score, coverage, infos[i].all.size)
        }
        return ranked
            .sortedWith(compareBy<Ranked>({ -it.score }, { it.size }).thenBy { foods[it.index].name })
            .take(limit)
            .map { FoodMatch(foods[it.index], it.score, it.coverage) }
    }

    companion object {
        /** TSV: id, name, kcal, protein, fat, carbs (as written by `nutrition.cli export-tsv`). */
        fun fromTsv(lines: Sequence<String>): FoodCatalog {
            val foods = ArrayList<CatalogFood>()
            for (line in lines) {
                if (line.isBlank()) continue
                val p = line.split('\t')
                if (p.size < 6) continue
                foods += CatalogFood(p[0], p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
            }
            return FoodCatalog(foods)
        }

        fun fromGzippedTsv(stream: InputStream): FoodCatalog =
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { fromTsv(it.lineSequence()) }
    }
}
