package ru.kadrograd

import com.lagradost.cloudstream3.TvType

internal object ContentKinds {
    fun rezka(url: String, series: Boolean): TvType = when {
        "/animation/" in url -> if (series) TvType.Anime else TvType.AnimeMovie
        "/cartoons/" in url -> TvType.Cartoon
        series -> TvType.TvSeries
        else -> TvType.Movie
    }
}
