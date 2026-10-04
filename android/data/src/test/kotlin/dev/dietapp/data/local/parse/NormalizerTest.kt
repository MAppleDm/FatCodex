package dev.dietapp.data.local.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of the normalisation tests in services/ai-parser/tests/test_parser.py: same rules, same behaviour. */
class NormalizerTest {
    private val entries = listOf(ContextEntry("e1", "сливочное масло", 20.0), ContextEntry("e2", "хлеб", 60.0))

    private fun normalize(json: String, ctx: List<ContextEntry> = emptyList()): ParseResult {
        val items = Json.parseToJsonElement("""{"items": $json}""").jsonObject["items"]!!.jsonArray
        return Normalizer.fromRaw(items, ctx, 0.6)
    }

    private fun item(name: String = "гречка", grams: String = "200", conf: String = "0.9", extra: String = "") =
        """{"action": "add", "name": "$name", "query_en": "buckwheat cooked", "grams": $grams, "confidence": $conf$extra}"""

    @Test fun `happy path normalises names, query and numbers`() {
        val r = normalize(
            """[
            {"action": "add", "name": "  куриная   грудка ", "query_en": "Chicken Breast Cooked", "grams": "150", "confidence": 0.85},
            ${item()}]""",
        )
        assertEquals(listOf("куриная грудка", "гречка"), r.items.map { it.name })
        assertEquals("chicken breast cooked", r.items[0].queryEn)
        assertEquals(150.0, r.items[0].grams, 0.0)
        assertEquals(Action.Add, r.items[0].action)
        assertNull(r.items[0].targetId)
        assertNull(r.clarifyQuestion)
    }

    @Test fun `no food is an empty answer, not an error`() {
        val r = normalize("[]")
        assertTrue(r.items.isEmpty())
        assertNull(r.clarifyQuestion)
    }

    @Test fun `invalid items are dropped`() {
        val bad = listOf(
            item(grams = "0"), item(grams = "-10"), item(grams = "5001"), item(grams = "\"много\""), item(grams = "null"),
            item(name = ""), """{"action": "eat", "name": "x", "grams": 10, "confidence": 1}""", "\"just a string\"", "42",
        )
        for (b in bad) {
            val r = normalize("[$b, ${item("рис", "150")}]")
            assertEquals(b, listOf("рис"), r.items.map { it.name })
        }
    }

    @Test fun `only invalid items is a bad answer`() {
        try {
            normalize("[${item(grams = "0")}]")
            fail("expected BadModelOutput")
        } catch (_: BadModelOutput) {
        }
    }

    @Test fun `missing confidence means unsure, and confidence is clamped`() {
        val r = normalize("""[{"action": "add", "name": "суп", "grams": 300}]""")
        assertEquals(0.5, r.items.single().confidence, 0.0)
        assertTrue(r.clarifyQuestion != null)
        val c = normalize("[${item("a", conf = "7")}, ${item("b", conf = "-3")}]")
        assertEquals(listOf(1.0, 0.0), c.items.map { it.confidence })
    }

    @Test fun `exactly one question, for the least sure item`() {
        val r = normalize(
            """[
            ${item("суп", "300", "0.5", ", \"clarify_question\": \"Какой суп?\"")},
            ${item("хлеб", "50", "0.4", ", \"clarify_question\": \"Сколько ломтиков?\"")},
            ${item("чай", "250", "0.95")}]""",
        )
        assertEquals("Сколько ломтиков?", r.clarifyQuestion)
        assertEquals(listOf("хлеб"), r.items.filter { it.clarifyQuestion != null }.map { it.name })
    }

    @Test fun `the threshold itself is not low, just below is`() {
        val at = normalize("[${item("суп", "300", "0.6", ", \"clarify_question\": \"Какой суп?\"")}]")
        assertNull(at.clarifyQuestion)
        assertNull(at.items.single().clarifyQuestion) // a stray model question is stripped
        val below = normalize("[${item("суп", "300", "0.59", ", \"clarify_question\": \"Какой суп?\"")}]")
        assertEquals("Какой суп?", below.clarifyQuestion)
    }

    @Test fun `a fallback question when the model forgot to write one`() {
        val r = normalize("[${item("суп", "300", "0.3")}]")
        assertEquals("Уточни, пожалуйста: сколько граммов «суп»?", r.clarifyQuestion)
        assertEquals(r.clarifyQuestion, r.items.single().clarifyQuestion)
    }

    @Test fun `update targets an existing entry`() {
        val r = normalize("""[{"action": "update", "target_id": "e1", "name": "сливочное масло", "grams": 10, "confidence": 0.65}]""", entries)
        val i = r.items.single()
        assertEquals(Action.Update, i.action)
        assertEquals("e1", i.targetId)
        assertEquals(10.0, i.grams, 0.0)
        assertNull(r.clarifyQuestion)
    }

    @Test fun `update takes the entry's name when the model leaves it out`() {
        val r = normalize("""[{"action": "update", "target_id": "e2", "grams": 30, "confidence": 0.9}]""", entries)
        assertEquals("хлеб", r.items.single().name)
    }

    @Test fun `remove has zero grams`() {
        val r = normalize("""[{"action": "remove", "target_id": "e2", "name": "хлеб", "grams": 999, "confidence": 0.9}]""", entries)
        assertEquals(Action.Remove, r.items.single().action)
        assertEquals(0.0, r.items.single().grams, 0.0)
    }

    @Test fun `a hallucinated or foreign target is dropped and the user is asked which entry`() {
        val r = normalize("""[{"action": "update", "target_id": "ghost", "name": "х", "grams": 10, "confidence": 0.9}]""", entries)
        assertTrue(r.items.isEmpty())
        assertEquals(Normalizer.UNCLEAR_TARGET_QUESTION, r.clarifyQuestion)
        val none = normalize("""[{"action": "update", "target_id": "e1", "name": "масло", "grams": 10, "confidence": 0.9}, ${item("рис", "100")}]""")
        assertEquals(listOf("рис"), none.items.map { it.name })
        assertEquals(Normalizer.UNCLEAR_TARGET_QUESTION, none.clarifyQuestion)
    }

    @Test fun `add ignores a stray target id`() {
        assertNull(normalize("[${item(extra = ", \"target_id\": \"e1\"")}]", entries).items.single().targetId)
    }

    @Test fun `numbers given as text with a decimal comma are read`() {
        assertEquals(12.5, normalize("[${item(grams = "\"12,5\"")}]").items.single().grams, 0.0)
    }

    @Test fun `booleans are not numbers`() {
        try {
            normalize("[${item(grams = "true")}]")
            fail("expected BadModelOutput")
        } catch (_: BadModelOutput) {
        }
    }
}
