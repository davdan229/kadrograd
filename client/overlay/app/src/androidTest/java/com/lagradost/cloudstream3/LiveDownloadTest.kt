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
import com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload
import com.lagradost.cloudstream3.utils.downloader.DownloadChoices
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
        require(query.isNotBlank()) { "movieQuery is required for the opt-in live transfer" }
        val output = File(context.cacheDir, "live-download-${System.nanoTime()}.media")
        var previousClient: okhttp3.OkHttpClient? = null
        try {
            context.getSharedPreferences("rebuild_preference", 0).edit().putString("HAS_DONE_SETUP", "true").commit()
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt("app_layout_key", 0).putBoolean("auto_update_plugins", false).commit()
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(60_000) {
                    while (getApiFromNameNull("Кадроград · Zona") == null) delay(100)
                }
                val provider = checkNotNull(getApiFromNameNull("Кадроград · Zona"))
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
                val movie = provider.load(hit.url) as? MovieLoadResponse ?: error("Expected a movie")
                val links = mutableListOf<ExtractorLink>()
                provider.loadLinks(movie.dataUrl, false, {}, links::add)
                val choices = DownloadChoices.select(links)
                android.util.Log.i("KadrogradDownloadTest", "Available choices: ${choices.map { Triple(it.quality, it.type, it.name) }}")
                val height = InstrumentationRegistry.getArguments().getString("movieQuality")?.toIntOrNull() ?: 1080
                val selected = choices.firstOrNull { it.quality == height &&
                    it.type in setOf(ExtractorLinkType.DASH, ExtractorLinkType.M3U8) && provider.getVideoInterceptor(it) != null }
                    ?: error("No selected adaptive quality $height; available=${choices.map { it.quality to it.type }}")
                var lastLog = 0L
                val exported = withTimeout(15 * 60_000L) {
                    AdaptiveDownload.exportToFile(context, selected, output, provider.getVideoInterceptor(selected),
                        progress = { percent, bytes, _ ->
                            if (bytes - lastLog >= 25_000_000) {
                                lastLog = bytes
                                android.util.Log.i("KadrogradDownloadTest", "Live transfer: $percent%, $bytes bytes, output=${output.length()}")
                            }
                        })
                }
                assertTrue(exported)
                assertTrue(output.length() > 10_000_000)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.absolutePath)
                    val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    val video = formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                    assertTrue(video.getLong(MediaFormat.KEY_DURATION) > 60 * 60_000_000L)
                    movie.duration?.takeIf { it > 0 }?.let { minutes ->
                        assertTrue("The saved movie ends before its catalog duration",
                            video.getLong(MediaFormat.KEY_DURATION) >= (minutes - 3).coerceAtLeast(1) * 60_000_000L)
                    }
                    assertEquals(1, formats.count { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
                    android.util.Log.i("KadrogradDownloadTest", "Completed full movie: ${output.length()} bytes, duration=${video.getLong(MediaFormat.KEY_DURATION)}, height=${video.getInteger(MediaFormat.KEY_HEIGHT)}")
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
                        player!!.seekTo(player!!.duration * 3 / 4)
                    }
                    withTimeout(20_000) {
                        while (true) {
                            var ready = false
                            instrumentation.runOnMainSync {
                                player?.playerError?.let { throw AssertionError("Saved movie seek failed", it) }
                                ready = player?.playbackState == Player.STATE_READY && player!!.currentPosition > player!!.duration / 2
                            }
                            if (ready) break
                            delay(100)
                        }
                    }
                } finally { instrumentation.runOnMainSync { player?.release() } }
            }
        } finally {
            previousClient?.let { app.baseClient = it }
            output.delete()
        }
    }
}
