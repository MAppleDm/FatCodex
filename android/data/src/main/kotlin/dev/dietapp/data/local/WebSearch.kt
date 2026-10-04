package dev.dietapp.data.local

import dev.dietapp.data.local.parse.ModelTrace
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class WebResult(val title: String, val url: String, val snippet: String)

data class PageText(val url: String, val title: String, val text: String)

/**
 * The model's way onto the web, for foods no base has ("сосиски Ремит рубленые"): a DuckDuckGo search (no key, no
 * account) and the readable text of a page, cut down to the part about nutrition. Only the search query and the
 * pages the model picks leave the phone; nothing about the user does.
 */
class WebSearch(
    http: OkHttpClient,
    private val trace: ModelTrace = ModelTrace.None,
    private val searchUrl: String = "https://html.duckduckgo.com/html/",
) {
    /** A slow site must not hold the whole message: a page gets a few seconds, then the model goes on without it. */
    private val http = http.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, limit: Int = 6): List<WebResult> {
        val url = searchUrl + "?q=" + URLEncoder.encode(query, "UTF-8")
        val html = fetch(url) ?: return emptyList()
        val links = RESULT.findAll(html).toList()
        val snippets = SNIPPET.findAll(html).map { text(it.groupValues[1]) }.toList()
        return links.mapIndexedNotNull { i, m ->
            val target = target(m.groupValues[1]) ?: return@mapIndexedNotNull null
            WebResult(text(m.groupValues[2]), target, snippets.getOrElse(i) { "" })
        }.distinctBy { it.url }.take(limit).also { trace.log("web", "search \"$query\": ${it.size} results") }
    }

    /** The page as plain text: its title and the part around the first mention of calories (or the start). */
    suspend fun page(url: String, max: Int = 4000): PageText? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val html = fetch(url) ?: return null
        val title = TITLE.find(html)?.groupValues?.get(1)?.let(::text).orEmpty()
        val body = text(html.replace(BLOCKS, " "))
        val at = NUTRITION.find(body)?.range?.first ?: 0
        val start = (at - 300).coerceAtLeast(0)
        return PageText(url, title, body.substring(start, (start + max).coerceAtMost(body.length)))
            .also { trace.log("web", "page $url: ${body.length} chars of text, nutrition at ${if (at > 0) at else "—"}") }
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", AGENT).header("Accept-Language", "ru,en;q=0.8").build()
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body ?: return@use null
                if (!response.isSuccessful) {
                    trace.error("web", "GET $url: HTTP ${response.code}")
                    return@use null
                }
                val type = body.contentType()
                if (type != null && type.type != "text") {
                    trace.error("web", "GET $url: not text ($type)")
                    return@use null
                }
                val bytes = body.source().let { src ->
                    src.request(MAX_BYTES)
                    src.buffer.let { buf -> buf.readByteArray(minOf(buf.size, MAX_BYTES)) }
                }
                // many Russian sites still declare windows-1251, in the header or only in a <meta>
                val declared = type?.charset() ?: META_CHARSET.find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))
                    ?.groupValues?.get(1)?.let { runCatching { Charset.forName(it) }.getOrNull() }
                String(bytes, declared ?: Charsets.UTF_8)
            }
        } catch (e: IOException) {
            trace.error("web", "GET $url: ${e.javaClass.simpleName}: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            trace.error("web", "GET $url: ${e.message}")
            null
        }
    }

    /** DuckDuckGo links go through a redirect: `//duckduckgo.com/l/?uddg=<the real address>&rut=…`. Ads are skipped. */
    private fun target(href: String): String? {
        val raw = href.replace("&amp;", "&")
        if (raw.contains("duckduckgo.com/y.js")) return null
        val real = UDDG.find(raw)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: raw
        return real.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    companion object {
        private const val AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Mobile FatCodex"
        private const val MAX_BYTES = 1_500_000L
        private val RESULT = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val SNIPPET = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val UDDG = Regex("""[?&]uddg=([^&]+)""")
        private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val BLOCKS = Regex("""<(script|style|noscript|svg|head|template)\b[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val META_CHARSET = Regex("""charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)
        private val NUTRITION = Regex("""(?i)(калорийн|энергетическ|пищевая ценность|ккал|kcal)""")
        private val TAG = Regex("<[^>]+>")
        private val SPACE = Regex("\\s+")
        private val ENTITY = Regex("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);")
        private val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "laquo" to "«", "raquo" to "»",
            "mdash" to "—", "ndash" to "–", "hellip" to "…", "deg" to "°", "times" to "×", "middot" to "·",
        )

        /** Tags out, entities decoded, whitespace collapsed. */
        fun text(html: String): String = html.replace(TAG, " ").replace(ENTITY) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED[e.lowercase()] ?: m.value
            }
        }.replace(SPACE, " ").trim()
    }
}
