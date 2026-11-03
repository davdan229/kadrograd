package com.lagradost.cloudstream3

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class AdaptiveDownloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** Local HTTP verifies that every manifest/segment uses the provider's request interceptor. */
    private inner class FixtureServer : AutoCloseable {
        val requests = ConcurrentLinkedQueue<String>()
        private val server = ServerSocket(0)
        private val executor = Executors.newFixedThreadPool(4)
        val origin = "http://127.0.0.1:${server.localPort}"
        private val worker = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                executor.submit {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?') ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine()?.takeIf(String::isNotEmpty) ?: break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        requests.add(path)
                        val bytes = if (headers["x-fixture-auth"] == "selected") {
                            runCatching { instrumentation.context.assets.open("adaptive$path").use { input -> input.readBytes() } }.getOrNull()
                        } else null
                        val status = if (bytes == null) "403 Forbidden" else "200 OK"
                        val body = bytes ?: byteArrayOf()
                        val type = when { path.endsWith(".mpd") -> "application/dash+xml"; path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"; else -> "application/octet-stream" }
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); executor.shutdownNow(); worker.join(1000) }
    }

    private fun selection(height: Int, voice: Int) = Interceptor { chain ->
        val response = chain.proceed(chain.request().newBuilder().header("X-Fixture-Auth", "selected").build())
        when {
            chain.request().url.encodedPath.endsWith(".mpd") && response.isSuccessful -> {
                val xml = response.body.string()
                val filtered = Regex("<Representation\\b[\\s\\S]*?</Representation>").replace(xml) { match ->
                    val value = match.value
                    val id = Regex("""\bid="(\d+)"""").find(value)?.groupValues?.get(1)?.toInt()
                    val wanted = if (value.contains("height=\"")) value.contains("height=\"$height\"") else id == voice
                    if (wanted) value else ""
                }
                // Empty audio adaptation sets are invalid and must be removed too.
                val cleaned = Regex("<AdaptationSet\\b[\\s\\S]*?</AdaptationSet>").replace(filtered) {
                    if (it.value.contains("<Representation")) it.value else ""
                }
                response.newBuilder().body(cleaned.toResponseBody("application/dash+xml".toMediaType())).build()
            }
            chain.request().url.encodedPath.endsWith("master.m3u8") && response.isSuccessful -> {
                val filtered = response.body.string().lineSequence().filterNot { it.contains("Studio A") }.joinToString("\n")
                    .replace("DEFAULT=NO", "DEFAULT=YES") + "\n"
                response.newBuilder().body(filtered.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
            }
            else -> response
        }
    }

    private fun assertTracks(file: File, expectedWidth: Int, expectedHeight: Int) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            assertEquals(expectedWidth, video.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(expectedHeight, video.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals(1, formats.count { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
            assertTrue(video.getLong(MediaFormat.KEY_DURATION) >= 1_000_000L)
        } finally { extractor.release() }
    }

    @Test fun dashPreservesSdFullHdAndUhdAndSelectedOpusVoice() = runBlocking {
        FixtureServer().use { server ->
            for ((width, height) in listOf(854 to 480, 1920 to 1080, 3840 to 2160)) {
                val output = File.createTempFile("adaptive-", ".mp4", context.cacheDir)
                try {
                    val link = newExtractorLink("Fixture", "Selected voice", "${server.origin}/dash/manifest.mpd", ExtractorLinkType.DASH) { quality = height }
                    assertTrue(withTimeout(90_000) { AdaptiveDownload.exportToFile(context, link, output, selection(height, 4)) })
                    assertTracks(output, width, height)
                } finally { output.delete() }
            }
            assertTrue(server.requests.any { it.contains("init-4.webm") })
            assertFalse(server.requests.any { it.contains("init-3.webm") || it.contains("chunk-3-") })
        }
    }

    @Test fun hlsWithSeparateAudioSavesTheSelectedVoice() = runBlocking {
        FixtureServer().use { server ->
            val output = File.createTempFile("adaptive-hls-", ".mp4", context.cacheDir)
            try {
                val link = newExtractorLink("Fixture", "Studio B", "${server.origin}/hls/master.m3u8", ExtractorLinkType.M3U8) { quality = 1080 }
                assertTrue(withTimeout(90_000) { AdaptiveDownload.exportToFile(context, link, output, selection(1080, 4)) })
                assertTracks(output, 1920, 1080)
                assertTrue(server.requests.any { it.contains("audio-b-") })
                assertFalse(server.requests.any { it.contains("audio-a") })
            } finally { output.delete() }
        }
    }

    @Test fun stoppedExportReleasesTheConnectionAndCanBeStartedAgain() = runBlocking {
        FixtureServer().use { server ->
            val output = File.createTempFile("adaptive-stop-", ".mp4", context.cacheDir)
            try {
                val link = newExtractorLink("Fixture", "Studio B", "${server.origin}/dash/manifest.mpd", ExtractorLinkType.DASH) { quality = 1080 }
                assertFalse(withTimeout(20_000) { AdaptiveDownload.exportToFile(context, link, output, selection(1080, 4), keepRunning = { false }) })
                assertTrue(withTimeout(90_000) { AdaptiveDownload.exportToFile(context, link, output, selection(1080, 4)) })
                assertTracks(output, 1920, 1080)
            } finally { output.delete() }
        }
    }
}
