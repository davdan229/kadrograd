package ru.kadrograd

import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
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
        val failures = mutableListOf<String>()
        for (query in queries) {
            try {
                val cards = api.search(query).filter { it.type == TvType.Movie && it.name.equals(query, ignoreCase = true) }
                assertTrue("No exact movie match for $query", cards.isNotEmpty())
                for (card in cards) {
                    val movie = api.load(card.url) as MovieLoadResponse
                    val links = mutableListOf<ExtractorLink>()
                    assertTrue(api.loadLinks(movie.dataUrl, false, {}, links::add))
                    println("${movie.name} (${movie.year}) ${card.url}: ${links.map { it.quality to it.name }.distinct()}")
                }
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
}
