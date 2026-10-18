package dev.dietapp.data.local

import dev.dietapp.data.domain.DishPart
import dev.dietapp.data.domain.FoodChoice
import dev.dietapp.data.domain.Per100
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Food options of a proposal note, stored as JSON in `notes.payload`. */
object ProposalCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(choices: List<FoodChoice>): String = json.encodeToString(
        ListSerializer(Stored.serializer()),
        choices.map {
            Stored(
                it.name, it.per100.kcal, it.per100.protein, it.per100.fat, it.per100.carbs, it.source, it.url, it.note, it.estimated,
                it.parts.map { p -> StoredPart(p.name, p.grams, p.per100.kcal, p.per100.protein, p.per100.fat, p.per100.carbs) },
            )
        },
    )

    fun decode(payload: String?): List<FoodChoice> {
        if (payload.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Stored.serializer()), payload) }.getOrDefault(emptyList())
            .map {
                FoodChoice(
                    it.name, Per100(it.kcal, it.protein, it.fat, it.carbs), it.source, it.url, it.note, it.estimated,
                    it.parts.map { p -> DishPart(p.name, p.grams, Per100(p.kcal, p.protein, p.fat, p.carbs)) },
                )
            }
    }

    @Serializable
    private data class Stored(
        val name: String,
        val kcal: Double,
        val protein: Double,
        val fat: Double,
        val carbs: Double,
        val source: String? = null,
        val url: String? = null,
        val note: String? = null,
        val estimated: Boolean = false,
        val parts: List<StoredPart> = emptyList(),
    )

    @Serializable
    private data class StoredPart(val name: String, val grams: Double, val kcal: Double, val protein: Double, val fat: Double, val carbs: Double)
}
