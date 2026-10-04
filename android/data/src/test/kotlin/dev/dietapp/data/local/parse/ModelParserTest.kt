package dev.dietapp.data.local.parse

import dev.dietapp.data.local.TestFiles
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ModelParserTest {
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()
    private val prompts = PromptAssets.load { name ->
        FileInputStream(TestFiles.repoFile("android/data/src/main/assets/$name"))
    }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun parser(key: String? = "sk-test-key-123", retries: Int = 2) = ModelParser(
        http = OkHttpClient.Builder().build(),
        prompts = prompts,
        keyProvider = { key },
        baseUrl = server.url("/").toString(),
        retries = retries,
        pause = { sleeps += it },
    )

    private fun toolCall(items: String) =
        """{"choices": [{"message": {"role": "assistant", "content": null, "tool_calls": [{"id": "c1", "type": "function",
            "function": {"name": "record_food", "arguments": ${Json.encodeToString(kotlinx.serialization.serializer<String>(), """{"items": $items}""")}}}]}}]}"""

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun item(name: String = "гречка", grams: Int = 200, conf: Double = 0.9) =
        """{"action": "add", "name": "$name", "query_en": "Buckwheat Groats, Cooked", "grams": $grams, "confidence": $conf}"""

    private fun parse(text: String? = "гречка 200 г", ctx: ParseContext = ParseContext(), image: String? = null, p: ModelParser = parser()) =
        runBlocking { p.parse(ParseRequest(text, imageBase64 = image, context = ctx)) }

    // ---------- what is sent ----------

    @Test fun `the request is the one the service sends`() {
        enqueue(200, toolCall("[${item()}]"))
        val result = parse()
        assertEquals(listOf("гречка"), result.items.map { it.name })
        assertEquals("buckwheat groats, cooked", result.items.single().queryEn)

        val req = server.takeRequest()
        assertEquals("/chat/completions", req.path)
        assertEquals("Bearer sk-test-key-123", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals(0.0, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("record_food", body["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(prompts.toolSpec, body["tools"]!!.jsonArray.single())
        val messages = body["messages"]!!.jsonArray
        assertEquals(prompts.systemPrompt, messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val user = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("context: {}\nmessage: гречка 200 г", user.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun `a photo is sent as a data url, and a photo alone says so`() {
        enqueue(200, toolCall("[${item()}]"))
        parse(text = null, image = "aGVsbG8=")
        val user = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonArray
        assertEquals("context: {}\nmessage: (photo only)", user[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,aGVsbG8=", user[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun `the context is byte for byte what the service builds`() {
        // expected strings come from ai_parser.prompts._context_json on the same input
        val full = ParseContext(
            entries = listOf(ContextEntry("e1", "сливочное масло", 20.0), ContextEntry("e2", "хлеб", 60.5)),
            frequent = listOf(FrequentMeal("овсянка, банан (утром)", listOf(FrequentItem("овсянка", "oats, cooked", 250.0), FrequentItem("банан", null, 120.0)))),
            pending = PendingQuestion("Какой суп?", "e1"),
            localTime = "08:15",
        )
        assertEquals(
            """{"local_time":"08:15","entries":[{"id":"e1","name":"сливочное масло","grams":20.0},{"id":"e2","name":"хлеб","grams":60.5}],""" +
                """"frequent":[{"label":"овсянка, банан (утром)","items":[{"name":"овсянка","query_en":"oats, cooked","grams":250.0},""" +
                """{"name":"банан","grams":120.0}]}],"pending_question":{"question":"Какой суп?","target_id":"e1"}}""",
            parser().contextJson(full),
        )
        assertEquals("""{"pending_question":{"question":"Что это?","target_id":null}}""",
            parser().contextJson(ParseContext(pending = PendingQuestion("Что это?", null))))
        assertEquals("{}", parser().contextJson(ParseContext()))
    }

    @Test fun `the bundled prompt files are the service's own`() {
        val service = { name: String -> TestFiles.repoFile("services/ai-parser/ai_parser/$name").readBytes() }
        val bundled = { name: String -> TestFiles.repoFile("android/data/src/main/assets/parse/$name").readBytes() }
        assertTrue(service("system_prompt.txt").contentEquals(bundled("system_prompt.txt")))
        assertTrue(service("tool_spec.json").contentEquals(bundled("tool_spec.json")))
        assertEquals("record_food", prompts.toolName)
        assertTrue(prompts.systemPrompt.contains("Never estimate or mention calories"))
    }

    // ---------- what comes back ----------

    @Test fun `plain content with json is accepted when there is no tool call`() {
        val content = "Sure!\\n```json\\n{\\\"items\\\":[{\\\"action\\\":\\\"add\\\",\\\"name\\\":\\\"яблоко\\\",\\\"grams\\\":150,\\\"confidence\\\":0.9}]}\\n```"
        enqueue(200, """{"choices": [{"message": {"role": "assistant", "content": "$content"}}]}""")
        assertEquals(listOf("яблоко"), parse().items.map { it.name })
    }

    @Test fun `a corrected entry comes back as an update`() {
        val ctx = ParseContext(entries = listOf(ContextEntry("e1", "сливочное масло", 20.0)))
        enqueue(200, toolCall("""[{"action": "update", "target_id": "e1", "name": "сливочное масло", "grams": 10, "confidence": 0.65}]"""))
        val r = parse("масла было меньше", ctx)
        assertEquals(Action.Update, r.items.single().action)
        assertEquals("e1", r.items.single().targetId)
    }

    @Test fun `one question for the least sure item`() {
        enqueue(200, toolCall("""[{"action": "add", "name": "суп", "grams": 300, "confidence": 0.4, "clarify_question": "Какой суп?"}]"""))
        assertEquals("Какой суп?", parse("суп").clarifyQuestion)
    }

    // ---------- failures ----------

    @Test fun `a temporary failure is retried with growing pauses`() {
        enqueue(503, "{}")
        enqueue(429, "{}")
        enqueue(200, toolCall("[${item()}]"))
        assertEquals(1, parse().items.size)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(500L, 1000L), sleeps)
    }

    @Test fun `it gives up after two retries`() {
        repeat(3) { enqueue(500, "{}") }
        try {
            parse()
            fail("expected Unavailable")
        } catch (e: ModelFailure.Unavailable) {
            assertTrue(e.retryable)
        }
        assertEquals(3, server.requestCount)
        assertEquals(listOf(500L, 1000L), sleeps)
    }

    @Test fun `no connection is unavailable`() {
        server.shutdown()
        try {
            parse(p = parser(retries = 0))
            fail("expected Unavailable")
        } catch (_: ModelFailure.Unavailable) {
        }
    }

    @Test fun `a dropped connection is retried too`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        enqueue(200, toolCall("[${item()}]"))
        assertEquals(1, parse().items.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a refused key is not retried`() {
        for (code in listOf(401, 402, 403)) {
            enqueue(code, """{"error": {"message": "nope"}}""")
            try {
                parse()
                fail("expected Auth for $code")
            } catch (e: ModelFailure.Auth) {
                assertEquals(false, e.retryable)
            }
        }
        assertEquals(3, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun `an invalid request is not retried`() {
        enqueue(400, "{}")
        try {
            parse()
            fail("expected Rejected")
        } catch (_: ModelFailure.Rejected) {
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `no key means no request`() {
        try {
            parse(p = parser(key = null))
            fail("expected Auth")
        } catch (_: ModelFailure.Auth) {
        }
        assertEquals(0, server.requestCount)
        try {
            parse(p = parser(key = "  "))
            fail("expected Auth")
        } catch (_: ModelFailure.Auth) {
        }
    }

    @Test fun `unusable answers are retried, then reported`() {
        repeat(3) { enqueue(200, toolCall("[${item(grams = 0)}]")) }
        try {
            parse()
            fail("expected BadOutput")
        } catch (_: ModelFailure.BadOutput) {
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun `malformed json then a good answer succeeds`() {
        enqueue(200, """{"choices": [{"message": {"content": "no json here"}}]}""")
        enqueue(200, toolCall("[${item()}]"))
        assertEquals(1, parse().items.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun `an empty answer means there was no food`() {
        enqueue(200, toolCall("[]"))
        val r = parse("привет")
        assertTrue(r.items.isEmpty())
        assertNull(r.clarifyQuestion)
    }
}
