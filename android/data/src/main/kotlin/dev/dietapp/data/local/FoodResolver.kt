package dev.dietapp.data.local

import dev.dietapp.data.domain.Per100

/** Where the numbers for a food come from, and whether they are exact (the user's label) or typical (an estimate, shown with "~"). */
class Resolved(val per100: Per100, val foodName: String, val approximate: Boolean)

/**
 * Nutrition for a food name, from the user's own database and nothing else:
 *  1. the food the agent picked by id,
 *  2. the user's own food with that name (or one of its other names).
 * Nothing found means nothing returned: the resolver never makes a number up. A food the database lacks is looked up on
 * the web by the agent and added with the user's yes (see FoodTools).
 */
class FoodResolver(private val foods: FoodLookup) {
    suspend fun resolve(name: String, foodId: String? = null): Resolved? {
        foodId?.let { foods.byId(it) }?.let { return Resolved(it.per100, it.name, it.approximate) }
        return foods.mine(name)?.let { Resolved(it.per100, it.name, it.approximate) }
    }
}
