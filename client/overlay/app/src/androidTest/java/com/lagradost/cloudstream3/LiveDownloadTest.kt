package com.lagradost.cloudstream3

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.AdaptiveTransferCache
import com.lagradost.cloudstream3.utils.downloader.DownloadChoices
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in full transfer, kept separate from deterministic fixture checks. */
class LiveDownloadTest {
    @Test fun fullSelectedMovieDownloadsAndSeeksLocally() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val query = InstrumentationRegistry.getArguments().getString("movieQuery").orEmpty()
        val sourceName = InstrumentationRegistry.getArguments().getString("sourceName").orEmpty().ifBlank { "Кадроград · Zona" }
        val episodeNumber = InstrumentationRegistry.getArguments().getString("episodeNumber")?.toIntOrNull()
        require(query.isNotBlank()) { "movieQuery is required for the opt-in live transfer" }
        val directory = File(context.getExternalFilesDir(null), "live-download-${System.nanoTime()}").apply { mkdirs() }
        val id = -75184910
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val pathKey = context.getString(R.string.download_path_key)
        val previousPath = preferences.getString(pathKey, null)
        preferences.edit().putString(pathKey, directory.absolutePath).commit()
        var previousClient: okhttp3.OkHttpClient? = null
        try {
            context.getSharedPreferences("rebuild_preference", 0).edit().putString("HAS_DONE_SETUP", "true").commit()
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt("app_layout_key", 0).putBoolean("auto_update_plugins", false).commit()
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(60_000) {
                    while (getApiFromNameNull(sourceName) == null) delay(100)
                }
                val provider = checkNotNull(getApiFromNameNull(sourceName))
                val baseClient = app.baseClient
                previousClient = baseClient
                app.baseClient = baseClient.newBuilder().addInterceptor { chain ->
                    val host = chain.request().url.host
                    try {
                        chain.proceed(chain.request()).also { response ->
                            android.util.Log.i("KadrogradDownloadTest", "Source HTTP $host ${response.code}")
                        }
                    } catch (error: java.io.IOException) {
                        android.util.Log.w("KadrogradDownloadTest", "Source HTTP $host ${error.javaClass.simpleName}")
                        throw error
                    }
                }.build()
                assertTrue(provider.hasDownloadSupport)
                val hit = checkNotNull(provider.search(query)?.firstOrNull { it.name.equals(query, true) }) { "Exact movie not found" }
                val title = checkNotNull(provider.load(hit.url))
                val selectedEpisode = when (title) {
                    is MovieLoadResponse -> null
                    is AnimeLoadResponse -> title.episodes.values.flatten().single { it.episode == episodeNumber }
                    is TvSeriesLoadResponse -> title.episodes.single { it.episode == episodeNumber }
                    else -> error("Unsupported title")
                }
                val data = (title as? MovieLoadResponse)?.dataUrl ?: checkNotNull(selectedEpisode).data
                val links = mutableListOf<ExtractorLink>()
                provider.loadLinks(data, false, {}, links::add)
                val choices = DownloadChoices.select(links)
                android.util.Log.i("KadrogradDownloadTest", "Available choices: ${choices.map { Triple(it.quality, it.type, it.name) }}")
                val height = InstrumentationRegistry.getArguments().getString("movieQuality")?.toIntOrNull() ?: 1080
                val format = when (InstrumentationRegistry.getArguments().getString("movieFormat")) {
                    "dash" -> ExtractorLinkType.DASH
                    "hls" -> ExtractorLinkType.M3U8
                    "mp4" -> ExtractorLinkType.VIDEO
                    else -> null
                }
                val selected = choices.firstOrNull { it.quality == height &&
                    (if (format != null) it.type == format else it.type in setOf(ExtractorLinkType.DASH, ExtractorLinkType.M3U8)) }
                    ?: error("No selected stream at $height; available=${choices.map { it.quality to it.type }}")
                val episode = DownloadObjects.DownloadEpisodeMetadata(id, id, "Full transfer", provider.name,
                    null, null, selectedEpisode?.season, selectedEpisode?.episode, title.type)
                val downloaded = withTimeout(15 * 60_000L) {
                    VideoDownloadManager.downloadSingleEpisode(context, null, "", episode, selected, { _, _ -> })
                }
                assertTrue("Transfer did not finish", downloaded.success)
                val saved = checkNotNull(VideoDownloadManager.getDownloadFileInfo(context, id))
                assertEquals(saved.fileLength, saved.totalBytes)
                val output = directory.walkTopDown().single { it.isFile && it.extension in setOf("mp4", "webm") }
                assertTrue(output.length() > 10_000_000)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.absolutePath)
                    val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    val video = formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                    assertTrue(video.getLong(MediaFormat.KEY_DURATION) > (if (selectedEpisode == null) 60 else 15) * 60_000_000L)
                    title.duration?.takeIf { it > 0 }?.let { minutes ->
                        assertTrue("The saved movie ends before its catalog duration",
                            video.getLong(MediaFormat.KEY_DURATION) >= (minutes - 3).coerceAtLeast(1) * 60_000_000L)
                    }
                    assertEquals(1, formats.count { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
                    android.util.Log.i("KadrogradDownloadTest", "Completed full media: ${output.length()} bytes, duration=${video.getLong(MediaFormat.KEY_DURATION)}, resolution=${video.getInteger(MediaFormat.KEY_WIDTH)}x${video.getInteger(MediaFormat.KEY_HEIGHT)}")
                } finally { extractor.release() }
                var player: ExoPlayer? = null
                try {
                    instrumentation.runOnMainSync {
                        player = ExoPlayer.Builder(context).build().apply {
                            setMediaItem(MediaItem.fromUri(output.toURI().toString()))
                            prepare()
                        }
                    }
                    withTimeout(20_000) {
                        while (true) {
                            var ready = false
                            instrumentation.runOnMainSync {
                                checkNotNull(player).playerError?.let { throw AssertionError("Saved movie failed", it) }
                                ready = player?.playbackState == Player.STATE_READY
                            }
                            if (ready) break
                            delay(100)
                        }
                    }
                    instrumentation.runOnMainSync {
                        assertTrue(checkNotNull(player).isCurrentMediaItemSeekable)
                        player!!.seekTo((player!!.duration - 10_000).coerceAtLeast(0))
                    }
                    withTimeout(20_000) {
                        while (true) {
                            var ready = false
                            instrumentation.runOnMainSync {
                                player?.playerError?.let { throw AssertionError("Saved movie seek failed", it) }
                                ready = player?.playbackState == Player.STATE_READY && player!!.currentPosition >= player!!.duration - 15_000
                            }
                            if (ready) break
                            delay(100)
                        }
                    }
                } finally { instrumentation.runOnMainSync { player?.release() } }
            }
        } finally {
            previousClient?.let { app.baseClient = it }
            CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
            AdaptiveTransferCache.discard(context, id)
            preferences.edit().apply { if (previousPath == null) remove(pathKey) else putString(pathKey, previousPath) }.commit()
            directory.deleteRecursively()
        }
    }
}
