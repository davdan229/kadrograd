package com.lagradost.cloudstream3

import android.media.MediaExtractor
import android.media.MediaFormat
import android.app.Notification
import android.app.NotificationManager
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.instanceOf
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AdaptiveDownloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** Local HTTP verifies that every manifest/segment uses the provider's request interceptor. */
    private inner class FixtureServer(private val beforeBody: (String) -> Unit = {}) : AutoCloseable {
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
                            flush()
                            beforeBody(path)
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

    private fun assertTracks(file: File, expectedWidth: Int, expectedHeight: Int,
        expectedVideo: String = "video/x-vnd.on2.vp9", expectedAudio: String = "audio/opus") {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            assertEquals(expectedWidth, video.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(expectedHeight, video.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals(expectedVideo, video.getString(MediaFormat.KEY_MIME))
            assertEquals(1, formats.count { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
            assertEquals(expectedAudio, formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }.getString(MediaFormat.KEY_MIME))
            assertTrue(video.getLong(MediaFormat.KEY_DURATION) >= 1_000_000L)
        } finally { extractor.release() }
    }

    @Test fun movieButtonOffersQualityAndVoiceAndSurvivesActivityRecreationDuringDownload() = runBlocking<Unit> {
        val waiting = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        val delayed = AtomicBoolean(false)
        FixtureServer { path ->
            if (path.contains("/chunk-1-00002.") && delayed.compareAndSet(false, true)) {
                waiting.countDown()
                check(releaseBody.await(30, TimeUnit.SECONDS))
            }
        }.use { server ->
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val pathKey = context.getString(R.string.download_path_key)
            val previous = preferences.getString(pathKey, null)
            val directory = File(context.getExternalFilesDir(null), "adaptive-ui-${System.nanoTime()}").apply { mkdirs() }
            preferences.edit().putString(pathKey, directory.absolutePath).putInt("app_layout_key", 0).commit()
            context.getSharedPreferences("rebuild_preference", 0).edit().putString("HAS_DONE_SETUP", "true").commit()
            val provider = object : MainAPI() {
                override var name = "Movie download fixture"
                override var mainUrl = server.origin
                override val hasDownloadSupport = true
                override val supportedTypes = setOf(TvType.Movie)
                override suspend fun load(url: String): LoadResponse =
                    newMovieLoadResponse("Download fixture", url, TvType.Movie, "fixture-data")
                override fun getVideoInterceptor(extractorLink: com.lagradost.cloudstream3.utils.ExtractorLink) =
                    selection(extractorLink.quality, if (extractorLink.name.contains("Studio B")) 4 else 3)
                override suspend fun loadLinks(data: String, isCasting: Boolean,
                    subtitleCallback: (SubtitleFile) -> Unit,
                    callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit): Boolean {
                    for (height in listOf(480, 1080, 2160)) for (voice in listOf("Studio A", "Studio B")) {
                        callback(newExtractorLink(name, "$voice · $height", "${server.origin}/dash/manifest.mpd#$height-$voice", ExtractorLinkType.DASH) {
                            quality = height
                        })
                    }
                    return true
                }
            }
            var downloadId: Int? = null
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    withTimeout(30_000) {
                        while (!PluginManager.loadedOnlinePlugins || !PluginManager.loadedLocalPlugins) delay(100)
                    }
                    APIHolder.addPluginMapping(provider)
                    scenario.onActivity { it.loadResult("${server.origin}/movie", provider.name, "Download fixture") }
                    withTimeout(20_000) {
                        while (true) {
                            var ready = false
                            scenario.onActivity { ready = it.findViewById<View>(R.id.download_movie_button)?.let { view -> view.isShown && view.isEnabled } == true }
                            if (ready) break
                            delay(100)
                        }
                    }
                    onView(withId(R.id.download_movie_button)).perform(click())
                    // The movie button must show all choices before anything is queued.
                    for (height in listOf(480, 1080, 2160)) for (voice in listOf("Studio A", "Studio B")) {
                        onData(allOf(instanceOf(String::class.java), containsString("$voice · $height")))
                            .check(matches(isDisplayed()))
                    }
                    assertTrue(VideoDownloadManager.currentDownloads.value.isEmpty())
                    onData(allOf(instanceOf(String::class.java), containsString("Studio B · 1080"))).perform(click())
                    withContext(Dispatchers.IO) { assertTrue("Queue service did not request video", waiting.await(20, TimeUnit.SECONDS)) }
                    downloadId = checkNotNull(DownloadQueueService.downloadInstances.value.singleOrNull()) {
                        "Queue entry disappeared: status=${VideoDownloadManager.downloadStatus}, files=${directory.walkTopDown().filter { it.isFile }.map { it.name }.toList()}"
                    }.downloadQueueWrapper.id
                    assertTrue(DownloadQueueService.isRunning)
                    val activeInfo = CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, downloadId.toString())
                    assertNotNull("The active download has no progress entry", activeInfo)
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    withTimeout(5_000) {
                        while (notifications.activeNotifications.none {
                            it.id == downloadId && it.notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX) == 100 &&
                                it.notification.extras.getInt(Notification.EXTRA_PROGRESS) in 1..98
                        }) delay(100)
                    }
                    val screenshots = File(context.getExternalFilesDir(null), "android-checks").apply { mkdirs() }
                    instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        File(screenshots, "download-progress.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    scenario.recreate()
                    scenario.onActivity { assertFalse(it.isFinishing) }
                    delay(2_000)
                    releaseBody.countDown()
                    withTimeout(60_000) {
                        while (CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, downloadId.toString())?.displayName == activeInfo?.displayName) delay(100)
                    }
                    val file = directory.walkTopDown().single { it.isFile }
                    assertTracks(file, 1920, 1080)
                    scenario.onActivity { assertFalse(it.isFinishing) }
                }
            } finally {
                releaseBody.countDown()
                downloadId?.let { id ->
                    withContext(Dispatchers.Main.immediate) {
                        VideoDownloadManager.downloadEvent(id to VideoDownloadManager.DownloadActionType.Stop)
                    }
                    CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                }
                APIHolder.removePluginMapping(provider)
                preferences.edit().apply { if (previous == null) remove(pathKey) else putString(pathKey, previous) }.commit()
                directory.deleteRecursively()
            }
        }
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
                assertTracks(output, 1920, 1080, "video/avc", "audio/mp4a-latm")
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

    @Test fun stoppingAPausedExportReleasesItsReaders() = runBlocking {
        FixtureServer().use { server ->
            val output = File.createTempFile("adaptive-paused-stop-", ".webm", context.cacheDir)
            val running = AtomicBoolean(true)
            try {
                val link = newExtractorLink("Fixture", "Studio B", "${server.origin}/dash/manifest.mpd", ExtractorLinkType.DASH)
                coroutineScope {
                    val job = async {
                        AdaptiveDownload.exportToFile(context, link, output, selection(1080, 4),
                            keepRunning = running::get, isPaused = { true })
                    }
                    delay(400)
                    assertFalse(job.isCompleted)
                    assertTrue(server.requests.isEmpty())
                    running.set(false)
                    assertFalse(withTimeout(10_000) { job.await() })
                }
            } finally { output.delete() }
        }
    }

    @Test fun managedPauseResumesTheSameExportWithoutRefetchingItsManifest() = runBlocking {
        val waiting = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        val pausedOnce = AtomicBoolean(false)
        FixtureServer { path ->
            if (path.contains("/chunk-1-") && pausedOnce.compareAndSet(false, true)) {
                waiting.countDown()
                check(releaseBody.await(10, TimeUnit.SECONDS))
            }
        }.use { server ->
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val pathKey = context.getString(R.string.download_path_key)
            val previous = preferences.getString(pathKey, null)
            val directory = File(context.getExternalFilesDir(null), "adaptive-pause-${System.nanoTime()}").apply { mkdirs() }
            preferences.edit().putString(pathKey, directory.absolutePath).commit()
            val id = 987654322
            val provider = object : MainAPI() {
                override var name = "Adaptive pause fixture"
                override fun getVideoInterceptor(extractorLink: com.lagradost.cloudstream3.utils.ExtractorLink) = selection(1080, 4)
            }
            APIHolder.addPluginMapping(provider)
            try {
                val link = newExtractorLink(provider.name, "Studio B", "${server.origin}/dash/manifest.mpd", ExtractorLinkType.DASH) { quality = 1080 }
                coroutineScope {
                    val job = async { AdaptiveDownload.download(context, link, provider.name, "Pause fixture", "", id) {} }
                    withContext(Dispatchers.IO) { assertTrue(waiting.await(10, TimeUnit.SECONDS)) }
                    withContext(Dispatchers.Main.immediate) {
                        VideoDownloadManager.downloadEvent(id to VideoDownloadManager.DownloadActionType.Pause)
                    }
                    delay(400)
                    releaseBody.countDown()
                    delay(400)
                    assertFalse(job.isCompleted)
                    val requested = server.requests.size
                    delay(400)
                    assertEquals(requested, server.requests.size)
                    withContext(Dispatchers.Main.immediate) {
                        VideoDownloadManager.downloadEvent(id to VideoDownloadManager.DownloadActionType.Resume)
                    }
                    assertTrue(withTimeout(60_000) { job.await() }.success)
                }
                assertEquals(1, server.requests.count { it.endsWith("/manifest.mpd") })
                assertTracks(directory.listFiles().orEmpty().single { it.isFile }, 1920, 1080)
            } finally {
                releaseBody.countDown()
                APIHolder.removePluginMapping(provider)
                CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                preferences.edit().apply { if (previous == null) remove(pathKey) else putString(pathKey, previous) }.commit()
                directory.deleteRecursively()
            }
        }
    }

    @Test fun managedDownloadCanStopAndThenPublishOneCompleteFile() = runBlocking {
        FixtureServer().use { server ->
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val pathKey = context.getString(R.string.download_path_key)
            val previous = preferences.getString(pathKey, null)
            val directory = File(context.getExternalFilesDir(null), "adaptive-fixture-${System.nanoTime()}").apply { mkdirs() }
            preferences.edit().putString(pathKey, directory.absolutePath).commit()
            val id = 987654321
            val provider = object : MainAPI() {
                override var name = "Adaptive fixture"
                override fun getVideoInterceptor(extractorLink: com.lagradost.cloudstream3.utils.ExtractorLink) = selection(1080, 4)
            }
            APIHolder.addPluginMapping(provider)
            try {
                val link = newExtractorLink(provider.name, "Studio B", "${server.origin}/dash/manifest.mpd", ExtractorLinkType.DASH) { quality = 1080 }
                val stopped = AdaptiveDownload.download(context, link, provider.name, "Fixture", "", id) { state ->
                    if (state.type == VideoDownloadManager.DownloadType.IsDownloading) {
                        VideoDownloadManager.downloadEvent(id to VideoDownloadManager.DownloadActionType.Stop)
                    }
                }
                assertTrue(stopped.success)
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                val completed = withTimeout(90_000) { AdaptiveDownload.download(context, link, provider.name, "Fixture", "", id) {} }
                assertTrue(completed.success)
                val file = directory.listFiles().orEmpty().single { it.isFile }
                assertTracks(file, 1920, 1080)
                val saved = CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                assertEquals(file.name, saved?.displayName)
                assertEquals(file.length(), saved?.totalBytes)
                assertTrue(File(context.filesDir, "adaptive-downloads").listFiles().orEmpty().isEmpty())
            } finally {
                APIHolder.removePluginMapping(provider)
                CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                preferences.edit().apply { if (previous == null) remove(pathKey) else putString(pathKey, previous) }.commit()
                directory.deleteRecursively()
            }
        }
    }

    @Test fun stoppingAReplacementKeepsThePreviouslyCompletedFileAndRecord() = runBlocking<Unit> {
        FixtureServer().use { server ->
            val directory = File(context.getExternalFilesDir(null), "adaptive-previous-${System.nanoTime()}").apply { mkdirs() }
            val file = File(directory, "completed.webm").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val id = 987654323
            val previous = DownloadObjects.DownloadedFileInfo(file.length(), "", file.name, basePath = directory.absolutePath)
            CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), previous)
            try {
                val link = newExtractorLink("Fixture", "Studio B", "${server.origin}/dash/manifest.mpd", ExtractorLinkType.DASH)
                val result = AdaptiveDownload.download(context, link, null, "Replacement", "", id) { state ->
                    if (state.type == VideoDownloadManager.DownloadType.IsDownloading) {
                        VideoDownloadManager.downloadEvent(id to VideoDownloadManager.DownloadActionType.Stop)
                    }
                }
                assertTrue(result.success)
                assertEquals(previous, CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString()))
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
            } finally {
                CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                directory.deleteRecursively()
            }
        }
    }
}
