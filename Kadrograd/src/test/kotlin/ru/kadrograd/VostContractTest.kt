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
    private val requests = mutableListOf<RecordedRequest>()
    private var reply: (RecordedRequest) -> Pair<Int, String> = { 404 to "" }
    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val (code, body) = reply(request)
                return MockResponse.Builder().code(code).body(body).build()
            }
        }
        server.start()
        provider = VostProvider(server.url("/").toString().trimEnd('/'))
    }
    @After fun stop() { server.close() }
    private fun release(type: String = "ТВ", id: Int = 154) = """
        <input name="story"><div class="global_search"></div><div class="shortstory"><div class="shortstoryHead"><h2>
        <a href="/tip/tv/$id-death-note.html">Тетрадь Смерти / Death Note</a></h2></div>
        <div class="shortstoryContent"><img src="/poster.jpg"><p><strong>Год выхода: </strong>2006</p>
        <p><strong>Тип: </strong>$type</p><div id="ratig-layer-$id"></div></div></div>
        <script>var data = {"1 серия":"101","2 серия":"102",};</script>
    """
    private fun setup(frame: String = """{"file":"${provider.mainUrl}/720/101.mp4?token=fresh"}""") {
        reply = { req -> 200 to when {
            req.url.encodedPath == "/frame5.php" -> frame
            req.url.encodedPath.endsWith(".mp4") -> "\u0000\u0000\u0000 ftypisom"
            else -> release()
        } }
    }
    @Test fun webSearchPostsCyrillicToMirrorWithoutCallingApi() = runBlocking {
        reply = { 200 to release() }
        val found = provider.search("Тетрадь смерти & мир").single() as AnimeSearchResponse
        assertEquals(2006, found.year)
        assertTrue(found.url.endsWith("/tip/tv/154-death-note.html"))
        assertEquals("POST", requests.single().method)
        assertTrue(requests.single().body!!.utf8().contains("%26"))
        assertTrue(requests.single().body!!.utf8().contains("titleonly=3"))
        assertFalse(requests.single().headers["User-Agent"].orEmpty().contains("Mobile"))
        assertTrue(requests.single().headers["User-Agent"].orEmpty().contains("Windows NT"))
        assertEquals("/index.php", requests.single().url.encodedPath)
    }
    @Test fun catalogPaginationUsesMirrorPagesAndCorrectRelativePoster() = runBlocking {
        reply = { 200 to release() + """<a href="/page/3/">3</a>""" }
        val result = provider.getMainPage(2, MainPageRequest("Каталог", "all", false))
        assertTrue(result.hasNext)
        assertEquals("/page/2/", requests.single().url.encodedPath)
        assertEquals("${provider.mainUrl}/poster.jpg", result.items.single().list.single().posterUrl)
    }
    @Test fun oldApiCardAndEpisodeReferencesUseMirrorAndFreshPlayer() = runBlocking {
        setup()
        val loaded = provider.load("https://api.animevost.org/v1/info?id=154") as AnimeLoadResponse
        assertEquals(2, loaded.episodes.getValue(DubStatus.Dubbed).size)
        val data = loaded.episodes.getValue(DubStatus.Dubbed).first().data
        assertFalse(data.contains("token="))
        val links = mutableListOf<ExtractorLink>()
        assertTrue(provider.loadLinks(data, false, {}, links::add))
        assertTrue(links.single().url.contains("token=fresh"))
        assertEquals(720, links.single().quality)
        assertTrue(requests.any { it.headers["Range"] == "bytes=0-31" })
        assertFalse(requests.any { it.url.encodedPath.startsWith("/v1/") })
    }
    @Test fun inaccessibleMediaIsNotSentToPlayer() = runBlocking {
        setup()
        val original = reply
        reply = { req -> if (req.url.encodedPath.endsWith(".mp4")) 403 to "Forbidden" else original(req) }
        val links = mutableListOf<ExtractorLink>()
        try { provider.loadLinks(VostProvider.EpisodeRef(154, "101.mp4").toJson(), false, {}, links::add); fail("Blocked CDN") }
        catch (expected: ErrorLoadingException) { assertTrue(expected.message.orEmpty().contains("видеосерверов")) }
        assertTrue(links.isEmpty())
    }
    @Test fun missingEpisodeCannotBecomeAnotherEpisode() = runBlocking {
        setup()
        try { provider.loadLinks(VostProvider.EpisodeRef(154, "999.mp4").toJson(), false, {}, {}); fail("Missing episode") }
        catch (expected: ErrorLoadingException) { assertTrue(requests.none { it.url.encodedPath == "/frame5.php" }) }
    }
    @Test fun movieAndWrongReleaseAreDistinguished() = runBlocking {
        reply = { 200 to release("полнометражный фильм").replace("\"2 серия\":\"102\",", "") }
        assertTrue(provider.load("${provider.mainUrl}/index.php?newsid=154") is MovieLoadResponse)
        reply = { 200 to release(id = 999) }
        try { provider.load("${provider.mainUrl}/index.php?newsid=154"); fail("Wrong release") }
        catch (expected: ErrorLoadingException) { assertTrue(expected.message.orEmpty().contains("не найден")) }
    }
    @Test fun candidatesKeepOnlySelectedEpisodeAndDontInventQualityUrls() {
        val found = provider.candidates("""{"file":"[HD]https://cdn.example/720/101.mp4?a=1 or https://cdn.example/720/101.mp4?a=2"}
            var ad='https://ads.example/999.mp4'; var sd='https://other.example/101.mp4';""", "101")
        assertEquals(listOf("https://cdn.example/720/101.mp4?a=1", "https://other.example/101.mp4"), found)
    }
    @Test fun searchIncludesOldTitlesFromSecondResultPage() = runBlocking {
        reply = { req -> 200 to if (req.body!!.utf8().contains("search_start=2")) release(id = 2727)
            else release() + """<div class="global_search">найдено 18 ответов</div>""" }
        assertEquals(2, provider.search("Монстр").size)
        assertEquals(2, requests.size)
    }
    @Test fun directCardAndPlaybackUseArticleInsteadOfNumericRedirectAndBrowserHeaders() = runBlocking {
        setup()
        val card = provider.getMainPage(1,MainPageRequest("Каталог","all",false)).items.single().list.single()
        val loaded = provider.load(card.url) as AnimeLoadResponse
        val links = mutableListOf<ExtractorLink>()
        assertTrue(provider.loadLinks(loaded.episodes.getValue(DubStatus.Dubbed).first().data,false,{},links::add))
        assertTrue(requests.none { it.url.encodedPath == "/index.php" })
        assertEquals(2,requests.count { it.url.encodedPath == "/tip/tv/154-death-note.html" })
        assertTrue(requests.first { it.url.encodedPath == "/tip/tv/154-death-note.html" }.headers["User-Agent"].orEmpty().contains("Chrome/"))
        assertTrue(requests.filter { it.url.encodedPath in setOf("/","/tip/tv/154-death-note.html","/frame5.php") }
            .all { it.headers["User-Agent"].orEmpty().contains("Windows NT") })
    }
}
