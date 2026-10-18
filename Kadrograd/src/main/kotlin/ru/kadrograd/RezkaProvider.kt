package ru.kadrograd

// Adapted from hexated / lxnhere; see NOTICE.md (GPL-3.0).

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Session
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * HDrezka — tokenless scrape of public mirrors (n0madic/go-hdrezka defaults + extras).
 * Picks the first reachable mirror at runtime and refreshes paths against it.
 * Clears Techaro Anubis PoW (pass via homepage — film redir returns 500).
 *
 * Uses [Session] (CookieJar) — global [app] is a bare Requests with NO cookie jar,
 * so Anubis auth cookies would otherwise be discarded after pass-challenge.
 */
class RezkaProvider(baseUrl: String) : MainAPI() {
    override var mainUrl = baseUrl
    override var name = "Кадроград · HDRezka"
    override val hasMainPage = true
    override var lang = "ru"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama, TvType.Cartoon, TvType.AnimeMovie
    )

    private val http by lazy {
        Session(app.baseClient).apply {
            defaultTimeOut = 12L
            defaultHeaders = app.defaultHeaders
            responseParser = app.responseParser
        }
    }

    companion object {
        /** Open-source defaults from n0madic/go-hdrezka + common public mirrors. */
        private val MIRRORS = listOf(
            "https://hdrezka-home.tv",
            "https://hdrezka.ag",
            "https://rezka.ag",
            "https://hdrzk.org",
            "https://hdrezka.co",
            "https://rezka-ua.in",
        )

        private val ANUBIS_SCRIPT = Regex(
            """<script[^>]*id=["']anubis_challenge["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        )
        private val ANUBIS_PREFIX = Regex(
            """<script[^>]*id=["']anubis_base_prefix["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        )
    }

    @Volatile
    private var mirrorReady = false
    private val mirrorLock = Mutex()
    private val voiceSlots = Semaphore(3)
    fun changeMirror(url: String) { mainUrl = url; mirrorReady = false }

    private fun isAnubisChallenge(html: String): Boolean =
        html.contains("anubis_challenge", ignoreCase = true) ||
            html.contains("не бот")

    /** Keep [mainUrl] on the host OkHttp actually landed on (ag → home.tv redirects). */
    private fun syncMainUrl(finalUrl: String?) {
        if (finalUrl.isNullOrBlank()) return
        try {
            val u = java.net.URI(finalUrl)
            if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) {
                mainUrl = "${u.scheme}://${u.host}"
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    /**
     * Anubis 1.25 "fast" PoW: leading zero **bytes** (and high nibble if odd difficulty),
     * matching `sha256-purejs.mjs` worker — not hex-char prefix alone.
     */
    private suspend fun solveAnubisPow(randomData: String, difficulty: Int): Pair<Int, String> {
        require(difficulty in 0..6) { "HDRezka: слишком сложная проверка браузера" }
        val deadline = System.nanoTime() + 8_000_000_000L
        val fullBytes = difficulty / 2
        val odd = difficulty % 2 != 0
        val md = MessageDigest.getInstance("SHA-256")
        var nonce = 0
        while (true) {
            if (nonce % 4096 == 0) {
                coroutineContext.ensureActive()
                if (System.nanoTime() > deadline) throw ErrorLoadingException("HDRezka: проверка браузера превысила 8 секунд")
            }
            val digest = md.digest("$randomData$nonce".toByteArray(Charsets.UTF_8))
            md.reset()
            var ok = true
            for (i in 0 until fullBytes) {
                if (digest[i] != 0.toByte()) {
                    ok = false
                    break
                }
            }
            if (ok && odd && ((digest[fullBytes].toInt() and 0xff) shr 4) != 0) {
                ok = false
            }
            if (ok) {
                val hex = buildString(digest.size * 2) {
                    for (b in digest) append("%02x".format(b))
                }
                return nonce to hex
            }
            nonce++
            if (nonce > 50_000_000) error("Anubis PoW too hard")
        }
    }

    private suspend fun passAnubisChallenge(html: String, redir: String) {
        val raw = ANUBIS_SCRIPT.find(html)?.groupValues?.getOrNull(1)?.trim() ?: return
        val payload = tryParseJson<AnubisPayload>(raw) ?: return
        val ch = payload.challenge ?: return
        val randomData = ch.randomData ?: return
        val id = ch.id ?: return
        val diff = payload.rules?.difficulty ?: ch.difficulty ?: 4
        val started = System.currentTimeMillis()
        val (nonce, response) = solveAnubisPow(randomData, diff)
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(50L)
        val basePrefix = ANUBIS_PREFIX.find(html)?.groupValues?.getOrNull(1)
            ?.trim()?.trim('"')?.trim().orEmpty()
        val q = listOf(
            "id" to id,
            "response" to response,
            "nonce" to nonce.toString(),
            // Homepage redir is required — film URL as redir yields HTTP 500 on the title page.
            "redir" to redir,
            "elapsedTime" to elapsed.toString(),
        ).joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        // Pass on the SAME host that issued the challenge (cookie domain must match).
        // Auth cookies land in [http] CookieJar — bare app.get would discard them.
        val pass = http.get(
            "$mainUrl$basePrefix/.within.website/x/cmd/anubis/api/pass-challenge?$q",
            referer = redir,
            timeout = 35,
        )
        val auth = pass.cookies.keys.filter { it.contains("anubis", ignoreCase = true) }
        Log.i(
            "HDrezka",
            "anubis pass code=${pass.code} authCookies=$auth jarHas=${pass.cookies.isNotEmpty()} nonce=$nonce ${elapsed}ms",
        )
    }

    /** GET that clears Anubis via homepage auth cookie when challenged. */
    private suspend fun fetchDocument(url: String, timeout: Long = 20): Document {
        var resp = http.get(url, timeout = timeout)
        syncMainUrl(resp.url)
        var html = resp.text
        if (isAnubisChallenge(html)) {
            passAnubisChallenge(html, redir = "$mainUrl/")
            val retryUrl = when {
                resp.url?.startsWith("http") == true -> {
                    // Same path on the synced host
                    val path = try {
                        java.net.URI(resp.url!!).rawPath + (java.net.URI(resp.url!!).rawQuery?.let { "?$it" } ?: "")
                    } catch (e: Exception) {
            if (e is CancellationException) throw e
                        null
                    }
                    if (path != null) "$mainUrl$path" else pageUrl(url)
                }
                else -> pageUrl(url)
            }
            resp = http.get(retryUrl, timeout = timeout)
            syncMainUrl(resp.url)
            html = resp.text
        }
        return Jsoup.parse(html, resp.url ?: url)
    }

    private fun filmPath(url: String): String {
        return try {
            val u = java.net.URI(if (url.startsWith("http")) url else pageUrl(url))
            (u.rawPath ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            url.substringAfter(mainUrl, url).let { if (it.startsWith("/")) it else "/$it" }
        }
    }

    private fun isUsableFilmPage(doc: Document): Boolean {
        if (isAnubisChallenge(doc.html())) return false
        val hasFavs = !doc.selectFirst("input#ctrl_favs")?.attr("value").isNullOrBlank()
        val hasTr = doc.select("ul#translators-list li").isNotEmpty()
        val hasTitle = doc.selectFirst("div.b-post__title h1, div.b-post__origtitle") != null
        return hasTitle && (hasFavs || hasTr)
    }

    /**
     * Film pages are host-sensitive (Anubis cookies + dead mirrors like rezka-ua.in).
     * Try path on current host, then every mirror after clearing Anubis on that host.
     */
    private suspend fun fetchFilmDocument(url: String): Pair<Document, String> {
        val path = filmPath(url)
        val tried = LinkedHashSet<String>()
        val candidates = mutableListOf<String>()
        candidates += pageUrl(url)
        candidates += "$mainUrl$path"
        for (m in MIRRORS) {
            candidates += m.trimEnd('/') + path
        }
        var lastDoc: Document? = null
        var lastUrl = pageUrl(url)
        for (candidate in candidates) {
            if (!tried.add(candidate)) continue
            try {
                // Warm Anubis on this host's homepage first (film redir breaks).
                val host = try {
                    val u = java.net.URI(candidate)
                    "${u.scheme}://${u.host}"
                } catch (e: Exception) {
            if (e is CancellationException) throw e
                    mainUrl
                }
                var home = http.get("$host/", timeout = 5)
                syncMainUrl(home.url)
                if (isAnubisChallenge(home.text)) {
                    passAnubisChallenge(home.text, redir = "$mainUrl/")
                }
                val doc = fetchDocument(candidate)
                lastDoc = doc
                lastUrl = candidate
                if (isUsableFilmPage(doc)) {
                    syncMainUrl(candidate)
                    Log.i("HDrezka", "film OK $candidate translators=${doc.select("ul#translators-list li").size}")
                    return doc to candidate
                }
                Log.w(
                    "HDrezka",
                    "film unusable $candidate len=${doc.html().length} anubis=${isAnubisChallenge(doc.html())}",
                )
            } catch (t: Exception) {
                if (t is CancellationException) throw t
                Log.w("HDrezka", "film try failed $candidate: ${t.message}")
            }
        }
        return (lastDoc ?: fetchDocument(pageUrl(url))) to lastUrl
    }

    private suspend fun ensureWorkingMirror() = mirrorLock.withLock {
        if (mirrorReady) return@withLock
        for (mirror in (listOf(mainUrl) + MIRRORS).distinct()) {
            try {
                val base = mirror.trimEnd('/')
                var resp = http.get(base, timeout = 5)
                syncMainUrl(resp.url)
                var html = resp.text
                if (isAnubisChallenge(html)) {
                    passAnubisChallenge(html, redir = "$mainUrl/")
                    resp = http.get(mainUrl, timeout = 5)
                    syncMainUrl(resp.url)
                    html = resp.text
                }
                val doc = Jsoup.parse(html, resp.url ?: base)
                val ok = doc.selectFirst("div.b-content__inline_items, #search, form#search") != null
                    || doc.select("div.b-content__inline_item").isNotEmpty()
                    || doc.selectFirst("a[href*=/films/], a[href*=/series/]") != null

                if (ok && !isAnubisChallenge(html)) {
                    mirrorReady = true
                    return@withLock
                }
            } catch (e: Exception) {
            if (e is CancellationException) throw e
                // try next
            }
        }
        throw ErrorLoadingException("HDRezka: зеркала недоступны. Измените адрес в настройках Кадрограда.")
    }

    private fun pageUrl(pathQuery: String): String {
        val p = pathQuery.trim()
        if (p.startsWith("http://") || p.startsWith("https://")) {
            // Keep the search-result host (often hdrezka-home.tv); only rewrite path onto
            // current mainUrl when we already synced to that mirror.
            return try {
                val u = java.net.URI(p)
                val path = u.rawPath ?: "/"
                val q = u.rawQuery?.let { "?$it" }.orEmpty()
                val host = u.host
                if (!host.isNullOrBlank() && mainUrl.contains(host, ignoreCase = true)) {
                    p
                } else if (!host.isNullOrBlank()) {
                    // Prefer the URL's own host — Anubis cookies are host-scoped.
                    "${u.scheme}://$host$path$q"
                } else {
                    "$mainUrl$path$q"
                }
            } catch (e: Exception) {
            if (e is CancellationException) throw e
                p
            }
        }
        return mainUrl + if (p.startsWith("/")) p else "/$p"
    }

    // Paths only — host resolved via [ensureWorkingMirror]
    override val mainPage = mainPageOf(
        "/films/?filter=last" to "фильмы — новинки",
        "/films/?filter=watching" to "фильмы — смотрят",
        "/films/?filter=popular" to "фильмы — популярные",
        "/series/?filter=last" to "сериалы — новинки",
        "/series/?filter=watching" to "сериалы — смотрят",
        "/series/?filter=popular" to "сериалы — популярные",
        "/cartoons/?filter=last" to "мультфильмы — новинки",
        "/cartoons/?filter=watching" to "мультфильмы — смотрят",
        "/animation/?filter=last" to "аниме — новинки",
        "/animation/?filter=watching" to "аниме — смотрят",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        ensureWorkingMirror()
        val parts = request.data.split("?", limit = 2)
        val path = parts.first()
        val query = parts.getOrNull(1)?.let { "?$it" }.orEmpty()
        val home = fetchDocument(pageUrl("${path}page/$page/$query")).select(
            "div.b-content__inline_items div.b-content__inline_item"
        ).mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title =
            this.selectFirst("div.b-content__inline_item-link > a")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = this.selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val posterUrl = this.select("img").attr("src")
        val year = this.selectFirst("div.b-content__inline_item-link > div")?.text()
            ?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""(?<![0-9])((?:19|20)\d{2})(?![0-9])""").findAll(href)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.lastOrNull()
        val series = this.select("span.info").isNotEmpty()
        val type = ContentKinds.rezka(href, series)
        return if (!series) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            val episode =
                this.select("span.info").text().substringAfter(",").replace(Regex("[^0-9]"), "")
                    .toIntOrNull()
            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
                addDubStatus(
                    dubExist = true,
                    dubEpisodes = episode,
                    subExist = false
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureWorkingMirror()
        val link = "$mainUrl/search/?do=search&subaction=search&q=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val document = fetchDocument(link)

        return document.select("div.b-content__inline_items div.b-content__inline_item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureWorkingMirror()
        val (document, resolved) = fetchFilmDocument(url)

        val id = resolved.split("/").last().split("-").first()
        val title = document.selectFirst("div.b-post__title h1")?.text()?.trim()
            ?: throw ErrorLoadingException("HDRezka: карточка недоступна или требуется вход на сайте")
        val poster = fixUrlNull(document.selectFirst("div.b-sidecover img")?.attr("src"))
        val tags =
            document.select("table.b-post__info > tbody > tr:contains(Жанр) span[itemprop=genre]")
                .map { it.text() }
        val year = Regex("(?:19|20)\\d{2}").find(document.select("table.b-post__info tr:contains(Дата выхода)").text())?.value?.toIntOrNull()
        val isSeries = document.select("div#simple-episodes-tabs").isNotEmpty()
        val tvType = ContentKinds.rezka(resolved, isSeries)
        val description = document.selectFirst("div.b-post__description_text")?.text()?.trim()
        val rating =
            document.selectFirst("table.b-post__info > tbody > tr:nth-child(1) span.bold")?.text()
        val actors =
            document.select("table.b-post__info > tbody > tr:last-child span.item").mapNotNull {
                Actor(
                    it.selectFirst("span[itemprop=name]")?.text() ?: return@mapNotNull null,
                    it.selectFirst("span[itemprop=actor]")?.attr("data-photo")
                )
            }

        val recommendations = document.select("div.b-sidelist div.b-content__inline_item").mapNotNull {
            it.toSearchResult()
        }

        val data = HashMap<String, Any>()
        val server = ArrayList<Map<String, String>>()

        data["id"] = id
        data["favs"] = document.selectFirst("input#ctrl_favs")?.attr("value").orEmpty()
        data["ref"] = resolved

        return if (isSeries) {
            // Series-only: movies keep the branch below untouched.
            val seenTr = LinkedHashSet<String>()
            document.select("ul#translators-list li").forEach { res ->
                val node = res.selectFirst("[data-translator_id]") ?: res
                val tid = node.attr("data-translator_id").ifBlank {
                    res.attr("data-translator_id")
                }
                if (tid.isBlank() || !seenTr.add(tid)) return@forEach
                server.add(
                    mapOf(
                        "translator_name" to (node.attr("title").ifBlank { node.text() }).trim(),
                        "translator_id" to tid,
                    )
                )
            }

            // Site uses <a class="b-simple_episode__item">, not <ul><li> — old
            // "ul li" selector returned 0 episodes for Simpsons / long-running shows.
            val episodeKeys = LinkedHashSet<Pair<Int, Int>>()
            collectEpisodePairs(document).forEach { episodeKeys += it }
            // Initial DOM often only has the latest seasons for the default voiceover.
            // get_episodes expands the full S×E grid for a translator (e.g. 2x2 → S1…).
            expandSeriesEpisodes(id, data["favs"] as String, resolved, server, episodeKeys)

            val episodes = episodeKeys.sortedWith(compareBy({ it.first }, { it.second })).map { (season, episode) ->
                val episodeData = HashMap<String, Any>(data).apply {
                    this["season"] = "$season"
                    this["episode"] = "$episode"
                    this["server"] = server
                    this["action"] = "get_stream"
                }
                newEpisode(episodeData.toJson(), {
                    this.name = "Episode $episode"
                    this.season = season
                    this.episode = episode
                }, fix = false)
            }
            Log.i(
                "HDrezka",
                "load series id=$id host=$mainUrl translators=${server.size} episodes=${episodes.size} " +
                    "seasons=${episodeKeys.map { it.first }.toSet().sorted()} anubisLeft=${isAnubisChallenge(document.html())}",
            )

            newTvSeriesLoadResponse(title, resolved, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            document.select("ul#translators-list li").map { res ->
                val node = res.selectFirst("a[data-translator_id]") ?: res
                server.add(
                    mapOf(
                        "translator_name" to (node.attr("title").ifBlank { node.text() }),
                        "translator_id" to node.attr("data-translator_id"),
                        "camrip" to node.attr("data-camrip"),
                        "ads" to node.attr("data-ads"),
                        "director" to node.attr("data-director")
                    )
                )
            }

            data["server"] = server
            data["action"] = "get_movie"
            Log.i(
                "HDrezka",
                "load movie id=$id host=$mainUrl translators=${server.size} anubisLeft=${isAnubisChallenge(document.html())}",
            )

            newMovieLoadResponse(title, resolved, tvType, data.toJson()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
            }
        }
    }

    /** Episode nodes on series pages (anchors or list items). */
    private fun collectEpisodePairs(root: org.jsoup.nodes.Element): List<Pair<Int, Int>> {
        return root.select(
            "div#simple-episodes-tabs a.b-simple_episode__item, " +
                "div#simple-episodes-tabs li.b-simple_episode__item, " +
                "ul.b-simple_episodes__list [data-season_id][data-episode_id], " +
                "[data-season_id][data-episode_id]"
        ).mapNotNull { el ->
            val season = el.attr("data-season_id").toIntOrNull() ?: return@mapNotNull null
            val episode = el.attr("data-episode_id").toIntOrNull() ?: return@mapNotNull null
            season to episode
        }
    }

    /**
     * Long-running series only ship the latest seasons in the first HTML.
     * Merge episode grids from get_episodes across voiceovers until season 1
     * appears (or a few translators were tried). Movie path never calls this.
     */
    private suspend fun expandSeriesEpisodes(
        id: String,
        favs: String,
        referer: String,
        translators: List<Map<String, String>>,
        into: LinkedHashSet<Pair<Int, Int>>,
    ) {
        if (translators.isEmpty()) return
        val before = into.size
        for (tr in translators.take(6)) {
            val tid = tr["translator_id"] ?: continue
            try {
                val ajax = http.post(
                    url = "$mainUrl/ajax/get_cdn_series/?t=${Date().time}",
                    data = mapOf(
                        "id" to id,
                        "translator_id" to tid,
                        "favs" to favs,
                        "season" to "1",
                        "action" to "get_episodes",
                    ),
                    referer = referer,
                ).parsedSafe<EpisodesAjax>() ?: continue
                if (ajax.success == false) continue
                val html = buildString {
                    append(ajax.episodes.orEmpty())
                    append(ajax.seasons.orEmpty())
                }
                if (html.isBlank()) continue
                collectEpisodePairs(Jsoup.parse(html)).forEach { into += it }
            } catch (t: Exception) {
                if (t is CancellationException) throw t
                Log.w("HDrezka", "get_episodes failed tr=$tid: ${t.message}")
            }
            // Early seasons present → catalog S1/S2 lookups will work.
            if (into.any { it.first == 1 }) break
        }
        Log.i("HDrezka", "expand episodes $before → ${into.size}")
    }

    private fun decryptStreamUrl(data: String) = StreamTools.decodeRezka(data)

    private suspend fun cleanCallback(
        source: String,
        url: String,
        quality: String,
        isM3u8: Boolean,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        sourceCallback.invoke(
            newExtractorLink(
                source,
                source,
                url,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = getQuality(quality)
                this.headers = mapOf(
                    "Origin" to mainUrl
                )
            }
        )
    }

    private fun getLanguage(str: String): String {
        return when (str) {
            "Русский" -> "Russian"
            "Українська" -> "Ukrainian"
            else -> str
        }
    }

    private fun getQuality(str: String) = StreamTools.quality(str)

    private suspend fun invokeSources(
        source: String,
        url: String,
        subtitle: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        for ((quality, link) in StreamTools.labeledUrls(decryptStreamUrl(url))) {
            cleanCallback("$source · $quality · ${if (link.contains(".m3u8")) "HLS" else "MP4"}",
                link, quality, link.contains(".m3u8"), sourceCallback)
        }
        for ((language, link) in StreamTools.labeledUrls(subtitle)) {
            subCallback(newSubtitleFile(getLanguage(language), link))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureWorkingMirror()
        val res = tryParseJson<Data>(data) ?: return false
        var count = 0
        val seen = HashSet<String>()
        val seenSubs = HashSet<String>()
        val emit: (ExtractorLink) -> Unit = { link -> if (seen.add("${link.name}|${link.url}")) { callback(link); count++ } }
        val emitSub: (SubtitleFile) -> Unit = { sub -> if (seenSubs.add("${sub.lang}|${sub.url}")) subtitleCallback(sub) }
        if (res.server.isNullOrEmpty()) {
            val document = fetchDocument(res.ref ?: return false)
            for (script in document.select("script")) {
                val body = script.data()
                if (!body.contains("sof.tv.initCDNMoviesEvents(")) continue
                val payload = body.substringAfter("false, {").substringBefore("});")
                val source = tryParseJson<LocalSources>("{$payload}") ?: continue
                invokeSources("HDRezka", source.streams, source.subtitle?.toString().orEmpty(), emitSub, emit)
            }
        } else {
            val emitLock = Mutex()
            withTimeoutOrNull(40_000) { coroutineScope {
                res.server.map { server -> async { voiceSlots.withPermit {
                    try {
                        val result = http.post("$mainUrl/ajax/get_cdn_series/?t=${System.currentTimeMillis()}",
                            data = mapOf("id" to res.id, "translator_id" to server.translator_id, "favs" to res.favs,
                                "is_camrip" to server.camrip, "is_ads" to server.ads, "is_director" to server.director,
                                "season" to res.season, "episode" to res.episode, "action" to res.action)
                                .filterValues { it != null }.mapValues { it.value!! },
                            referer = res.ref, timeout = 15).parsedSafe<Sources>()
                        if (result != null) emitLock.withLock {
                            invokeSources(server.translator_name ?: "HDRezka", result.url,
                                result.subtitle?.toString().orEmpty(), emitSub, emit)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                } } }.awaitAll()
            } }
        }
        return count > 0
    }

    data class EpisodesAjax(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("episodes") val episodes: String? = null,
        @JsonProperty("seasons") val seasons: String? = null,
        @JsonProperty("message") val message: String? = null,
    )

    data class LocalSources(
        @JsonProperty("streams") val streams: String,
        @JsonProperty("subtitle") val subtitle: Any?,
    )

    data class Sources(
        @JsonProperty("url") val url: String,
        @JsonProperty("subtitle") val subtitle: Any?,
    )

    data class Server(
        @JsonProperty("translator_name") val translator_name: String?,
        @JsonProperty("translator_id") val translator_id: String?,
        @JsonProperty("camrip") val camrip: String?,
        @JsonProperty("ads") val ads: String?,
        @JsonProperty("director") val director: String?,
    )

    data class Data(
        @JsonProperty("id") val id: String?,
        @JsonProperty("favs") val favs: String?,
        @JsonProperty("server") val server: List<Server>?,
        @JsonProperty("season") val season: String?,
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("action") val action: String?,
        @JsonProperty("ref") val ref: String?,
    )

    data class Trailer(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("code") val code: String?,
    )

    data class AnubisPayload(
        @JsonProperty("rules") val rules: AnubisRules? = null,
        @JsonProperty("challenge") val challenge: AnubisChallenge? = null,
    )

    data class AnubisRules(
        @JsonProperty("algorithm") val algorithm: String? = null,
        @JsonProperty("difficulty") val difficulty: Int? = null,
    )

    data class AnubisChallenge(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("randomData") val randomData: String? = null,
        @JsonProperty("difficulty") val difficulty: Int? = null,
        @JsonProperty("method") val method: String? = null,
    )
}
