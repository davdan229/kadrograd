package com.lagradost.cloudstream3.utils.downloader

import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import java.util.Locale

/** Prefer explicitly named voices when the extension supplies every available combination. */
internal object DownloadChoices {
    private data class Stream(val url: String, val kind: String, val width: Int, val height: Int)
    private data class Offer(val stream: Stream, val voices: Set<String>)

    private fun offer(link: ExtractorLink): Offer? = runCatching {
        val text = link.extractorData?.takeIf { it.length <= 1_000_000 } ?: return@runCatching null
        val context = JSONObject(text)
        val kind = context.optString("kind")
        if (kind !in setOf("zona-dash-v1", "zona-hls-v1")) return@runCatching null
        val width = context.optInt("width")
        val height = context.optInt("height")
        if (width <= 0 || height <= 0) return@runCatching null
        val audio = context.getJSONObject("voices")
        val voices = audio.keys().asSequence().map { audio.getString(it).trim().lowercase(Locale.ROOT) }
            .filter(String::isNotEmpty).toSet()
        if (voices.isEmpty()) return@runCatching null
        Offer(Stream(link.url.substringBefore('#'), kind, width, height), voices)
    }.getOrNull()

    fun select(links: List<ExtractorLink>): List<ExtractorLink> {
        val named = links.map { it to offer(it) }
        val offered = named.mapNotNull { it.second }.filter { it.voices.size == 1 }.groupBy { it.stream }
            .mapValues { (_, items) -> items.flatMap { it.voices }.toSet() }
        val used = mutableSetOf<Offer>()
        return named.filter { (_, offer) ->
            when {
                offer == null -> true
                offer.voices.size > 1 -> offered[offer.stream]?.containsAll(offer.voices) != true
                else -> used.add(offer)
            }
        }.map { it.first }
    }
}
