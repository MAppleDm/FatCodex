package dev.dietapp.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Hand-written mirror of /contracts/gateway.openapi.json. Keep the names and nullability in step with it:
 * DtoContractTest parses sample payloads of every response shape.
 */

@Serializable data class RequestCodeBody(val email: String)

@Serializable data class VerifyBody(val email: String, val code: String)

@Serializable
data class TokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class MeDto(val email: String, @SerialName("calorie_goal") val calorieGoal: Int? = null)

@Serializable data class GoalBody(@SerialName("calorie_goal") val calorieGoal: Int)

@Serializable data class Per100Dto(val kcal: Double, val protein: Double, val fat: Double, val carbs: Double)

@Serializable
data class EntryDto(
    val id: String,
    @SerialName("meal_id") val mealId: String? = null,
    val day: String,
    @SerialName("eaten_at") val eatenAt: String,
    val position: Int = 0,
    val name: String,
    val grams: Double,
    val kcal: Double? = null,
    val protein: Double? = null,
    val fat: Double? = null,
    val carbs: Double? = null,
    val per100: Per100Dto? = null,
    @SerialName("food_name") val foodName: String? = null,
    val status: String,
    val confidence: Double = 1.0,
    val source: String = "text",
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
data class PendingQuestionBody(
    val question: String,
    @SerialName("target_id") val targetId: String? = null,
)

@Serializable
data class MessageBody(
    @SerialName("client_id") val clientId: String,
    val text: String? = null,
    @SerialName("image_base64") val imageBase64: String? = null,
    @SerialName("image_mime") val imageMime: String = "image/jpeg",
    val day: String,
    @SerialName("eaten_at") val eatenAt: String,
    val source: String = "text",
    @SerialName("pending_question") val pendingQuestion: PendingQuestionBody? = null,
)

@Serializable
data class MessageResultDto(
    val entries: List<EntryDto> = emptyList(),
    @SerialName("removed_ids") val removedIds: List<String> = emptyList(),
    @SerialName("clarify_question") val clarifyQuestion: String? = null,
    @SerialName("clarify_target_id") val clarifyTargetId: String? = null,
)

@Serializable data class EntryPatchBody(val grams: Double? = null, val name: String? = null)

@Serializable data class WeightBody(val day: String, val kg: Double)

@Serializable
data class WeightDto(
    val id: String,
    val day: String,
    val kg: Double,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
data class SyncDto(
    @SerialName("server_time") val serverTime: String,
    @SerialName("next_since") val nextSince: String,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("calorie_goal") val calorieGoal: Int? = null,
    val entries: List<EntryDto> = emptyList(),
    val weights: List<WeightDto> = emptyList(),
)

@Serializable
data class SttDto(val text: String, val language: String = "", @SerialName("duration_s") val durationS: Double = 0.0)

@Serializable data class ErrorBody(val code: String, val message: String)

@Serializable data class ErrorEnvelope(val error: ErrorBody)
