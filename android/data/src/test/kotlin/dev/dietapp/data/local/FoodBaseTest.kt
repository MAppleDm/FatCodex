package dev.dietapp.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.TestEnv
import dev.dietapp.data.domain.Per100
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

    @Test fun `search covers the user's own foods and nothing else`() = runTest {
        base.save(casein)
        val hits = base.search("казеиновый протеин")
        assertEquals(listOf("Казеиновый протеин"), hits.map { it.name })
        assertTrue(hits.first().approximate)
        assertTrue(hits.first().id.startsWith("my:"))
        assertEquals("by another name too", listOf("Казеиновый протеин"), base.search("казеин").map { it.name })

        // there is no catalog and no table of dishes behind it: what the user never saved is not found
        assertTrue(base.search("борщ").isEmpty())
        assertTrue(base.search("гречка").isEmpty())
        assertTrue(base.search("buckwheat groats cooked").isEmpty())
        assertTrue(base.search("water tap").isEmpty())
    }

    @Test fun `search ranks the closest match first and ignores words that are not names`() = runTest {
        base.save(FoodInput("Колбаса Любительская (Мясновъ)", Per100(300.0, 12.0, 28.0, 0.1)))
        base.save(FoodInput("Колбаса докторская", Per100(257.0, 12.8, 22.2, 1.5)))
        assertEquals("Колбаса Любительская (Мясновъ)", base.search("любительская колбаса").first().name)
        assertEquals(2, base.search("колбаса").size)
        assertTrue(base.search("   ").isEmpty())
    }

    @Test fun `a hit can be fetched again by its id`() = runTest {
        val saved = base.save(casein)
        assertEquals("Казеиновый протеин", base.byId("my:${saved.id}")!!.name)
        assertNull(base.byId("my:999"))
        assertNull(base.byId("ru:борщ"))
        assertNull(base.byId("usda:01001"))
        assertNull(base.byId("nonsense"))
    }

    @Test fun `the resolver takes a food by id, or by the user's own name for it, and invents nothing`() = runTest {
        val saved = base.save(casein)
        val byId = env.resolver.resolve("что-то другое", "my:${saved.id}")!!
        assertEquals(360.0, byId.per100.kcal, 0.0)
        assertEquals("Казеиновый протеин", byId.foodName)
        assertTrue(byId.approximate)
        assertEquals(360.0, env.resolver.resolve("казеин", null)!!.per100.kcal, 0.0)
        assertNull(env.resolver.resolve("борщ", null))
        assertNull("an id that is not the user's own is not followed", env.resolver.resolve("борщ", "usda:01001"))
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
