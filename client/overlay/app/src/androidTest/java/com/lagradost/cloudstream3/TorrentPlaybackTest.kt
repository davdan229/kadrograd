package com.lagradost.cloudstream3

import android.graphics.ImageFormat
import android.media.ImageReader
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ui.player.Torrent
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/** Exercises the shipped native engine with synthetic media and an isolated HTTP web seed. */
class TorrentPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun bencode(value: Any): ByteArray = ByteArrayOutputStream().apply {
        when (value) {
            is ByteArray -> { write("${value.size}:".toByteArray()); write(value) }
            is String -> write(bencode(value.toByteArray(Charsets.UTF_8)))
            is Number -> write("i${value}e".toByteArray())
            is List<*> -> { write('l'.code); value.forEach { write(bencode(requireNotNull(it))) }; write('e'.code) }
            is Map<*, *> -> {
                write('d'.code)
                value.entries.sortedBy { it.key.toString() }.forEach {
                    write(bencode(it.key.toString())); write(bencode(requireNotNull(it.value)))
                }
                write('e'.code)
            }
            else -> error("Unsupported bencode value")
        }
    }.toByteArray()

    private inner class WebSeed : AutoCloseable {
        val requests = ConcurrentLinkedQueue<String>()
        private val media = instrumentation.context.assets.open("adaptive/torrent/selected.webm").use { it.readBytes() }
        private val readme = "Synthetic UHD fixture\n".toByteArray()
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newFixedThreadPool(4)
        val origin = "http://127.0.0.1:${server.localPort}"
        private val torrent = run {
            val bytes = readme + media
            val pieces = ByteArrayOutputStream()
            val digest = MessageDigest.getInstance("SHA-1")
            for (start in bytes.indices step 16_384) {
                pieces.write(digest.digest(bytes.copyOfRange(start, minOf(start + 16_384, bytes.size))))
            }
            bencode(mapOf("url-list" to listOf("$origin/seed/"), "info" to mapOf(
                "name" to "fixture", "private" to 1, "piece length" to 16_384, "pieces" to pieces.toByteArray(),
                "files" to listOf(
                    mapOf("length" to readme.size, "path" to listOf("readme.txt")),
                    mapOf("length" to media.size, "path" to listOf("selected.webm")),
                ),
            )))
        }
        private val worker = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                executor.submit {
                    socket.use {
                        it.soTimeout = 15_000
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine()?.split(' ') ?: return@use
                        val path = request.getOrNull(1)?.substringBefore('?') ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine()?.takeIf(String::isNotEmpty) ?: break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        requests.add(path)
                        val bytes = when {
                            path == "/fixture.torrent" -> torrent
                            path.endsWith("/selected.webm") -> media
                            path.endsWith("/readme.txt") -> readme
                            else -> byteArrayOf()
                        }
                        val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                        val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val end = minOf(range?.groupValues?.get(2)?.toIntOrNull() ?: (bytes.size - 1), bytes.size - 1)
                        val valid = bytes.isNotEmpty() && start in bytes.indices && end >= start
                        val status = when { !valid -> "404 Not Found"; range != null -> "206 Partial Content"; else -> "200 OK" }
                        val body = if (valid) bytes.copyOfRange(start, end + 1) else byteArrayOf()
                        val contentRange = if (valid && range != null) "Content-Range: bytes $start-$end/${bytes.size}\r\n" else ""
                        it.getOutputStream().apply {
                            write(("HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n" +
                                contentRange + "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            if (request[0] != "HEAD") write(body)
                            flush()
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() { server.close(); executor.shutdownNow(); worker.join(1000) }
    }

    @Test fun nativeEnginePlaysTheSelectedFileInUhdWithBothAudioTracks() = runBlocking {
        WebSeed().use { seed ->
            ActivityScenario.launch(MainActivity::class.java).use { _ ->
                val link = newExtractorLink("Fixture", "Synthetic UHD", "${seed.origin}/fixture.torrent?index=1", ExtractorLinkType.TORRENT) {
                    quality = 2160
                }
                try {
                    val (stream, _) = withTimeout(45_000) { withContext(Dispatchers.IO) { Torrent.transformLink(link) } }
                    assertEquals(ExtractorLinkType.VIDEO, stream.type)
                    assertEquals(2160, stream.quality)
                    assertTrue(stream.url.contains("index=1&play"))
                    val firstFrame = CompletableDeferred<VideoSize>()
                    val reader = ImageReader.newInstance(3840, 2160, ImageFormat.PRIVATE, 2)
                    var player: ExoPlayer? = null
                    try {
                        instrumentation.runOnMainSync {
                            val active = ExoPlayer.Builder(instrumentation.targetContext).build()
                            player = active
                            active.addListener(object : Player.Listener {
                                override fun onRenderedFirstFrame() { firstFrame.complete(active.videoSize) }
                                override fun onPlayerError(error: PlaybackException) { firstFrame.completeExceptionally(error) }
                            })
                            active.setVideoSurface(reader.surface)
                            active.setMediaItem(MediaItem.fromUri(stream.url))
                            active.prepare()
                            active.play()
                        }
                        val size = withTimeout(45_000) { firstFrame.await() }
                        assertEquals(3840, size.width)
                        assertEquals(2160, size.height)
                        instrumentation.runOnMainSync {
                            val audioTracks = player!!.currentTracks.groups.flatMap { group ->
                                (0 until group.length).map(group::getTrackFormat)
                            }.filter { MimeTypes.isAudio(it.sampleMimeType) }
                            assertEquals(2, audioTracks.size)
                        }
                        assertTrue(seed.requests.any { it.endsWith("/selected.webm") })
                        android.util.Log.i("KadrogradTorrentTest", "Native engine rendered ${size.width}×${size.height} with two audio tracks")
                    } finally {
                        instrumentation.runOnMainSync { player?.release() }
                        reader.close()
                    }
                } finally { withContext(Dispatchers.IO) { Torrent.clearAll() } }
            }
        }
    }
}
