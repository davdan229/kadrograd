package ru.kadrograd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Uses the official web mirror, independently of api.animevost.org. */
class VostProvider(baseUrl: String) : MainAPI() {
    override var mainUrl = baseUrl
    override var name = "Кадроград · AnimeVost"
    override var lang = "ru"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf("all" to "Аниме — каталог AnimeVost")
    private val probes = Semaphore(4)
    private val pagePaths = java.util.Collections.synchronizedMap(object : LinkedHashMap<Int,String>(64,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,String>) = size > 256
    })
    // The mobile site uses a different template. Request the desktop markup consistently, including search.
    private val browserHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept-Language" to "ru-RU,ru;q=0.9,en;q=0.5", "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8")
    private fun articlePath(value: String?, id: Int): String? = runCatching {
        URI(value.orEmpty()).rawPath?.takeIf { Regex("/(?:[^/?#]+/)*$id-[^/?#]+\\.html").matches(it) }
    }.getOrNull()
    private fun absolute(value: String) = URI(mainUrl + "/").resolve(value).toString()
    private fun metadata(node: Element, label: String): String = node.select(".shortstoryContent p").firstOrNull {
        it.selectFirst("strong")?.text()?.startsWith(label, true) == true
    }?.let { it.text().removePrefix(it.selectFirst("strong")!!.text()).trim() }.orEmpty()
    private fun kind(node: Element) = when (metadata(node, "Тип").lowercase()) {
        "полнометражный фильм", "короткометражный фильм" -> TvType.AnimeMovie
        "ova", "ona", "тв-спэшл" -> TvType.OVA
        else -> TvType.Anime
    }
    private fun title(node: Element) = node.selectFirst(".shortstoryHead h1, .shortstoryHead h2")?.text().orEmpty()
    private fun poster(node: Element) = node.selectFirst(".shortstoryContent img[src]")?.attr("src")?.let(::absolute)
    private fun card(node: Element): SearchResponse? {
        val link = node.selectFirst(".shortstoryHead h2 a[href]") ?: return null
        val id = Regex("/(\\d+)-").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (link.text().isBlank()) return null
        val path = articlePath(link.attr("href"),id) ?: return null
        pagePaths[id] = path
        return newAnimeSearchResponse(link.text(), "$mainUrl$path", kind(node)) {
            posterUrl = poster(node)
            year = metadata(node, "Год выхода").toIntOrNull()
            addDubStatus(true)
        }
    }
    private suspend fun document(url: String): Document {
        val raw = SourceHttp.get("AnimeVost", url, referer = "$mainUrl/", headers = browserHeaders)
        if (raw.code !in 200..299) throw ErrorLoadingException("AnimeVost: зеркало вернуло HTTP ${raw.code}")
        return raw.document
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = document(if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/")
        val cards = doc.select(".shortstory").mapNotNull(::card)
        if (cards.isEmpty()) throw ErrorLoadingException("AnimeVost: зеркало не выдало каталог. Проверьте его адрес в настройках расширения.")
        return newHomePageResponse(request.name, cards, hasNext = doc.select("a[href]").any {
            runCatching { URI(absolute(it.attr("href"))).path.endsWith("/page/${page + 1}/") }.getOrDefault(false)
        })
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return withTimeoutOrNull(16_000) {
            suspend fun page(number: Int): Document {
                val raw = SourceHttp.post("AnimeVost", "$mainUrl/index.php?do=search", mapOf("do" to "search", "subaction" to "search",
                    "full_search" to "0", "titleonly" to "3", "search_start" to number.toString(), "story" to query.trim()),
                    headers = browserHeaders, referer = "$mainUrl/")
                if (raw.code !in 200..299) throw ErrorLoadingException("AnimeVost: поиск вернул HTTP ${raw.code}")
                return raw.document.also {
                    if (it.select(".global_search").isEmpty()) throw ErrorLoadingException("AnimeVost: зеркало поиска недоступно")
                }
            }
            val first = page(0)
            val total = Regex("найдено\\s+(\\d+)", RegexOption.IGNORE_CASE).find(first.select(".global_search").text())
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            // The site's search is paginated; descriptions must not crowd out old exact titles.
            val pages = ((total + 9) / 10).coerceIn(1, 5)
            val slots = Semaphore(2)
            val extra = coroutineScope { (2..pages).map { n -> async { slots.withPermit { page(n) } } }.awaitAll() }
            (listOf(first) + extra).flatMap { it.select(".shortstory").mapNotNull(::card) }.distinctBy { it.url }
        } ?: throw ErrorLoadingException("AnimeVost: поиск не ответил за 16 секунд")
    }
    private suspend fun details(id: Int, path: String? = null): Document {
        val selected = articlePath(path,id) ?: pagePaths[id]
        val doc = document(mainUrl + (selected ?: "/index.php?newsid=$id"))
        if (doc.select(".shortstory [id=ratig-layer-$id]").isEmpty())
            throw ErrorLoadingException("AnimeVost: запрошенный релиз не найден на зеркале")
        articlePath(doc.location(),id)?.let { pagePaths[id] = it }
        return doc
    }
    private fun episodes(doc: Document): Map<String, String> {
        val raw = doc.select("script").asSequence().mapNotNull {
            Regex("""\bvar\s+data\s*=\s*(\{[\s\S]*?})\s*;""").find(it.data())?.groupValues?.get(1)
        }.firstOrNull() ?: throw ErrorLoadingException("AnimeVost: список серий изменился или отсутствует")
        return tryParseJson<Map<String, String?>>(raw.replace(Regex(",\\s*}"), "}"))
            ?.mapNotNull { (label, video) -> video?.takeIf { it.matches(Regex("\\d+")) && label.isNotBlank() }?.let { label to it } }
            ?.toMap() ?: throw ErrorLoadingException("AnimeVost: не удалось разобрать список серий")
    }
    override suspend fun load(url: String): LoadResponse = withTimeoutOrNull(15_000) { loadPage(url) }
        ?: throw ErrorLoadingException("AnimeVost: карточка не загрузилась за 15 секунд. Проверьте доступ к странице аниме через эту сеть")

    private suspend fun loadPage(url: String): LoadResponse {
        val uri = runCatching { URI(url) }.getOrNull()
        // Recognize cards and episode references saved by versions 1/2 as well.
        val id = uri?.query?.split('&')?.firstOrNull { it.startsWith("newsid=") || it.startsWith("id=") }
            ?.substringAfter('=')?.toIntOrNull()
            ?: uri?.path?.let { Regex("/(\\d+)-").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: throw ErrorLoadingException("AnimeVost: неверный номер релиза")
        if (id <= 0) throw ErrorLoadingException("AnimeVost: неверный номер релиза")
        val doc = details(id,articlePath(url,id))
        val node = doc.select(".shortstory").firstOrNull { it.select("[id=ratig-layer-$id]").isNotEmpty() }
            ?: throw ErrorLoadingException("AnimeVost: карточка недоступна")
        val list = episodes(doc)
        if (list.isEmpty()) throw ErrorLoadingException("AnimeVost: опубликованных видео пока нет")
        val path = articlePath(url,id) ?: pagePaths[id]
        val canonical = mainUrl + (path ?: "/index.php?newsid=$id")
        val type = kind(node)
        val plot = node.selectFirst("[itemprop=description]")?.text()
        if (type == TvType.AnimeMovie && list.size == 1) {
            return newMovieLoadResponse(title(node), canonical, type, EpisodeRef(id, "${list.values.single()}.mp4",path).toJson()) {
                posterUrl = poster(node); year = metadata(node, "Год выхода").toIntOrNull(); this.plot = plot
            }
        }
        return newAnimeLoadResponse(title(node), canonical, type) {
            posterUrl = poster(node); year = metadata(node, "Год выхода").toIntOrNull(); this.plot = plot
            tags = metadata(node, "Жанр").split(',').map(String::trim).filter(String::isNotEmpty)
            addEpisodes(DubStatus.Dubbed, list.map { (label, video) ->
                newEpisode(EpisodeRef(id, "$video.mp4",path).toJson(), {
                    name = label
                    episode = Regex("^(\\d+)\\s+серия").find(label)?.groupValues?.get(1)?.toIntOrNull()
                }, fix = false)
            })
        }
    }
    internal fun candidates(html: String, video: String): List<String> = Regex("""https?://[^\s"'<>,]+\.mp4(?:\?[^\s"'<>,]*)?""")
        .findAll(html.replace("\\/", "/").replace("&amp;", "&")).map { it.value }
        .filter { StreamTools.isHttp(it) && URI(it).path.substringAfterLast('/') == "$video.mp4" }
        .distinctBy { URI(it).let { u -> "${u.host}|${u.path}" } }.take(8).toList()

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val ref = tryParseJson<EpisodeRef>(data)?.takeIf { it.releaseId > 0 } ?: return false
        val video = Regex("^(\\d+)\\.mp4$").matchEntire(ref.key)?.groupValues?.get(1) ?: return false
        val found = withTimeoutOrNull(28_000) {
            if (video !in episodes(details(ref.releaseId,ref.page)).values)
                throw ErrorLoadingException("AnimeVost: выбранная серия больше не опубликована на зеркале")
            val frame = SourceHttp.get("AnimeVost", "$mainUrl/frame5.php?play=$video&_=${System.currentTimeMillis()}", "$mainUrl/", headers = browserHeaders)
            if (frame.code !in 200..299) throw ErrorLoadingException("AnimeVost: плеер зеркала вернул HTTP ${frame.code}")
            coroutineScope { candidates(frame.text, video).map { url -> async {
                probes.withPermit { url.takeIf { SourceHttp.accessibleMp4(it, "$mainUrl/") } }
            } }.awaitAll().filterNotNull() }
        } ?: throw ErrorLoadingException("AnimeVost: видеосерверы не ответили за 28 секунд")
        if (found.isEmpty()) throw ErrorLoadingException("AnimeVost: доступных видеосерверов в этой сети не найдено")
        for (url in found.sortedByDescending { URI(it).path.contains("/720/") }) {
            val quality = if (URI(url).path.contains("/720/")) 720 else 480
            callback(newExtractorLink(name, "AnimeVost · ${URI(url).host} · ${quality}p · MP4", url, ExtractorLinkType.VIDEO) {
                this.quality = quality; referer = "$mainUrl/"
            })
        }
        return true
    }
    data class EpisodeRef(@JsonProperty("releaseId") val releaseId: Int, @JsonProperty("key") val key: String,
        @JsonProperty("page") val page: String? = null)
}
