package dev.dietapp.data.local

import dev.dietapp.data.domain.DayFacts
import dev.dietapp.data.domain.DishPart
import dev.dietapp.data.domain.Per100
import dev.dietapp.data.domain.Workout
import dev.dietapp.data.domain.WorkoutKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The ingredients of a dish as JSON, for the `parts` column of `foods` and `entries`. Null stands for "not a dish". */
object DishCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(parts: List<DishPart>): String? {
        if (parts.isEmpty()) return null
        return json.encodeToString(ListSerializer(Stored.serializer()), parts.map { Stored(it.name, it.grams, it.per100.kcal, it.per100.protein, it.per100.fat, it.per100.carbs) })
    }

    fun decode(text: String?): List<DishPart> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Stored.serializer()), text) }.getOrDefault(emptyList())
            .map { DishPart(it.name, it.grams, Per100(it.kcal, it.protein, it.fat, it.carbs)) }
    }

    @Serializable
    private data class Stored(val name: String, val grams: Double, val kcal: Double, val protein: Double, val fat: Double, val carbs: Double)
}

/** Workouts of a day as JSON, for the `workouts` column of `day_facts`. */
object WorkoutCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(workouts: List<Workout>): String? =
        if (workouts.isEmpty()) null else json.encodeToString(ListSerializer(Stored.serializer()), workouts.map { Stored(it.kind.name, it.minutes) })

    fun decode(text: String?): List<Workout> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Stored.serializer()), text) }.getOrDefault(emptyList())
            .map { Workout(WorkoutKind.entries.firstOrNull { k -> k.name == it.kind } ?: WorkoutKind.Other, it.minutes) }
    }

    @Serializable
    private data class Stored(val kind: String, val minutes: Int)
}
