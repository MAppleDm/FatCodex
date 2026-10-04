package dev.dietapp.data.local.food

import dev.dietapp.data.local.TestFiles
import java.io.FileInputStream
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin ranking must give the same answers as the nutrition service. Both read the same files:
 *  - expected_matches.json: hand-judged patterns for 70+ queries (also checked by pytest),
 *  - golden_top1.json: the exact best match Python produces on the real USDA subset,
 *  - sr_legacy_subset.csv: 1 337 real USDA rows, the candidates for those queries.
 */
class FoodCatalogParityTest {
    private val data = TestFiles.repoFile("services/nutrition/tests/data")
    private val shared = Json.parseToJsonElement(data.resolve("expected_matches.json").readText()).jsonObject
    private val expected: Map<String, String> = shared["expected"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val absent: List<String> = shared["absent"]!!.jsonArray.map { it.jsonPrimitive.content }
    private val extra: List<String> = shared["extra"]!!.jsonArray.map { it.jsonPrimitive.content }
    private val golden: JsonObject = Json.parseToJsonElement(data.resolve("golden_top1.json").readText()).jsonObject

    private val subset: FoodCatalog by lazy {
        val lines = data.resolve("sr_legacy_subset.csv").readLines().drop(1)
        FoodCatalog(
            lines.filter { it.isNotBlank() }.map {
                val p = TestFiles.parseCsvLine(it)
                CatalogFood(p[0], p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
            },
        )
    }

    private val full: FoodCatalog by lazy {
        FileInputStream(TestFiles.repoFile("android/data/src/main/assets/foods_sr_legacy.tsv.gzip")).use { FoodCatalog.fromGzippedTsv(it) }
    }

    private fun best(catalog: FoodCatalog, query: String): FoodMatch? =
        catalog.search(listOf(query), limit = 1).firstOrNull()?.takeIf { it.coverage >= 0.75 }

    @Test fun `the bundled catalog is the whole of SR Legacy`() {
        assertEquals(7785, full.size)
    }

    @Test fun `the subset has the real rows`() {
        assertTrue(subset.size > 1300)
    }

    @Test fun `every expected query finds the judged food on the subset`() {
        val failures = expected.filter { (q, pattern) ->
            val name = best(subset, q)?.food?.name
            name == null || !Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(name)
        }.map { (q, _) -> "$q -> ${best(subset, q)?.food?.name}" }
        assertTrue("wrong foods: $failures", failures.isEmpty())
    }

    @Test fun `every expected query finds the judged food on the complete catalog too`() {
        val failures = expected.filter { (q, pattern) ->
            val name = best(full, q)?.food?.name
            name == null || !Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(name)
        }.map { (q, _) -> "$q -> ${best(full, q)?.food?.name}" }
        assertTrue("wrong foods on the full catalog: $failures", failures.isEmpty())
    }

    @Test fun `foods that are not in the database are not replaced by something else`() {
        for (q in absent) assertEquals("'$q' must stay unmatched", null, best(full, q))
        for (q in absent) assertEquals("'$q' must stay unmatched", null, best(subset, q))
    }

    @Test fun `the best match is exactly what the Python service picks`() {
        val queries = expected.keys + absent + extra
        assertEquals("golden file covers the same queries", queries.toSet(), golden.keys)
        val mismatches = ArrayList<String>()
        for (q in queries) {
            val want = golden.getValue(q)
            val got = best(subset, q)
            if (want is JsonNull) {
                if (got != null) mismatches += "$q: python found nothing, kotlin found '${got.food.name}'"
                continue
            }
            val wantName = want.jsonObject["name"]!!.jsonPrimitive.content
            val wantScore = want.jsonObject["score"]!!.jsonPrimitive.double
            when {
                got == null -> mismatches += "$q: python '$wantName', kotlin found nothing"
                got.food.name != wantName -> mismatches += "$q: python '$wantName', kotlin '${got.food.name}'"
                abs(got.score - wantScore) > 0.0011 -> mismatches += "$q: score ${got.score} vs python $wantScore"
            }
        }
        assertTrue("Kotlin and Python disagree:\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test fun `staples have the calories a person would recognise`() {
        val ranges = mapOf(
            "buckwheat cooked" to (85.0..100.0), "chicken breast cooked" to (150.0..175.0), "egg boiled" to (150.0..160.0),
            "banana" to (85.0..95.0), "butter" to (700.0..730.0), "bacon cooked" to (500.0..600.0),
            "olive oil" to (880.0..890.0), "apple" to (48.0..56.0), "rice brown cooked" to (105.0..130.0),
        )
        for ((q, range) in ranges) {
            val kcal = best(full, q)?.food?.kcal
            assertNotNull(q, kcal)
            assertTrue("$q: $kcal kcal", kcal!! in range)
        }
    }

    @Test fun `searching is quick enough to do on every message`() {
        full.search(listOf("warm up"))
        val t = System.nanoTime()
        repeat(50) { full.search(listOf("chicken breast cooked", "куриная грудка")) }
        val ms = (System.nanoTime() - t) / 1_000_000 / 50
        assertTrue("one search took ~$ms ms", ms < 100)
    }
}
