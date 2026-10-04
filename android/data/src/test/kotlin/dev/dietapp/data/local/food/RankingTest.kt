package dev.dietapp.data.local.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of services/nutrition/tests/test_text.py and test_ranking.py. */
class TextTest {
    @Test fun `fold strips diacritics and case`() {
        assertEquals("ежик иогурт creme", Text.fold("Ёжик Йогурт Crème"))
    }

    @Test fun `tokenize drops stopwords, punctuation and duplicates`() {
        assertEquals(
            listOf("chicken", "broilers", "fryers", "breast", "meat", "cooked"),
            Text.tokenize("Chicken, broilers or fryers, breast, meat only, cooked, cooked"),
        )
        assertEquals(listOf("hard", "boiled", "2", "5"), Text.tokenize("hard-boiled 2.5%"))
    }

    @Test fun `english plurals`() {
        mapOf(
            "eggs" to "egg", "berries" to "berry", "potatoes" to "potato", "glass" to "glass", "couscous" to "couscous",
            "peaches" to "peach", "radishes" to "radish", "boxes" to "box", "glasses" to "glass", "cheeses" to "cheese",
            "apples" to "apple",
        ).forEach { (word, stem) -> assertEquals(word, stem, Text.stem(word)) }
    }

    @Test fun `russian inflections collapse`() {
        assertEquals(Text.stem("гречка"), Text.stem("гречки"))
        assertEquals(Text.stem("гречка"), Text.stem("гречку"))
        assertEquals(Text.stem("яйцо"), Text.stem("яйца"))
        assertEquals(Text.stem("масло"), Text.stem("масла"))
        assertEquals(Text.stem("масло"), Text.stem("масле"))
    }
}

class RankingTest {
    private fun score(query: String, food: String): Double =
        Ranking.scoreMatch(Text.stemSet(Ranking.expandQuery(query)), Ranking.analyse(food)).first

    @Test fun `first segment is what the food is`() {
        val info = Ranking.analyse("Apples, raw, with skin")
        assertEquals(setOf("apple"), info.head)
        assertEquals(setOf("raw"), info.second)
        assertEquals(setOf("skin"), info.later)
    }

    @Test fun `food group prefixes are transparent`() {
        val info = Ranking.analyse("Beverages, coffee, brewed, prepared with tap water")
        assertEquals(setOf("beverage"), info.category)
        assertEquals(setOf("coffee"), info.head)
        assertEquals(setOf("salmon"), Ranking.analyse("Fish, salmon, Atlantic, farmed, cooked").head)
        assertEquals(setOf("mayonnaise"), Ranking.analyse("Salad dressing, mayonnaise, regular").head)
        assertEquals(setOf("guava", "sauce"), Ranking.analyse("Guava sauce, cooked").head)
        assertEquals(setOf("granola"), Ranking.analyse("Cereals ready-to-eat, granola, homemade").head)
    }

    @Test fun `noise segments do not push the identity down`() {
        val info = Ranking.analyse("Chicken, broilers or fryers, thigh, meat only, cooked, roasted")
        assertEquals(setOf("chicken"), info.head)
        assertEquals(setOf("thigh"), info.second)
        assertTrue(info.all.containsAll(setOf("broiler", "fryer", "meat")))
    }

    @Test fun `brands are spotted but usda abbreviations are not`() {
        assertTrue(Ranking.analyse("Rice, brown, parboiled, cooked, UNCLE BENS").branded)
        assertTrue(Ranking.analyse("McDONALD'S, french fries").branded)
        assertFalse(Ranking.analyse("Rice, brown, long-grain, cooked (Includes foods for USDA's Food Distribution Program)").branded)
        assertFalse(Ranking.analyse("Milk, NFS").branded)
    }

    @Test fun `a food beats a product that merely mentions it`() {
        assertTrue(score("apple", "Apples, raw, with skin") > score("apple", "Croissants, apple"))
        assertTrue(score("apple", "Apples, raw, with skin") > score("apple", "Rose-apples, raw"))
        assertTrue(score("egg", "Egg, whole, raw, fresh") > score("egg", "Bagels, egg"))
        assertTrue(score("milk whole", "Milk, whole, 3.25% milkfat, with added vitamin D") > score("milk whole", "Cheese, mozzarella, whole milk"))
    }

    @Test fun `word order in the query does not matter`() {
        assertTrue(score("olive oil", "Oil, olive, salad or cooking") > score("olive oil", "Oil, corn, peanut, and olive"))
        assertEquals(score("oil olive", "Oil, olive, salad or cooking"), score("olive oil", "Oil, olive, salad or cooking"), 0.0)
    }

    @Test fun `missing part of the query loses to having all of it`() {
        assertTrue(score("buckwheat cooked", "Buckwheat groats, roasted, cooked") > score("buckwheat cooked", "Buckwheat"))
    }

    @Test fun `a match on the cooking word alone is no match`() {
        assertEquals(0.0 to 0.0, Ranking.scoreMatch(Text.stemSet("oats cooked"), Ranking.analyse("Guava sauce, cooked")))
    }

    @Test fun `processed, branded and unusual variants lose`() {
        assertTrue(score("tomato", "Tomatoes, red, ripe, raw") > score("tomato", "Tomato powder"))
        assertTrue(score("rice brown cooked", "Rice, brown, long-grain, cooked") > score("rice brown cooked", "Rice, brown, parboiled, cooked, UNCLE BENS"))
        assertTrue(score("milk", "Milk, whole, 3.25% milkfat") > score("milk", "Milk, sheep, fluid"))
        assertTrue(score("sour cream", "Cream, sour, cultured") > score("sour cream", "Sour cream, reduced fat"))
    }

    @Test fun `synonyms bridge everyday words and usda names`() {
        assertEquals("oats cooked", Ranking.expandQuery("Oatmeal cooked"))
        assertEquals("pork cured bacon", Ranking.expandQuery("bacon"))
        assertEquals("kiwifruit", Ranking.expandQuery("Kiwi"))
        assertEquals("frankfurter meat", Ranking.expandQuery("hot dog"))
        assertEquals("kiwis", Ranking.expandQuery("kiwis"))
        assertEquals("baconator", Ranking.expandQuery("baconator"))
        assertEquals("chicken breast cooked", Ranking.expandQuery("  Chicken, Breast  (cooked)! "))
    }
}
