package ru.kadrograd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Guest playback only. The site reserves HD and downloads for registered viewers. */
class VibeProvider(baseUrl: String) : MainAPI() {
    override var mainUrl = baseUrl
    override var name = "Кадроград · KinoVibe"
    override var lang = "ru"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.AnimeMovie)
    override val mainPage = mainPageOf("films" to "Фильмы", "serial" to "Сериалы", "cartoon" to "Мультфильмы", "anime" to "Аниме")

    private fun kind(title: String, category: String = ""): TvType {
        val series = Regex("\\d+\\s*сезон", RegexOption.IGNORE_CASE).containsMatchIn(title) || category == "serial"
        return when {
            category == "anime" || title.startsWith("Аниме", true) -> if (series) TvType.Anime else TvType.AnimeMovie
            category == "cartoon" || title.startsWith("Мульт", true) -> TvType.Cartoon
            series || title.startsWith("Сериал", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }
    private fun card(node: Element, category: String): SearchResponse? {
        val title = node.selectFirst(".custom1-title, h2")?.text()?.takeIf(String::isNotBlank) ?: return null
        val href = node.attr("href").takeIf(String::isNotBlank) ?: return null
        val type = kind(title, category)
        return newMovieSearchResponse(title, fixUrl(href), type) {
            posterUrl = node.selectFirst("img[src]")?.attr("src")?.let(::fixUrl)
            year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
    private suspend fun document(url: String): Document {
        val response = SourceHttp.get("KinoVibe", url)
        if (response.code !in 200..299) throw ErrorLoadingException("KinoVibe: сервер вернул HTTP ${response.code}")
        return response.document
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = document("$mainUrl/${request.data}/" + if (page > 1) "page/$page/" else "")
        val nodes = doc.select("#dle-content .custom1-item a.custom1-img")
        if (nodes.isEmpty()) throw ErrorLoadingException("KinoVibe: каталог недоступен или изменился")
        return newHomePageResponse(request.name, nodes.mapNotNull { card(it, request.data) },
            hasNext = doc.select("a[href]").any { node ->
                runCatching { URI(mainUrl).resolve(node.attr("href")).path?.endsWith("/page/${page + 1}/") }.getOrNull() == true
            })
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val raw = SourceHttp.post("KinoVibe", "$mainUrl/index.php?do=search", data = mapOf("do" to "search", "subaction" to "search",
            "search_start" to "0", "full_search" to "0", "result_from" to "1", "story" to query.trim()))
        if (raw.code !in 200..299) throw ErrorLoadingException("KinoVibe: поиск вернул HTTP ${raw.code}")
        val doc = raw.document
        if (doc.select("form#quicksearch").isEmpty()) throw ErrorLoadingException("KinoVibe: поиск недоступен")
        return doc.select("a.sres-wrap").mapNotNull { card(it, "") }
    }

    private fun pageUrl(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: throw ErrorLoadingException("KinoVibe: неверный адрес карточки")
        val path = uri.rawPath.orEmpty()
        if (!Regex("/\\d+-[^/]+(?:\\.html|/\\d+-seria\\.html)").matches(path))
            throw ErrorLoadingException("KinoVibe: неверный адрес карточки")
        return mainUrl + path
    }
    private fun playerFile(doc: Document): String {
        val script = doc.select("script").firstOrNull { it.data().contains("id:\"playerjshd\"") }?.data().orEmpty()
        return Regex("""file:\s*"([^"\r\n]+)"""").find(script)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("KinoVibe: источник не выдал гостевое видео")
    }
    private suspend fun entries(doc: Document, ref: String): List<Entry> {
        val file = playerFile(doc)
        if (!file.substringBefore('?').endsWith(".txt"))
            return if (guestStreams(file).isEmpty()) emptyList() else listOf(Entry("Фильм", file))
        val raw = SourceHttp.get("KinoVibe", fixUrl(file), referer = ref)
        if (raw.code !in 200..299) throw ErrorLoadingException("KinoVibe: плейлист вернул HTTP ${raw.code}")
        val playlist = tryParseJson<Playlist>(raw.text.trimStart('\uFEFF'))?.playlist
            ?: throw ErrorLoadingException("KinoVibe: изменился формат списка серий")
        return playlist.filter { it.comment.isNotBlank() && guestStreams(it.file).isNotEmpty() }
    }
    override suspend fun load(url: String): LoadResponse {
        val ref = pageUrl(url)
        val doc = document(ref)
        val heading = doc.selectFirst("h1")?.text()?.takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("KinoVibe: карточка недоступна")
        val title = heading.replace(Regex("^(Фильм|Сериал|Мультфильм)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+смотреть онлайн.*$", RegexOption.IGNORE_CASE), "")
        val list = entries(doc, ref).distinctBy { it.key() }
        if (list.isEmpty()) throw ErrorLoadingException("KinoVibe: доступных гостевых видео нет")
        val type = kind(heading)
        val poster = doc.selectFirst(".movie-poster img[src]")?.attr("src")?.let(::fixUrl)
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val plot = doc.selectFirst(".movie-desc")?.text()
        val season = Regex("(\\d+)\\s*сезон", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()
        if (list.size == 1 && type in setOf(TvType.Movie, TvType.AnimeMovie, TvType.Cartoon) && !playerFile(doc).endsWith(".txt")) {
            return newMovieLoadResponse(title, ref, type, PlaybackRef(ref, list.single().key()).toJson()) {
                this.posterUrl = poster; this.year = year; this.plot = plot
            }
        }
        return newTvSeriesLoadResponse(title, ref, type, list.map { entry ->
            newEpisode(PlaybackRef(ref, entry.key()).toJson(), {
                name = entry.key()
                this.season = season
                episode = Regex("^(\\d+)").find(entry.key())?.value?.toIntOrNull()
            }, fix = false)
        }) { this.posterUrl = poster; this.year = year; this.plot = plot }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val stored = tryParseJson<PlaybackRef>(data) ?: return false
        val ref = pageUrl(stored.page)
        val doc = document(ref)
        val entry = entries(doc, ref).firstOrNull { it.key() == stored.key } ?: return false
        val voice = entry.comment.substringAfter("<br>", "").let { Jsoup.parse(it).text() }
            .ifBlank { doc.selectFirst("[itemprop=translator]")?.text().orEmpty() }
        val streams = guestStreams(entry.file)
        streams.forEach { stream ->
            callback(newExtractorLink(name, listOf("KinoVibe", voice, "SD", "MP4").filter(String::isNotBlank).joinToString(" · "),
                stream, ExtractorLinkType.VIDEO) { referer = ref; quality = Qualities.Unknown.value })
        }
        return streams.isNotEmpty()
    }

    internal fun guestStreams(file: String): List<String> {
        // Select only the guest SD variant explicitly supplied by the site. Never synthesize HD URLs.
        val expanded = file.replace(Regex("\\[480,720\\]"), "480")
        return expanded.split(Regex(",\\s*(?=https?://|$)")).map(String::trim).filter { url ->
            StreamTools.isHttp(url) && runCatching { URI(url).path }.getOrNull()?.let { path ->
                path.endsWith(".mp4") && !Regex("[_.\\/](?:720|1080|2160)p?[_.\\/]").containsMatchIn(path)
            } == true
        }.distinct()
    }
    data class PlaybackRef(@JsonProperty("page") val page: String, @JsonProperty("key") val key: String)
    data class Playlist(@JsonProperty("playlist") val playlist: List<Entry>? = null)
    data class Entry(@JsonProperty("comment") val comment: String = "", @JsonProperty("file") val file: String = "") {
        fun key() = Jsoup.parse(comment).text().trim()
    }
}
