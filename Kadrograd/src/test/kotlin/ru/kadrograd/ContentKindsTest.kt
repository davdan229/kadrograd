package ru.kadrograd

import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentKindsTest {
    @Test fun animeSeriesIsNotClassifiedAsOrdinarySeries() {
        assertEquals(TvType.Anime, ContentKinds.rezka("https://example.org/animation/adventure/42-title.html", true))
    }
    @Test fun animeMovieAndCartoonRemainInTheirCategories() {
        assertEquals(TvType.AnimeMovie, ContentKinds.rezka("https://example.org/animation/drama/42-title.html", false))
        assertEquals(TvType.Cartoon, ContentKinds.rezka("https://example.org/cartoons/comedy/42-title.html", true))
    }
    @Test fun liveActionMoviesAndSeriesKeepTheirKinds() {
        assertEquals(TvType.Movie, ContentKinds.rezka("https://example.org/films/drama/42-title.html", false))
        assertEquals(TvType.TvSeries, ContentKinds.rezka("https://example.org/series/drama/42-title.html", true))
    }
}
