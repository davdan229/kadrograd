package ru.kadrograd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.net.URI

/** Opt-in checks against Zona's current public website and media endpoints. */
class ZonaLiveCheck {
    private val api = ZonaProvider("https://w1.zona.im")
    private fun sample(link: ExtractorLink) {
        val client = app.baseClient.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
        fun get(url: String, range: String? = null): Pair<Int, ByteArray> {
            val request = Request.Builder().url(url).header("Referer", link.referer).apply {
                link.headers.forEach { (key, value) -> header(key, value) }
                if (range != null) header("Range", range)
            }.build()
            return client.newCall(request).execute().use { response ->
                assertTrue("Media HTTP status ${response.code}", response.isSuccessful)
                val input = response.body.byteStream(); val buffer = ByteArray(4096); var count = 0
                while (count < buffer.size) { val n = input.read(buffer, count, buffer.size-count); if (n < 0) break; count += n }
                assertTrue(count > 31)
                response.code to buffer.copyOf(count)
            }
        }
        if (URI(link.url).path.endsWith(".m3u8")) {
            var url = link.url
            repeat(3) {
                val text = String(get(url).second)
                assertTrue(text.startsWith("#EXTM3U"))
                val next = text.lineSequence().map(String::trim).first { it.isNotBlank() && !it.startsWith('#') }
                url = URI(url).resolve(next).toString()
                if (!text.contains("#EXT-X-STREAM-INF:")) {
                    get(url, "bytes=0-4095"); println("Zona HLS: media playlist and segment verified"); return
                }
            }
            fail("Too many playlist levels")
        } else for (offset in listOf(0, 1_048_576)) {
            val (code, bytes) = get(link.url, "bytes=$offset-${offset+4095}")
            assertEquals(206, code); assertEquals(4096, bytes.size)
            if (offset == 0) assertEquals("ftyp", String(bytes,4,4))
            println("Zona MP4: ${link.quality}p; offset=$offset; bytes=${bytes.size}")
        }
    }
    private suspend fun play(data: String) {
        val links = mutableListOf<ExtractorLink>()
        assertTrue(api.loadLinks(data, false, {}, links::add)); assertTrue(links.isNotEmpty())
        // One reachable variant per resolution; production validates all emitted candidates.
        links.distinctBy { it.quality }.forEach(::sample)
        println("Zona: ${links.size} reachable streams; qualities=${links.map { it.quality }.distinct()}")
    }
    @Test fun moviesCartoonsAndAnimeFilms() = runBlocking {
        for ((query, year, slug) in listOf(Triple("Король Лев",1994,"korol-lev"),Triple("Форма голоса",2016,"forma-golosa"))) {
            val card = api.search(query).first { it.url.endsWith("/$slug") }
            val loaded = api.load(card.url) as MovieLoadResponse
            assertEquals(year, loaded.year); play(loaded.dataUrl)
            println("Verified $query ($year), type=${loaded.type}")
        }
    }
    @Test fun matrixUnavailableCdnDoesNotReachPlayer() = runBlocking {
        val loaded = api.load("${api.mainUrl}/movies/matritsa") as MovieLoadResponse
        assertEquals(1999,loaded.year)
        val links = mutableListOf<ExtractorLink>()
        try {
            api.loadLinks(loaded.dataUrl,false,{},links::add)
            links.distinctBy { it.quality }.forEach(::sample)
            println("Matrix: available CDN returned verified media")
        } catch (error: ErrorLoadingException) {
            assertTrue(links.isEmpty())
            assertTrue(error.message.orEmpty().contains("видеоссылок"))
            println("LIMITATION: Matrix catalogue exists, but supported CDN is unavailable from this checking network")
        }
    }
    @Test fun mentalistAllSeasonsAndFirstLastMedia() = runBlocking {
        val card = api.search("Менталист").first { it.url.endsWith("/mentalist-2008") }
        val loaded = api.load(card.url) as TvSeriesLoadResponse
        assertEquals(7, loaded.episodes.map { it.season }.distinct().size)
        assertEquals(150, loaded.episodes.size)
        assertEquals(1,loaded.episodes.first().episode); assertEquals(13,loaded.episodes.last().episode)
        play(loaded.episodes.first().data); play(loaded.episodes.last().data)
        println("Verified Mentalist: 7 seasons, 150 episodes")
    }
    @Test fun monsterAnimeAndGenrePagination() = runBlocking {
        val card = api.search("Монстр").first { it.url.endsWith("/monstr-2004-2") }
        val loaded = api.load(card.url) as TvSeriesLoadResponse
        assertEquals(TvType.Anime, loaded.type); assertEquals(74,loaded.episodes.size)
        play(loaded.episodes.first().data); play(loaded.episodes.last().data)
        for (request in api.mainPage) {
            val first = api.getMainPage(1, MainPageRequest(request.name,request.data,false))
            val second = api.getMainPage(2, MainPageRequest(request.name,request.data,false))
            assertTrue(first.hasNext); assertTrue(first.items.single().list.isNotEmpty()); assertTrue(second.items.single().list.isNotEmpty())
            assertNotEquals(first.items.single().list.first().url,second.items.single().list.first().url)
            println("Zona ${request.name}: pages 1 and 2 distinct")
        }
    }
}
