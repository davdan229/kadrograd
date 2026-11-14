package com.lagradost.cloudstream3

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.ErrorEvent
import com.lagradost.cloudstream3.ui.player.PlayerAttachedEvent
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadRecordRepair
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
                        withTimeout(20_000) {
                            while (CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())?.completedBytes != length) delay(50)
                        }
                        val repaired = checkNotNull(VideoDownloadManager.getDownloadFileInfo(context, id))
                        assertEquals(length, repaired.fileLength)
                        assertEquals(length, repaired.totalBytes)
                        assertEquals(VideoDownloadManager.DownloadType.IsDone, DownloadRecordRepair.status(id, length))
                        assertEquals(originalDigest, digest(file))
                        // A playable prefix is not a completed movie or episode.
                        val partial = File(directory, "partial-$asset")
                        file.inputStream().use { input -> partial.outputStream().use { output ->
                            val bytes = ByteArray((length / 2).toInt()); input.read(bytes); output.write(bytes)
                        } }
                        val partialInfo = info.copy(displayName = partial.name)
                        CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), partialInfo)
                        val result = DownloadRecordRepair.reconcile(context, id, partialInfo, Uri.fromFile(partial), partial.length())
                        if (index == 2) DownloadRecordRepair.verifyLegacyHls(context, id, partialInfo, Uri.fromFile(partial), partial.length())
                        assertNull(result.completedBytes)
                        assertNotEquals(VideoDownloadManager.DownloadType.IsDone, DownloadRecordRepair.status(id, partial.length()))
                        assertEquals(length / 2, partial.length())
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
                    fun link(voice: String) = newExtractorLink("Fixture", voice, "${server.origin}/$path?voice=$voice", type) {
                        headers = mapOf("X-Fixture-Auth" to "selected")
                    }
                    main { sut.loadPlayer(context, false, link("A"), null, 0, emptySet(), null, false, false) }
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
                    main { sut.loadPlayer(context, true, link("B"), null, null, emptySet(), null, null, false) }
                    ready(6_000)
                    main { assertFalse(current.get()!!.playWhenReady); sut.handleEvent(CSPlayerEvent.Play) }
                    // A rapid source change while the new player is buffering must not save isPlaying=false.
                    main {
                        sut.loadPlayer(context, true, link("A"), null, null, emptySet(), null, null, false)
                        sut.loadPlayer(context, true, link("B"), null, null, emptySet(), null, null, false)
                        assertTrue(current.get()!!.playWhenReady)
                    }
                    ready(6_000)
                    main { sut.handleEvent(CSPlayerEvent.Pause); assertTrue(current.get()!!.currentPosition >= 5_400) }
                }
            } finally { main { sut.release(); sut.releaseCallbacks() } }
        }
    }
}
