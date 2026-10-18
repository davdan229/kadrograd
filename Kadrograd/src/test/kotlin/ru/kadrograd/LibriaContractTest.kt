package ru.kadrograd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import mockwebserver3.MockWebServer
import mockwebserver3.MockResponse
import mockwebserver3.Dispatcher
import mockwebserver3.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Executes the actual Kotlin adapter and Cloudstream API against a local HTTP fixture. */
class LibriaContractTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: LibriaProvider
    private var body: (String) -> Pair<Int, String> = { 404 to "{}" }
    private val paths = mutableListOf<String>()

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                paths += path
                val (status, text) = body(path)
                return MockResponse.Builder().code(status).addHeader("Content-Type", "application/json; charset=utf-8").body(text).build()
            }
        }
        server.start()
        provider = LibriaProvider(server.url("/").toString().trimEnd('/'))
    }
    @After fun stop() { server.close() }

    private fun release(blocked: Boolean = false, token: String = "fresh") = """{
        "id":42,"name":{"main":"Проверочный сериал","english":"Test"},"type":{"value":"TV"},
        "year":2007,"is_ongoing":false,"is_blocked_by_geo":$blocked,
        "episodes":[{"id":"episode-a","ordinal":1,"name":"Первая серия",
        "hls_720":"https://cdn.example/video.m3u8?token=$token"}],"unknown_future_field":true
    }"""

    @Test fun catalogPaginationUsesApiMetadata() = runBlocking {
        body = { 200 to """{"data":[${release()}],"meta":{"pagination":{"current_page":2,"total_pages":2}}}""" }
        val result = provider.getMainPage(2, MainPageRequest("Каталог", "all", false))
        assertEquals("Проверочный сериал", result.items.first().list.first().name)
        assertFalse(result.hasNext)
        assertTrue(paths.single().contains("page=2"))
    }

    @Test fun searchEncodesCyrillicAndReservedCharacters() = runBlocking {
        body = { 200 to "[${release()}]" }
        assertEquals(1, provider.search("Наруто & друзья").size)
        assertTrue(paths.single().contains("%26"))
        assertFalse(paths.single().contains("Наруто"))
    }

    @Test fun playbackRefetchesSignedUrlsAndEmitsAvailableQuality() = runBlocking {
        var requests = 0
        body = { requests++; 200 to release(token = "fresh-$requests") }
        val loaded = provider.load("${provider.mainUrl}/api/v1/anime/releases/42") as AnimeLoadResponse
        assertEquals(2007, loaded.year)
        val data = loaded.episodes[DubStatus.Dubbed]!!.first().data
        assertFalse(data.contains("fresh-1"))
        val links = mutableListOf<ExtractorLink>()
        assertTrue(provider.loadLinks(data, false, {}, links::add))
        assertEquals(2, requests)
        assertEquals(720, links.single().quality)
        assertTrue(links.single().url.contains("fresh-2"))
    }

    @Test fun blockedReleaseIsNotExposedAsPlayable() = runBlocking {
        body = { 200 to release(blocked = true) }
        try {
            provider.load("${provider.mainUrl}/api/v1/anime/releases/42")
            fail("Blocked releases must not become playable")
        } catch (expected: ErrorLoadingException) {
            assertTrue(expected.message.orEmpty().contains("ограничила"))
        }
    }

    @Test fun missingEpisodeDoesNotFallBackToDifferentEpisode() = runBlocking {
        body = { 200 to release() }
        val ref = LibriaProvider.EpisodeRef(42, "missing").toJson()
        val links = mutableListOf<ExtractorLink>()
        assertFalse(provider.loadLinks(ref, false, {}, links::add))
        assertTrue(links.isEmpty())
    }
    @Test fun serverFailureIsNotPresentedAsEmptyCatalog() = runBlocking {
        body = { 503 to "{}" }
        try {
            provider.getMainPage(1, MainPageRequest("Каталог", "all", false))
            fail("HTTP failure must remain visible")
        } catch (expected: ErrorLoadingException) {
            assertTrue(expected.message.orEmpty().contains("503"))
        }
    }
    @Test fun malformedSuccessResponseDoesNotBecomeEmptyCatalog() = runBlocking {
        body = { 200 to "{\"unexpected\":true}" }
        try {
            provider.getMainPage(1, MainPageRequest("Каталог", "all", false))
            fail("Changed API schema must remain visible")
        } catch (expected: ErrorLoadingException) {
            assertTrue(expected.message.orEmpty().contains("каталог"))
        }
    }
}
