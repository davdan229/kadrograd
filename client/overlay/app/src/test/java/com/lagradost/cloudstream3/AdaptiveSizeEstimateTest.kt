package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.downloader.AdaptiveSizeEstimate
import org.junit.Assert.*
import org.junit.Test

class AdaptiveSizeEstimateTest {
    private fun dash(rate: Long) = """<MPD mediaPresentationDuration="PT1H30M"><Period>
        <AdaptationSet contentType="video"><Representation bandwidth="$rate"/></AdaptationSet>
        <AdaptationSet contentType="audio"><Representation bandwidth="128000"/></AdaptationSet>
        </Period></MPD>"""

    @Test fun selectedManifestEstimateStaysFixedAcrossProgressAndRefreshes() {
        val size = AdaptiveSizeEstimate()
        size.observe("https://cdn.example/movie.mpd", dash(2_000_000))
        assertEquals(1_436_400_000L, size.bytes)
        size.observe("https://cdn.example/movie.mpd", dash(8_000_000))
        assertEquals(1_436_400_000L, size.bytes)
    }

    @Test fun ambiguousOrUnknownSizeDoesNotCreateAFakeTotal() {
        val size = AdaptiveSizeEstimate()
        size.observe("https://cdn.example/movie.mpd", dash(0))
        assertEquals(0L, size.bytes)
        size.observe("https://cdn.example/video.m3u8", "#EXTM3U\n#EXTINF:8,\npart.ts\n#EXT-X-ENDLIST\n")
        assertEquals(0L, size.bytes)
    }
}
