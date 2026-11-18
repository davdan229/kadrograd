package com.lagradost.cloudstream3

import android.net.Uri
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.ErrorEvent
import com.lagradost.cloudstream3.ui.player.PlayerAttachedEvent
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFileName
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadRecordRepair
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class SavedMediaTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").apply {
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; update(buffer, 0, count) }
        }
    }.digest().toList()

    private fun describe(file: File): String {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).joinToString { track ->
                val format = extractor.getTrackFormat(track)
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val first = extractor.sampleTime
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
                extractor.seekTo((duration - 2_000_000).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var last = -1L
                var count = 0
                while (extractor.sampleTime >= 0 && count++ < 4096) {
                    last = extractor.sampleTime
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(track)
                "track=$track format=$format first=$first last=$last samples=$count"
            }
        } catch (error: Exception) { error.toString() }
        finally { extractor.release() }
    }

    @Test fun incompatibleHlsResumeKeepsTheSavedFileIncomplete() = runBlocking<Unit> {
        AdaptiveDownloadTest().FixtureServer().use { server ->
            val settings = PreferenceManager.getDefaultSharedPreferences(context)
            val pathKey = context.getString(R.string.download_path_key)
            val previous = settings.getString(pathKey, null)
            val directory = File(context.getExternalFilesDir(null), "hls-resume-check").apply { mkdirs() }
            val id = -918260
            val episode = DownloadObjects.DownloadEpisodeMetadata(id, id, "Saved episode",
                "Fixture", null, null, 2, 3, TvType.TvSeries)
            val name = getFileName(context, episode) + ".mp4"
            val file = File(directory, name)
            val link = newExtractorLink("Fixture", "HLS", "${server.origin}/legacy-hls.m3u8", ExtractorLinkType.M3U8) {
                headers = mapOf("X-Fixture-Auth" to "selected")
            }
            try {
                settings.edit().putString(pathKey, directory.absolutePath).commit()
                instrumentation.context.assets.open("adaptive/legacy-hls.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
                val originalDigest = digest(file)
                val info = DownloadObjects.DownloadedFileInfo(file.length() + 1_000_000, "", name,
                    extraInfo = "13", basePath = directory.absolutePath, linkHash = link.url.hashCode())
                CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), info)
                val result = VideoDownloadManager.downloadSingleEpisode(context, null, "", episode, link, { _, _ -> }, tryResume = true)
                assertFalse("Saved segment index exceeds the new playlist", result.success)
                assertFalse("Do not replace an incompatible partial file with another rendition", result.tryNext)
                assertEquals(originalDigest, digest(file))
                assertEquals(info, CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString()))
                assertEquals(listOf("/legacy-hls.m3u8"), server.requests.toList())
            } finally {
                CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                settings.edit().apply { if (previous == null) remove(pathKey) else putString(pathKey, previous) }.commit()
                file.delete()
                directory.delete()
            }
        }
    }

    @Test fun interruptedDirectVideoResumesFromSavedBytes() = runBlocking<Unit> {
        val path = "/voice-tracks.mp4"
        AdaptiveDownloadTest().FixtureServer(disconnectOn = path, retryFailures = 2).use { server ->
            val settings = PreferenceManager.getDefaultSharedPreferences(context)
            val pathKey = context.getString(R.string.download_path_key)
            val previous = settings.getString(pathKey, null)
            val directory = File(context.getExternalFilesDir(null), "mp4-resume-check").apply { mkdirs() }
            val id = -918261
            val file = File(directory, "Direct video.mp4")
            val original = instrumentation.context.assets.open("adaptive$path").use { it.readBytes() }
            val link = newExtractorLink("Fixture", "Direct video", "${server.origin}$path", ExtractorLinkType.VIDEO) {
                headers = mapOf("X-Fixture-Auth" to "selected")
            }
            try {
                settings.edit().putString(pathKey, directory.absolutePath).commit()
                val interrupted = VideoDownloadManager.downloadThing(context, link, "Direct video", "", "mp4",
                    false, id, {}, parallelConnections = 1)
                assertFalse("A truncated response was reported as complete", interrupted.success)
                val offset = file.length()
                assertTrue("The received bytes should survive", offset in 1L until original.size.toLong())
                assertNull(CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(
                    VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())?.completedBytes)
                server.ranges.clear()
                val resumed = VideoDownloadManager.downloadThing(context, link, "Direct video", "", "mp4",
                    true, id, {}, parallelConnections = 1)
                assertTrue("Resuming the direct stream did not finish", resumed.success)
                assertTrue("The downloader did not request the saved offset", (path to offset) in server.ranges)
                assertArrayEquals(original, file.readBytes())
                val saved = checkNotNull(VideoDownloadManager.getDownloadFileInfo(context, id))
                assertEquals(original.size.toLong(), saved.fileLength)
                assertEquals(saved.fileLength, saved.totalBytes)
            } finally {
                CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                settings.edit().apply { if (previous == null) remove(pathKey) else putString(pathKey, previous) }.commit()
                file.delete()
                directory.delete()
            }
        }
    }

    @Test fun oldFilesAreCheckedByContentAndCompletedHlsRecordsUseActualSize() = runBlocking<Unit> {
        AdaptiveDownloadTest().FixtureServer().use { server ->
            val directory = File(context.getExternalFilesDir(null), "saved-media-check").apply { mkdirs() }
            try {
                for ((index, asset) in listOf("voice-tracks.mp4", "voice-tracks.webm", "legacy-hls.mp4").withIndex()) {
                    val id = -918250 - index
                    val file = File(directory, asset)
                    instrumentation.context.assets.open("adaptive/$asset").use { input -> file.outputStream().use { input.copyTo(it) } }
                    val length = file.length()
                    val originalDigest = digest(file)
                    val link = newExtractorLink("Fixture", "HLS", "${server.origin}/legacy-hls.m3u8", ExtractorLinkType.M3U8) {
                        headers = mapOf("X-Fixture-Auth" to "selected")
                    }
                    val info = DownloadObjects.DownloadedFileInfo(length + 4_000_000, "", file.name,
                        extraInfo = if (index == 2) "12" else null, basePath = directory.absolutePath, linkHash = link.url.hashCode())
                    try {
                        CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), info)
                        val episode = DownloadObjects.DownloadEpisodeMetadata(id, id, "Fixture", "Fixture", null, null, 1, 1, TvType.TvSeries)
                        val resume = DownloadObjects.DownloadResumePackage(DownloadObjects.DownloadItem("Fixture", "", episode, listOf(link)), 0)
                        CloudStreamApp.setKey(VideoDownloadManager.KEY_RESUME_PACKAGES, id.toString(), resume)
                        VideoDownloadManager.getDownloadFileInfo(context, id)
                        val verified = withTimeoutOrNull(15_000) {
                            while (CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())?.completedBytes != length) delay(50)
                            true
                        }
                        assertEquals("Could not verify $asset, manifest requests=${server.requests}, ${if (verified != true) describe(file) else ""}", true, verified)
                        val repaired = checkNotNull(VideoDownloadManager.getDownloadFileInfo(context, id))
                        assertEquals(length, repaired.fileLength)
                        assertEquals(length, repaired.totalBytes)
                        assertEquals(VideoDownloadManager.DownloadType.IsDone, DownloadRecordRepair.status(id, length))
                        assertEquals(originalDigest, digest(file))
                        // A playable prefix is not a completed movie or episode.
                        val partial = File(directory, "partial-$asset")
                        val partialLength = if (index == 2) length / 376 * 188 else length / 2
                        file.inputStream().use { input -> partial.outputStream().use { output ->
                            val bytes = ByteArray(partialLength.toInt())
                            java.io.DataInputStream(input).readFully(bytes)
                            output.write(bytes)
                        } }
                        val partialInfo = info.copy(displayName = partial.name)
                        CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), partialInfo)
                        val result = DownloadRecordRepair.reconcile(context, id, partialInfo, Uri.fromFile(partial), partial.length())
                        if (index == 2) DownloadRecordRepair.verifyLegacyHls(context, id, partialInfo, Uri.fromFile(partial), partial.length())
                        assertNull(result.completedBytes)
                        assertNull(CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(
                            VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())?.completedBytes)
                        assertNotEquals(VideoDownloadManager.DownloadType.IsDone, DownloadRecordRepair.status(id, partial.length()))
                        assertEquals(partialLength, partial.length())
                        partial.delete()
                    } finally {
                        CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                        CloudStreamApp.removeKey(VideoDownloadManager.KEY_RESUME_PACKAGES, id.toString())
                        file.delete()
                    }
                }
                assertTrue(server.requests.any { it.endsWith("legacy-hls.m3u8") })
                assertFalse("Verification downloaded media again", server.requests.any { it.endsWith(".mp4") || it.endsWith(".webm") })
            } finally { directory.listFiles()?.forEach(File::delete); directory.delete() }
        }
    }

    @Test fun changingEmbeddedVoiceAndStreamPreservesPositionAndPlayIntent() = runBlocking<Unit> {
        AdaptiveDownloadTest().FixtureServer().use { server ->
            val current = AtomicReference<ExoPlayer?>()
            val error = AtomicReference<Throwable?>()
            val sut = CS3IPlayer()
            fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
            suspend fun ready(position: Long) {
                withTimeout(20_000) {
                    while (true) {
                        var success = false
                        main {
                            error.get()?.let { throw AssertionError("Player failed", it) }
                            current.get()?.let { exo -> success = exo.playbackState == Player.STATE_READY &&
                                kotlin.math.abs(exo.currentPosition - position) < 600 }
                        }
                        if (success) break
                        delay(50)
                    }
                }
            }
            main { sut.initCallbacks({ event ->
                if (event is PlayerAttachedEvent) current.set(event.player as? ExoPlayer)
                if (event is ErrorEvent) error.set(event.error)
            }, null) }
            try {
                for ((path, type) in listOf("voice-tracks.mp4" to ExtractorLinkType.VIDEO, "hls/master.m3u8" to ExtractorLinkType.M3U8)) {
                    suspend fun link(voice: String) = newExtractorLink("Fixture", voice, "${server.origin}/$path?voice=$voice", type) {
                        headers = mapOf("X-Fixture-Auth" to "selected")
                    }
                    val first = link("A")
                    val second = link("B")
                    main { sut.loadPlayer(context, false, first, null, 0, emptySet(), null, false, false) }
                    ready(0)
                    main { sut.seekTo(6_000) }
                    ready(6_000)
                    var chosenId: String? = null
                    main {
                        val tracks = sut.getVideoTracks()
                        val previousId = checkNotNull(tracks.currentAudioTrack?.id)
                        val track = tracks.allAudioTracks.first { it.id != null && it.id != previousId }
                        chosenId = checkNotNull(track.id)
                        sut.setPreferredAudioTrack(track.language, track.id, track.formatIndex)
                    }
                    ready(6_000)
                    withTimeout(10_000) {
                        while (true) {
                            var selected = false
                            main { selected = sut.getVideoTracks().currentAudioTrack?.id == chosenId }
                            if (selected) break
                            delay(50)
                        }
                    }
                    main { assertFalse(current.get()!!.playWhenReady) }
                    main { sut.loadPlayer(context, true, second, null, null, emptySet(), null, null, false) }
                    ready(6_000)
                    main { assertFalse(current.get()!!.playWhenReady); sut.handleEvent(CSPlayerEvent.Play) }
                    // A rapid source change while the new player is buffering must not save isPlaying=false.
                    main {
                        sut.loadPlayer(context, true, first, null, null, emptySet(), null, null, false)
                        sut.loadPlayer(context, true, second, null, null, emptySet(), null, null, false)
                        assertTrue(current.get()!!.playWhenReady)
                    }
                    ready(6_000)
                    main { sut.handleEvent(CSPlayerEvent.Pause); assertTrue(current.get()!!.currentPosition >= 5_400) }
                }
            } finally { main { sut.release(); sut.releaseCallbacks() } }
        }
    }
}
