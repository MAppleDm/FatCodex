package dev.dietapp.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.TestEnv
import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.parse.OfflineParser
import dev.dietapp.data.local.parse.ParseRequest
import dev.dietapp.data.net.AppError
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodBaseTest {
    private val env = TestEnv()
    private val base = env.foods

    @After fun tearDown() = env.close()

    private val casein = FoodInput("Казеиновый протеин", Per100(360.0, 80.0, 1.5, 8.0), aliases = listOf("казеин"), estimated = true)

    private suspend fun refused(input: FoodInput, author: Author = Author.User): String {
        try {
            base.save(input, author = author)
        } catch (e: AppError) {
            return e.message!!
        }
        fail("should have been refused: $input")
        error("unreachable")
    }

    // ---------- saving ----------

    @Test fun `a food is saved with a tidy name and found again by any of its names`() = runTest {
        val saved = base.save(casein.copy(name = "  Казеиновый   протеин "))
        assertEquals("Казеиновый протеин", saved.name)
        assertEquals(listOf("казеин"), saved.aliases)
        assertEquals(saved.id, base.mine("казеиновый протеин")!!.id.removePrefix("my:").toLong())
        assertNotNull(base.mine("Казеин"))
        assertNull(base.mine("протеин"))
    }

    @Test fun `the same name again updates the food instead of making a second one`() = runTest {
        val first = base.save(casein)
        val second = base.save(casein.copy(name = "казеиновый протеин", per100 = Per100(370.0, 82.0, 2.0, 7.0)))
        assertEquals(first.id, second.id)
        assertEquals(1, env.db.foods().all().size)
        assertEquals(370.0, env.db.foods().all().single().kcal, 0.0)
    }

    @Test fun `renaming onto another food's name is refused`() = runTest {
        base.save(casein)
        val whey = base.save(FoodInput("сывороточный протеин", Per100(380.0, 78.0, 6.0, 6.0)))
        val message = try {
            base.save(casein, id = whey.id); null
        } catch (e: AppError) { e.message }
        assertEquals("В базе уже есть «Казеиновый протеин».", message)
    }

    @Test fun `values that cannot describe 100 g are refused`() = runTest {
        assertTrue(refused(casein.copy(name = " ")).contains("название"))
        assertTrue(refused(casein.copy(per100 = Per100(-1.0, 80.0, 1.5, 8.0))).contains("отрицательными"))
        assertTrue(refused(casein.copy(per100 = Per100(950.0, 0.0, 100.0, 0.0))).contains("900"))
        assertTrue(refused(casein.copy(per100 = Per100(400.0, 60.0, 30.0, 30.0))).contains("больше 100 г"))
    }

    @Test fun `the model has to make the energy add up, a person copying a label does not`() = runTest {
        val vodka = FoodInput("водка", Per100(231.0, 0.0, 0.0, 0.0))
        assertTrue(refused(vodka, Author.Model).startsWith("Не сходится энергия"))
        assertEquals("водка", base.save(vodka, author = Author.User).name) // alcohol: 7 kcal/g that no macro accounts for
        assertEquals("Казеиновый протеин", base.save(casein, author = Author.Model).name) // 4·80 + 9·1.5 + 4·8 = 365.5
    }

    @Test fun `deleting a food`() = runTest {
        val saved = base.save(casein)
        assertEquals("Казеиновый протеин", base.delete(saved.id)!!.name)
        assertNull(base.mine("казеин"))
        assertNull(base.delete(saved.id))
    }

    // ---------- searching ----------

    @Test fun `search covers the user's foods first, then the table and the catalog`() = runTest {
        base.save(casein)
        val hits = base.search("казеиновый протеин")
        assertEquals(FoodHit.Source.Mine, hits.first().source)
        assertTrue(hits.first().approximate)

        val borscht = base.search("борщ")
        assertTrue(borscht.any { it.id == "ru:борщ" && it.source == FoodHit.Source.Table })

        val buckwheat = base.search("гречка")
        assertTrue("the dictionary's USDA query is followed: ${buckwheat.map { it.name }}",
            buckwheat.any { it.source == FoodHit.Source.Usda && it.name.startsWith("Buckwheat groats") })

        val english = base.search("water tap")
        assertTrue(english.any { it.source == FoodHit.Source.Usda && it.name.contains("Water", ignoreCase = true) })
    }

    @Test fun `a hit can be fetched again by its id`() = runTest {
        val saved = base.save(casein)
        assertEquals("Казеиновый протеин", base.byId("my:${saved.id}")!!.name)
        assertEquals(FoodHit.Source.Table, base.byId("ru:борщ")!!.source)
        val usda = base.search("buckwheat groats roasted cooked").first { it.source == FoodHit.Source.Usda }
        assertEquals(usda.per100, base.byId(usda.id)!!.per100)
        assertNull(base.byId("my:999"))
        assertNull(base.byId("nonsense"))
    }

    @Test fun `the offline parser recognises the user's own foods by name`() = runTest {
        base.save(casein)
        val parsed = OfflineParser(base.lexicon()).parse(ParseRequest("казеиновый протеин 30 грамм"))
        assertEquals("Казеиновый протеин", parsed.items.single().name)
        assertEquals(30.0, parsed.items.single().grams, 0.0)
        val resolved = env.resolver.resolve(parsed.items.single().name, null)!!
        assertEquals(360.0, resolved.per100.kcal, 0.0)
        assertTrue(resolved.approximate)
    }

    // ---------- the file ----------

    @Test fun `export and import round trip, merging by name`() = runTest {
        base.save(casein)
        base.save(FoodInput("батончик", Per100(400.0, 20.0, 15.0, 45.0), note = "с упаковки"))
        val file = base.exportJson()
        assertTrue(file.contains("\"format\": \"dietapp-foods\""))
        assertTrue("readable, not escaped", file.contains("Казеиновый протеин"))

        env.db.foods().delete(env.db.foods().all().first { it.name == "батончик" }.id)
        val edited = file.replace("\"kcal\": 360.0", "\"kcal\": 365.0")
        val result = base.importJson(edited)
        assertEquals(1, result.added)
        assertEquals(1, result.updated)
        assertTrue(result.skipped.isEmpty())
        assertEquals(365.0, base.byId(base.mine("казеин")!!.id)!!.per100.kcal, 0.0)
        assertEquals("с упаковки", env.db.foods().all().first { it.name == "батончик" }.note)
    }

    @Test fun `a bad row is skipped with the reason, the rest is imported`() = runTest {
        val result = base.importJson(
            """{"format": "dietapp-foods", "version": 1, "foods": [
                {"name": "творог 5%", "kcal": 121, "protein": 17, "fat": 5, "carbs": 1.8},
                {"name": "", "kcal": 100, "protein": 1, "fat": 1, "carbs": 1}
            ]}""",
        )
        assertEquals(1, result.added)
        assertEquals(1, result.skipped.size)
        assertTrue(result.skipped.single().contains("Нужно название"))
    }

    @Test fun `something that is not a food file is refused as a whole`() = runTest {
        for (text in listOf("not json", """{"format": "other", "foods": []}""")) {
            val error = try { base.importJson(text); null } catch (e: AppError) { e }
            assertEquals("bad_file", error!!.code)
        }
        assertFalse(env.db.foods().all().isNotEmpty())
    }
}
