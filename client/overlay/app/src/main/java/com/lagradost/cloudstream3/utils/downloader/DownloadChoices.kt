package com.lagradost.cloudstream3.utils.downloader

import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import java.util.Locale

/** Prefer explicitly named voices when the extension supplies every available combination. */
internal object DownloadChoices {
    private fun voices(link: ExtractorLink): Set<String>? = runCatching {
        val text = link.extractorData?.takeIf { it.length <= 1_000_000 } ?: return@runCatching null
        val context = JSONObject(text)
        if (context.optString("kind") !in setOf("zona-dash-v1", "zona-hls-v1")) return@runCatching null
        if (context.optInt("width") <= 0 || context.optInt("height") <= 0) return@runCatching null
        val audio = context.getJSONObject("voices")
        audio.keys().asSequence().map { audio.getString(it).trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty).toSet()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun select(links: List<ExtractorLink>): List<ExtractorLink> {
        val named = links.map { it to voices(it) }
        val offered = named.filter { it.second?.size == 1 }.groupBy { it.first.quality }
            .mapValues { (_, items) -> items.flatMap { it.second.orEmpty() }.toSet() }
        val used = mutableSetOf<Pair<Int, String>>()
        return named.filter { (link, audio) ->
            when {
                audio == null -> true
                audio.size > 1 -> offered[link.quality]?.containsAll(audio) != true
                else -> used.add(link.quality to audio.single())
            }
        }.map { it.first }
    }
}
