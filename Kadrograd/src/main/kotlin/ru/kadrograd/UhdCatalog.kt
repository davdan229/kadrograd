package ru.kadrograd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

/** Stores title identities and observed dimensions. Playback URLs are requested afresh. */
internal class UhdCatalog {
    data class Movie(
        @JsonProperty("slug") val slug: String = "",
        @JsonProperty("kpId") val kpId: Int = 0,
        @JsonProperty("width") val width: Int = 0,
        @JsonProperty("height") val height: Int = 0,
        @JsonProperty("checked") val checked: Long = 0,
    )
    data class Snapshot(
        @JsonProperty("version") val version: Int = 1,
        @JsonProperty("cursor") val cursor: Int = 1,
        @JsonProperty("offset") val offset: Int = 0,
        @JsonProperty("movies") val movies: List<Movie> = emptyList(),
    )

    private var cache: Pair<Long, List<Movie>>? = null

    internal fun parse(text: String, now: Long = System.currentTimeMillis()): List<Movie> {
        require(text.length <= 2_000_000)
        val snapshot = requireNotNull(tryParseJson<Snapshot>(text))
        require(snapshot.version == 1 && snapshot.movies.size <= 20_000)
        return snapshot.movies.filter {
            Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(it.slug) && it.kpId > 0 &&
                ZonaTracks.Variant(it.width, it.height).isFourK &&
                now - it.checked in 0..(3 * 24 * 60 * 60 * 1_000L)
        }.distinctBy { it.kpId }.sortedByDescending { it.checked }
    }

    suspend fun movies(url: String?): List<Movie> {
        if (url == null) return emptyList()
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.first in 0..300_000 }?.let { return it.second }
        return try {
            val text = SourceHttp.get("4K-каталог", url, timeoutSeconds = 3).text
            parse(text).also { cache = now to it }
        } catch (_: ErrorLoadingException) { emptyList() }
          catch (_: IllegalArgumentException) { emptyList() }
    }
}
