package com.lagradost.cloudstream3.utils.downloader

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI

/** A single, explicitly approximate size from the selected manifest, never bytes / current percent. */
internal class AdaptiveSizeEstimate {
    @Volatile var bytes: Long = 0
        private set
    private var hlsBitrate = 0L
    private var hlsVideo: String? = null

    @Synchronized fun observe(url: String, text: String) {
        if (bytes > 0 || text.length > 1_048_576) return
        if (text.contains("<!DOCTYPE", true) || text.contains("<!ENTITY", true)) return
        if (URI(url).path.endsWith(".mpd")) {
            val doc = Jsoup.parse(text, "", Parser.xmlParser())
            val mpd = doc.getElementsByTag("MPD").firstOrNull() ?: return
            val periods = mpd.getElementsByTag("Period")
            if (periods.size != 1) return
            val seconds = duration(mpd.attr("mediaPresentationDuration"))
                ?: duration(periods[0].attr("duration")) ?: return
            val tracks = periods[0].getElementsByTag("AdaptationSet").filter {
                it.attr("contentType") in setOf("audio", "video") ||
                    it.getElementsByTag("Representation").any { r -> r.hasAttr("width") || r.attr("mimeType").startsWith("audio/") }
            }
            if (tracks.size != 2 || tracks.any { it.getElementsByTag("Representation").size != 1 }) return
            val rates = tracks.map { it.getElementsByTag("Representation")[0].attr("bandwidth").toLongOrNull() ?: return }
            if (rates.any { it <= 0 }) return
            bytes = estimate(seconds, rates.sum())
        } else if (text.trimStart().startsWith("#EXTM3U")) {
            val lines = text.lineSequence().map(String::trim).toList()
            val variants = lines.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF:") }
            if (variants.size == 1) {
                val variant = variants.single()
                hlsBitrate = Regex("(?:^|[:,])AVERAGE-BANDWIDTH=(\\d+)").find(variant.value)?.groupValues?.get(1)?.toLongOrNull()
                    ?: Regex("(?:^|[:,])BANDWIDTH=(\\d+)").find(variant.value)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                hlsVideo = lines.getOrNull(variant.index + 1)?.takeUnless { it.startsWith('#') }
                    ?.let { URI(url).resolve(it).toString().substringBefore('#') }
            } else if (url.substringBefore('#') == hlsVideo && lines.any { it == "#EXT-X-ENDLIST" }) {
                val seconds = lines.filter { it.startsWith("#EXTINF:") }.sumOf { it.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0 }
                bytes = estimate(seconds, hlsBitrate)
            }
        }
    }

    private fun estimate(seconds: Double, bitrate: Long): Long {
        val value = seconds * bitrate / 8.0
        return if (seconds > 0 && bitrate > 0 && value.isFinite() && value in 1.0..16_000_000_000_000.0) value.toLong() else 0
    }

    private fun duration(value: String): Double? {
        val match = Regex("P(?:(\\d+(?:\\.\\d+)?)D)?T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?").matchEntire(value) ?: return null
        val parts = match.groupValues.drop(1).map { it.toDoubleOrNull() ?: 0.0 }
        return parts[0] * 86400 + parts[1] * 3600 + parts[2] * 60 + parts[3]
    }
}
