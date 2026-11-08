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
    @Test fun inspectRequestedMovie() = runBlocking<Unit> {
        val query = System.getenv("KADROGRAD_INSPECT_TITLE").orEmpty()
        assumeTrue("Set KADROGRAD_INSPECT_TITLE to run a source inspection", query.isNotBlank())
        val api = ZonaProvider("https://w1.zona.im")
        val cards = api.search(query).filter { it.type == TvType.Movie && it.name.equals(query, ignoreCase = true) }
        assertTrue("No exact movie match for the requested title", cards.isNotEmpty())
        for (card in cards) {
            val movie = api.load(card.url) as MovieLoadResponse
            val links = mutableListOf<ExtractorLink>()
            assertTrue(api.loadLinks(movie.dataUrl, false, {}, links::add))
            println("${movie.name} (${movie.year}) ${card.url}: ${links.map { it.quality to it.name }.distinct()}")
        }
    }
}
