package dev.dietapp.data.local.parse

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The system prompt and the function schema, the same files the ai-parser service uses (see assets/parse), plus
 * the local-mode addendum that tells the model about the food base tools.
 */
class PromptAssets(val systemPrompt: String, val toolSpec: JsonObject, val localAgent: String = "") {
    val toolName: String get() = toolSpec["function"]!!.jsonObject["name"]!!.let { (it as JsonPrimitive).content }

    /** The system message the agent gets: the shared prompt plus what the local mode adds. */
    val agentPrompt: String get() = systemPrompt + "\n\n" + localAgent

    /**
     * record_food with two more optional fields per item: the id of the food picked from the food base, and whether
     * the user should confirm it (the standard record mode records the rest at once).
     */
    val toolSpecWithFoodId: JsonObject by lazy {
        val fn = toolSpec["function"]!!.jsonObject
        val params = fn["parameters"]!!.jsonObject
        val itemsProp = params["properties"]!!.jsonObject["items"]!!.jsonObject
        val item = itemsProp["items"]!!.jsonObject
        val props = item["properties"]!!.jsonObject
        val withId = JsonObject(
            props + ("food_id" to buildJsonObject {
                putJsonArray("type") { add("string"); add("null") }
                put("description", "Id from search_foods or save_food ('my:…', 'ru:…', 'usda:…')")
            }) + ("needs_confirmation" to buildJsonObject {
                putJsonArray("type") { add("boolean"); add("null") }
                put(
                    "description",
                    "true when this food's values vary a lot between brands, recipes or sizes and you could not pin " +
                        "down which one (sausages, a home-made dish, a restaurant meal, a product you only guessed): " +
                        "the user confirms it. false for plain foods whose values hardly vary (boiled pasta, buckwheat, " +
                        "a banana, an egg, milk 2.5%) or a product matched exactly.",
                )
            }),
        )
        JsonObject(
            toolSpec + ("function" to JsonObject(
                fn + ("parameters" to JsonObject(
                    params + ("properties" to JsonObject(
                        params["properties"]!!.jsonObject + ("items" to JsonObject(
                            itemsProp + ("items" to JsonObject(item + ("properties" to withId))),
                        )),
                    )),
                )),
            )),
        )
    }

    companion object {
        fun load(open: (String) -> InputStream): PromptAssets {
            val prompt = open("parse/system_prompt.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val spec = open("parse/tool_spec.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val local = open("parse/local_agent.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            return PromptAssets(prompt, Json.parseToJsonElement(spec).jsonObject, local)
        }
    }
}

/**
 * A tool's answer: what goes back to the model, a line for the user (a change to their food base), a proposal
 * for the user to confirm, and a change to the food base that waits for the user's yes.
 */
class ToolOutcome(
    val result: JsonElement,
    val note: String? = null,
    val proposal: Proposal? = null,
    val change: BaseChange? = null,
)

/** What the model may do with the food base while it reads a message (local mode). */
interface AgentTools {
    /** The tools' function specs, besides record_food. */
    val specs: List<JsonObject>

    /** A new message is about to be read: forget what the previous one taught the tools (for example, a page that was read). */
    fun startRun() = Unit

    /** Runs one tool. Problems come back as a result the model can read (and fix), not as exceptions. */
    suspend fun call(name: String, args: JsonObject): ToolOutcome
}

/** Where the model client reports what it does: the diagnostics journal in the app, nowhere in most tests. */
interface ModelTrace {
    fun log(tag: String, message: String)
    fun error(tag: String, message: String, error: Throwable? = null)

    companion object {
        val None = object : ModelTrace {
            override fun log(tag: String, message: String) = Unit
            override fun error(tag: String, message: String, error: Throwable?) = Unit
        }
    }
}

/**
 * Why the model could not help. The caller decides whether the offline parser can take over. [detail] is short and
 * concrete ("HTTP 400: Model Not Exist", "SocketTimeoutException: timeout") and is shown to the user.
 */
sealed class ModelFailure(val detail: String, val retryable: Boolean, summary: String) : Exception("$summary: $detail") {
    /** A name that survives R8 (class names in a release build do not), for the journal. */
    val kind: String get() = when (this) {
        is Unavailable -> "unavailable"
        is Auth -> "auth"
        is Rejected -> "rejected"
        is BadOutput -> "bad output"
    }

    /** Offline, timed out, busy or down. */
    class Unavailable(detail: String) : ModelFailure(detail, retryable = true, "The model is unavailable")

    /** The key was refused, or there is no balance left. */
    class Auth(detail: String) : ModelFailure(detail, retryable = false, "The model refused the key")

    /** The request was refused as invalid. */
    class Rejected(detail: String) : ModelFailure(detail, retryable = false, "The model rejected the request")

    /** Answered, but with nothing usable. */
    class BadOutput(detail: String) : ModelFailure(detail, retryable = true, "The model's answer was unusable")
}

/**
 * DeepSeek called straight from the phone with the user's own key (local mode).
 *
 * Without [tools]: the same request as the ai-parser service (forced `record_food` call, thinking off, temperature 0,
 * same retries). With [tools] it is a small agent: it may search the food base, add or change the user's own foods
 * and reply, for at most [maxSteps] rounds, and must finish with `record_food` (forced on the last round).
 * Thinking stays off: DeepSeek does not allow a forced tool choice in thinking mode, and the tools matter more here.
 * Either way record_food only names foods and grams; the numbers come from the food base.
 */
class ModelParser(
    private val http: OkHttpClient,
    private val prompts: PromptAssets,
    private val keyProvider: () -> String?,
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-flash",
    private val retries: Int = 2,
    private val backoffMs: Long = 500,
    private val threshold: Double = 0.6,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val tools: AgentTools? = null,
    private val maxSteps: Int = 8,
    private val trace: ModelTrace = ModelTrace.None,
) : MessageParser {

    override suspend fun parse(request: ParseRequest): ParseResult {
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: throw ModelFailure.Auth("no key set")
        if (tools != null) return agent(request, key, tools)
        val body = requestBody(request)
        var last: ModelFailure? = null
        for (attempt in 0..retries) {
            try {
                val answer = call(key, body, round = 0, attempt = attempt, newFrom = 1)
                return Normalizer.fromRaw(extractItems(answer), request.context.entries, threshold)
            } catch (e: BadModelOutput) {
                trace.error("model", "unusable answer: ${e.message}")
                last = ModelFailure.BadOutput(e.message ?: "")
            } catch (e: ModelFailure) {
                if (!e.retryable) throw e
                last = e
            }
            if (attempt < retries) pause(backoffMs shl attempt)
        }
        throw last!!
    }

    // ---------- the agent ----------

    private suspend fun agent(request: ParseRequest, key: String, tools: AgentTools): ParseResult {
        tools.startRun()
        val messages = mutableListOf(
            buildJsonObject { put("role", "system"); put("content", prompts.agentPrompt) },
            userMessage(request),
        )
        val notes = ArrayList<String>()
        val proposals = LinkedHashMap<String, Proposal>() // one per item: a later proposal for the same item replaces it
        val changes = ArrayList<BaseChange>()
        var newFrom = 1 // the messages this round adds to the conversation: only those are written to the journal
        for (step in 0 until maxSteps) {
            val answer = callWithRetries(key, agentBody(messages, tools, forceRecord = step == maxSteps - 1), step, newFrom)
            newFrom = messages.size
            val message = (answer["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
                ?: throw ModelFailure.BadOutput("no choices")
            val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            if (calls.isEmpty()) return proseAnswer(answer, message, request, notes, changes)

            messages += buildJsonObject {
                put("role", "assistant")
                put("content", message["content"] ?: JsonNull)
                put("tool_calls", JsonArray(calls))
            }
            var recorded: JsonArray? = null
            for (call in calls) {
                val id = (call["id"] as? JsonPrimitive)?.content ?: ""
                val fn = call["function"] as? JsonObject
                val name = (fn?.get("name") as? JsonPrimitive)?.content ?: ""
                val args = (fn?.get("arguments") as? JsonPrimitive)?.content?.let { loose(it) as? JsonObject } ?: JsonObject(emptyMap())
                val result: JsonElement = if (name == prompts.toolName) {
                    recorded = (args["items"] as? JsonArray) ?: JsonArray(emptyList())
                    buildJsonObject { put("ok", true) }
                } else {
                    val outcome = tools.call(name, args)
                    outcome.note?.let(notes::add)
                    outcome.proposal?.let { proposals[it.forItem.lowercase()] = it }
                    outcome.change?.let(changes::add)
                    outcome.result
                }
                messages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", id)
                    put("content", COMPACT.encodeToString(JsonElement.serializer(), result))
                }
            }
            recorded?.let { items ->
                return try {
                    Normalizer.fromRaw(items, request.context.entries, threshold)
                        .copy(notes = notes, proposals = proposals.values.toList(), changes = changes).also { r ->
                        trace.log("model", "done in ${step + 1} round(s): " + r.items.joinToString("; ") {
                            "${it.action.name.lowercase()} ${it.name} ${it.grams} g" + (it.foodId?.let { id -> " [$id]" } ?: "")
                        }.ifEmpty { "no items" } + (r.clarifyQuestion?.let { "; question: $it" } ?: "") +
                            r.proposals.joinToString("") { p -> "; proposal for ${p.forItem}: ${p.choices.size} option(s)" } +
                            r.changes.joinToString("") { c -> "; waits for the user: ${c.question.lineSequence().first()}" })
                    }
                } catch (e: BadModelOutput) {
                    trace.error("model", "record_food arguments unusable: ${e.message}: ${short(items.toString(), 600)}")
                    throw ModelFailure.BadOutput(e.message ?: "")
                }
            }
        }
        throw ModelFailure.BadOutput("no record_food after $maxSteps rounds")
    }

    /** No tool call: items written as JSON in prose, or just a sentence for the user. */
    private fun proseAnswer(
        answer: JsonObject, message: JsonObject, request: ParseRequest, notes: List<String>, changes: List<BaseChange>,
    ): ParseResult {
        try {
            return Normalizer.fromRaw(extractItems(answer), request.context.entries, threshold).copy(notes = notes, changes = changes)
        } catch (e: BadModelOutput) {
            val text = (message["content"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()
            if (text.isEmpty() && notes.isEmpty() && changes.isEmpty()) throw ModelFailure.BadOutput(e.message ?: "")
            return ParseResult(emptyList(), null, notes + listOfNotNull(text.take(500).ifEmpty { null }), changes = changes)
        }
    }

    internal fun agentBody(messages: List<JsonObject>, tools: AgentTools, forceRecord: Boolean): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", JsonArray(messages))
        put("tools", buildJsonArray {
            add(prompts.toolSpecWithFoodId)
            tools.specs.forEach { add(it) }
        })
        if (forceRecord) {
            putJsonObject("tool_choice") {
                put("type", "function")
                putJsonObject("function") { put("name", prompts.toolName) }
            }
        } else {
            put("tool_choice", "auto")
        }
        put("temperature", 0)
        put("max_tokens", 1024)
        putJsonObject("thinking") { put("type", "disabled") }
    }

    private suspend fun callWithRetries(key: String, body: JsonObject, round: Int, newFrom: Int): JsonObject {
        var last: ModelFailure? = null
        for (attempt in 0..retries) {
            try {
                return call(key, body, round, attempt, newFrom)
            } catch (e: ModelFailure) {
                if (!e.retryable) throw e
                last = e
            }
            if (attempt < retries) pause(backoffMs shl attempt)
        }
        throw last!!
    }

    // ---------- request ----------

    private fun userMessage(req: ParseRequest): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "context: ${contextJson(req.context)}\nmessage: ${req.text ?: "(photo only)"}")
            })
            if (req.imageBase64 != null) {
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:${req.imageMime};base64,${req.imageBase64}") }
                })
            }
        })
    }

    internal fun requestBody(req: ParseRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", prompts.systemPrompt) })
            add(userMessage(req))
        })
        put("tools", buildJsonArray { add(prompts.toolSpec) })
        putJsonObject("tool_choice") {
            put("type", "function")
            putJsonObject("function") { put("name", prompts.toolName) }
        }
        put("temperature", 0)
        put("max_tokens", 1024)
        putJsonObject("thinking") { put("type", "disabled") }
    }

    /** The same compact JSON the service builds, so the model sees identical input in both modes. */
    internal fun contextJson(ctx: ParseContext): String {
        val obj = buildJsonObject {
            ctx.localTime?.let { put("local_time", it) }
            ctx.language?.let { put("language", it) }
            if (ctx.entries.isNotEmpty()) {
                putJsonArray("entries") {
                    ctx.entries.forEach { e -> add(buildJsonObject { put("id", e.id); put("name", e.name); put("grams", e.grams) }) }
                }
            }
            if (ctx.frequent.isNotEmpty()) {
                putJsonArray("frequent") {
                    ctx.frequent.forEach { m ->
                        add(buildJsonObject {
                            put("label", m.label)
                            putJsonArray("items") {
                                m.items.forEach { i ->
                                    add(buildJsonObject {
                                        put("name", i.name)
                                        i.queryEn?.let { put("query_en", it) }
                                        put("grams", i.grams)
                                    })
                                }
                            }
                        })
                    }
                }
            }
            ctx.pending?.let { p ->
                putJsonObject("pending_question") {
                    put("question", p.question)
                    put("target_id", p.targetId)
                }
            }
        }
        return COMPACT.encodeToString(JsonObject.serializer(), obj)
    }

    // ---------- transport ----------

    private suspend fun call(key: String, body: JsonObject, round: Int, attempt: Int, newFrom: Int): JsonObject {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val payload = body.toString()
        trace.log("model", "→ POST $url · round ${round + 1}, attempt ${attempt + 1} · ${payload.length} chars\n" + describeRequest(body, newFrom))
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key").post(payload.toRequestBody(JSON)).build()
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            val why = "${e.javaClass.simpleName}: ${e.message}"
            trace.error("model", "✕ no answer after ${ms()} ms: $why")
            throw ModelFailure.Unavailable(why)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            val code = it.code
            if (code in 200..299) {
                val json = try {
                    COMPACT.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    trace.error("model", "← HTTP $code in ${ms()} ms, but not JSON: ${short(text, 1500)}")
                    throw ModelFailure.BadOutput("HTTP $code, not JSON")
                }
                trace.log("model", "← HTTP $code in ${ms()} ms\n" + describeAnswer(json))
                return json
            }
            trace.error("model", "← HTTP $code in ${ms()} ms: ${short(text, 2000)}")
            val why = "HTTP $code: ${serverReason(text)}"
            when (code) {
                401, 402, 403 -> throw ModelFailure.Auth(why)
                408, 429 -> throw ModelFailure.Unavailable(why)
                in 500..599 -> throw ModelFailure.Unavailable(why)
                else -> throw ModelFailure.Rejected(why)
            }
        }
    }

    // ---------- the journal ----------

    /** The request without what is long and useless to read: the system prompt, the tool schemas, image bytes. */
    private fun describeRequest(body: JsonObject, newFrom: Int): String = buildString {
        append("model=").append((body["model"] as? JsonPrimitive)?.content)
        append(" tool_choice=").append(body["tool_choice"]?.let { if (it is JsonPrimitive) it.content else "record_food (forced)" } ?: "none")
        val tools = (body["tools"] as? JsonArray).orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("function") as? JsonObject)?.get("name")?.let { n -> (n as JsonPrimitive).content }
        }
        append(" tools=").append(tools)
        val messages = (body["messages"] as? JsonArray).orEmpty()
        append(" messages=").append(messages.size)
        messages.drop(newFrom.coerceAtMost(messages.size)).forEach { m -> append("\n  ").append(describeMessage(m as JsonObject)) }
    }

    private fun describeMessage(m: JsonObject): String {
        val role = (m["role"] as? JsonPrimitive)?.content
        val content = when (val c = m["content"]) {
            is JsonPrimitive -> if (c is JsonNull) "" else c.content
            is JsonArray -> c.joinToString(" ") { part ->
                val p = part as? JsonObject
                when ((p?.get("type") as? JsonPrimitive)?.content) {
                    "text" -> (p["text"] as? JsonPrimitive)?.content.orEmpty()
                    "image_url" -> "<image, ${((p["image_url"] as? JsonObject)?.get("url") as? JsonPrimitive)?.content?.length ?: 0} chars of base64>"
                    else -> part.toString()
                }
            }
            else -> ""
        }
        return when (role) {
            "assistant" -> "assistant: " + toolCalls(m).ifEmpty { short(content, 400) }
            "tool" -> "tool[${(m["tool_call_id"] as? JsonPrimitive)?.content}]: ${short(content, 600)}"
            else -> "$role: ${short(content, 1200)}"
        }
    }

    private fun describeAnswer(json: JsonObject): String {
        val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return "no choices: ${short(json.toString(), 1000)}"
        val message = choice["message"] as? JsonObject
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.content
        val usage = (json["usage"] as? JsonObject)?.let { u ->
            " tokens=${(u["prompt_tokens"] as? JsonPrimitive)?.content}+${(u["completion_tokens"] as? JsonPrimitive)?.content}"
        }.orEmpty()
        val content = (message?.get("content") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        return "  finish=$finish$usage" +
            (message?.let { toolCalls(it) }?.takeIf { it.isNotEmpty() }?.let { "\n  calls: $it" } ?: "") +
            (content?.takeIf { it.isNotBlank() }?.let { "\n  content: ${short(it, 600)}" } ?: "")
    }

    private fun toolCalls(m: JsonObject): String = (m["tool_calls"] as? JsonArray).orEmpty().joinToString("; ") { c ->
        val fn = (c as? JsonObject)?.get("function") as? JsonObject
        "${(fn?.get("name") as? JsonPrimitive)?.content}(${short((fn?.get("arguments") as? JsonPrimitive)?.content.orEmpty(), 500)})"
    }

    private fun short(text: String, max: Int) = if (text.length <= max) text else text.take(max) + "… (${text.length} chars)"

    /** DeepSeek's own explanation: `{"error": {"message": …}}`, or the body itself when it is plain text. */
    private fun serverReason(text: String): String {
        val fromJson = runCatching {
            val error = COMPACT.parseToJsonElement(text).jsonObject["error"]
            when (error) {
                is JsonObject -> (error["message"] as? JsonPrimitive)?.content
                is JsonPrimitive -> error.content
                else -> null
            }
        }.getOrNull()
        return (fromJson ?: text).trim().replace(Regex("\\s+"), " ").take(160).ifEmpty { "no details" }
    }

    // ---------- answer ----------

    /** The forced tool call's arguments, or JSON found in plain content (the model sometimes answers in prose). */
    internal fun extractItems(answer: JsonObject): JsonArray {
        val message = (answer["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
            ?: throw BadModelOutput("no choices")
        val candidates = ArrayList<String>()
        val calls = message["tool_calls"] as? JsonArray
        calls?.firstOrNull()?.jsonObject?.get("function")?.jsonObject?.get("arguments")?.let { (it as? JsonPrimitive)?.content }?.let(candidates::add)
        (message["content"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { c ->
            candidates += FENCE.find(c)?.groupValues?.get(1) ?: c
        }
        for (raw in candidates) {
            when (val data = loose(raw)) {
                is JsonArray -> return data
                is JsonObject -> (data["items"] as? JsonArray)?.let { return it }
                else -> Unit
            }
        }
        throw BadModelOutput("no items payload")
    }

    private fun loose(raw: String): JsonElement? {
        runCatching { return COMPACT.parseToJsonElement(raw) }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start in 0 until end) runCatching { return COMPACT.parseToJsonElement(raw.substring(start, end + 1)) }
        return null
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val COMPACT = Json { explicitNulls = true }
        val FENCE = Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWith(Result.failure(e)) }
        override fun onResponse(call: Call, response: Response) { cont.resumeWith(Result.success(response)) }
    })
}
