package ru.kadrograd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI

/** Public AnimeVost mobile API; no saved video URLs or account required. */
class VostProvider(baseUrl: String) : MainAPI() {
    override var mainUrl = baseUrl
    override var name = "Кадроград · AnimeVost"
    override var lang = "ru"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf("last" to "Аниме — каталог AnimeVost")

    private fun text(value: String) = Jsoup.parse(value).text()
    private fun kind(item: Release) = when {
        item.type.contains("фильм", true) -> TvType.AnimeMovie
        item.type.equals("OVA", true) || item.type.equals("ONA", true) -> TvType.OVA
        else -> TvType.Anime
    }
    private fun card(item: Release): SearchResponse? {
        if (item.id <= 0 || item.title.isBlank()) return null
        return newAnimeSearchResponse(text(item.title), "$mainUrl/v1/info?id=${item.id}", kind(item)) {
            posterUrl = mediaUrl(item.poster)
            year = item.year.toIntOrNull()
            addDubStatus(true)
        }
    }

    private fun envelope(status: Int, body: String): Envelope {
        if (status !in 200..299) throw ErrorLoadingException("AnimeVost: сервер вернул HTTP $status")
        val result = tryParseJson<Envelope>(body)
        if (result?.state?.status != "ok" || result.data == null)
            throw ErrorLoadingException("AnimeVost: изменился ответ сервера или источник недоступен")
        return result
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val raw = app.get("$mainUrl/v1/last?page=$page&quantity=24", timeout = 20_000)
        val result = envelope(raw.code, raw.text)
        val items = result.data.orEmpty()
        return newHomePageResponse(request.name, items.mapNotNull(::card), hasNext =
            result.state?.count?.let { page.toLong() * 24 < it } ?: (items.size == 24))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val raw = app.post("$mainUrl/v1/search", data = mapOf("name" to query.trim()), timeout = 20_000)
        return envelope(raw.code, raw.text).data.orEmpty().mapNotNull(::card)
    }

    private suspend fun release(id: Int): Release {
        val raw = app.post("$mainUrl/v1/info", data = mapOf("id" to id.toString()), timeout = 20_000)
        return envelope(raw.code, raw.text).data.orEmpty().firstOrNull { it.id == id }
            ?: throw ErrorLoadingException("AnimeVost: запрошенный релиз не найден")
    }

    private suspend fun playlist(id: Int): List<VostEpisode> {
        val raw = app.post("$mainUrl/v1/playlist", data = mapOf("id" to id.toString()), timeout = 20_000)
        if (raw.code !in 200..299) throw ErrorLoadingException("AnimeVost: список серий вернул HTTP ${raw.code}")
        return tryParseJson<List<VostEpisode>>(raw.text)
            ?: throw ErrorLoadingException("AnimeVost: список серий недоступен")
    }

    override suspend fun load(url: String): LoadResponse {
        val id = runCatching { URI(url).query?.substringAfter("id=")?.toIntOrNull() }.getOrNull()
            ?.takeIf { it > 0 } ?: throw ErrorLoadingException("AnimeVost: неверный номер релиза")
        val item = release(id)
        val episodes = playlist(id).filter { it.key() != null && (mediaUrl(it.hd) != null || mediaUrl(it.std) != null) }
            .distinctBy { it.key() }
        if (episodes.isEmpty()) throw ErrorLoadingException("AnimeVost: опубликованных видео пока нет")
        val title = text(item.title)
        val type = kind(item)
        if (type == TvType.AnimeMovie && episodes.size == 1) {
            return newMovieLoadResponse(title, url, type, EpisodeRef(id, episodes.single().key()!!).toJson()) {
                posterUrl = mediaUrl(item.poster)
                year = item.year.toIntOrNull()
                plot = text(item.description)
                tags = item.genre.split(',').map(String::trim).filter(String::isNotEmpty)
            }
        }
        return newAnimeLoadResponse(title, url, type) {
            posterUrl = mediaUrl(item.poster)
            year = item.year.toIntOrNull()
            plot = text(item.description)
            tags = item.genre.split(',').map(String::trim).filter(String::isNotEmpty)
            addEpisodes(DubStatus.Dubbed, episodes.map { ep ->
                newEpisode(EpisodeRef(id, ep.key()!!).toJson(), {
                    name = text(ep.name)
                    episode = Regex("^(\\d+)\\s+серия").find(ep.name)?.groupValues?.get(1)?.toIntOrNull()
                    posterUrl = mediaUrl(ep.preview)
                }, fix = false)
            })
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val ref = tryParseJson<EpisodeRef>(data)?.takeIf { it.releaseId > 0 && it.key.isNotBlank() } ?: return false
        // Fetch again so history contains an episode identity, never a stale CDN URL.
        val episode = playlist(ref.releaseId).firstOrNull { it.key() == ref.key } ?: return false
        val seen = HashSet<String>()
        for ((label, quality, raw) in listOf(Triple("720p", 720, episode.hd),
            Triple("SD", Qualities.Unknown.value, episode.std))) {
            val stream = mediaUrl(raw) ?: continue
            if (!seen.add(stream)) continue
            callback(newExtractorLink(name, "AnimeVost · $label · MP4", stream, ExtractorLinkType.VIDEO) {
                this.quality = quality
                referer = "https://animevost.org/"
            })
        }
        return seen.isNotEmpty()
    }

    private fun mediaUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf(StreamTools::isHttp) ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        // HTTPS has been checked on these legacy HTTP hosts; do not rewrite unknown hosts.
        return if (uri.scheme == "http" && uri.host in setOf("video.animetop.info", "media.animetop.info"))
            "https://" + value.substringAfter("://") else value
    }

    data class EpisodeRef(@JsonProperty("releaseId") val releaseId: Int, @JsonProperty("key") val key: String)
    data class State(@JsonProperty("status") val status: String = "", @JsonProperty("count") val count: Int? = null)
    data class Envelope(@JsonProperty("state") val state: State? = null, @JsonProperty("data") val data: List<Release>? = null)
    data class Release(@JsonProperty("id") val id: Int = 0, @JsonProperty("title") val title: String = "",
        @JsonProperty("description") val description: String = "", @JsonProperty("genre") val genre: String = "",
        @JsonProperty("year") val year: String = "", @JsonProperty("type") val type: String = "",
        @JsonProperty("urlImagePreview") val poster: String? = null)
    data class VostEpisode(@JsonProperty("name") val name: String = "", @JsonProperty("hd") val hd: String? = null,
        @JsonProperty("std") val std: String? = null, @JsonProperty("preview") val preview: String? = null) {
        fun key(): String? = sequenceOf(hd, std).mapNotNull { value ->
            runCatching { URI(value ?: return@mapNotNull null).path?.substringAfterLast('/') }
                .getOrNull()?.takeIf { it.matches(Regex("\\d+\\.mp4")) }
        }.firstOrNull() ?: name.trim().takeIf(String::isNotEmpty)?.let { "name:$it" }
    }
}
