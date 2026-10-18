package ru.kadrograd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class LibriaProvider(baseUrl: String) : MainAPI() {
    override var mainUrl = baseUrl
    override var name = "Кадроград · AniLibria"
    override var lang = "ru"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf("all" to "Аниме — весь каталог", "ongoing" to "Аниме — выходит сейчас")
    private val api get() = "$mainUrl/api/v1"

    private fun kind(release: Release) = when (release.type?.value) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "ONA", "SPECIAL" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun image(path: String?) = path?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("https://") || it.startsWith("http://")) it else "$mainUrl/${it.trimStart('/')}"
    }

    private fun card(release: Release): SearchResponse? {
        val title = release.name?.main?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(title, "$api/anime/releases/${release.id}", kind(release)) {
            posterUrl = image(release.poster?.optimized?.src ?: release.poster?.src)
            year = release.year
            addDubStatus(true, release.episodesTotal)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val extra = if (request.data == "ongoing") "&f[is_ongoing]=true" else ""
        val raw = SourceHttp.get("AniLibria", "$api/anime/catalog/releases?limit=24&page=$page$extra")
        if (raw.code !in 200..299) throw ErrorLoadingException("AniLibria: сервер каталога вернул HTTP ${raw.code}")
        val response = raw.parsedSafe<Catalog>() ?: throw ErrorLoadingException("AniLibria: каталог недоступен")
        val pagination = response.meta?.pagination
        return newHomePageResponse(request.name, response.data.mapNotNull(::card),
            hasNext = pagination?.let { it.currentPage < it.totalPages } ?: (response.data.size == 24))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return tryParseJson<List<Release>>(SourceHttp.get("AniLibria", "$api/app/search/releases?query=$encoded").text)
            ?.mapNotNull(::card)
            ?: throw ErrorLoadingException("AniLibria: поиск временно недоступен")
    }

    private suspend fun release(id: Int): Release {
        if (id <= 0) throw ErrorLoadingException("AniLibria: неверный номер релиза")
        val raw = SourceHttp.get("AniLibria", "$api/anime/releases/$id")
        if (raw.code !in 200..299) throw ErrorLoadingException("AniLibria: сервер карточки вернул HTTP ${raw.code}")
        val item = raw.parsedSafe<Release>() ?: throw ErrorLoadingException("AniLibria: не удалось получить карточку")
        if (item.id != id) throw ErrorLoadingException("AniLibria: ответ относится к другому релизу")
        return item
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast('/').toIntOrNull() ?: throw ErrorLoadingException("AniLibria: неверный номер релиза")
        val item = release(id)
        if (item.blockedGeo || item.blockedCopyright) throw ErrorLoadingException("AniLibria ограничила доступ к этому релизу")
        val title = item.name?.main ?: throw ErrorLoadingException("AniLibria: отсутствует название")
        val episodes = item.episodes.sortedBy { it.ordinal }.map { ep ->
            newEpisode(EpisodeRef(id, ep.id).toJson(), {
                name = ep.name?.takeIf { it.isNotBlank() } ?: "Серия ${ep.ordinal}"
                episode = ep.ordinal
                posterUrl = image(ep.preview?.src)
            }, fix = false)
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("AniLibria: опубликованных серий пока нет")
        return newAnimeLoadResponse(title, url, kind(item)) {
            posterUrl = image(item.poster?.optimized?.src ?: item.poster?.src)
            year = item.year
            plot = item.description
            tags = item.genres.mapNotNull { it.name }
            engName = item.name?.english
            showStatus = if (item.ongoing) ShowStatus.Ongoing else ShowStatus.Completed
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val ref = tryParseJson<EpisodeRef>(data) ?: return false
        // Resolve at playback time: signed CDN URLs must not be saved in watch history.
        val item = release(ref.releaseId)
        if (item.blockedGeo || item.blockedCopyright) throw ErrorLoadingException("AniLibria ограничила доступ к релизу")
        val ep = item.episodes.firstOrNull { it.id == ref.episodeId } ?: return false
        var found = false
        for ((quality, url) in listOf(1080 to ep.hls1080, 720 to ep.hls720, 480 to ep.hls480)) {
            if (url == null || !StreamTools.isHttp(url)) continue
            callback(newExtractorLink(name, "AniLibria · $quality p · HLS", url, ExtractorLinkType.M3U8) {
                this.quality = quality
                referer = "$mainUrl/"
            })
            found = true
        }
        return found
    }

    data class EpisodeRef(@JsonProperty("releaseId") val releaseId: Int,
        @JsonProperty("episodeId") val episodeId: String)
    data class Label(@JsonProperty("value") val value: String? = null, @JsonProperty("name") val name: String? = null)
    data class Names(@JsonProperty("main") val main: String? = null, @JsonProperty("english") val english: String? = null)
    data class Image(@JsonProperty("src") val src: String? = null, @JsonProperty("optimized") val optimized: Image? = null)
    data class Release(
        @JsonProperty("id") val id: Int = 0, @JsonProperty("name") val name: Names? = null,
        @JsonProperty("type") val type: Label? = null, @JsonProperty("year") val year: Int? = null,
        @JsonProperty("poster") val poster: Image? = null, @JsonProperty("description") val description: String? = null,
        @JsonProperty("is_ongoing") val ongoing: Boolean = false,
        @JsonProperty("is_blocked_by_geo") val blockedGeo: Boolean = false,
        @JsonProperty("is_blocked_by_copyrights") val blockedCopyright: Boolean = false,
        @JsonProperty("episodes_total") val episodesTotal: Int? = null,
        @JsonProperty("genres") val genres: List<Label> = emptyList(),
        @JsonProperty("episodes") val episodes: List<LibriaEpisode> = emptyList())
    data class LibriaEpisode(@JsonProperty("id") val id: String = "", @JsonProperty("ordinal") val ordinal: Int = 0,
        @JsonProperty("name") val name: String? = null, @JsonProperty("preview") val preview: Image? = null,
        @JsonProperty("hls_480") val hls480: String? = null, @JsonProperty("hls_720") val hls720: String? = null,
        @JsonProperty("hls_1080") val hls1080: String? = null)
    data class Catalog(@JsonProperty("data") val data: List<Release>, @JsonProperty("meta") val meta: Meta? = null)
    data class Meta(@JsonProperty("pagination") val pagination: Pagination? = null)
    data class Pagination(@JsonProperty("current_page") val currentPage: Int = 1, @JsonProperty("total_pages") val totalPages: Int = 1)
}
