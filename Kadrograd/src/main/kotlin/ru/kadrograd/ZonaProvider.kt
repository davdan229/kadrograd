package ru.kadrograd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ZonaProvider(baseUrl: String) : MainAPI() {
    override var mainUrl = baseUrl
    override var name = "Кадроград · Zona"
    override var lang = "ru"
    override val hasMainPage = true
    // Zona explicitly reserves downloading for premium accounts.
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.AnimeMovie)
    override val mainPage = mainPageOf(
        "movies" to "Фильмы", "series" to "Сериалы",
        "movies/filter?genre=multfilm" to "Мультфильмы",
        "series/filter?genre=multfilm" to "Мультсериалы",
        "movies/filter?genre=anime" to "Аниме-фильмы", "series/filter?genre=anime" to "Аниме-сериалы"
    )
    internal var videoApiOrigins = listOf("https://apiw1.mzona.net", "https://apir1.mzona.net")
    internal var streamApiOrigins = listOf("https://vsw01.zonasearch.com", "https://vsr01.zonasearch.com")
    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val headers get() = mapOf("User-Agent" to ua)
    private val nativeTracks by lazy { ZonaTracks({streamApiOrigins},ua) }
    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor? =
        ZonaTracks.context(extractorLink)?.let(ZonaTracks::interceptor)
    private data class Playback(val direct: List<Stream>, val native: ZonaTracks.Found?)
    private fun escaped(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun error(message: String): Nothing = throw ErrorLoadingException("Zona: $message")

    private suspend inline fun <reified T> json(url: String): T =
        tryParseJson<T>(SourceHttp.get("Zona", url, headers = headers).text) ?: error("изменился формат каталога")

    private fun type(series: Boolean, tags: List<Tag> = emptyList(), category: String = ""): TvType {
        val genres = tags.map { it.slug }
        return when {
            "anime" in genres || "genre=anime" in category -> if (series) TvType.Anime else TvType.AnimeMovie
            "multfilm" in genres || "genre=multfilm" in category -> TvType.Cartoon
            series -> TvType.TvSeries
            else -> TvType.Movie
        }
    }
    private fun pagePath(series: Boolean, slug: String) = "/${if (series) "tvseries" else "movies"}/$slug"
    private fun validSlug(slug: String) = Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(slug)
    private fun card(row: Title, series: Boolean, category: String): SearchResponse? {
        if (!validSlug(row.slug) || row.title.isBlank() || row.trailer) return null
        return newMovieSearchResponse(row.title, mainUrl + pagePath(series, row.slug), type(series, row.meta?.tags.orEmpty(), category)) {
            posterUrl = row.cover; year = row.year
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        require(page > 0)
        if (mainPage.none { it.data == request.data }) error("неизвестный раздел")
        val url = "$mainUrl/api/${request.data}${if ('?' in request.data) '&' else '?'}page=$page"
        val result = json<Catalog>(url)
        return newHomePageResponse(request.name, result.data.mapNotNull { card(it, request.data.startsWith("series"), request.data) },
            hasNext = !result.links?.next.isNullOrBlank())
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val doc = SourceHttp.get("Zona", "$mainUrl/search/${escaped(query.trim())}", headers = headers).document
        if (!doc.title().contains("Результаты поиска", true)) error("поиск недоступен или изменился")
        return doc.select("a[title][href]").mapNotNull { a ->
            val path = a.attr("href")
            if (!Regex("/(movies|tvseries)/[a-z0-9]+(?:-[a-z0-9]+)*").matches(path) || a.selectFirst("img") == null) return@mapNotNull null
            val title = a.attr("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val year = Regex("\\((\\d{4})\\)").find(a.selectFirst("img")?.attr("alt").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            newMovieSearchResponse(title, mainUrl + path, if (path.startsWith("/tvseries/")) TvType.TvSeries else TvType.Movie) {
                posterUrl = a.selectFirst("img")?.attr("src")?.let(::fixUrl); this.year = year
            }
        }.distinctBy { it.url }
    }
    private fun identity(url: String): Pair<Boolean, String> {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val m = Regex("/(movies|tvseries)/([a-z0-9]+(?:-[a-z0-9]+)*)/?").matchEntire(path) ?: error("неверный адрес карточки")
        return (m.groupValues[1] == "tvseries") to m.groupValues[2]
    }
    private suspend fun details(series: Boolean, slug: String, season: Int? = null): Title {
        val title = json<Detail>("$mainUrl/api/${if (series) "series" else "movies"}/$slug" + (season?.let { "?season=$it" } ?: "")).data
            ?: error("карточка не найдена")
        if (title.slug != slug || title.trailer || title.kpId == null) error("карточка не содержит доступного произведения")
        return title
    }
    override suspend fun load(url: String): LoadResponse {
        val (series, slug) = identity(url)
        val title = details(series, slug)
        val ref = mainUrl + pagePath(series, slug)
        val kind = type(series, title.meta?.tags.orEmpty())
        if (!series) return newMovieLoadResponse(title.title, ref, kind, PlaybackRef(ref, title.kpId!!).toJson()) {
            posterUrl = title.cover; year = title.year; plot = title.description; tags = title.meta?.tags?.filter { it.type == "genre" }?.map { it.title }
        }
        val seasons = title.seasons.filter { !it.banned && it.part > 0 && it.count > 0 }.sortedBy { it.part }
        if (seasons.isEmpty() || seasons.size > 100) error("доступных сезонов нет")
        val episodes = withTimeoutOrNull(30_000) {
            coroutineScope {
                val limit = Semaphore(3)
                seasons.map { season -> async {
                    limit.withPermit {
                        val data = if (season.part == 1) title else details(true, slug, season.part)
                        data.episodes.filter { !it.banned && it.part > 0 }.map { episode ->
                            newEpisode(PlaybackRef(ref, title.kpId!!, season.part, episode.part).toJson(), {
                                name = episode.title; this.season = season.part; this.episode = episode.part
                                posterUrl = episode.cover?.takeIf(String::isNotBlank)
                            }, fix = false)
                        }
                    }
                } }.awaitAll().flatten()
            }
        } ?: error("сезоны не загрузились вовремя; попробуйте открыть карточку ещё раз")
        if (episodes.isEmpty()) error("сайт не опубликовал доступных серий")
        return newTvSeriesLoadResponse(title.title, ref, kind, episodes.distinctBy { it.season to it.episode }) {
            posterUrl = title.cover; year = title.year; plot = title.description; tags = title.meta?.tags?.filter { it.type == "genre" }?.map { it.title }
        }
    }

    private fun serverTime(date: String?): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(date!!)?.time
    }.getOrNull() ?: System.currentTimeMillis()

    private suspend fun transport(origins: List<String>, path: String, query: String, time: Long): String {
        for (origin in origins) {
            try {
                val q = query + "&client_time=" + ZonaTransport.clientTime(time, ua)
                val response = SourceHttp.get("Zona", "$origin$path?q=${escaped(ZonaTransport.encode(q))}",
                    referer = "https://kinoserial.online/", headers = headers, timeoutSeconds = 6)
                return ZonaTransport.decode(response.text)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { /* The second official endpoint may be reachable in this network. */ }
        }
        error("видеосервер не ответил или недоступен через эту сеть")
    }
    private suspend fun clock(): Long {
        for (origin in videoApiOrigins) {
            try {
                val response = SourceHttp.get("Zona", "$origin/getTime", headers = headers, timeoutSeconds = 4)
                return response.okhttpResponse.use { serverTime(it.header("Date")) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { }
        }
        error("сервер времени недоступен через эту сеть")
    }
    private suspend fun streams(source: Source, time: Long, season: Int?, episode: Int?): List<Stream> {
        return when (source.type) {
            1 -> {
                val result = tryParseJson<Map<String, String?>>(transport(videoApiOrigins, "/getMobiVideo", "id=${escaped(source.key)}", time)).orEmpty()
                listOf("mqHlsUrl", "mqUrl", "lqHlsUrl", "lqUrl").mapNotNull { key -> result[key]?.let {
                    Stream(it, if (key.startsWith("mq")) "Среднее" else "Низкое", "Русский язык")
                } }
            }
            3, 48 -> {
                val extractor = if (source.type == 3) "filmix720p" else "kinovibe"
                val key = source.key.substringBefore('-')
                val query = "extractor=$extractor&key=${escaped(key)}" + if (season != null && episode != null) "&season=$season&episode=$episode" else ""
                tryParseJson<List<Stream>>(transport(streamApiOrigins, "/getStreams/", query, time)).orEmpty()
            }
            else -> emptyList()
        }
    }
    private suspend fun available(stream: Stream, ref: String): Boolean {
        if (stream.trailer || !StreamTools.isHttp(stream.url) || Regex("/abuse_\\d+\\.mp4").containsMatchIn(stream.url)) return false
        if (!URI(stream.url).path.endsWith(".m3u8")) return SourceHttp.accessibleMp4(stream.url, ref)
        return try {
            var url = stream.url
            repeat(3) {
                val playlist = SourceHttp.get("Zona", url, referer = ref, headers = headers, timeoutSeconds = 3).text.trimStart()
                if (!playlist.startsWith("#EXTM3U")) return false
                val target = playlist.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return false
                val next = URI(url).resolve(target).toString()
                if (!StreamTools.isHttp(next)) return false
                if (playlist.contains("#EXT-X-STREAM-INF:")) { url = next }
                else if (playlist.contains("#EXTINF:")) {
                    return SourceHttp.get("Zona", next, referer = ref, headers = headers + ("Range" to "bytes=0-511"), timeoutSeconds = 3).okhttpResponse.use { response ->
                        withContext(Dispatchers.IO) {
                            val prefix = response.body.source().readByteArray(32)
                            val text = prefix.toString(Charsets.US_ASCII).trimStart()
                            !text.startsWith('<') && !text.startsWith('{') && !text.startsWith('[') &&
                                (prefix[0] == 0x47.toByte() || text.startsWith("ID3") ||
                                    String(prefix, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "styp", "moof") ||
                                    playlist.contains("METHOD=AES-128"))
                        }
                    }
                } else return false
            }
            false
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { false }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val stored = tryParseJson<PlaybackRef>(data) ?: return false
        val (series, slug) = identity(stored.page)
        if (stored.kpId <= 0 || (series && (stored.season == null || stored.episode == null || stored.season <= 0 || stored.episode <= 0))) return false
        val ref = mainUrl + pagePath(series, slug)
        val playback = withTimeoutOrNull(45_000) {
            val current = details(series, slug, stored.season)
            if (current.kpId != stored.kpId || (series && current.episodes.none { it.part == stored.episode && !it.banned })) error("выбранная серия больше не доступна")
            val time = clock()
            val key = if (series) "S${stored.season.toString().padStart(2, '0')}E${stored.episode.toString().padStart(2, '0')}" else ""
            val envelope = tryParseJson<Sources>(transport(videoApiOrigins, "/getVideoSources",
                "movie_source_types=1,3,32,48&kinopoisk_id=${stored.kpId}&episode_key=$key&trailer=false", time)) ?: error("изменился формат ответа плеера")
            val sources = envelope.data.filter { it.kpId == stored.kpId && it.key != "0" && (!series || it.episode == key) }.take(6)
            coroutineScope {
            val native = async {
                val source = sources.firstOrNull { it.type == 32 }
                if (source == null || isCasting) null else try { nativeTracks.load(source.key,stored.season,stored.episode) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            val candidates = sources.filter { it.type != 32 }.map { source -> async {
                try { streams(source, time, stored.season, stored.episode) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            } }.awaitAll().flatten().distinctBy { it.url }.take(24)
            val direct = coroutineScope {
                val limit = Semaphore(4)
                candidates.map { stream -> async { limit.withPermit { stream.takeIf { available(it, ref) } } } }.awaitAll().filterNotNull()
            }
            Playback(direct,native.await())
            }
        } ?: error("видеосерверы не ответили вовремя")
        if (playback.direct.isEmpty() && playback.native == null) error("доступных видеоссылок для этой серии или фильма нет в текущей сети")
        playback.native?.let { native ->
            for (variant in native.variants) {
                callback(newExtractorLink(name,"Zona · ${variant.dimensions} · ${native.context.voices.size} озвучек · DASH",
                    native.url + "#kg-${variant.width}x${variant.height}",ExtractorLinkType.DASH) {
                    quality = variant.height; referer = ref; headers = this@ZonaProvider.headers
                    extractorData = native.context.copy(width=variant.width,height=variant.height).toJson()
                })
            }
            native.subtitles.distinctBy { it.url }.forEach { sub -> subtitleCallback(SubtitleFile(sub.name,sub.url)) }
        }
        playback.direct.forEach { stream ->
            val hls = URI(stream.url).path.endsWith(".m3u8")
            callback(newExtractorLink(name, listOf("Zona", stream.translation, stream.resolution, if (hls) "HLS" else "MP4").filter(String::isNotBlank).joinToString(" · "),
                stream.url, if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                referer = ref; quality = StreamTools.quality(stream.resolution); headers = this@ZonaProvider.headers
            })
            stream.subtitles.forEach { sub ->
                val url = sub.url ?: sub.source?.url
                if (url != null && StreamTools.isHttp(url)) subtitleCallback(SubtitleFile(sub.name.ifBlank { sub.language.ifBlank { "Субтитры" } }, url))
            }
        }
        return true
    }

    data class Catalog(@JsonProperty("data") val data: List<Title> = emptyList(), @JsonProperty("links") val links: Links? = null)
    data class Links(@JsonProperty("next") val next: String? = null)
    data class Detail(@JsonProperty("data") val data: Title? = null)
    data class Title(@JsonProperty("slug") val slug: String = "", @JsonProperty("title") val title: String = "",
        @JsonProperty("cover_url") val cover: String? = null, @JsonProperty("year") val year: Int? = null,
        @JsonProperty("description") val description: String? = null, @JsonProperty("kp_id") val kpId: Int? = null,
        @JsonProperty("is_trailer") val trailer: Boolean = false, @JsonProperty("meta") val meta: Meta? = null,
        @JsonProperty("seasons") val seasons: List<Season> = emptyList(), @JsonProperty("episodes") val episodes: List<Episode> = emptyList())
    data class Meta(@JsonProperty("tags") val tags: List<Tag> = emptyList())
    data class Tag(@JsonProperty("slug") val slug: String = "", @JsonProperty("title") val title: String = "", @JsonProperty("type") val type: String = "")
    data class Season(@JsonProperty("part") val part: Int = 0, @JsonProperty("episode_count") val count: Int = 0, @JsonProperty("is_banned") val banned: Boolean = false)
    data class Episode(@JsonProperty("part") val part: Int = 0, @JsonProperty("title") val title: String = "", @JsonProperty("cover_url") val cover: String? = null, @JsonProperty("is_banned") val banned: Boolean = false)
    data class PlaybackRef(@JsonProperty("page") val page: String, @JsonProperty("kpId") val kpId: Int,
        @JsonProperty("season") val season: Int? = null, @JsonProperty("episode") val episode: Int? = null)
    data class Sources(@JsonProperty("data") val data: List<Source> = emptyList())
    data class Source(@JsonProperty("video_source_type_id") val type: Int = 0, @JsonProperty("kinopoisk_id") val kpId: Int = 0,
        @JsonProperty("download_link_key") val key: String = "", @JsonProperty("episode_key") val episode: String? = null)
    data class Stream(@JsonProperty("url") val url: String = "", @JsonProperty("resolution") val resolution: String = "",
        @JsonProperty("translation") val translation: String = "", @JsonProperty("isTrailer") val trailer: Boolean = false,
        @JsonProperty("subtitles") val subtitles: List<Sub> = emptyList())
    data class Sub(@JsonProperty("name") val name: String = "", @JsonProperty("language") val language: String = "",
        @JsonProperty("url") val url: String? = null, @JsonProperty("source") val source: SubSource? = null)
    data class SubSource(@JsonProperty("url") val url: String? = null)
}
