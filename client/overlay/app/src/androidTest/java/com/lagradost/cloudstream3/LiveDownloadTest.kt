package com.lagradost.cloudstream3

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
import kotlinx.coroutines.CompletableDeferred
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
        val keepOnFailure = InstrumentationRegistry.getArguments().getString("keepOnFailure") == "true"
        require(query.isNotBlank()) { "movieQuery is required for the opt-in live transfer" }
        val directory = File(context.getExternalFilesDir(null), "live-download-${System.nanoTime()}").apply { mkdirs() }
        val id = -75184910
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val pathKey = context.getString(R.string.download_path_key)
        val previousPath = preferences.getString(pathKey, null)
        preferences.edit().putString(pathKey, directory.absolutePath).commit()
        var previousClient: okhttp3.OkHttpClient? = null
        var verified = false
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
                assertTrue(saved.fileLength > 10_000_000)
                android.util.Log.i("KadrogradDownloadTest", "Saved media bytes=${saved.fileLength}")
                var player: ExoPlayer? = null
                val reader = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 3)
                val rendered = CompletableDeferred<Unit>()
                try {
                    instrumentation.runOnMainSync {
                        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
                        player = ExoPlayer.Builder(context).build().apply {
                            addListener(object : Player.Listener {
                                override fun onRenderedFirstFrame() { rendered.complete(Unit) }
                                override fun onPlayerError(error: PlaybackException) { rendered.completeExceptionally(error) }
                            })
                            setVideoSurface(reader.surface)
                            setMediaItem(MediaItem.fromUri(saved.path))
                            prepare()
                            play()
                        }
                    }
                    withTimeout(30_000) { rendered.await() }
                    instrumentation.runOnMainSync {
                        val exo = checkNotNull(player)
                        exo.pause()
                        assertTrue(exo.duration > (if (selectedEpisode == null) 60 else 15) * 60_000L)
                        title.duration?.takeIf { it > 0 }?.let { minutes ->
                            assertTrue("The saved movie ends before its catalog duration",
                                exo.duration >= (minutes - 3).coerceAtLeast(1) * 60_000L)
                        }
                        val video = checkNotNull(exo.videoFormat) { "CloudStream could not read the saved video format" }
                        assertEquals(1, exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length })
                        Regex("""(\d+)×(\d+)""").find(selected.name)?.destructured?.let { (width, height) ->
                            assertEquals(width.toInt(), video.width)
                            assertEquals(height.toInt(), video.height)
                        }
                        android.util.Log.i("KadrogradDownloadTest", "Completed full media: ${saved.fileLength} bytes, durationMs=${exo.duration}, resolution=${video.width}x${video.height}")
                        assertTrue(exo.isCurrentMediaItemSeekable)
                        exo.seekTo((exo.duration - 10_000).coerceAtLeast(0))
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
                } finally {
                    instrumentation.runOnMainSync { player?.release() }
                    reader.close()
                }
                verified = true
            }
        } finally {
            previousClient?.let { app.baseClient = it }
            CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
            AdaptiveTransferCache.discard(context, id)
            preferences.edit().apply { if (previousPath == null) remove(pathKey) else putString(pathKey, previousPath) }.commit()
            if (verified || !keepOnFailure) directory.deleteRecursively()
        }
    }
}
