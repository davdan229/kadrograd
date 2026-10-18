package ru.kadrograd

// Adapted from lxnhere / resoul; see NOTICE.md (GPL-3.0).

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Element

/**
 * Filmix CloudStream provider.
 *
 * Logic ported from the open-source Swift package
 * [resoul/filmix](https://github.com/resoul/filmix) (MIT/demo educational client).
 * Does NOT use Lampa / online_mod code.
 */
class FilmixProvider(baseUrl: String) : MainAPI() {
    override var mainUrl = baseUrl
    private val voiceSlots = Semaphore(3)
    private val http by lazy { Session(app.baseClient).apply { defaultTimeOut = 12L; defaultHeaders = app.defaultHeaders; responseParser = app.responseParser } }
    override var name = "Кадроград · Filmix (экспериментальный)"
    override val hasMainPage = true
    override var lang = "ru"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override val mainPage get() = mainPageOf(
        "$mainUrl/films" to "Фильмы",
        "$mainUrl/serials" to "Сериалы",
        "$mainUrl/multfilms" to "Мультфильмы",
        "$mainUrl/multserialy" to "Мультсериалы",
    )

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    private val qualityOrder = listOf("4K UHD", "1080p Ultra+", "1080p", "720p", "480p")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page"
        val doc = http.get(url, headers = mapOf("User-Agent" to ua)).document
        val home = doc.select("#dle-content article.shortstory, article.shortstory").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val data = mapOf(
            "scf" to "fx",
            "story" to query,
            "search_start" to "0",
            "do" to "search",
            "subaction" to "search",
            "years_ot" to "1880",
            "years_do" to java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString(),
            "kpi_ot" to "1",
            "kpi_do" to "10",
            "imdb_ot" to "1",
            "imdb_do" to "10",
            "sort_name" to "",
            "sort_date" to "",
            "sort_favorite" to "",
            "simple" to "1",
        )
        val html = http.post(
            "$mainUrl/engine/ajax/sphinx_search.php",
            data = data,
            headers = mapOf(
                "User-Agent" to ua,
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/search/",
            ),
        ).text
        return org.jsoup.Jsoup.parse(html)
            .select("#dle-content article.shortstory, article.shortstory")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = this.attr("data-id").toIntOrNull() ?: return null
        val href = this.selectFirst("div.short a.watch, a.watch")?.attr("href")
            ?: this.selectFirst("a[href]")?.attr("href")
            ?: return null
        val url = fixUrl(href)
        val title = this.selectFirst("div.full div.title-one-line h2.name")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("h2.name")?.text()?.trim()
            ?: return null
        val poster = this.selectFirst("div.short a.fancybox")?.attr("href")
            ?: this.selectFirst("div.short img")?.attr("src")
        val posterUrl = poster?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val year = this.selectFirst(".item.year .item-content")?.text()?.toIntOrNull()
        val isSeries = detectIsSeries(url)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                this.id = id
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.id = id
            }
        }
    }

    private fun detectIsSeries(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/serial") || u.contains("/multserial") || u.contains("/serie")
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = http.get(url, headers = mapOf("User-Agent" to ua)).document
        val article = doc.selectFirst("#dle-content article.fullstory, #dle-content article, article.fullstory")
            ?: throw ErrorLoadingException("Filmix: карточка недоступна или изменилась разметка")

        val postId = article.attr("data-id").toIntOrNull()
            ?: Regex("""/(\d+)-""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: throw ErrorLoadingException("Filmix: не найден номер фильма")

        val title = article.selectFirst("h1.name")?.text()?.trim().orEmpty()
            .ifBlank { doc.selectFirst("title")?.text()?.trim().orEmpty() }
        val poster = article.selectFirst(".short a.fancybox")?.attr("href")
            ?: article.selectFirst(".short img.poster, .short img")?.attr("src")
        val posterUrl = poster?.let { if (it.startsWith("http")) it else fixUrl(it) }
        val plot = article.selectFirst(".about .full-story, [itemprop=description]")?.text()?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(article.selectFirst("time.date, .item.year .item-content")?.text().orEmpty())
            ?.value?.toIntOrNull()
        val tags = article.select(".item.category a, .item:contains(Жанр) a").map { it.text() }
        val isSeries = detectIsSeries(url) ||
            article.select(".short span.not-movie").isNotEmpty()

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, FilmixLink(postId, false).toJson()) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }

        val translations = fetchTranslations(postId, isSeries = true)
        val episodes = mutableListOf<Episode>()
        val seenEpisodes = HashSet<Pair<Int, Int>>()
        for (translation in translations) {
            for (season in translation.seasons) {
                val seasonNum = parseIndex(season.title) ?: continue
                for (ep in season.episodes) {
                    val epNum = parseIndex(ep.title) ?: parseIndex(ep.id) ?: continue
                    if (!seenEpisodes.add(seasonNum to epNum)) continue
                    episodes += newEpisode(FilmixLink(postId, true, seasonNum, epNum).toJson(), {
                        name = "Серия $epNum"; this.season = seasonNum; episode = epNum
                    }, fix = false)
                }
            }
        }
        episodes.sortWith(compareBy({ it.season }, { it.episode }))
        if (episodes.isEmpty()) throw ErrorLoadingException("Filmix: список серий недоступен. Возможно, источник требует вход.")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val link = tryParseJson<FilmixLink>(data) ?: return false
        val translations = fetchTranslations(link.postId, link.isSeries)
        if (translations.isEmpty()) throw ErrorLoadingException("Filmix не выдал видеоссылки. Проверьте доступность этого фильма на сайте источника.")

        var any = false
        if (!link.isSeries) {
            translations.forEach { tr ->
                sortedQualities(tr.streams).forEach { (quality, streamUrl) ->
                    any = true
                    emitLink(tr.studio, quality, streamUrl, callback)
                }
            }
            return any
        }

        val season = link.season ?: 1
        val episode = link.episode ?: 1
        translations.forEach { tr ->
            val seasonDto = tr.seasons.firstOrNull { parseIndex(it.title) == season }
                ?: return@forEach
            val epDto = seasonDto.episodes.firstOrNull { parseIndex(it.title) == episode || parseIndex(it.id) == episode }
                ?: return@forEach
            sortedQualities(epDto.streams).forEach { (quality, streamUrl) ->
                any = true
                emitLink(tr.studio, quality, streamUrl, callback)
            }
        }
        return any
    }

    private suspend fun emitLink(
        studio: String,
        quality: String,
        streamUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (!StreamTools.isHttp(streamUrl)) return
        val type = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
        callback(
            newExtractorLink(
                source = name,
                name = "$studio · $quality · ${if (type == ExtractorLinkType.M3U8) "HLS" else "MP4"}",
                url = streamUrl,
                type = type,
            ) {
                this.referer = "$mainUrl/"
                this.quality = qualityToInt(quality)
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                )
            }
        )
    }

    private suspend fun fetchTranslations(postId: Int, isSeries: Boolean): List<Translation> {
        // Warm cookies (x-a-key) — player-data returns 403 without them.
        val warm = http.get("$mainUrl/", headers = mapOf("User-Agent" to ua))
        val xaKey = warm.cookies["x-a-key"] ?: "sinatra"
        val ts = System.currentTimeMillis()
        val response = http.post(
            "$mainUrl/api/movies/player-data?t=$ts",
            data = mapOf(
                "post_id" to "$postId",
                "showfull" to "true",
            ),
            headers = mapOf(
                "User-Agent" to ua,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "x-a-key" to xaKey,
            ),
        ).text

        val dto = tryParseJson<FilmixVideoDTO>(response) ?: return emptyList()
        val videoMap = dto.message?.translations?.video.orEmpty()
        if (videoMap.isEmpty()) return emptyList()

        if (!isSeries) {
            return videoMap.mapNotNull { (studio, encoded) ->
                val raw = decodeTokens(encoded)
                val parts = raw.split(',').filter { it.isNotBlank() }
                val streams = decodeQualityMap(parts)
                if (streams.isEmpty()) null else Translation(studio, streams, emptyList())
            }.sortedBy { it.studio }
        }

        return videoMap.toList().amap { (studio, encoded) ->
            voiceSlots.withPermit { try {
                val secondUrl = decodeTokens(encoded)
                if (!StreamTools.isHttp(secondUrl)) return@withPermit null
                val nested = http.get(
                    secondUrl,
                    headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/"),
                ).text
                val json = decodeTokens(nested)
                val serials = tryParseJson<List<FilmixSerialDTO>>(json).orEmpty()
                if (serials.isEmpty()) return@withPermit null
                val seasons = serials.map { serial ->
                    SeasonData(
                        title = serial.title,
                        episodes = serial.folder.map { folder ->
                            EpisodeData(
                                title = folder.title,
                                id = folder.id,
                                streams = decodeQualityMap(
                                    folder.file.split(',').filter { it.isNotBlank() }
                                ),
                            )
                        },
                    )
                }
                Translation(studio, emptyMap(), seasons)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } }
        }.filterNotNull().sortedBy { it.studio }
    }

    private fun sortedQualities(streams: Map<String, String>): List<Pair<String, String>> {
        val known = qualityOrder.filter { streams.containsKey(it) }
        val unknown = streams.keys.filter { it !in qualityOrder }.sorted()
        return (known + unknown).mapNotNull { q -> streams[q]?.let { q to it } }
    }

    private fun qualityToInt(quality: String) = StreamTools.quality(quality)

    private fun parseIndex(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        return Regex("""(\d+)""").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun decodeTokens(input: String) = StreamTools.decodeFilmix(input)

    private fun decodeQualityMap(parts: List<String>): Map<String, String> =
        StreamTools.labeledUrls(parts.joinToString(",")).associate { it.label to it.url }

    data class FilmixLink(
        @JsonProperty("postId") val postId: Int,
        @JsonProperty("isSeries") val isSeries: Boolean,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
    )

    private data class Translation(
        val studio: String,
        val streams: Map<String, String>,
        val seasons: List<SeasonData>,
    )

    private data class SeasonData(
        val title: String,
        val episodes: List<EpisodeData>,
    )

    private data class EpisodeData(
        val title: String,
        val id: String,
        val streams: Map<String, String>,
    )

    private data class FilmixVideoDTO(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("message") val message: FilmixVideoMessageDTO? = null,
    )

    private data class FilmixVideoMessageDTO(
        @JsonProperty("translations") val translations: FilmixVideoTranslateDTO? = null,
    )

    private data class FilmixVideoTranslateDTO(
        @JsonProperty("video") val video: Map<String, String>? = null,
    )

    private data class FilmixSerialDTO(
        @JsonProperty("title") val title: String = "",
        @JsonProperty("folder") val folder: List<FilmixFolderDTO> = emptyList(),
    )

    private data class FilmixFolderDTO(
        @JsonProperty("title") val title: String = "",
        @JsonProperty("id") val id: String = "",
        @JsonProperty("file") val file: String = "",
    )
}
