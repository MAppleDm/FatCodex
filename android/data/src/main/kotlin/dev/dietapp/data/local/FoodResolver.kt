package dev.dietapp.data.local

import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.food.CatalogFood
import dev.dietapp.data.local.food.FoodCatalog
import dev.dietapp.data.local.parse.Lexicon
import dev.dietapp.data.local.parse.Preset

/** Where the numbers for a food come from, and whether they are exact (USDA row) or typical (table of Russian dishes). */
class Resolved(val per100: Per100, val foodName: String, val approximate: Boolean)

fun interface CatalogSource {
    /** The loaded catalog. May block while the asset is read the first time, so call it off the main thread. */
    fun get(): FoodCatalog
}

/**
 * Nutrition for a food name, from the best source available:
 *  1. numbers already known (copied from an earlier entry),
 *  2. the food the model picked by id from the food base,
 *  3. the user's own food with that name,
 *  4. the dictionary's own table value,
 *  5. the USDA catalog, searched with the model's English query and the user's own words,
 *  6. the dictionary's USDA query for that Russian name (when the model's query found nothing).
 * Nothing found means nothing returned: the resolver never makes a number up. (The model may add a food with
 * typical values to the user's base; such foods are marked estimated and shown with "~".)
 */
class FoodResolver(
    private val catalog: CatalogSource,
    private val lexicon: Lexicon,
    private val foods: FoodLookup? = null,
    private val minCoverage: Double = 0.75,
) {
    suspend fun resolve(name: String, queryEn: String?, preset: Preset? = null, foodId: String? = null): Resolved? {
        if (preset != null) return Resolved(preset.per100, preset.foodName, preset.approximate)
        foodId?.let { foods?.byId(it) }?.let { return Resolved(it.per100, it.name, it.approximate) }
        foods?.mine(name)?.let { return Resolved(it.per100, it.name, it.approximate) }

        val entry = lexicon.find(name)
        entry?.per100?.let { return Resolved(it, entry.name, approximate = true) }

        search(listOf(queryEn, name))?.let { return it }
        return entry?.query?.let { search(listOf(it)) }
    }

    private fun search(queries: List<String?>): Resolved? {
        val best = catalog.get().search(queries, limit = 1).firstOrNull() ?: return null
        return if (best.coverage >= minCoverage) fromFood(best.food) else null
    }

    private fun fromFood(f: CatalogFood) = Resolved(Per100(f.kcal, f.protein, f.fat, f.carbs), f.name, approximate = false)
}
