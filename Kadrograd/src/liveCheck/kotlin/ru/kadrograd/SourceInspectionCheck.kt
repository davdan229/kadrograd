package ru.kadrograd

import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit diagnostic: print available resolutions without publishing signed media URLs. */
class SourceInspectionCheck {
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
