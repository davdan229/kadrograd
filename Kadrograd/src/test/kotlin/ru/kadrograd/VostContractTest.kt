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

class VostContractTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: VostProvider
    private var reply: (RecordedRequest) -> Pair<Int, String> = { 404 to "{}" }
    private val requests = mutableListOf<RecordedRequest>()
    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val (code, body) = reply(request)
                return MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8").body(body).build()
            }
        }
        server.start()
        provider = VostProvider(server.url("/").toString().trimEnd('/'))
    }
    @After fun stop() { server.close() }
    private fun detail(type: String = "ТВ", id: Int = 154) = """{"state":{"status":"ok"},"data":[{
        "id":$id,"title":"Тетрадь Смерти / Death Note [1-37 из 37]","year":"2006","type":"$type"}]}"""
    private fun episodes(token: String = "one") = """[
        {"name":"1 серия","hd":"http://video.animetop.info/720/101.mp4?token=$token","std":"http://video.animetop.info/101.mp4"},
        {"name":"2 серия","hd":"https://video.animetop.info/720/102.mp4"}]"""
    @Test fun cyrillicSearchUsesFormPostAndPreservesCorrectReleaseYear() = runBlocking {
        reply = { 200 to detail() }
        val results = provider.search("Тетрадь смерти & мир")
        assertEquals(2006, (results.single() as AnimeSearchResponse).year)
        assertEquals("POST", requests.single().method)
        assertTrue(requests.single().body!!.utf8().contains("%26"))
    }
    @Test fun catalogTraversesFullIndexUsingCountRatherThanCurrentYear() = runBlocking {
        reply = { 200 to """{"state":{"status":"ok","page":2,"count":3610},"data":[]} """ }
        assertTrue(provider.getMainPage(2, MainPageRequest("Каталог", "last", false)).hasNext)
        assertTrue(requests.single().url.toString().contains("page=2&quantity=24"))
    }
    @Test fun playbackRefreshesUrlsAndKeepsEpisodeIdentityWhenPlaylistReorders() = runBlocking {
        var count = 0
        reply = { req -> 200 to if (req.url.encodedPath.endsWith("/info")) detail() else {
            count++
            if (count == 1) episodes() else """[{"name":"2 серия","hd":"https://video.animetop.info/720/102.mp4"},
                {"name":"1 серия","hd":"http://video.animetop.info/720/101.mp4?token=fresh"}]"""
        } }
        val loaded = provider.load("${provider.mainUrl}/v1/info?id=154") as AnimeLoadResponse
        val ep = loaded.episodes[DubStatus.Dubbed]!!.first()
        assertFalse(ep.data.contains("token="))
        val links = mutableListOf<ExtractorLink>()
        assertTrue(provider.loadLinks(ep.data, false, {}, links::add))
        assertEquals("https://video.animetop.info/720/101.mp4?token=fresh", links.single().url)
        assertEquals(720, links.single().quality)
    }
    @Test fun missingEpisodeDoesNotOpenDifferentEpisode() = runBlocking {
        reply = { 200 to episodes() }
        val links = mutableListOf<ExtractorLink>()
        assertFalse(provider.loadLinks(VostProvider.EpisodeRef(154, "999.mp4").toJson(), false, {}, links::add))
        assertTrue(links.isEmpty())
    }
    @Test fun movieUsesMovieCardAndSingleVideo() = runBlocking {
        reply = { req -> 200 to if (req.url.encodedPath.endsWith("/info")) detail("полнометражный фильм")
            else """[{"name":"Фильм","hd":"https://video.animetop.info/720/101.mp4"}]""" }
        val loaded = provider.load("${provider.mainUrl}/v1/info?id=154")
        assertTrue(loaded is MovieLoadResponse)
        assertEquals(TvType.AnimeMovie, loaded.type)
    }
    @Test fun failedApiAndChangedSchemaAreVisibleErrors() = runBlocking {
        for ((code, body) in listOf(503 to "{}", 200 to "{}", 200 to """{"state":{"status":"fail"},"data":[]}""")) {
            reply = { code to body }
            try { provider.search("пример"); fail("Must report failure") }
            catch (expected: ErrorLoadingException) { assertTrue(expected.message.orEmpty().startsWith("AnimeVost:")) }
        }
    }
    @Test fun wrongReleaseFromServerIsRejected() = runBlocking {
        reply = { 200 to detail(id = 999) }
        try { provider.load("${provider.mainUrl}/v1/info?id=154"); fail("Wrong title") }
        catch (expected: ErrorLoadingException) { assertTrue(expected.message.orEmpty().contains("не найден")) }
    }
}
