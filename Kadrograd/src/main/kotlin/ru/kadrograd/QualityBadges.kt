package ru.kadrograd

import com.lagradost.cloudstream3.SearchQuality

/** Cache badges observed in available media. */
internal class QualityBadges(
    initial: Set<String> = emptySet(),
    private val now: () -> Long = System::currentTimeMillis,
    private val save: (Set<String>) -> Unit = {},
) {
    private val ttl = 6 * 60 * 60 * 1_000L
    private val entries = initial.mapNotNull { row ->
        val time = row.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
        val key = row.substringAfter('|',"")
        (key to time).takeIf { StreamTools.isHttp(key.removePrefix("dub|")) && now()-time in 0..ttl }
    }.sortedBy { it.second }.takeLast(256).toMap(linkedMapOf())
    private val dubLabel = Regex("""(?iu)(?:^|[^\p{L}])(?:дубляж|дублированн\p{L}*|дубл\.|dubbed)(?=$|[^\p{L}])""")

    @Synchronized
    fun observe(url: String, variants: List<ZonaTracks.Variant>, voices: Collection<String> = emptyList()) {
        if (!StreamTools.isHttp(url)) return
        val keys = buildList {
            if (variants.any { it.isFourK }) add(url)
            if (voices.any(dubLabel::containsMatchIn)) add("dub|$url")
        }
        var removed = false
        if (variants.isNotEmpty() && url !in keys) removed = entries.remove(url) != null
        if (voices.isNotEmpty() && "dub|$url" !in keys) removed = entries.remove("dub|$url") != null || removed
        if (keys.isEmpty() && !removed) return
        val time = now()
        entries.entries.removeAll { time-it.value !in 0..ttl }
        for (key in keys) {
            entries.remove(key)
            entries[key] = time
        }
        while (entries.size > 256) entries.remove(entries.keys.first())
        save(entries.mapTo(mutableSetOf()) { (key,stamp) -> "$stamp|$key" })
    }

    @Synchronized
    fun quality(url: String): SearchQuality? =
        if (entries[url]?.let { now()-it in 0..ttl } == true) SearchQuality.FourK else null

    @Synchronized
    fun dubbed(url: String): Boolean = entries["dub|$url"]?.let { now()-it in 0..ttl } == true

    @Synchronized
    fun fourKPages(): List<String> = entries.entries.asSequence()
        .filter { (key, stamp) -> !key.startsWith("dub|") && now()-stamp in 0..ttl }
        .map { it.key }.toList().asReversed()
}
