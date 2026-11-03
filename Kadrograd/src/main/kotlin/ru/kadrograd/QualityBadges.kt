package ru.kadrograd

import com.lagradost.cloudstream3.SearchQuality

/** Cache only dimensions observed in an available media representation. */
internal class QualityBadges(
    initial: Set<String> = emptySet(),
    private val now: () -> Long = System::currentTimeMillis,
    private val save: (Set<String>) -> Unit = {},
) {
    private val ttl = 6 * 60 * 60 * 1_000L
    private val entries = initial.mapNotNull { row ->
        val time = row.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
        val url = row.substringAfter('|',"")
        (url to time).takeIf { StreamTools.isHttp(url) && now()-time in 0..ttl }
    }.sortedBy { it.second }.takeLast(256).toMap(linkedMapOf())

    @Synchronized
    fun observe(url: String, variants: List<ZonaTracks.Variant>) {
        if (!variants.any { it.isFourK } || !StreamTools.isHttp(url)) return
        val time = now()
        entries.entries.removeAll { time-it.value !in 0..ttl }
        entries.remove(url)
        entries[url] = time
        while (entries.size > 256) entries.remove(entries.keys.first())
        save(entries.mapTo(mutableSetOf()) { (key,stamp) -> "$stamp|$key" })
    }

    @Synchronized
    fun quality(url: String): SearchQuality? =
        if (entries[url]?.let { now()-it in 0..ttl } == true) SearchQuality.FourK else null
}
