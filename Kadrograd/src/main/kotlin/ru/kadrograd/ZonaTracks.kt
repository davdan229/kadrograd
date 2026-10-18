package ru.kadrograd

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.math.roundToLong

/** Native DASH with named audio tracks. Only literal data is parsed; remote JavaScript is not executed. */
internal class ZonaTracks(private val origins: () -> List<String>, private val ua: String) {
    private val headers get() = mapOf("User-Agent" to ua, "Cache-Control" to "no-cache")
    private val json = mapper.copy().configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
    private var alphabetCache: Pair<Long, String>? = null

    data class Variant(val width: Int, val height: Int) {
        val dimensions get() = "${width}×$height"
    }
    data class Found(val url: String, val context: Context, val variants: List<Variant>, val subtitles: List<Sub>)
    data class Sub(val name: String, val url: String)
    data class Context(@JsonProperty("kind") val kind: String = "zona-dash-v1",
        @JsonProperty("media") val media: String = "", @JsonProperty("alphabet") val alphabet: String = "",
        @JsonProperty("parameter") val parameter: String = "", @JsonProperty("epoch") val epoch: Long = 0,
        @JsonProperty("observed") val observed: Long = 0, @JsonProperty("voices") val voices: Map<String, String> = emptyMap(),
        @JsonProperty("width") val width: Int = 0, @JsonProperty("height") val height: Int = 0,
        @JsonProperty("agent") val agent: String = "Mozilla/5.0") {
        fun valid() = kind == "zona-dash-v1" && StreamTools.isHttp(media) && alphabet.length == 52 &&
            alphabet.toSet() == LETTERS.toSet() && Regex("[A-Za-z0-9_=.-]{1,120}").matches(parameter) && epoch > 0 &&
            agent.length in 1..512 && !agent.contains('\r') && !agent.contains('\n')

        fun transform(value: String, now: Long = System.currentTimeMillis()): String {
            if (!valid()) throw IllegalArgumentException("Invalid Zona playback context")
            val uri = URI(value)
            val domain = URI(media).host.orEmpty().substringAfter('.')
            if (!StreamTools.isHttp(value) || !(uri.host == URI(media).host || (domain.contains('.') && uri.host.endsWith(".$domain"))) || uri.path.startsWith("/x-en-x/")) return value
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.let { if (parameter in it) it else it + parameter }.joinToString("&")
            val hour = ((epoch + (now - observed) / 1_000.0) / 3_600).roundToLong()
            val path = uri.rawPath + "?" + query
            val encoded = Base64.encode("$hour/$path".toByteArray(Charsets.UTF_8)).map { ch -> LETTERS.indexOf(ch).let { if (it < 0) ch else alphabet[it] } }.joinToString("")
            return "${uri.scheme}://${uri.rawAuthority}/x-en-x/$encoded"
        }
    }
    companion object {
        const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        fun context(link: ExtractorLink): Context? = tryParseJson<Context>(link.extractorData)?.takeIf { it.valid() }
        fun interceptor(context: Context): Interceptor = Interceptor { chain ->
            val request = chain.request()
            val original = request.url.toString()
            val rewritten = context.transform(original)
            val response = chain.proceed(request.newBuilder().url(rewritten).apply { if (rewritten != original) header("User-Agent",context.agent) }.build())
            if (rewritten != original && URI(original).path.endsWith(".mpd") && response.isSuccessful) {
                try {
                    val rendered = rewriteManifest(response.body.string(), context)
                    response.newBuilder().request(request).header("Content-Length",rendered.toByteArray(Charsets.UTF_8).size.toString())
                        .body(rendered.toResponseBody("application/dash+xml".toMediaType())).build()
                } catch (error: Exception) {
                    response.close()
                    throw java.io.IOException("Zona: видеосервер вернул неподдерживаемый манифест",error)
                }
            } else response.newBuilder().request(request).build()
        }
        fun manifest(text: String): Document {
            require(text.length < 2_000_000 && !text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
            val doc = Jsoup.parse(text, "", Parser.xmlParser())
            require(doc.getElementsByTag("MPD").isNotEmpty() && doc.getElementsByTag("ContentProtection").isEmpty())
            return doc
        }
        fun variants(doc: Document): List<Variant> = doc.getElementsByTag("Representation").mapNotNull {
            val w = it.attr("width").toIntOrNull(); val h = it.attr("height").toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) Variant(w,h) else null
        }.distinct().sortedByDescending { it.height }

        fun namedAudio(doc: Document, names: List<String>): Map<String,String> {
            val tracks = doc.getElementsByTag("AdaptationSet").filter { it.attr("contentType") == "audio" }
            return tracks.flatMapIndexed { position, track ->
                // Slot in lang (ru0, ru1...) identifies the voice; Representation IDs also include video.
                val slot = Regex("[a-zA-Z]{2,3}(\\d+)").matchEntire(track.attr("lang"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: position.takeIf { names.size == tracks.size }
                val label = slot?.let(names::getOrNull)?.takeIf { it.isNotBlank() && it != "delete" }
                track.getElementsByTag("Representation").mapNotNull { rep -> label?.let { rep.attr("id") to it } }
            }.toMap()
        }

        fun rewriteManifest(text: String, context: Context): String {
            val doc = manifest(text)
            if (context.width > 0 && context.height > 0) {
                val all = doc.getElementsByTag("Representation").filter { it.hasAttr("width") }
                require(all.any { it.attr("width") == context.width.toString() && it.attr("height") == context.height.toString() })
                all.filter { it.attr("width") != context.width.toString() || it.attr("height") != context.height.toString() }.forEach(Element::remove)
            }
            doc.getElementsByTag("AdaptationSet").forEach { track ->
                if (track.attr("contentType") == "audio") {
                    val id = track.getElementsByTag("Representation").firstOrNull()?.attr("id")
                    val name = id?.let { context.voices[it] }
                    if (name == null && context.voices.isNotEmpty()) { track.remove(); return@forEach }
                    if (name != null) {
                        track.getElementsByTag("Label").forEach(Element::remove)
                        track.prependElement("Label").text(name)
                        track.attr("lang", when { name.contains("Укр",true) -> "uk"; name.contains("Eng",true) || name.contains("Original",true) -> "en"; else -> "ru" })
                    }
                }
            }
            return doc.outputSettings(doc.outputSettings().prettyPrint(false)).outerHtml()
        }
        // Find an object/array literal while respecting strings and escaped braces.
        fun literal(text: String, start: Int): String {
            require(start in text.indices && text[start] in "{[")
            val stack = ArrayDeque<Char>(); var quote: Char? = null; var escaped = false
            for (i in start until text.length.coerceAtMost(start + 1_500_000)) {
                val c = text[i]
                if (quote != null) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null; continue }
                if (c == '"' || c == '\'') { quote = c; continue }
                when(c) {
                    '{', '[' -> stack.addLast(c)
                    '}', ']' -> { require(stack.isNotEmpty() && stack.removeLast() == if (c == '}') '{' else '['); if (stack.isEmpty()) return text.substring(start,i+1) }
                }
            }
            error("Unclosed player data")
        }
    }
    private suspend fun config(): String {
        for (origin in origins()) {
            try {
                val raw = SourceHttp.get("Zona", "$origin/static/ext32s.txt", headers = headers, timeoutSeconds = 5).text
                val node = json.readTree(String(Base64.decode(raw.trim()), Charsets.UTF_8))
                val value = String(Base64.decode(node.path("ut").asText()).map { (it.toInt() xor 59).toByte() }.toByteArray(), Charsets.UTF_8)
                require(value.startsWith("https://") && value.count { it == '{' } == 1 && value.contains("{key}"))
                return value
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { }
        }
        throw ErrorLoadingException("Zona: основной плеер недоступен через эту сеть")
    }
    private suspend fun alphabet(embed: String): String {
        alphabetCache?.takeIf { System.currentTimeMillis() - it.first < 900_000 }?.let { return it.second }
        val origin = URI(embed).let { "${it.scheme}://${it.rawAuthority}" }
        val text = SourceHttp.get("Zona", "$origin/cdn.js", headers = headers, timeoutSeconds = 5).text
        val result = Regex("\"$LETTERS\",[a-zA-Z]+=\"([A-Za-z]{52})\"").find(text)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Zona: формат видеоплеера изменился")
        require(result.toSet() == LETTERS.toSet())
        alphabetCache = System.currentTimeMillis() to result
        return result
    }
    suspend fun load(key: String, season: Int?, episode: Int?): Found {
        require(key.matches(Regex("\\d+")))
        val embed = config().replace("{key}",key)
        val html = SourceHttp.get("Zona", embed, headers = headers, timeoutSeconds = 7).text
        val marker = Regex(if (season == null) "\\bsource\\s*:\\s*(\\{)" else "\\bseasons\\s*:\\s*(\\[)").find(html)
            ?: throw ErrorLoadingException("Zona: выбранное видео не опубликовано в основном плеере")
        val root = json.readTree(literal(html,marker.groups[1]!!.range.first))
        val media: JsonNode = if (season == null) root else {
            root.firstOrNull { it.path("season").asInt() == season && !it.path("blocked").asBoolean() }
                ?.path("episodes")?.firstOrNull { it.path("episode").asText().toIntOrNull() == episode && !it.path("blocked").asBoolean() }
                ?: throw ErrorLoadingException("Zona: нужная серия отсутствует в основном плеере")
        }
        val url = media.path("dash").asText().takeIf(StreamTools::isHttp)
            ?: throw ErrorLoadingException("Zona: основной плеер не выдал видео DASH")
        val variable = Regex("""\+(['"])(&)\1\+(\w+)""").find(html)?.groupValues?.get(3)
            ?: throw ErrorLoadingException("Zona: изменились параметры видеосервера")
        val parameter = Regex("""\b${Regex.escape(variable)}\s*=\s*(["'])([^"']+)\1""").findAll(html).lastOrNull()?.groupValues?.get(2)
            ?: throw ErrorLoadingException("Zona: параметры видеосервера отсутствуют")
        val epoch = Regex("\\bunixTime\\s*=\\s*(\\d+)").find(html)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw ErrorLoadingException("Zona: время видеосервера отсутствует")
        val names = media.path("audio").path("names").map { it.asText() }
        val context = Context(media=url, alphabet=alphabet(embed), parameter=parameter, epoch=epoch, observed=System.currentTimeMillis(), agent=ua)
        require(context.valid())
        val response = SourceHttp.get("Zona",context.transform(url),headers=headers,timeoutSeconds=6)
        val document = manifest(response.text)
        val voices = namedAudio(document,names)
        val variants = variants(document)
        if (variants.isEmpty()) throw ErrorLoadingException("Zona: разрешения основного видео отсутствуют")
        val subs = media.path("cc").mapNotNull { node -> node.path("url").asText().takeIf(StreamTools::isHttp)?.let { Sub(node.path("name").asText("Субтитры"),it) } }
        // Verify initialization, early and middle media for every video resolution and every audio track.
        withContext(Dispatchers.IO) {
            val client = com.lagradost.cloudstream3.app.baseClient.newBuilder().callTimeout(4,java.util.concurrent.TimeUnit.SECONDS).build()
            val nodes = document.getElementsByTag("Representation")
            val jobs = nodes.map { representation ->
                val template = representation.getElementsByTag("SegmentTemplate").firstOrNull()
                    ?: throw ErrorLoadingException("Zona: неподдерживаемый формат сегментов")
                val base = document.getElementsByTag("BaseURL").firstOrNull()?.text()?.let { URI(url).resolve(it).toString() } ?: url
                val times = template.getElementsByTag("S").map { (it.attr("r").toIntOrNull() ?: 0) + 1 }.sum()
                val start = template.attr("startNumber").toIntOrNull() ?: 1
                val middle = start + times.coerceAtLeast(2)/2
                representation.attr("id") to listOf(template.attr("initialization"), template.attr("media").replace("\$Number\$",start.toString()), template.attr("media").replace("\$Number\$",middle.toString()))
                    .filter(String::isNotBlank).map { URI(base).resolve(it).toString() }
            }
            val results = kotlinx.coroutines.coroutineScope {
                val limit = kotlinx.coroutines.sync.Semaphore(8)
                jobs.map { (id,targets) -> async {
                    limit.acquire()
                    try {
                        for (target in targets.distinct()) {
                            val req = okhttp3.Request.Builder().url(context.transform(target)).header("User-Agent",ua).header("Range","bytes=0-63").build()
                            client.newCall(req).execute().use { r ->
                                val magic = r.body.source().readByteArray(4).joinToString("") { "%02x".format(it.toInt() and 255) }
                                if (!r.isSuccessful || magic !in setOf("1a45dfa3","1f43b675")) throw ErrorLoadingException("Zona: сегмент основного видео недоступен (HTTP ${r.code})")
                            }
                        }
                        id to true
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { id to false }
                      finally { limit.release() }
                } }.map { it.await() }.toMap()
            }
            document.getElementsByTag("Representation").filter { results[it.attr("id")] != true }.forEach(Element::remove)
        }
        val availableVariants = variants(document)
        val availableVoices = voices.filterKeys { id -> document.getElementsByTag("AdaptationSet").filter { it.attr("contentType") == "audio" }.any { track -> track.getElementsByTag("Representation").any { it.attr("id") == id } } }
        if (availableVariants.isEmpty() || availableVoices.isEmpty()) throw ErrorLoadingException("Zona: проверенные видео или звуковые дорожки недоступны")
        return Found(url,context.copy(voices=availableVoices),availableVariants,subs)
    }
}
