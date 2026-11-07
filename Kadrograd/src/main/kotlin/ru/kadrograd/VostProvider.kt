package ru.kadrograd

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Uses the official web mirror with the public API as a fallback. */
class VostProvider(baseUrl: String) : QualityProvider() {
    override var mainUrl = baseUrl
    override var name = "Кадроград · AnimeVost"
    override var lang = "ru"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = animeHomePage
    private val probes = Semaphore(4)
    internal var infoEndpoint: String? = null
    internal var searchEndpoint: String? = null
    internal var catalogEndpoint: String? = null
    private val catalogMutex = Mutex()
    private var cachedCatalog: Triple<Int, Long, JsonNode>? = null
    private val episodeMapper = mapper.copy().enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
    private val pagePaths = java.util.Collections.synchronizedMap(object : LinkedHashMap<Int,String>(64,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,String>) = size > 256
    })
    private val apiRows = java.util.Collections.synchronizedMap(object : LinkedHashMap<Int,Pair<Long,JsonNode>>(64,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,Pair<Long,JsonNode>>) = size > 256
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
    private fun kind(node: Element) = kind(metadata(node, "Тип"))
    private fun kind(value: String) = when (value.lowercase()) {
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
        require(page > 0 && mainPage.any { it.data == request.data })
        if (page == 1 && request.data == "all" && catalogOrigin() != null) {
            val official = withTimeoutOrNull(4_000) {
                try { apiMainPage(page, request).takeIf { result -> result.items.any { it.list.isNotEmpty() } } }
                catch (_: ErrorLoadingException) { null }
            }
            if (official != null) return official
        }
        val web = withTimeoutOrNull(4_000) {
            try { webMainPage(page, request) }
            catch (_: ErrorLoadingException) { null }
        }
        return web ?: apiMainPage(page, request)
    }

    private fun catalogOrigin() = catalogEndpoint ?: "https://api.animevost.org/v1/last".takeIf {
        mainUrl in setOf("https://v14.vost.pw", "https://animevost.org")
    }

    private suspend fun apiMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val endpoint = catalogOrigin() ?: throw ErrorLoadingException("AnimeVost: каталог зеркала недоступен")
        val node = catalogMutex.withLock {
            val cached = cachedCatalog
            if (cached != null && cached.first == page && System.currentTimeMillis() - cached.second in 0..30_000) {
                cached.third
            } else {
                val raw = SourceHttp.get("AnimeVost", "$endpoint?page=$page&quantity=20", timeoutSeconds = 6).text
                val root = tryParseJson<JsonNode>(raw)
                    ?: throw ErrorLoadingException("AnimeVost: изменился формат API каталога")
                if (!root.path("data").isArray || root.path("state").path("page").asInt() != page) {
                    throw ErrorLoadingException("AnimeVost: API вернул другую страницу каталога")
                }
                cachedCatalog = Triple(page, System.currentTimeMillis(), root)
                root
            }
        }
        return newHomePageResponse(request.name, apiCards(node, request.data), hasNext = page.toLong() * 20 < node.path("state").path("count").asLong())
    }

    private fun apiCards(node: JsonNode, category: String = "all"): List<SearchResponse> =
        node.path("data").mapNotNull { row ->
            val id = row.path("id").asInt()
            val title = row.path("title").asText("")
            val type = kind(row.path("type").asText(""))
            val progress = Regex("""\[(?:\d{1,5}-)?(\d{1,5})\s+из\s+(\d{1,5})(\+)?]""").find(title)
            val ongoing = progress != null && (progress.groupValues[3].isNotEmpty() ||
                progress.groupValues[1].toInt() < progress.groupValues[2].toInt())
            val included = when (category) {
                "movies" -> type == TvType.AnimeMovie
                "series" -> type == TvType.Anime
                "ongoing" -> ongoing
                else -> true
            }
            if (id <= 0 || title.isBlank() || !included) return@mapNotNull null
            if (row.path("series").isTextual) apiRows[id] = System.currentTimeMillis() to row
            newAnimeSearchResponse(title, "$mainUrl/index.php?newsid=$id", type) {
                posterUrl = row.path("urlImagePreview").asText("").takeIf(StreamTools::isHttp)
                year = row.path("year").asText("").toIntOrNull()
                addDubStatus(true)
            }
        }.distinctBy { it.url }

    private suspend fun webMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = when (request.data) {
            "ongoing" -> "/ongoing/"
            "movies" -> "/tip/polnometrazhnyy-film/"
            "series" -> "/tip/tv/"
            else -> "/"
        }
        val doc = document(mainUrl + section + if (page <= 1) "" else "page/$page/")
        val cards = doc.select(".shortstory").mapNotNull(::card).distinctBy { it.url }
        if (cards.isEmpty()) throw ErrorLoadingException("AnimeVost: зеркало не выдало каталог. Проверьте его адрес в настройках расширения.")
        return newHomePageResponse(request.name, cards, hasNext = doc.select("a[href]").any {
            runCatching { URI(absolute(it.attr("href"))).path == "${section}page/${page + 1}/" }.getOrDefault(false)
        })
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val web = withTimeoutOrNull(4_000) {
            try { webSearch(query) }
            catch (_: ErrorLoadingException) { null }
        }
        if (web != null) return web
        val endpoint = searchEndpoint ?: "https://api.animevost.org/v1/search".takeIf {
            mainUrl in setOf("https://v14.vost.pw", "https://animevost.org")
        } ?: throw ErrorLoadingException("AnimeVost: поиск зеркала недоступен")
        val raw = SourceHttp.post("AnimeVost", endpoint, mapOf("name" to query.trim()), timeoutSeconds = 6).text
        val node = tryParseJson<JsonNode>(raw)?.takeIf { it.path("data").isArray }
            ?: throw ErrorLoadingException("AnimeVost: изменился формат API поиска")
        return apiCards(node)
    }

    private suspend fun webSearch(query: String): List<SearchResponse> {
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
        val base = mainUrl
        val byId = "/index.php?newsid=$id"
        val backup = when (base) {
            "https://v14.vost.pw" -> "https://animevost.org"
            "https://animevost.org" -> "https://v14.vost.pw"
            else -> base
        }
        val doc = firstReachable(listOf(base + (selected ?: byId), backup + byId), alternateDelayMillis = 1_500) { url ->
            document(url).takeIf { it.select(".shortstory [id=ratig-layer-$id]").isNotEmpty() }
                ?: throw ErrorLoadingException("AnimeVost: ответ относится к другому релизу")
        } ?: throw ErrorLoadingException("AnimeVost: релиз не найден или недоступен на проверенных адресах")
        articlePath(doc.location(),id)?.let { pagePaths[id] = it }
        return doc
    }
    private fun episodes(doc: Document): Map<String, String> {
        val raw = doc.select("script").asSequence().mapNotNull {
            Regex("""\bvar\s+data\s*=\s*(\{[\s\S]*?})\s*;""").find(it.data())?.groupValues?.get(1)
        }.firstOrNull() ?: throw ErrorLoadingException("AnimeVost: список серий изменился или отсутствует")
        return parseEpisodes(raw)
    }
    private fun parseEpisodes(raw: String): Map<String, String> {
        val entries = episodeMapper.readTree(raw.replace(Regex(",\\s*}"), "}"))
        require(entries.isObject)
        return entries.fields().asSequence().mapNotNull { (label, video) ->
            video.asText().takeIf { it.matches(Regex("\\d+")) && label.isNotBlank() }?.let { label to it }
        }.toMap()
    }

    private data class Release(val name: String, val kind: TvType, val poster: String?, val year: Int?,
        val plot: String?, val tags: List<String>, val score: Score?, val duration: Int?, val episodes: Map<String, String>)

    private suspend fun release(id: Int, path: String?): Release {
        suspend fun web(): Release {
            val doc = details(id, path)
            val node = doc.select(".shortstory").first { it.select("[id=ratig-layer-$id]").isNotEmpty() }
            return Release(title(node), kind(node), poster(node), metadata(node, "Год выхода").toIntOrNull(),
                node.selectFirst("[itemprop=description]")?.text(), genres(metadata(node, "Жанр")),
                Score.from100(node.selectFirst(".current-rating")?.text()?.toDoubleOrNull()?.takeIf { it in 0.1..100.0 }),
                Regex("""(\d+)\s*мин""", RegexOption.IGNORE_CASE).find(metadata(node, "Количество серий"))
                    ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }, episodes(doc))
        }
        val endpoint = infoEndpoint ?: "https://api.animevost.org/v1/info".takeIf {
            mainUrl in setOf("https://v14.vost.pw", "https://animevost.org")
        }
        val failures = ConcurrentHashMap<String, String>()
        return firstReachable(listOfNotNull("web", endpoint), alternateDelayMillis = 900) { source ->
            try {
                (if (source == "web") web() else apiRelease(source, id))
                    .takeIf { it.name.isNotBlank() && it.episodes.isNotEmpty() }
                    ?: throw ErrorLoadingException("AnimeVost: нет данных о релизе")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val route = if (source == "web") "зеркало" else "API"
                failures[route] = (failure as? ErrorLoadingException)?.message?.removePrefix("AnimeVost: ")
                    ?: failure.javaClass.simpleName
                throw failure
            }
        } ?: cachedApiRelease(id) ?: throw ErrorLoadingException(
            "AnimeVost: карточка недоступна (${failures.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} — ${it.value}" }})"
        )
    }

    private fun cachedApiRelease(id: Int): Release? {
        val (seen, row) = apiRows[id] ?: return null
        if (System.currentTimeMillis() - seen !in 0..300_000) return null
        return runCatching { readApiRelease(row) }.getOrNull()?.takeIf { it.name.isNotBlank() && it.episodes.isNotEmpty() }
    }

    private suspend fun apiRelease(endpoint: String, id: Int): Release {
        val response = SourceHttp.post("AnimeVost", endpoint, mapOf("id" to id.toString()), timeoutSeconds = 6)
        val row = tryParseJson<JsonNode>(response.text)?.path("data")?.firstOrNull { it.path("id").asInt() == id }
            ?: throw ErrorLoadingException("AnimeVost: API не подтвердил релиз")
        return readApiRelease(row)
    }

    private fun readApiRelease(row: JsonNode): Release {
        fun field(key: String) = row.path(key).asText("").takeIf(String::isNotBlank)
        val votes = row.path("votes").asInt()
        val average = row.path("rating").asDouble().div(votes.coerceAtLeast(1))
        return Release(field("title").orEmpty(), kind(field("type").orEmpty()),
            field("urlImagePreview")?.takeIf(StreamTools::isHttp), field("year")?.toIntOrNull(),
            field("description")?.let { org.jsoup.Jsoup.parse(it).text() }, genres(field("genre").orEmpty()),
            Score.from5(average.takeIf { votes > 0 && it in 1.0..5.0 }), null,
            parseEpisodes(field("series").orEmpty()))
    }
    private fun genres(value: String) = value.split(',').map(String::trim).filter(String::isNotEmpty)
    override suspend fun load(url: String): LoadResponse = withTimeoutOrNull(15_000) { loadPage(url) }
        ?: throw ErrorLoadingException("AnimeVost: карточка не загрузилась за 15 секунд. Проверьте доступ к странице аниме через эту сеть")

    private fun LoadResponse.applyDetails(release: Release) {
        posterUrl = release.poster
        year = release.year
        plot = release.plot
        tags = release.tags
        score = release.score
        duration = release.duration
    }

    private suspend fun loadPage(url: String): LoadResponse {
        val uri = runCatching { URI(url) }.getOrNull()
        // Recognize cards and episode references saved by versions 1/2 as well.
        val id = uri?.query?.split('&')?.firstOrNull { it.startsWith("newsid=") || it.startsWith("id=") }
            ?.substringAfter('=')?.toIntOrNull()
            ?: uri?.path?.let { Regex("/(\\d+)-").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: throw ErrorLoadingException("AnimeVost: неверный номер релиза")
        if (id <= 0) throw ErrorLoadingException("AnimeVost: неверный номер релиза")
        val release = release(id, articlePath(url,id))
        val list = release.episodes
        val path = articlePath(url,id) ?: pagePaths[id]
        val canonical = mainUrl + (path ?: "/index.php?newsid=$id")
        val type = release.kind
        if (type == TvType.AnimeMovie && list.size == 1) {
            return newMovieLoadResponse(release.name, canonical, type, EpisodeRef(id, "${list.values.single()}.mp4",path).toJson()) {
                applyDetails(release)
            }
        }
        return newAnimeLoadResponse(release.name, canonical, type) {
            applyDetails(release)
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

    override suspend fun loadSourceLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val ref = tryParseJson<EpisodeRef>(data)?.takeIf { it.releaseId > 0 } ?: return false
        val video = Regex("^(\\d+)\\.mp4$").matchEntire(ref.key)?.groupValues?.get(1) ?: return false
        val found = withTimeoutOrNull(28_000) {
            if (video !in release(ref.releaseId,ref.page).episodes.values)
                throw ErrorLoadingException("AnimeVost: выбранная серия больше не опубликована на зеркале")
            val frame = SourceHttp.get("AnimeVost", "$mainUrl/frame5.php?play=$video&_=${System.currentTimeMillis()}", "$mainUrl/", headers = browserHeaders)
            if (frame.code !in 200..299) throw ErrorLoadingException("AnimeVost: плеер зеркала вернул HTTP ${frame.code}")
            coroutineScope { candidates(frame.text, video).map { url -> async {
                probes.withPermit { url.takeIf { SourceHttp.accessibleMp4(it, "$mainUrl/") } }
            } }.awaitAll().filterNotNull() }
        } ?: throw ErrorLoadingException("AnimeVost: видеосерверы не ответили за 28 секунд")
        if (found.isEmpty()) throw ErrorLoadingException("AnimeVost: доступных видеосерверов в этой сети не найдено")
        val links = found.map { url ->
            val path = URI(url).path
            val quality = when {
                path.contains("/1080/") || path.contains("/1080p/") -> 1080
                path.contains("/720/") || path.contains("/720p/") -> 720
                else -> 480
            }
            url to quality
        }
        for ((url, quality) in links.sortedByDescending { it.second }) {
            callback(newExtractorLink(name, "AnimeVost · ${URI(url).host} · ${quality}p · MP4", url, ExtractorLinkType.VIDEO) {
                this.quality = quality; referer = "$mainUrl/"
            })
        }
        return true
    }
    data class EpisodeRef(@JsonProperty("releaseId") val releaseId: Int, @JsonProperty("key") val key: String,
        @JsonProperty("page") val page: String? = null)
}
