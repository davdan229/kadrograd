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

class VibeContractTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: VibeProvider
    private var reply: (RecordedRequest) -> Pair<Int, String> = { 404 to "" }
    private val requests = mutableListOf<RecordedRequest>()
    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val (code, body) = reply(request)
                return MockResponse.Builder().code(code).addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()
            }
        }
        server.start()
        provider = VibeProvider(server.url("/").toString().trimEnd('/'))
    }
    @After fun stop() { server.close() }
    private fun page(title: String = "Фильм Пример (1999)", file: String = "https://cdn.example/film_480.mp4") = """
        <h1>$title смотреть онлайн</h1><div class="movie-poster"><img src="/poster.jpg"></div>
        <div class="movie-desc">Описание</div><div itemprop="translator">Дубляж</div>
        <script>var mainPlayer = new Playerjs({id:"playerjshd", file:"$file", "qualities":"480,720"});</script>
    """
    private fun playlist(token: String = "old") = """{"playlist":[
        {"comment":"1 Серия<br>Baibako","file":"https://cdn.example/one.mp4?token=$token"},
        {"comment":"2 Серия<br>Baibako","file":"https://cdn.example/two.mp4"}]}"""
    @Test fun catalogHasNextPageAndExcludesSidebarVideos() = runBlocking {
        reply = { 200 to """<div class="custom1-item"><a class="custom1-img" href="/2-recommendation.html">
            <div class="custom1-title">Повторяющаяся рекомендация</div></a></div>
            <div id="dle-content"><div class="custom1-item"><a class="custom1-img" href="/169-test.html">
            <div class="custom1-title">Пример (1999)</div><img src="/poster.jpg"></a></div></div>
            <a href="/films/page/2/">Дальше</a><a href="/newtrailers/">Трейлеры</a>""" }
        val result = provider.getMainPage(1, MainPageRequest("Фильмы", "films", false))
        assertEquals(1, result.items.single().list.size)
        assertEquals("Пример (1999)", result.items.single().list.single().name)
        assertTrue(result.hasNext)
    }
    @Test fun searchUsesRealPostFormAndKeepsSeasonInTitle() = runBlocking {
        reply = { 200 to """<form id="quicksearch"></form><a class="sres-wrap" href="/4037-mentalist.html">
            <h2>Менталист 7 Сезон (2014)</h2></a>""" }
        val found = provider.search("Менталист & друзья").single()
        assertEquals(TvType.TvSeries, found.type)
        assertEquals("POST", requests.single().method)
        assertTrue(requests.single().body!!.utf8().contains("%26"))
    }
    @Test fun movieKeepsWatchHistoryFreeOfCdnUrlsAndRefreshesAtPlayback() = runBlocking {
        var count = 0
        reply = { count++; 200 to page(file = "https://cdn.example/film_480.mp4?token=$count") }
        val movie = provider.load("${provider.mainUrl}/169-test.html") as MovieLoadResponse
        assertFalse(movie.dataUrl.contains("token="))
        val links = mutableListOf<ExtractorLink>()
        assertTrue(provider.loadLinks(movie.dataUrl, false, {}, links::add))
        assertTrue(links.single().url.contains("token=2"))
        assertEquals("${provider.mainUrl}/169-test.html", links.single().referer)
        assertFalse(provider.hasDownloadSupport)
    }
    @Test fun seriesParsesBomPlaylistAndCorrectSeason() = runBlocking {
        reply = { req -> 200 to if (req.url.encodedPath.endsWith(".txt")) "\uFEFF" + playlist()
            else page("Сериал Менталист 7 Сезон (2014)", "${provider.mainUrl}/player/test.txt") }
        val loaded = provider.load("${provider.mainUrl}/4037-mentalist.html") as TvSeriesLoadResponse
        assertEquals(2, loaded.episodes.size)
        assertEquals(7, loaded.episodes.first().season)
        assertEquals(1, loaded.episodes.first().episode)
        val links = mutableListOf<ExtractorLink>()
        assertTrue(provider.loadLinks(loaded.episodes.last().data, false, {}, links::add))
        assertTrue(links.single().url.endsWith("two.mp4"))
    }
    @Test fun missingEpisodeDoesNotFallBackToFirstEpisode() = runBlocking {
        reply = { req -> 200 to if (req.url.encodedPath.endsWith(".txt")) playlist()
            else page("Сериал Пример 1 Сезон (2000)", "${provider.mainUrl}/player/test.txt") }
        assertFalse(provider.loadLinks(VibeProvider.PlaybackRef("${provider.mainUrl}/1-test.html", "99 Серия").toJson(), false, {}, {}))
    }
    @Test fun guestStreamsNeverExposeAccountRestrictedHd() {
        assertEquals(listOf("https://cdn.example/a_480.mp4"), provider.guestStreams("https://cdn.example/a_480.mp4,https://cdn.example/a_720.mp4,"))
        assertEquals(listOf("https://cdn.example/a_480.mp4"), provider.guestStreams("https://cdn.example/a_[480,720].mp4"))
        assertTrue(provider.guestStreams("https://cdn.example/a.1080p.mp4").isEmpty())
        assertEquals(listOf("https://cdn.example/a.mp4?token=a,b"), provider.guestStreams("https://cdn.example/a.mp4?token=a,b"))
    }
    @Test fun serverFailureAndMissingPlayerAreVisibleErrors() = runBlocking {
        for ((code, html) in listOf(503 to "", 200 to "<h1>Проверка браузера</h1>",
            200 to page(file = "https://cdn.example/only_720.mp4"))) {
            reply = { code to html }
            try { provider.load("${provider.mainUrl}/1-test.html"); fail("Unusable card") }
            catch (expected: ErrorLoadingException) { assertTrue(expected.message.orEmpty().startsWith("KinoVibe:")) }
        }
    }
}
