package ru.kadrograd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import mockwebserver3.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Collections

class ZonaContractTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: ZonaProvider
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private var reply: (RecordedRequest) -> Pair<Int, String> = { 404 to "" }
    private var base = ""
    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val (code, body) = reply(request)
                return MockResponse.Builder().code(code).body(body).addHeader("Date", "Sun, 18 Oct 2026 04:19:18 GMT").build()
            }
        }
        server.start(); base = server.url("/").toString().trimEnd('/')
        provider = ZonaProvider(base).apply { videoApiOrigins = listOf(base); streamApiOrigins = listOf(base) }
    }
    @After fun stop() { server.close() }
    private fun details(season: Int = 1, kp: Int = 412344) = """{"data":{"slug":"mentalist-2008","title":"Менталист","kp_id":$kp,"year":2008,
        "seasons":[{"part":1,"episode_count":2},{"part":7,"episode_count":1}],
        "episodes":[{"part":${if (season == 7) 13 else 1},"title":"$season серия"}${if (season == 1) ",{\"part\":2,\"title\":\"Вторая\"}" else ""}]}}"""
    private fun stored(ep: Int = 1) = ZonaProvider.PlaybackRef("$base/tvseries/mentalist-2008", 412344, 1, ep).toJson()
    private fun encoded(body: String) = ZonaTransport.encode(body, 31, ByteArray(12) { (it + 1).toByte() })
    private fun setup(mediaCode: Int = 206, streamBody: String? = null) {
        reply = { req -> when (req.url.encodedPath) {
            "/api/series/mentalist-2008" -> 200 to details(if (req.url.queryParameter("season") == "7") 7 else 1)
            "/getTime" -> 200 to ""
            "/getVideoSources" -> 200 to encoded("""{"data":[{"video_source_type_id":3,"kinopoisk_id":412344,"episode_key":"S01E01","download_link_key":"7179-title"}]}""")
            "/getStreams/" -> 200 to encoded(streamBody ?: """[{"url":"$base/720.mp4?signature=fresh","resolution":"720p","translation":"Дубляж","subtitles":[{"name":"Русские","source":{"url":"$base/sub.vtt"}}]}]""")
            "/720.mp4" -> mediaCode to "\u0000\u0000\u0000 ftypisom"
            else -> 404 to ""
        } }
    }
    @Test fun catalogPagingKeepsOldTitlesAndExcludesTrailers() = runBlocking {
        reply = { 200 to """{"data":[{"slug":"king","title":"Король Лев","year":1994},{"slug":"trailer","title":"Реклама","is_trailer":true}],"links":{"next":"https://other/api/movies?page=3"}}""" }
        val page = provider.getMainPage(2, MainPageRequest("Мультфильмы", "movies/filter?genre=multfilm", false))
        assertTrue(page.hasNext); assertEquals(TvType.Cartoon, page.items.single().list.single().type)
        assertEquals(1994, (page.items.single().list.single() as MovieSearchResponse).year)
        assertEquals("2", requests.single().url.queryParameter("page")); assertEquals("multfilm", requests.single().url.queryParameter("genre"))
        assertEquals(base, provider.mainUrl)
        assertFalse(provider.hasDownloadSupport)
    }
    @Test fun searchEncodesQueryAndDoesNotIncludeVideosFiltersOrTrailers() = runBlocking {
        reply = { 200 to """<title>Результаты поиска по запросу — Zona</title>
            <a title="Менталист" href="/tvseries/mentalist-2008"><img alt="Сериал Менталист (2008)" src="/poster.jpg"></a>
            <a title="Жанр" href="/movies/filter/genre-anime"><img></a><a title="Трейлер" href="/trailers/film"><img></a>""" }
        assertEquals("Менталист", provider.search("Менталист & я").single().name)
        assertTrue(requests.single().url.encodedPath.contains("%26"))
    }
    @Test fun allPublishedSeasonsUseExactSeasonAndEpisodeNumbers() = runBlocking {
        setup()
        val loaded = provider.load("$base/tvseries/mentalist-2008") as TvSeriesLoadResponse
        assertEquals(listOf(1 to 1, 1 to 2, 7 to 13), loaded.episodes.map { it.season to it.episode })
        assertTrue(requests.any { it.url.queryParameter("season") == "7" })
        assertFalse(loaded.episodes.any { it.data.contains("signature") })
    }
    @Test fun playbackRefreshesUrlsPassesVoiceQualityAndSubtitles() = runBlocking {
        setup(); val links = mutableListOf<ExtractorLink>(); val subs = mutableListOf<SubtitleFile>()
        assertTrue(provider.loadLinks(stored(), false, subs::add, links::add))
        assertEquals(720, links.single().quality); assertTrue(links.single().name.contains("Дубляж")); assertTrue(links.single().url.endsWith("signature=fresh"))
        assertEquals("$base/sub.vtt", subs.single().url)
        val query = requests.first { it.url.encodedPath == "/getVideoSources" }.url.queryParameter("q")!!
        assertTrue(ZonaTransport.decode(query, 17).contains("episode_key=S01E01"))
        val selected = requests.first { it.url.encodedPath == "/getStreams/" }.url.queryParameter("q")!!
        assertTrue(ZonaTransport.decode(selected, 17).contains("key=7179&season=1&episode=1"))
    }
    @Test fun blockedCdnProducesAnErrorBeforePlayer() = runBlocking {
        setup(403); val links = mutableListOf<ExtractorLink>()
        try { provider.loadLinks(stored(), false, {}, links::add); fail("Blocked media") }
        catch (expected: ErrorLoadingException) { assertTrue(expected.message.orEmpty().contains("видеоссылок")) }
        assertTrue(links.isEmpty())
    }
    @Test fun missingEpisodeOrChangedTitleNeverFallsBackToAnotherVideo() = runBlocking {
        setup()
        try { provider.loadLinks(stored(99), false, {}, {}); fail("Missing episode") } catch (_: ErrorLoadingException) { }
        assertTrue(requests.none { it.url.encodedPath == "/getVideoSources" })
        reply = { 200 to details(kp = 999) }
        try { provider.loadLinks(stored(), false, {}, {}); fail("Wrong title") } catch (_: ErrorLoadingException) { }
        assertTrue(requests.none { it.url.encodedPath == "/getVideoSources" })
    }
    @Test fun otherEpisodeFromServerIsRejected() = runBlocking {
        setup(); val previous = reply
        reply = { req -> if (req.url.encodedPath == "/getVideoSources") {
            200 to encoded("""{"data":[{"video_source_type_id":3,"kinopoisk_id":412344,"episode_key":"S01E02","download_link_key":"7179-title"}]}""")
        } else previous(req) }
        try { provider.loadLinks(stored(), false, {}, {}); fail("Wrong episode") } catch (_: ErrorLoadingException) { }
        assertTrue(requests.none { it.url.encodedPath == "/getStreams/" })
    }
    @Test fun hlsWithFailingSegmentIsRejected() = runBlocking {
        setup(streamBody = """[{"url":"$base/video.m3u8","resolution":"HLS"}]"""); val previous = reply
        reply = { req -> when(req.url.encodedPath) {
            "/video.m3u8" -> 200 to "#EXTM3U\n#EXTINF:5,\nsegment.ts\n"
            "/segment.ts" -> 404 to "Not found"
            else -> previous(req)
        } }
        try { provider.loadLinks(stored(), false, {}, {}); fail("Broken HLS") } catch (_: ErrorLoadingException) { }
        assertTrue(requests.any { it.url.encodedPath == "/segment.ts" })
    }
    @Test fun hlsValidTransportStreamIsAcceptedAndHttp200HtmlIsRejected() = runBlocking {
        setup(streamBody = """[{"url":"$base/video.m3u8","resolution":"HLS"}]"""); val previous = reply
        var segment = "G" + "\u0000".repeat(187)
        reply = { req -> when(req.url.encodedPath) {
            "/video.m3u8" -> 200 to "#EXTM3U\n#EXTINF:5,\nsegment.ts\n"
            "/segment.ts" -> 200 to segment
            else -> previous(req)
        } }
        val links = mutableListOf<ExtractorLink>()
        assertTrue(provider.loadLinks(stored(), false, {}, links::add)); assertEquals(1,links.size)
        segment = "<html>Access denied by server</html>".repeat(3)
        try { provider.loadLinks(stored(), false, {}, {}); fail("HTML masquerades as video") } catch (_: ErrorLoadingException) { }
    }
    @Test fun publicTransportMatchesIndependentVectorsAndRejectsMalformedResponses() {
        val plain = "Тест &=123"
        val encoded = ZonaTransport.encode(plain, 17, ByteArray(12) { it.toByte() })
        assertEquals("DAABAgMEBQYHCAkKC+G469X2qwC2WWSAeXN5", encoded)
        assertEquals(plain, ZonaTransport.decode(encoded, 17))
        assertEquals("1792297158742.267", ZonaTransport.clientTime(1792297158000, "test-agent"))
        for (bad in listOf("", "a===", "AAAA", "PHA+aHRtbDwvcD4=")) {
            try { ZonaTransport.decode(bad); fail("Malformed envelope") } catch (_: IllegalArgumentException) { }
        }
    }
}
