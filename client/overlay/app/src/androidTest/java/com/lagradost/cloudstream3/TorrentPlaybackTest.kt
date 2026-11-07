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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the shipped native engine with synthetic media and an isolated BitTorrent peer. */
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

    private inner class PeerSeed : AutoCloseable {
        val requests = ConcurrentLinkedQueue<String>()
        val blocksServed = AtomicInteger()
        private val media = instrumentation.context.assets.open("adaptive/torrent/selected.webm").use { it.readBytes() }
        private val readme = "Synthetic UHD fixture\n".toByteArray()
        private val content = readme + media
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val peer = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newFixedThreadPool(4)
        private val peerExecutor = Executors.newFixedThreadPool(2)
        val origin = "http://127.0.0.1:${server.localPort}"
        private val info = run {
            val pieces = ByteArrayOutputStream()
            val digest = MessageDigest.getInstance("SHA-1")
            for (start in content.indices step 16_384) {
                pieces.write(digest.digest(content.copyOfRange(start, minOf(start + 16_384, content.size))))
            }
            mapOf(
                "name" to "fixture", "private" to 1, "piece length" to 16_384, "pieces" to pieces.toByteArray(),
                "files" to listOf(
                    mapOf("length" to readme.size, "path" to listOf("readme.txt")),
                    mapOf("length" to media.size, "path" to listOf("selected.webm")),
                ),
            )
        }
        private val infoHash = MessageDigest.getInstance("SHA-1").digest(bencode(info))
        private val torrent = bencode(mapOf("announce" to "$origin/announce", "info" to info))
        private val peerWorker = Thread {
            while (!peer.isClosed) {
                val socket = try { peer.accept() } catch (_: IOException) { break }
                android.util.Log.i("KadrogradTorrentTest", "Fixture peer accepted a connection")
                peerExecutor.submit {
                    try {
                        socket.use {
                            it.soTimeout = 15_000
                            val input = DataInputStream(it.getInputStream())
                            val output = DataOutputStream(it.getOutputStream())
                            val first = input.readUnsignedByte()
                            android.util.Log.i("KadrogradTorrentTest", "Peer handshake prefix=$first")
                            if (first != 19) return@use
                            val protocol = ByteArray(19).also(input::readFully)
                            if (String(protocol, Charsets.US_ASCII) != "BitTorrent protocol") return@use
                            input.readFully(ByteArray(8))
                            val hash = ByteArray(20).also(input::readFully)
                            if (!hash.contentEquals(infoHash)) return@use
                            android.util.Log.i("KadrogradTorrentTest", "Peer info hash matched")
                            input.readFully(ByteArray(20))
                            output.writeByte(19)
                            output.write(protocol)
                            output.write(ByteArray(8))
                            output.write(infoHash)
                            output.write("-KD0001-000000000001".toByteArray())
                            val count = (content.size + 16_383) / 16_384
                            val bitfield = ByteArray((count + 7) / 8)
                            for (index in 0 until count) {
                                bitfield[index / 8] = (bitfield[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
                            }
                            output.writeInt(1 + bitfield.size)
                            output.writeByte(5)
                            output.write(bitfield)
                            output.writeInt(1)
                            output.writeByte(1)
                            output.flush()
                            while (!peer.isClosed) {
                                val length = input.readInt()
                                if (length == 0) continue
                                if (length !in 1..262_144) return@use
                                val message = ByteArray(length).also(input::readFully)
                                android.util.Log.i("KadrogradTorrentTest", "Peer message=${message[0]} length=$length")
                                if (message[0].toInt() != 6 || length != 13) continue
                                val fields = ByteBuffer.wrap(message, 1, 12)
                                val index = fields.int
                                val begin = fields.int
                                val requested = fields.int
                                if (index !in 0 until count || begin !in 0..16_383 || requested !in 1..16_384) return@use
                                val start = index * 16_384 + begin
                                if (begin + requested > 16_384 || start + requested > content.size) return@use
                                output.writeInt(9 + requested)
                                output.writeByte(7)
                                output.writeInt(index)
                                output.writeInt(begin)
                                output.write(content, start, requested)
                                output.flush()
                                blocksServed.incrementAndGet()
                            }
                        }
                    } catch (closed: IOException) {
                        android.util.Log.i("KadrogradTorrentTest", "Fixture peer closed: ${closed.javaClass.simpleName}")
                    }
                }
            }
        }.apply { isDaemon = true; start() }
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
                            path == "/announce" -> bencode(mapOf("interval" to 15, "complete" to 1, "incomplete" to 0,
                                "peers" to byteArrayOf(127, 0, 0, 1, (peer.localPort ushr 8).toByte(), peer.localPort.toByte())))
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

        override fun close() {
            server.close(); peer.close()
            executor.shutdownNow(); peerExecutor.shutdownNow()
            worker.join(1000); peerWorker.join(1000)
        }
    }

    @Test fun nativeEnginePlaysTheSelectedFileInUhdWithBothAudioTracks() = runBlocking<Unit> {
        PeerSeed().use { seed ->
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
                        assertTrue("No blocks arrived from the fixture peer", seed.blocksServed.get() > 0)
                        android.util.Log.i("KadrogradTorrentTest", "Native engine rendered ${size.width}×${size.height} with two audio tracks")
                    } finally {
                        instrumentation.runOnMainSync { player?.release() }
                        reader.close()
                    }
                } finally {
                    android.util.Log.i("KadrogradTorrentTest", "Fixture requests=${seed.requests.toList()}, blocks=${seed.blocksServed.get()}")
                    withContext(Dispatchers.IO) { Torrent.clearAll() }
                }
            }
        }
    }
}
