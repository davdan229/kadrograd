package ru.kadrograd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in real HTTP checks: -PliveSources --tests '*LiveSourcesCheck'. No Android UI. */
class LiveSourcesCheck {
    @Test fun animeVostPublicApiKeepsCatalogSearchAndDetailsWhenWebIsUnavailable() = runBlocking {
        val api = VostProvider("http://127.0.0.1:9").apply {
            catalogEndpoint = "https://api.animevost.org/v1/last"
            searchEndpoint = "https://api.animevost.org/v1/search"
            infoEndpoint = "https://api.animevost.org/v1/info"
        }
        val first = api.getMainPage(1, MainPageRequest("Каталог", "all", false))
        val next = api.getMainPage(2, MainPageRequest("Каталог", "all", false))
        assertTrue(first.items.single().list.isNotEmpty())
        assertTrue(next.items.single().list.isNotEmpty())
        assertNotEquals(first.items.single().list.first().url, next.items.single().list.first().url)
        for (item in first.items.single().list.take(3)) {
            assertTrue(api.load(item.url).name.isNotBlank())
        }
        val movie = api.search("Форма голоса").first { (it as? AnimeSearchResponse)?.year == 2016 }
        assertTrue(api.load(movie.url) is MovieLoadResponse)
    }
    @Test fun aniLibriaStrictFullHdReturnsMedia() = runBlocking {
        val api = LibriaProvider("https://anilibria.top").apply { fullHdOnly = true }
        val card = api.search("Магическая битва").first { (it as? AnimeSearchResponse)?.year == 2020 }
        val release = api.load(card.url) as AnimeLoadResponse
        val streams = links(api,release.episodes.getValue(DubStatus.Dubbed).first().data)
        assertTrue(streams.all { it.quality == 1080 })
        var url = streams.first().url
        repeat(3) {
            val manifest = SourceHttp.get("AniLibria",url,referer=api.mainUrl).text
            assertTrue(manifest.trimStart().startsWith("#EXTM3U"))
            val part = manifest.lineSequence().map(String::trim).first { it.isNotEmpty() && !it.startsWith('#') }
            url = java.net.URI(url).resolve(part).toString()
            if (manifest.contains("#EXTINF:")) {
                SourceHttp.get("AniLibria",url,referer=api.mainUrl,headers=mapOf("Range" to "bytes=0-4095"))
                    .okhttpResponse.use { response -> assertTrue(response.body.source().readByteArray(16).isNotEmpty()) }
                return@runBlocking
            }
        }
        fail("Expected an HLS media playlist")
    }
    private fun sample(link: ExtractorLink) {
        val client = app.baseClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        for (start in listOf(0, 1_048_576)) {
            val request = Request.Builder().url(link.url).header("Referer", link.referer)
                .header("Range", "bytes=$start-${start + 4095}").build()
            client.newCall(request).execute().use { response ->
                assertEquals("Video must support partial requests", 206, response.code)
                assertTrue(response.header("Content-Type").orEmpty().startsWith("video/"))
                val buffer = ByteArray(4096)
                var total = 0
                val input = response.body.byteStream()
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
                assertEquals(4096, total)
                if (start == 0) assertEquals("ftyp", String(buffer, 4, 4))
                println("${link.source}: quality=${link.quality}; HTTP ${response.code}; offset=$start; bytes=$total")
            }
        }
    }
    private suspend fun links(api: MainAPI, data: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        assertTrue(api.loadLinks(data, false, {}, links::add))
        assertTrue(links.isNotEmpty())
        return links
    }
    @Test fun animeVostSearchDetailsEpisodesAndMedia() = runBlocking {
        val api = VostProvider("https://v14.vost.pw")
        assertTrue(api.getMainPage(2, MainPageRequest("Каталог", "all", false)).items.single().list.isNotEmpty())
        for ((query, year, expected) in listOf(Triple("Тетрадь смерти", 2006, 37), Triple("Монстр", 2004, 74), Triple("Форма голоса", 2016, 1))) {
            val found = api.search(query).filterIsInstance<AnimeSearchResponse>().first { it.year == year && it.name.contains(query, true) }
            val loaded = api.load(found.url)
            val data = when (loaded) {
                is MovieLoadResponse -> { assertEquals(1, expected); listOf(loaded.dataUrl) }
                is AnimeLoadResponse -> loaded.episodes.getValue(DubStatus.Dubbed).let {
                    assertEquals(expected, it.size); listOf(it.first().data, it.last().data).distinct()
                }
                else -> error("Unexpected load response")
            }
            data.forEach { episode -> links(api, episode).forEach(::sample) }
            println("$query ($year): $expected published videos")
        }
    }
    @Test fun kinoVibeMovieAndCartoonMedia() = runBlocking {
        val api = VibeProvider("https://kinovibe.cc")
        for ((query, year) in listOf("Матрица" to 1999, "Король Лев" to 1994)) {
            val found = api.search(query).first { it.name.contains(query, true) && it.name.contains("($year)") }
            val loaded = api.load(found.url) as MovieLoadResponse
            assertEquals(year, loaded.year)
            links(api, loaded.dataUrl).forEach(::sample)
            println("$query ($year): guest stream verified")
        }
    }
    @Test fun kinoVibeOldAndCurrentSeriesMedia() = runBlocking {
        val api = VibeProvider("https://kinovibe.cc")
        val old = api.search("Менталист").first { it.name.contains("7 Сезон") }
        val latest = api.getMainPage(1, MainPageRequest("Сериалы", "serial", false)).items.single().list.first()
        for (card in listOf(old, latest)) {
            val loaded = api.load(card.url) as TvSeriesLoadResponse
            assertTrue(loaded.episodes.isNotEmpty())
            for (data in listOf(loaded.episodes.first().data, loaded.episodes.last().data).distinct())
                links(api, data).forEach(::sample)
            println("${loaded.name}: ${loaded.episodes.size} published episodes")
        }
    }
    @Test fun kinoVibeCategoryPagination() = runBlocking {
        val api = VibeProvider("https://kinovibe.cc")
        for (category in listOf("films", "serial", "cartoon", "anime")) {
            val first = api.getMainPage(1, MainPageRequest(category, category, false))
            assertTrue(first.hasNext)
            assertTrue(first.items.single().list.isNotEmpty())
            val next = api.getMainPage(2, MainPageRequest(category, category, false))
            assertTrue(next.items.single().list.isNotEmpty())
            assertNotEquals(first.items.single().list.first().url, next.items.single().list.first().url)
            println("KinoVibe $category: pages 1 and 2 distinct")
        }
    }
}
