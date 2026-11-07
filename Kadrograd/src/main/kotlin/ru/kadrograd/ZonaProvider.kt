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
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ZonaProvider(baseUrl: String) : QualityProvider() {
    override var mainUrl = baseUrl
    override var name = "Кадроград · Zona"
    override var lang = "ru"
    override val hasMainPage = true
    override val hasDownloadSupport get() = ClientCapabilities.adaptiveDownloads
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.AnimeMovie)
    override val mainPage = mainPageOf(
        "movies" to "Фильмы", "series" to "Сериалы",
        "movies/filter?genre=multfilm" to "Мультфильмы",
        "series/filter?genre=multfilm" to "Мультсериалы",
        "series/filter?genre=anime" to "Аниме-сериалы", "movies/filter?genre=anime" to "Аниме-фильмы",
        "verified-4k" to "Фильмы в 4K"
    )
    internal var videoApiOrigins = listOf("https://apiw1.mzona.net", "https://apir1.mzona.net")
    internal var streamApiOrigins = listOf("https://vsw01.zonasearch.com", "https://vsr01.zonasearch.com")
    internal var sourceBudgetMillis = 30_000L
    internal var posterOrigins = listOf("https://st.kp.yandex.net/images/film_big",
        "https://kinopoiskapiunofficial.tech/images/posters/kp")
    internal var badges = QualityBadges()
    private val uhdCatalog = UhdCatalog()
    internal var uhdIndexUrl: String? = "https://raw.githubusercontent.com/davdan229/kadrograd/uhd-catalog/catalog.json"
        .takeIf { mainUrl == "https://w1.zona.im" }
    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val headers get() = mapOf("User-Agent" to ua)
    private val nativeTracks by lazy { ZonaTracks({streamApiOrigins},ua) }
    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor? =
        ZonaTracks.context(extractorLink)?.let(ZonaTracks::interceptor)
    private data class VerifiedStream(val stream: Stream, val uhd: ZonaTracks.Variant?) {
        val quality get() = uhd?.playbackQuality ?: StreamTools.quality(stream.resolution).takeUnless { it >= 2160 } ?: 0
    }
    private data class Playback(val direct: List<VerifiedStream>, val native: List<ZonaTracks.Found>)
    private fun escaped(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun error(message: String): Nothing = throw ErrorLoadingException("Zona: $message")

    private suspend inline fun <reified T> json(url: String): T =
        tryParseJson<T>(SourceHttp.getCatalog("Zona", url, referer = "$mainUrl/",
            headers = headers + ("Accept" to "application/json"))) ?: error("изменился формат каталога")

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
    private suspend fun detailPoster(url: String?, kpId: Int?): String? {
        val original = url?.takeIf(StreamTools::isHttp)
        val large = original?.replace(Regex("""^(https://img\d+\.zonapic\.com/images/)film_240/"""), "$1film_big/")
        if (original != null && large == original) return original
        suspend fun suitable(candidate: String): Boolean = try {
            SourceHttp.readEndpoint(candidate, headers + ("Range" to "bytes=0-2097151"), 2) {
                val image = it.header("Content-Type")?.startsWith("image/", ignoreCase = true) != false
                val source = it.body.source()
                if (!image || source.request(2_097_153)) false else {
                    val prefix = source.readByteArray(minOf(source.buffer.size, 12))
                    (prefix.size >= 3 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() && prefix[2] == 0xff.toByte()) ||
                        (prefix.size >= 8 && prefix.copyOfRange(0,8).contentEquals(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10))) ||
                        (prefix.size >= 6 && String(prefix,0,6,Charsets.US_ASCII) in setOf("GIF87a","GIF89a")) ||
                        (prefix.size >= 12 && String(prefix,0,4,Charsets.US_ASCII) == "RIFF" && String(prefix,8,4,Charsets.US_ASCII) == "WEBP")
                }
            }
        } catch (_: java.io.IOException) { false }
        if (large != null && suitable(large)) return large
        if (kpId != null && kpId > 0) {
            val candidates = posterOrigins.map { "$it/$kpId.jpg" }
            val fallback = firstReachable(candidates, alternateDelayMillis = 400) { candidate ->
                require(suitable(candidate)) { "Poster endpoint did not return a usable image" }
                candidate
            }
            if (fallback != null) return fallback
        }
        return original
    }
    private fun card(row: Title, series: Boolean, category: String): SearchResponse? {
        if (!validSlug(row.slug) || row.title.isBlank() || row.trailer) return null
        return posterCard(row.title, mainUrl + pagePath(series, row.slug), type(series, row.meta?.tags.orEmpty(), category),
            row.cover, row.year)
    }
    private fun posterCard(title: String, url: String, kind: TvType, poster: String?, releaseYear: Int?): SearchResponse {
        // CloudStream renders its Dub badge only for AnimeSearchResponse. Keep the real content type.
        if (badges.dubbed(url)) return newAnimeSearchResponse(title, url, kind) {
            posterUrl = poster; year = releaseYear; quality = badges.quality(url)
            addDubStatus(true)
        }
        return newMovieSearchResponse(title, url, kind) {
            posterUrl = poster; year = releaseYear; quality = badges.quality(url)
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        require(page > 0)
        if (mainPage.none { it.data == request.data }) error("неизвестный раздел")
        if (request.data == "verified-4k") {
            val prefix = "$mainUrl/movies/"
            val indexed = uhdCatalog.movies(uhdIndexUrl).associateBy { it.slug }
            val openCards = coroutineScope {
                BlenderUhd.films.map { film -> async {
                    val title = try { details(false, film.slug) }
                        catch (_: ErrorLoadingException) { return@async null }
                    if (title.kpId != film.kpId) return@async null
                    val video = BlenderUhd.uhd(film) ?: return@async null
                    badges.observe(prefix + film.slug, listOf(video.variant))
                    card(title, false, "")?.let { film.slug to it }
                } }.awaitAll().filterNotNull().toMap()
            }
            val movies = badges.fourKPages().filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }.filter(::validSlug)
            val current = (indexed.keys + openCards.keys + movies).toList()
            val start = (page.toLong() - 1) * 12
            val slugs = current.drop(start.coerceAtMost(current.size.toLong()).toInt()).take(12)
            val cards = coroutineScope {
                val slots = Semaphore(3)
                slugs.map { slug -> async {
                    slots.withPermit {
                        try {
                            openCards[slug] ?: details(false, slug).let { title ->
                                val recorded = indexed[slug]
                                if (recorded != null && title.kpId != recorded.kpId) return@withPermit null
                                if (recorded != null) badges.observe(prefix + slug,
                                    listOf(ZonaTracks.Variant(recorded.width, recorded.height)))
                                card(title, false, "")
                            }
                        }
                        catch (_: ErrorLoadingException) { null }
                    }
                } }.awaitAll().filterNotNull()
            }
            return newHomePageResponse(request.name, cards, hasNext = start + slugs.size < current.size)
        }
        val url = "$mainUrl/api/${request.data}${if ('?' in request.data) '&' else '?'}page=$page"
        val result = json<Catalog>(url)
        return newHomePageResponse(request.name, result.data.mapNotNull { card(it, request.data.startsWith("series"), request.data) },
            hasNext = !result.links?.next.isNullOrBlank())
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val input = query.trim()
        if (input.isBlank()) return emptyList()
        val results = searchOnce(input)
        val title = ZonaSearch.normalize(input)
        if (results.any {
            val found = ZonaSearch.normalize(it.name)
            found == title || found.startsWith("$title ") || title.startsWith("$found ")
        }) {
            return results.sortedByDescending { ZonaSearch.normalize(it.name) == title }
        }
        return coroutineScope {
            ZonaSearch.probes(input).map { probe ->
                async {
                    withTimeoutOrNull(4_000) {
                        try { searchOnce(probe) }
                        catch (_: ErrorLoadingException) { emptyList() }
                    }.orEmpty()
                }
            }.awaitAll().flatten().distinctBy { it.url }.mapNotNull { result ->
                ZonaSearch.distance(title, ZonaSearch.normalize(result.name))?.let { it to result }
            }.sortedBy { it.first }.map { it.second }.let { (it + results).distinctBy { card -> card.url } }
        }
    }

    private suspend fun searchOnce(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${escaped(query)}"
        // The compressed HTML response can stall after HTTP 200; the JSON endpoints use the normal encoding.
        val html = SourceHttp.getCatalogOrNull("Zona", url, referer = "$mainUrl/",
            headers = headers + mapOf("Accept" to "text/html", "Accept-Encoding" to "identity"))
            ?: return suggestions(query) ?: error("не удалось получить ответ поиска через эту сеть")
        val doc = org.jsoup.Jsoup.parse(html,url)
        if (!doc.title().contains("Результаты поиска", true)) error("поиск недоступен или изменился")
        return doc.select("a[title][href]").mapNotNull { a ->
            val path = a.attr("href")
            if (!Regex("/(movies|tvseries)/[a-z0-9]+(?:-[a-z0-9]+)*").matches(path) || a.selectFirst("img") == null) return@mapNotNull null
            val title = a.attr("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val year = Regex("\\((\\d{4})\\)").find(a.selectFirst("img")?.attr("alt").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            posterCard(title, mainUrl + path, if (path.startsWith("/tvseries/")) TvType.TvSeries else TvType.Movie,
                a.selectFirst("img")?.attr("src")?.let(::fixUrl), year)
        }.distinctBy { it.url }
    }
    internal suspend fun suggestions(query: String): List<SearchResponse>? = withContext(Dispatchers.IO) {
        // Public Next.js search box action. If the site changes the action, the normal HTML search still works.
        val request = Request.Builder().url("$mainUrl/")
            .header("Next-Action","40e13530e78a380c4fcbb1ee15fa5df27b78cb9777")
            .header("Accept","text/x-component").header("Origin",mainUrl).header("Referer","$mainUrl/")
            .header("User-Agent",ua)
            .post(listOf(mapOf("query" to query)).toJson().toRequestBody("text/plain; charset=utf-8".toMediaType())).build()
        val client = app.baseClient.newBuilder().callTimeout(5,TimeUnit.SECONDS).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.header("Content-Type").orEmpty().substringBefore(';') != "text/x-component")
                    return@withContext null
                val body = response.body.source()
                if (body.request(128_001)) return@withContext null
                val lines = body.readUtf8().lineSequence().toList()
                val envelope = lines.firstOrNull { it.startsWith("0:") }?.substringAfter(':') ?: return@withContext null
                val pointer = tryParseJson<com.fasterxml.jackson.databind.JsonNode>(envelope)?.path("a")?.asText()?.removePrefix("\$@")
                    ?.takeIf { it.matches(Regex("[a-f0-9]{1,8}")) } ?: return@withContext null
                val row = lines.firstOrNull { it.startsWith("$pointer:") }?.substringAfter(':') ?: return@withContext null
                tryParseJson<List<Suggestion>>(row)?.take(50)?.mapNotNull { hit ->
                    if (hit.published != 1 || hit.abused != 0 || hit.ownAbused != 0 || hit.manifest != 0 ||
                        hit.type !in setOf("movie","series")) return@mapNotNull null
                    card(Title(slug=hit.slug,title=hit.title,cover=hit.cover,year=hit.year),hit.type=="series","")
                }?.distinctBy { it.url }
            }
        } catch (_: java.io.IOException) { null }
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
    private fun LoadResponse.applyDetails(title: Title, poster: String?) {
        posterUrl = poster
        year = title.year
        plot = title.description
        tags = title.meta?.tags?.filter { it.type == "genre" }?.map { it.title }
        score = Score.from10(title.rating?.takeIf { it in 0.1..10.0 })
        duration = title.duration?.takeIf { it > 0 }
        contentRating = title.ageLimit?.takeIf { it in 0..21 }?.let { "$it+" }
        actors = title.meta?.actors.orEmpty().filter { it.name.isNotBlank() }.distinctBy { it.name }.take(24).map {
            ActorData(Actor(it.name, it.cover?.takeIf(StreamTools::isHttp)), roleString = it.role?.takeIf(String::isNotBlank))
        }.takeIf { it.isNotEmpty() }
    }

    override suspend fun load(url: String): LoadResponse {
        val (series, slug) = identity(url)
        val title = details(series, slug)
        val ref = mainUrl + pagePath(series, slug)
        val kind = type(series, title.meta?.tags.orEmpty())
        val poster = detailPoster(title.cover, title.kpId)
        if (!series) return newMovieLoadResponse(title.title, ref, kind, PlaybackRef(ref, title.kpId!!).toJson()) {
            applyDetails(title, poster)
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
                                name = episode.title?.takeIf(String::isNotBlank) ?: "Серия ${episode.part}"
                                this.season = season.part; this.episode = episode.part
                                posterUrl = episode.cover?.takeIf(String::isNotBlank)
                            }, fix = false)
                        }
                    }
                } }.awaitAll().flatten()
            }
        } ?: error("сезоны не загрузились вовремя; попробуйте открыть карточку ещё раз")
        if (episodes.isEmpty()) error("сайт не опубликовал доступных серий")
        return newTvSeriesLoadResponse(title.title, ref, kind, episodes.distinctBy { it.season to it.episode }) {
            applyDetails(title, poster)
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
    private suspend fun clock(): Long = firstReachable(videoApiOrigins) { origin ->
        SourceHttp.readEndpoint("$origin/getTime",headers,4) { serverTime(it.header("Date")) }
    } ?: error("сервер времени недоступен через эту сеть")
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
    internal suspend fun discoverUhd(kpId: Int): List<ZonaTracks.Variant> {
        require(kpId > 0)
        val time = clock()
        val result = tryParseJson<Sources>(transport(videoApiOrigins, "/getVideoSources",
            "movie_source_types=32&kinopoisk_id=$kpId&episode_key=&trailer=false", time))
            ?: error("изменился формат ответа плеера")
        val source = result.data.firstOrNull { it.type == 32 && it.kpId == kpId && it.key.matches(Regex("\\d+")) }
            ?: return emptyList()
        val found = java.util.concurrent.ConcurrentLinkedQueue<ZonaTracks.Found>()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Exception>()
        nativeTracks.load(source.key, null, null, found::add, formats = setOf("dasha"),
            minimumWidth = 3840, onFailure = failures::add)
        if (failures.isNotEmpty()) throw failures.first()
        return found.flatMap { it.variants }.filter { it.isFourK }.distinct()
    }
    private suspend fun available(stream: Stream, ref: String,
        onUhd: ((ZonaTracks.Variant) -> Unit)? = null, requireClearMedia: Boolean = false): Boolean {
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
                if (playlist.contains("#EXT-X-STREAM-INF:")) {
                    if (onUhd != null) {
                        val variants = runCatching { ZonaHls.parse(playlist, url).videos }.getOrDefault(emptyList())
                        val uhd = variants.filter { it.variant.isFourK }.distinctBy { it.url }.take(2)
                            .firstOrNull { available(stream.copy(url = it.url), ref, requireClearMedia = true) }
                        if (uhd != null) {
                            onUhd(uhd.variant)
                            return true
                        }
                        val lower = variants.firstOrNull { !it.variant.isFourK }
                        if (lower != null) {
                            url = lower.url
                            return@repeat
                        }
                    }
                    url = next
                }
                else if (playlist.contains("#EXTINF:")) {
                    return SourceHttp.get("Zona", next, referer = ref, headers = headers + ("Range" to "bytes=0-511"), timeoutSeconds = 3).okhttpResponse.use { response ->
                        withContext(Dispatchers.IO) {
                            val prefix = response.body.source().readByteArray(32)
                            val text = prefix.toString(Charsets.US_ASCII).trimStart()
                            !text.startsWith('<') && !text.startsWith('{') && !text.startsWith('[') &&
                                (prefix[0] == 0x47.toByte() || text.startsWith("ID3") ||
                                    String(prefix, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "styp", "moof") ||
                                    (!requireClearMedia && playlist.contains("METHOD=AES-128")))
                        }
                    }
                } else return false
            }
            false
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { false }
    }
    override suspend fun loadSourceLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val stored = tryParseJson<PlaybackRef>(data) ?: return false
        val (series, slug) = identity(stored.page)
        if (stored.kpId <= 0 || (series && (stored.season == null || stored.episode == null || stored.season <= 0 || stored.episode <= 0))) return false
        val ref = mainUrl + pagePath(series, slug)
        val current = details(series, slug, stored.season)
        if (current.kpId != stored.kpId || (series && current.episodes.none { it.part == stored.episode && !it.banned })) error("выбранная серия больше не доступна")
        val openFilm = if (series || isCasting) null else BlenderUhd.films.firstOrNull { it.slug == slug && it.kpId == stored.kpId }
        val key = if (series) "S${stored.season.toString().padStart(2, '0')}E${stored.episode.toString().padStart(2, '0')}" else ""
        val readyDirect = java.util.concurrent.ConcurrentLinkedQueue<VerifiedStream>()
        val readyNative = java.util.concurrent.ConcurrentLinkedQueue<ZonaTracks.Found>()
        val readyOpen = java.util.concurrent.ConcurrentLinkedQueue<BlenderUhd.Video>()
        val completed = withTimeoutOrNull(sourceBudgetMillis) {
            coroutineScope {
                if (openFilm != null) launch { readyOpen.addAll(BlenderUhd.choices(openFilm)) }
                val time = try { clock() } catch (failure: ErrorLoadingException) {
                    if (openFilm == null) throw failure
                    return@coroutineScope
                }
                suspend fun candidates(types: String): List<Source> =
                    (tryParseJson<Sources>(transport(videoApiOrigins, "/getVideoSources",
                        "movie_source_types=$types&kinopoisk_id=${stored.kpId}&episode_key=$key&trailer=false", time))
                        ?: error("изменился формат ответа плеера")).data.filter {
                        it.kpId == stored.kpId && it.key.isNotBlank() && it.key != "0" && (!series || it.episode == key)
                    }
                if (!isCasting) launch {
                    try {
                        // The mixed query may omit the main player. Only accept the exact requested title/episode and type.
                        val source = candidates("32").firstOrNull { it.type == 32 }
                        if (source != null) nativeTracks.load(source.key,stored.season,stored.episode, readyNative::add)
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
                val sources = try { candidates("1,3,48") }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                val limit = Semaphore(4)
                val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                val remaining = java.util.concurrent.atomic.AtomicInteger(24)
                sources.filter { it.type in setOf(1,3,48) }.take(6).forEach { source -> launch {
                    val candidates = try { streams(source, time, stored.season, stored.episode) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { emptyList() }
                    candidates.take(24).forEach { stream ->
                        if (seen.add(stream.url) && remaining.getAndDecrement() > 0) launch {
                            limit.withPermit {
                                var uhd: ZonaTracks.Variant? = null
                                if (available(stream, ref, onUhd = { uhd = it })) readyDirect.add(VerifiedStream(stream, uhd))
                            }
                        }
                    }
                } }
            }
            true
        } ?: false
        val playback = Playback(readyDirect.sortedByDescending { it.quality },
            readyNative.sortedWith(compareBy<ZonaTracks.Found> { it.av1 }.thenBy { it.context.kind }))
        badges.observe(ref,playback.native.flatMap { it.variants } + playback.direct.mapNotNull { it.uhd } + readyOpen.map { it.variant },
            playback.native.flatMap { it.context.voices.values } + playback.direct.map { it.stream.translation })
        if (playback.direct.isEmpty() && playback.native.isEmpty() && readyOpen.isEmpty()) {
            if (!completed) error("видеосерверы не ответили вовремя")
            error("доступных видеоссылок для этой серии или фильма нет в текущей сети")
        }
        readyOpen.forEach { video ->
            callback(newExtractorLink(name, "Blender Foundation · Оригинал · ${video.variant.dimensions} · WebM",
                video.url, ExtractorLinkType.VIDEO) {
                quality = video.quality
                headers = BlenderUhd.headers
            })
        }
        playback.native.forEach { native ->
            val hls = native.context.kind == "zona-hls-v1"
            val format = native.format
            for (variant in native.variants) {
                val tracks = native.voicesFor(variant)
                val audio = tracks.values.distinct().singleOrNull() ?: "Выбор озвучки"
                callback(newExtractorLink(name,"Zona · ${variant.dimensions} · $audio · $format",
                    native.url + "#kg-${variant.width}x${variant.height}",if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.DASH) {
                    quality = variant.playbackQuality; referer = ref; headers = this@ZonaProvider.headers
                    extractorData = native.context.copy(width=variant.width,height=variant.height,voices=tracks).toJson()
                })
            }
        }
        // The adaptive downloader needs a link locked to both resolution and voice.
        val voices = ZonaTracks.voiceChoices(playback.native, allQualities = ClientCapabilities.adaptiveDownloads)
        if (voices.size > 1) voices.forEachIndexed { index, voice ->
            val (native,variant,id,label) = voice
            val hls = native.context.kind == "zona-hls-v1"
            val format = native.format
            callback(newExtractorLink(name,"Zona · $label · ${variant.dimensions} · $format",
                native.url + "#kg-${variant.width}x${variant.height}-voice-$index",if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.DASH) {
                quality = variant.playbackQuality; referer = ref; headers = this@ZonaProvider.headers
                extractorData = native.context.copy(width=variant.width,height=variant.height,voices=mapOf(id to label)).toJson()
            })
        }
        playback.native.flatMap { it.subtitles }.distinctBy { it.url }
            .forEach { sub -> subtitleCallback(SubtitleFile(sub.name,sub.url)) }
        playback.direct.forEach { verified ->
            val stream = verified.stream
            val hls = URI(stream.url).path.endsWith(".m3u8")
            val resolution = verified.uhd?.dimensions ?: stream.resolution.takeUnless { StreamTools.quality(it) >= 2160 }.orEmpty()
            callback(newExtractorLink(name, listOf("Zona", stream.translation, resolution, if (hls) "HLS" else "MP4").filter(String::isNotBlank).joinToString(" · "),
                stream.url, if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                referer = ref; quality = verified.quality; headers = this@ZonaProvider.headers
            })
            stream.subtitles.forEach { sub ->
                val url = sub.url ?: sub.source?.url
                if (url != null && StreamTools.isHttp(url)) subtitleCallback(SubtitleFile(sub.name.ifBlank { sub.language.ifBlank { "Субтитры" } }, url))
            }
        }
        return true
    }

    data class Catalog(@JsonProperty("data") val data: List<Title> = emptyList(), @JsonProperty("links") val links: Links? = null)
    data class Suggestion(@JsonProperty("slug") val slug: String = "", @JsonProperty("title") val title: String = "",
        @JsonProperty("type") val type: String = "", @JsonProperty("year") val year: Int? = null,
        @JsonProperty("cover_url") val cover: String? = null, @JsonProperty("is_published") val published: Int = 0,
        @JsonProperty("is_abused") val abused: Int = 0, @JsonProperty("is_own_abused") val ownAbused: Int = 0,
        @JsonProperty("is_manifest") val manifest: Int = 0)
    data class Links(@JsonProperty("next") val next: String? = null)
    data class Detail(@JsonProperty("data") val data: Title? = null)
    data class Title(@JsonProperty("slug") val slug: String = "", @JsonProperty("title") val title: String = "",
        @JsonProperty("cover_url") val cover: String? = null, @JsonProperty("year") val year: Int? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("duration") val duration: Int? = null, @JsonProperty("age_limit") val ageLimit: Int? = null,
        @JsonProperty("description") val description: String? = null, @JsonProperty("kp_id") val kpId: Int? = null,
        @JsonProperty("is_trailer") val trailer: Boolean = false, @JsonProperty("meta") val meta: Meta? = null,
        @JsonProperty("seasons") val seasons: List<Season> = emptyList(), @JsonProperty("episodes") val episodes: List<Episode> = emptyList())
    data class Meta(@JsonProperty("tags") val tags: List<Tag> = emptyList(),
        @JsonProperty("actors") val actors: List<CastMember>? = null)
    data class CastMember(@JsonProperty("name") val name: String = "",
        @JsonProperty("cover_url") val cover: String? = null, @JsonProperty("role") val role: String? = null)
    data class Tag(@JsonProperty("slug") val slug: String = "", @JsonProperty("title") val title: String = "", @JsonProperty("type") val type: String = "")
    data class Season(@JsonProperty("part") val part: Int = 0, @JsonProperty("episode_count") val count: Int = 0, @JsonProperty("is_banned") val banned: Boolean = false)
    data class Episode(@JsonProperty("part") val part: Int = 0, @JsonProperty("title") val title: String? = null, @JsonProperty("cover_url") val cover: String? = null, @JsonProperty("is_banned") val banned: Boolean = false)
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
