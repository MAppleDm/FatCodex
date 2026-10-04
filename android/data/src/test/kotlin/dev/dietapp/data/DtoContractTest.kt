package dev.dietapp.data

import dev.dietapp.data.net.EntryDto
import dev.dietapp.data.net.EntryPatchBody
import dev.dietapp.data.net.ErrorBody
import dev.dietapp.data.net.GoalBody
import dev.dietapp.data.net.MeDto
import dev.dietapp.data.net.MessageBody
import dev.dietapp.data.net.MessageResultDto
import dev.dietapp.data.net.PendingQuestionBody
import dev.dietapp.data.net.Per100Dto
import dev.dietapp.data.net.RequestCodeBody
import dev.dietapp.data.net.SttDto
import dev.dietapp.data.net.SyncDto
import dev.dietapp.data.net.TokenDto
import dev.dietapp.data.net.VerifyBody
import dev.dietapp.data.net.WeightBody
import dev.dietapp.data.net.WeightDto
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Retrofit DTOs are written by hand against /contracts/gateway.openapi.json. These tests read that
 * committed file, so a backend change that is not mirrored here fails the Android build.
 */
class DtoContractTest {
    private val contract: JsonObject = run {
        // the test working directory is the module directory: android/data
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "contracts/gateway.openapi.json") }
            .first { it.exists() }
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun schema(name: String): JsonObject = contract["components"]!!.jsonObject["schemas"]!!.jsonObject[name]!!.jsonObject
    private fun properties(name: String): Set<String> = schema(name)["properties"]!!.jsonObject.keys
    private fun required(name: String): Set<String> =
        (schema(name)["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

    private fun names(serializer: KSerializer<*>): Set<String> =
        (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }.toSet()

    private fun optionalNames(serializer: KSerializer<*>): Set<String> =
        (0 until serializer.descriptor.elementsCount).filter { serializer.descriptor.isElementOptional(it) }
            .map { serializer.descriptor.getElementName(it) }.toSet()

    /** Every field we read or send exists in the contract. */
    private fun assertKnown(dto: KSerializer<*>, schemaName: String) {
        val unknown = names(dto) - properties(schemaName)
        assertTrue("${dto.descriptor.serialName} has fields missing in $schemaName: $unknown", unknown.isEmpty())
    }

    /** Request bodies must also supply everything the server requires. */
    private fun assertSuppliesRequired(dto: KSerializer<*>, schemaName: String) {
        val missing = required(schemaName) - names(dto)
        assertTrue("${dto.descriptor.serialName} cannot send required fields of $schemaName: $missing", missing.isEmpty())
    }

    @Test fun `request bodies match the contract`() {
        mapOf(
            serializer<RequestCodeBody>() to "RequestCodeIn",
            serializer<VerifyBody>() to "VerifyCodeIn",
            serializer<GoalBody>() to "GoalIn",
            serializer<MessageBody>() to "MessageIn",
            serializer<PendingQuestionBody>() to "PendingQuestionIn",
            serializer<EntryPatchBody>() to "EntryPatchIn",
            serializer<WeightBody>() to "WeightIn",
        ).forEach { (dto, name) ->
            assertKnown(dto, name)
            assertSuppliesRequired(dto, name)
        }
    }

    @Test fun `response bodies only read fields the contract has`() {
        mapOf(
            serializer<TokenDto>() to "TokenOut",
            serializer<MeDto>() to "MeOut",
            serializer<Per100Dto>() to "Per100",
            serializer<EntryDto>() to "EntryOut",
            serializer<MessageResultDto>() to "MessageOut",
            serializer<WeightDto>() to "WeightOut",
            serializer<SyncDto>() to "SyncOut",
            serializer<SttDto>() to "SttOut",
            serializer<ErrorBody>() to "ErrorDetail",
        ).forEach { (dto, name) -> assertKnown(dto, name) }
    }

    @Test fun `fields the server always sends are not optional surprises on our side`() {
        // if the contract marks a field required, our DTO may still default it, but the field must exist
        listOf(serializer<EntryDto>() to "EntryOut", serializer<SyncDto>() to "SyncOut", serializer<WeightDto>() to "WeightOut")
            .forEach { (dto, name) ->
                val missing = required(name) - names(dto)
                assertTrue("$name required fields we ignore: $missing", missing.isEmpty())
            }
    }

    @Test fun `enums the UI switches on match the contract`() {
        val status = properties("EntryOut").let { schema("EntryOut")["properties"]!!.jsonObject["status"]!!.jsonObject }
        val wire = (status["enum"] ?: status["\$ref"]?.let { schema("EntryStatus") }?.get("enum"))!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(setOf("ok", "uncertain", "unmatched"), wire.toSet())
    }

    // ---------- wire format ----------

    private val json = dev.dietapp.data.di.DataProvidesModule.json()

    @Test fun `a full gateway entry parses`() {
        val dto = json.decodeFromString<EntryDto>(entryJson())
        assertEquals("гречка", dto.name)
        assertEquals(92.0, dto.per100!!.kcal, 0.0)
        assertEquals("Buckwheat groats, roasted, cooked", dto.foodName)
        assertFalse(dto.deleted)
    }

    @Test fun `an entry that was not found parses with null numbers`() {
        val dto = json.decodeFromString<EntryDto>(entryJson(kcal = null, status = "unmatched"))
        assertEquals(null, dto.kcal)
        assertEquals(null, dto.per100)
    }

    @Test fun `unknown fields from a newer server are ignored`() {
        val dto = json.decodeFromString<MeDto>("""{"email": "a@b.c", "calorie_goal": 1900, "something_new": [1, 2]}""")
        assertEquals(1900, dto.calorieGoal)
    }

    @Test fun `request bodies omit nulls but keep defaults`() {
        val body = MessageBody(clientId = "c", text = "x", day = "2026-09-30", eatenAt = "2026-09-30T08:15:00+03:00")
        val encoded = json.parseToJsonElement(json.encodeToString(MessageBody.serializer(), body)).jsonObject
        assertEquals(setOf("client_id", "text", "image_mime", "day", "eaten_at", "source"), encoded.keys)
        assertEquals("image/jpeg", encoded["image_mime"]!!.jsonPrimitive.content)
        assertEquals("""{"grams":100.0}""", json.encodeToString(EntryPatchBody.serializer(), EntryPatchBody(grams = 100.0)))
    }
}
