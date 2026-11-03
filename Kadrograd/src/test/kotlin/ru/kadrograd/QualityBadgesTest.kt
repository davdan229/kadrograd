package ru.kadrograd

import com.lagradost.cloudstream3.SearchQuality
import org.junit.Assert.*
import org.junit.Test

class QualityBadgesTest {
    @Test fun onlyObservedUhdIsMarkedAndCacheSurvivesRecreationUntilExpiry() {
        val movie = "https://example.org/movies/film"
        val series = "https://example.org/tvseries/series"
        var time = 1_000L
        var saved = emptySet<String>()
        val badges = QualityBadges(now={time},save={saved=it})
        badges.observe(movie,listOf(ZonaTracks.Variant(1920,1080)))
        assertNull(badges.quality(movie))
        badges.observe(series,listOf(ZonaTracks.Variant(3840,1608)))
        assertEquals(SearchQuality.FourK,badges.quality(series))
        assertNull(badges.quality("https://mirror.example.org/tvseries/series"))
        val restored = QualityBadges(saved,{time})
        assertEquals(SearchQuality.FourK,restored.quality(series))
        time += 6 * 60 * 60 * 1_000L + 1
        assertNull(restored.quality(series))
    }
}
