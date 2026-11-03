package ru.kadrograd

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.atomic.AtomicBoolean

abstract class QualityProvider : MainAPI() {
    @Volatile internal var fullHdOnly = false

    final override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        if (!fullHdOnly) {
            val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val found = loadSourceLinks(data,isCasting,subtitleCallback,links::add)
            links.sortedWith(compareByDescending<ExtractorLink> { it.quality == 1080 }.thenByDescending { it.quality })
                .forEach(callback)
            return found
        }
        val accepted = AtomicBoolean(false)
        val filtered = AtomicBoolean(false)
        val subtitles = java.util.Collections.synchronizedList(mutableListOf<SubtitleFile>())
        loadSourceLinks(data,isCasting,subtitles::add) { link ->
            if (link.quality >= 1080) {
                accepted.set(true)
                callback(link)
            } else filtered.set(true)
        }
        if (accepted.get()) {
            subtitles.distinctBy { it.lang to it.url }.forEach(subtitleCallback)
            return true
        }
        if (filtered.get()) throw ErrorLoadingException("$name: источник не предложил доступный поток 1080p или выше. Режим можно изменить в настройках Кадрограда.")
        return false
    }

    protected abstract suspend fun loadSourceLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean
}
