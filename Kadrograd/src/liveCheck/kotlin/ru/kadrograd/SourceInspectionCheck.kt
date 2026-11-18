package ru.kadrograd

import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit diagnostic: print available resolutions without publishing signed media URLs. */
class SourceInspectionCheck {
    @Test fun inspectRequestedSeries() = runBlocking<Unit> {
        val query = System.getenv("KADROGRAD_INSPECT_SERIES").orEmpty().trim()
        assumeTrue("Set KADROGRAD_INSPECT_SERIES for a series inspection", query.isNotEmpty())
        val api: MainAPI = when (System.getenv("KADROGRAD_SERIES_SOURCE") ?: "zona") {
            "zona" -> ZonaProvider("https://w1.zona.im")
            "filmix" -> FilmixProvider("https://filmix.gg")
            "kinovibe" -> VibeProvider("https://kinovibe.cc")
            else -> error("Unknown source")
        }
        fun normalized(value: String) = value.lowercase(java.util.Locale.ROOT).replace('ё', 'е')
            .replace(Regex("^(мультсериал|сериал)\\s+"), "")
            .replace(Regex("\\s*\\(\\d{4}(?:\\s*[-–]\\s*\\d{4})?\\)\\s*$"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val requestedUrl = System.getenv("KADROGRAD_SERIES_URL")?.takeIf(String::isNotBlank)
        val url = requestedUrl ?: api.search(query).orEmpty().let { results ->
            println("Search candidates from ${api.name}: ${results.take(8).map { it.name to it.url }}")
            results.firstOrNull { normalized(it.name) == normalized(query) }?.url
        } ?: throw IllegalStateException("Series not found by exact title")
        val show = api.load(url) as? TvSeriesLoadResponse ?: error("Expected an episodic series")
        val episodes = show.episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        fun key(ep: com.lagradost.cloudstream3.Episode) = "${ep.season ?: 0}:${ep.episode ?: 0}"
        println("Series ${show.name} (${show.year}) $url: ${episodes.map(::key)}")
        val requested = System.getenv("KADROGRAD_SERIES_EPISODES").orEmpty().split(',').map(String::trim)
            .filter(String::isNotBlank).distinct()
        require(requested.size <= 15 && requested.all { Regex("\\d+:\\d+").matches(it) }) {
            "Select up to 15 season:episode pairs per pass"
        }
        val failures = mutableListOf<String>()
        for (pair in requested) {
            val episode = episodes.firstOrNull { key(it) == pair }
            if (episode == null) {
                failures += "$pair: missing from the catalog"
                continue
            }
            try {
                val links = java.util.concurrent.ConcurrentLinkedQueue<ExtractorLink>()
                api.loadLinks(episode.data, false, {}, links::add)
                val choices = links.map { it.quality to it.name }.distinct().sortedByDescending { it.first }
                println("Episode $pair: $choices")
                if (choices.isEmpty()) failures += "$pair: no available streams"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val reason = failure.message?.replace(Regex("https?://[^\\s]+"), "<url>")?.take(180)
                failures += "$pair: ${failure.javaClass.simpleName} $reason"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun inspectRequestedAnime() = runBlocking<Unit> {
        val query = System.getenv("KADROGRAD_INSPECT_ANIME").orEmpty()
        assumeTrue("Set KADROGRAD_INSPECT_ANIME for anime source inspection", query.isNotBlank())
        for (api in listOf(LibriaProvider("https://anilibria.top"), VostProvider("https://v14.vost.pw"), ZonaProvider("https://w1.zona.im"))) {
            try {
                val cards = api.search(query).orEmpty().take(5)
                println("Anime source=${api.name} cards=${cards.map { it.name }}")
                for (card in cards) {
                    val title = api.load(card.url) ?: continue
                    val episodes = when (title) {
                        is AnimeLoadResponse -> title.episodes.values.flatten()
                        is TvSeriesLoadResponse -> title.episodes
                        else -> emptyList()
                    }
                    val episode = episodes.firstOrNull() ?: continue
                    val links = mutableListOf<ExtractorLink>()
                    api.loadLinks(episode.data, false, {}, links::add)
                    println("Anime source=${api.name} title=${title.name} season=${episode.season} episode=${episode.episode}: ${links.map { it.quality to it.name }.distinct()}")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                println("Anime source=${api.name} unavailable=${failure.javaClass.simpleName}")
            }
        }
    }

    @Test fun inspectRequestedNativePlayers() = runBlocking<Unit> {
        val keys = System.getenv("KADROGRAD_INSPECT_NATIVE_KEYS").orEmpty().split(',')
            .map(String::trim).filter { it.matches(Regex("\\d+")) }.distinct().take(10)
        assumeTrue("Set KADROGRAD_INSPECT_NATIVE_KEYS for isolated native player diagnostics", keys.isNotEmpty())
        val tracks = ZonaTracks({ listOf("https://vsw01.zonasearch.com", "https://vsr01.zonasearch.com") },
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        for (key in keys) {
            try {
                tracks.load(key, null, null, { found ->
                    println("Native key=$key format=${found.format} variants=${found.variants} voices=${found.context.voices.values.distinct()}")
                }, onFailure = { failure ->
                    val reason = failure.message?.replace(Regex("https?://\\S+"), "<url>")
                    println("Native key=$key unavailable=${failure.javaClass.simpleName} reason=$reason")
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                println("Native key=$key unavailable=${failure.javaClass.simpleName}")
            }
        }
    }

    @Test fun inspectRequestedMovie() = runBlocking<Unit> {
        val queries = (System.getenv("KADROGRAD_INSPECT_TITLES") ?: System.getenv("KADROGRAD_INSPECT_TITLE"))
            .orEmpty().lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().take(25).toList()
        assumeTrue("Set KADROGRAD_INSPECT_TITLE or KADROGRAD_INSPECT_TITLES to run source inspections", queries.isNotEmpty())
        val api = ZonaProvider("https://w1.zona.im")
        val filmTypes = setOf(TvType.Movie, TvType.Cartoon, TvType.AnimeMovie)
        val year = System.getenv("KADROGRAD_INSPECT_YEAR")?.toIntOrNull()
        val estimateFullHd = System.getenv("KADROGRAD_ESTIMATE_FULL_HD") == "1"
        val failures = mutableListOf<String>()
        for (query in queries) {
            try {
                val cards = api.search(query).filter { it.type in filmTypes && it.name.equals(query, ignoreCase = true) }
                assertTrue("No exact movie match for $query", cards.isNotEmpty())
                var matchedYear = false
                for (card in cards) {
                    val movie = api.load(card.url) as MovieLoadResponse
                    if (year != null && movie.year != year) continue
                    matchedYear = true
                    val links = mutableListOf<ExtractorLink>()
                    assertTrue(api.loadLinks(movie.dataUrl, false, {}, links::add))
                    println("${movie.name} (${movie.year}) ${card.url}: ${links.map { it.quality to it.name }.distinct()}")
                    if (estimateFullHd) {
                        val fullHd = links.filter { it.quality == 1080 && it.type == ExtractorLinkType.DASH }
                        val link = fullHd.firstOrNull { ZonaTracks.context(it)?.voices?.size == 1 } ?: fullHd.firstOrNull()
                        val estimate = link?.let {
                            try { estimateDashTransfer(api, it) }
                            catch (_: java.io.IOException) { null }
                        }
                        println("Full HD estimate: durationMinutes=${movie.duration}, mediaBytes=$estimate")
                    }
                }
                assertTrue("No exact movie match for $query in $year", matchedYear)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures += "$query: ${failure.javaClass.simpleName}"
            } catch (failure: AssertionError) {
                failures += "$query: expected catalog or playback data missing"
            }
        }
        assertTrue("Source inspections unavailable: $failures", failures.isEmpty())
    }

    private suspend fun estimateDashTransfer(api: MainAPI, link: ExtractorLink): Long? = withContext(Dispatchers.IO) {
        val interceptor = api.getVideoInterceptor(link) ?: return@withContext null
        val client = SourceHttp.client().newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(interceptor).build()
        val request = Request.Builder().url(link.url).apply {
            link.headers.forEach { (key, value) -> header(key, value) }
            if (link.referer.isNotBlank()) header("Referer", link.referer)
        }.build()
        val manifest = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            ZonaTracks.manifest(response.peekBody(2_000_000).string())
        } ?: return@withContext null
        val base = java.net.URI(link.url).let { uri ->
            manifest.getElementsByTag("BaseURL").firstOrNull()?.text()?.let(uri::resolve) ?: uri
        }
        val tracks = manifest.getElementsByTag("AdaptationSet").filter { it.attr("contentType") in setOf("video", "audio") }
        if (tracks.size != 2) return@withContext null
        var bytes = 0L
        for (track in tracks) {
            val representation = track.getElementsByTag("Representation").singleOrNull() ?: return@withContext null
            val template = representation.getElementsByTag("SegmentTemplate").firstOrNull() ?: return@withContext null
            val media = template.attr("media").takeIf { it.contains("\$Number\$") } ?: return@withContext null
            val start = template.attr("startNumber").toIntOrNull() ?: 1
            val count = template.getElementsByTag("S").sumOf { 1 + (it.attr("r").toIntOrNull() ?: 0) }
            if (count !in 4..200_000) return@withContext null
            val samples = listOf(count / 8, count / 3, count * 2 / 3, count * 7 / 8).distinct()
            val lengths = samples.map { offset ->
                val url = base.resolve(media.replace("\$Number\$", (start + offset).toString())).toString()
                client.newCall(request.newBuilder().url(url).header("Range", "bytes=0-0").build()).execute().use { response ->
                    if (response.code != 206) null
                    else response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
                }
            }
            if (lengths.any { it == null }) return@withContext null
            bytes += lengths.filterNotNull().sum() * count / samples.size
        }
        bytes
    }
}
