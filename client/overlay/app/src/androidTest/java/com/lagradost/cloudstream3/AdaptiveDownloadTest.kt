package com.lagradost.cloudstream3

import android.media.MediaExtractor
import android.media.MediaFormat
import android.app.Notification
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
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
import org.junit.Before
import org.junit.After
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

class AdaptiveDownloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var previousLocale: Locale? = null
    private var previousLanguage: String? = null

    @Before fun useRussianInterface() {
        previousLocale = Locale.getDefault()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val key = context.getString(R.string.locale_key)
        previousLanguage = preferences.getString(key, null)
        preferences.edit().putString(key, "ru").commit()
        CommonActivity.setLocale(context, "ru")
        assertEquals("ru", context.resources.configuration.locales[0].language)
    }

    @After fun restoreLanguage() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val key = context.getString(R.string.locale_key)
        preferences.edit().apply {
            previousLanguage?.let { putString(key, it) } ?: remove(key)
        }.commit()
        previousLocale?.let { CommonActivity.setLocale(context, it.toLanguageTag()) }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun revealChoice(label: String, towardEnd: Boolean = true) {
        // AdapterDataLoaderAction can loop forever at an inset bottom-sheet edge.
        // Real swipes keep the visibility check bounded and exercise the user gesture.
        repeat(20) { attempt ->
            try {
                onView(allOf(withText(containsString(label)), isCompletelyDisplayed()))
                    .inRoot(isDialog()).check(matches(isDisplayed()))
                return
            } catch (missing: NoMatchingViewException) {
                if (attempt == 19) throw missing
                onView(withId(R.id.listview1)).inRoot(isDialog())
                    .perform(object : ViewAction {
                        override fun getDescription() = "Swipe inside the visible part of the choice sheet"
                        override fun getConstraints() = isDisplayed()
                        override fun perform(controller: UiController, view: View) {
                            val bounds = android.graphics.Rect()
                            check(view.getGlobalVisibleRect(bounds) && bounds.height() > 16)
                            val top = bounds.top + bounds.height() * 0.15f
                            val bottom = bounds.bottom - bounds.height() * 0.15f
                            GeneralSwipeAction(Swipe.FAST,
                                { floatArrayOf(bounds.exactCenterX(), if (towardEnd) bottom else top) },
                                { floatArrayOf(bounds.exactCenterX(), if (towardEnd) top else bottom) },
                                Press.FINGER).perform(controller, view)
                        }
                    })
            }
        }
    }

    internal suspend fun playFromDownloadsOffline(expectedWidth: Int, expectedHeight: Int, titleText: String = "Download fixture") {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        try {
            shell("cmd connectivity airplane-mode enable")
            shell("svc wifi disable")
            shell("svc data disable")
            withTimeout(10_000) { while (connectivity.activeNetwork != null) delay(100) }
            ActivityScenario.launch(MainActivity::class.java).use { offline ->
                offline.onActivity { activity ->
                    val nav = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    nav.navController.navigate(R.id.navigation_downloads)
                }
                withTimeout(20_000) {
                    while (true) {
                        var listed = false
                        offline.onActivity {
                            val titles = arrayListOf<View>()
                            it.window.decorView.findViewsWithText(titles, titleText, View.FIND_VIEWS_WITH_TEXT)
                            listed = titles.any { title -> title.id == R.id.download_header_title && title.isShown }
                        }
                        if (listed) break
                        delay(100)
                    }
                }
                onView(allOf(withId(R.id.download_header_title), withText(titleText))).perform(click())
                withTimeout(20_000) {
                    while (true) {
                        var playing = false
                        offline.onActivity {
                            val player = it.findViewById<PlayerView>(R.id.player_view)?.player
                            player?.playerError?.let { error -> throw AssertionError("Offline playback failed", error) }
                            playing = player != null && player.currentPosition > 0 &&
                                player.videoSize.width == expectedWidth && player.videoSize.height == expectedHeight
                        }
                        if (playing) break
                        delay(100)
                    }
                }
                offline.onActivity {
                    val view = checkNotNull(it.findViewById<PlayerView>(R.id.player_view))
                    val player = checkNotNull(view.player)
                    assertTrue("The saved movie has no duration", player.duration > 0)
                    assertTrue("The saved movie is not seekable", player.isCurrentMediaItemSeekable)
                    player.pause()
                    view.showController()
                }
                val timeBarId = androidx.media3.ui.R.id.exo_progress
                val timeBar = allOf(withId(timeBarId), isDisplayed())
                for (fraction in listOf(0.6, 0.1, 0.85)) {
                    var duration = 0L
                    var controlsVisible = false
                    offline.onActivity {
                        val view = checkNotNull(it.findViewById<PlayerView>(R.id.player_view))
                        duration = checkNotNull(view.player).duration
                        controlsVisible = view.findViewById<View>(timeBarId)?.isShown == true
                    }
                    if (!controlsVisible) {
                        // CloudStream shows its own controls when the video surface is tapped.
                        onView(withId(R.id.player_holder)).perform(GeneralClickAction(Tap.SINGLE, { view ->
                            val location = IntArray(2)
                            view.getLocationOnScreen(location)
                            floatArrayOf(location[0] + view.width * 0.5f, location[1] + view.height * 0.3f)
                        }, Press.FINGER))
                        withTimeout(5_000) {
                            while (!controlsVisible) {
                                offline.onActivity {
                                    controlsVisible = it.findViewById<View>(timeBarId)?.let { bar ->
                                        bar.isShown && bar.getGlobalVisibleRect(android.graphics.Rect())
                                    } == true
                                }
                                if (!controlsVisible) delay(100)
                            }
                        }
                    }
                    onView(timeBar).perform(GeneralClickAction(Tap.SINGLE, { view ->
                        check(view.isEnabled) { "Offline seek bar is disabled" }
                        val position = IntArray(2)
                        view.getLocationOnScreen(position)
                        floatArrayOf(position[0] + view.width * fraction.toFloat(), position[1] + view.height / 2f)
                    }, Press.FINGER))
                    val target = (duration * fraction).toLong()
                    withTimeout(10_000) {
                        while (true) {
                            var reached = false
                            offline.onActivity {
                                val player = checkNotNull(it.findViewById<PlayerView>(R.id.player_view)?.player)
                                player.playerError?.let { error -> throw AssertionError("Offline seeking failed", error) }
                                reached = kotlin.math.abs(player.currentPosition - target) < duration / 6 &&
                                    player.playbackState == androidx.media3.common.Player.STATE_READY
                            }
                            if (reached) break
                            delay(50)
                        }
                    }
                }
                // The arrows must move the same offline timeline as the time bar.
                for ((button, direction) in listOf(R.id.player_rew to -1, R.id.player_ffwd to 1)) {
                    var before = 0L
                    offline.onActivity {
                        before = checkNotNull(it.findViewById<PlayerView>(R.id.player_view)?.player).currentPosition
                    }
                    onView(allOf(withId(button), isDisplayed())).perform(click())
                    withTimeout(10_000) {
                        while (true) {
                            var moved = false
                            offline.onActivity {
                                val player = checkNotNull(it.findViewById<PlayerView>(R.id.player_view)?.player)
                                player.playerError?.let { error -> throw AssertionError("Offline arrow seeking failed", error) }
                                moved = (player.currentPosition - before) * direction > 500 &&
                                    player.playbackState == androidx.media3.common.Player.STATE_READY
                            }
                            if (moved) break
                            delay(50)
                        }
                    }
                }
                assertNull(connectivity.activeNetwork)
            }
        } finally {
            // Closing a fullscreen activity can leave the emulator in landscape
            // until the next activity enters. Give the download picker a portrait display.
            shell("wm user-rotation lock 0")
            shell("cmd connectivity airplane-mode disable")
            shell("svc wifi enable")
            shell("svc data enable")
        }
    }

    @Test fun existingHlsDownloadCanBeScrubbedWithoutChangingTheFile() = runBlocking<Unit> {
        val directory = File(context.getExternalFilesDir(null), "legacy-hls-fixture").apply { mkdirs() }
        val file = File(directory, "Legacy fixture.mp4")
        instrumentation.context.assets.open("adaptive/legacy-hls.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putInt("app_layout_key", 0).commit()
        context.getSharedPreferences("rebuild_preference", 0).edit().putString("HAS_DONE_SETUP", "true").commit()
        val id = -908234
        val title = "Legacy fixture"
        val key = id.toString()
        val seasonFolder = getFolderName(DOWNLOAD_EPISODE_CACHE, key)
        val length = file.length()
        val modified = file.lastModified()
        try {
            CloudStreamApp.setKey(DOWNLOAD_HEADER_CACHE, key,
                DownloadObjects.DownloadHeaderCached("Fixture", "https://example.test/legacy", TvType.Movie, title, null,
                    System.currentTimeMillis(), id))
            CloudStreamApp.setKey(seasonFolder, key,
                DownloadObjects.DownloadEpisodeCached(title, null, 0, null, id, description = null,
                    cacheTime = System.currentTimeMillis(), id = id))
            CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, key,
                DownloadObjects.DownloadedFileInfo(length, "", file.name, extraInfo = "12", basePath = directory.absolutePath))
            playFromDownloadsOffline(640, 360, title)
            assertEquals(length, file.length())
            assertEquals(modified, file.lastModified())
        } finally {
            CloudStreamApp.removeKey(DOWNLOAD_HEADER_CACHE, key)
            CloudStreamApp.removeKey(seasonFolder, key)
            CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, key)
            file.delete()
            directory.delete()
        }
    }

    /** Local HTTP verifies that every manifest/segment uses the provider's request interceptor. */
    internal inner class FixtureServer(private val disconnectOn: String? = null,
        private val beforeBody: (String) -> Unit = {}) : AutoCloseable {
        val requests = ConcurrentLinkedQueue<String>()
        val ranges = ConcurrentLinkedQueue<Pair<String, Long>>()
        private val broken = AtomicBoolean(false)
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
                        val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                        val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val end = minOf(range?.groupValues?.get(2)?.toIntOrNull() ?: Int.MAX_VALUE, (bytes?.size ?: 0) - 1)
                        ranges.add(path to start.toLong())
                        val status = when { bytes == null -> "403 Forbidden"; start > end -> "416 Range Not Satisfiable"; range != null -> "206 Partial Content"; else -> "200 OK" }
                        val body = if (bytes != null && start <= end) bytes.copyOfRange(start, end + 1) else byteArrayOf()
                        val contentRange = if (range != null && body.isNotEmpty()) "Content-Range: bytes $start-$end/${bytes!!.size}\r\n" else ""
                        val type = when { path.endsWith(".mpd") -> "application/dash+xml"; path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"; else -> "application/octet-stream" }
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: $type\r\n$contentRange".toByteArray())
                            write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            flush()
                            beforeBody(path)
                            if (disconnectOn != null && start == 0 && body.size > 1 && path.contains(disconnectOn) && broken.compareAndSet(false, true)) {
                                write(body, 0, body.size / 2)
                                flush()
                                return@use
                            }
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
                    for (height in listOf(480, 720, 1080, 1440, 2160)) for (voice in listOf("Studio A", "Studio B")) {
                        callback(newExtractorLink(name, "$voice · $height", "${server.origin}/dash/manifest.mpd#$height-$voice", ExtractorLinkType.DASH) {
                            quality = height
                        })
                    }
                    return true
                }
            }
            var downloadId: Int? = null
            var keepCompletedFile = false
            val observedBytes = AtomicLong()
            val progressListener: (Triple<Int, Long, Long>) -> Unit = { event ->
                if (event.first == downloadId) observedBytes.set(event.second)
            }
            VideoDownloadManager.downloadProgressEvent += progressListener
            val finished = CountDownLatch(1)
            kotlin.concurrent.thread(name = "download-ui-watchdog", isDaemon = true) {
                if (!finished.await(45, TimeUnit.SECONDS)) {
                    Thread.getAllStackTraces().forEach { (thread, trace) ->
                        android.util.Log.w("KadrogradDownloadTest", "${thread.name} ${thread.state}\n${trace.joinToString("\n")}")
                    }
                    val screenshots = File(context.getExternalFilesDir(null), "android-checks").apply { mkdirs() }
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(screenshots, "download-ui-stalled.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
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
                    onView(withId(R.id.listview1)).inRoot(isDialog()).check { view, error ->
                        if (error != null) throw error
                        val visible = android.graphics.Rect()
                        view.getGlobalVisibleRect(visible)
                        android.util.Log.i("KadrogradDownloadTest", "Choice list height=${view.height}, visible=$visible, bottomPadding=${view.paddingBottom}")
                    }
                    // The movie button must show all choices before anything is queued.
                    for (height in listOf(2160, 1440, 1080, 720, 480)) for (voice in listOf("Studio A", "Studio B")) {
                        android.util.Log.i("KadrogradDownloadTest", "Checking choice $voice $height")
                        revealChoice("$voice · $height")
                    }
                    assertTrue(VideoDownloadManager.currentDownloads.value.isEmpty())
                    revealChoice("Studio B · 1080", towardEnd = false)
                    onView(allOf(withText(containsString("Studio B · 1080")), isCompletelyDisplayed()))
                        .inRoot(isDialog()).perform(click())
                    withContext(Dispatchers.IO) { assertTrue("Queue service did not request video", waiting.await(20, TimeUnit.SECONDS)) }
                    downloadId = checkNotNull(DownloadQueueService.downloadInstances.value.singleOrNull()) {
                        "Queue entry disappeared: status=${VideoDownloadManager.downloadStatus}, files=${directory.walkTopDown().filter { it.isFile }.map { it.name }.toList()}"
                    }.downloadQueueWrapper.id
                    assertTrue(DownloadQueueService.isRunning)
                    val activeInfo = AdaptiveDownload.activeFileInfo(requireNotNull(downloadId))
                    assertNotNull("The active download has no progress entry", activeInfo)
                    assertNull(CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, downloadId.toString()))
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    withTimeout(5_000) { while (observedBytes.get() <= 0) delay(100) }
                    withTimeout(5_000) {
                        while (true) {
                            var text: CharSequence? = null
                            scenario.onActivity {
                                val label = it.findViewById<TextView>(R.id.result_movie_download_text_precentage)
                                if (label?.isShown == true) text = label.text
                            }
                            if (!text.isNullOrBlank()) {
                                assertNotEquals("100%", text.toString())
                                break
                            }
                            delay(100)
                        }
                    }
                    try {
                        withTimeout(5_000) {
                            while (notifications.activeNotifications.none {
                                val maximum = it.notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX)
                                val progress = it.notification.extras.getInt(Notification.EXTRA_PROGRESS)
                                val indeterminate = it.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
                                it.id == downloadId && ((maximum > 0 && progress in 1 until maximum) || indeterminate)
                            }) delay(100)
                        }
                    } finally {
                        android.util.Log.i("KadrogradDownloadTest", "Notifications=${notifications.activeNotifications.map {
                            listOf(it.id, it.notification.extras.getInt(Notification.EXTRA_PROGRESS),
                                it.notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX),
                                it.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
                        }}; progress=${CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, downloadId.toString())}")
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
                        while (CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, downloadId.toString()) == null) delay(100)
                    }
                    val file = directory.walkTopDown().single { it.isFile }
                    assertTracks(file, 1920, 1080)
                    scenario.onActivity { assertFalse(it.isFinishing) }
                }
                val requestsBeforeOffline = server.requests.size
                playFromDownloadsOffline(1920, 1080)
                assertEquals(requestsBeforeOffline, server.requests.size)
                if (InstrumentationRegistry.getArguments().getString("keepOfflineFixture") == "true") {
                    assertTrue(context.getSharedPreferences("kadrograd_offline_check", 0).edit()
                        .putInt("pid", android.os.Process.myPid())
                        .putInt("download_id", requireNotNull(downloadId))
                        .putString("directory", directory.absolutePath).commit())
                    keepCompletedFile = true
                }
            } finally {
                finished.countDown()
                releaseBody.countDown()
                VideoDownloadManager.downloadProgressEvent -= progressListener
                downloadId?.let { id ->
                    withContext(Dispatchers.Main.immediate) {
                        VideoDownloadManager.downloadEvent(id to VideoDownloadManager.DownloadActionType.Stop)
                    }
                    if (!keepCompletedFile) CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                }
                APIHolder.removePluginMapping(provider)
                preferences.edit().apply { if (previous == null) remove(pathKey) else putString(pathKey, previous) }.commit()
                if (!keepCompletedFile) directory.deleteRecursively()
            }
        }
    }

    @Test fun dashPreservesSdFullHdAndUhdAndSelectedOpusVoice() = runBlocking {
        FixtureServer().use { server ->
            for ((width, height) in listOf(854 to 480, 1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)) {
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

    @Test fun batchButtonAppliesChosenQualityAndVoiceToTheDisplayedEpisodes() = runBlocking<Unit> {
        FixtureServer().use { server ->
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val pathKey = context.getString(R.string.download_path_key)
            val previous = preferences.getString(pathKey, null)
            val directory = File(context.getExternalFilesDir(null), "batch-ui-${System.nanoTime()}").apply { mkdirs() }
            preferences.edit().putString(pathKey, directory.absolutePath).putInt("app_layout_key", 0).commit()
            context.getSharedPreferences("rebuild_preference", 0).edit().putString("HAS_DONE_SETUP", "true").commit()
            val provider = object : MainAPI() {
                override var name = "Batch download fixture"
                override var mainUrl = server.origin
                override val hasDownloadSupport = true
                override val supportedTypes = setOf(TvType.TvSeries)
                override suspend fun load(url: String): LoadResponse = newTvSeriesLoadResponse(
                    "Batch fixture", url, TvType.TvSeries, (1..2).map { number ->
                        newEpisode("episode-$number") { episode = number; season = 1 }
                    })

                override fun getVideoInterceptor(extractorLink: com.lagradost.cloudstream3.utils.ExtractorLink) =
                    selection(extractorLink.quality, if (extractorLink.name.contains("Studio B")) 4 else 3)

                override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
                    callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit): Boolean {
                    for (height in listOf(720, 1080)) for (voice in listOf("Studio A", "Studio B")) {
                        callback(newExtractorLink(name, "$voice · $height", "${server.origin}/dash/manifest.mpd?$data#$height-$voice", ExtractorLinkType.DASH) {
                            quality = height
                        })
                    }
                    return true
                }
            }
            val completed = ConcurrentLinkedQueue<Int>()
            val statusListener: (Pair<Int, VideoDownloadManager.DownloadType>) -> Unit = { (id, state) ->
                if (state == VideoDownloadManager.DownloadType.IsDone) completed.add(id)
            }
            VideoDownloadManager.downloadStatusEvent += statusListener
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    withTimeout(30_000) {
                        while (!PluginManager.loadedOnlinePlugins || !PluginManager.loadedLocalPlugins) delay(100)
                    }
                    APIHolder.addPluginMapping(provider)
                    scenario.onActivity { it.loadResult("${server.origin}/series", provider.name, "Batch fixture") }
                    withTimeout(20_000) {
                        while (true) {
                            var ready = false
                            scenario.onActivity { ready = it.findViewById<View>(R.id.result_batch_download_button)?.isShown == true }
                            if (ready) break
                            delay(100)
                        }
                    }
                    onView(withId(R.id.result_batch_download_button)).perform(click())
                    onView(withText(R.string.yes)).inRoot(isDialog()).perform(click())
                    revealChoice("Studio B · 1080")
                    assertTrue(server.requests.isEmpty())
                    onView(allOf(withText(containsString("Studio B · 1080")), isCompletelyDisplayed())).inRoot(isDialog()).perform(click())
                    withTimeout(60_000) { while (completed.distinct().size != 2) delay(100) }
                    val files = directory.walkTopDown().filter { it.isFile }.toList()
                    assertEquals(2, files.size)
                    files.forEach { assertTracks(it, 1920, 1080) }
                    assertEquals(2, server.requests.count { it.endsWith("/init-4.webm") })
                    assertFalse(server.requests.any { it.endsWith("/init-3.webm") || it.endsWith("/init-5.webm") })
                }
            } finally {
                VideoDownloadManager.downloadStatusEvent -= statusListener
                completed.distinct().forEach { CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, it.toString()) }
                APIHolder.removePluginMapping(provider)
                preferences.edit().apply { if (previous == null) remove(pathKey) else putString(pathKey, previous) }.commit()
                directory.deleteRecursively()
            }
        }
    }

    @Test fun interruptedMediaReadContinuesFromItsReceivedOffset() = runBlocking {
        FixtureServer(disconnectOn = "/chunk-0-").use { server ->
            val output = File.createTempFile("adaptive-range-retry-", ".webm", context.cacheDir)
            try {
                val link = newExtractorLink("Fixture", "Studio B", "${server.origin}/dash/manifest.mpd", ExtractorLinkType.DASH) { quality = 2160 }
                assertTrue(withTimeout(60_000) { AdaptiveDownload.exportToFile(context, link, output, selection(2160, 4)) })
                assertTracks(output, 3840, 2160)
                assertTrue("The broken chunk did not resume with a byte range", server.ranges.any { it.first.contains("/chunk-0-") && it.second > 0 })
                assertEquals(1, server.requests.count { it.endsWith("manifest.mpd") })
                assertEquals(1, server.requests.count { it.contains("init-0.webm") })
            } finally { output.delete() }
        }
    }

    @Test fun aSecondExportDoesNotRestartOrCancelTheFirst() = runBlocking {
        FixtureServer().use { first -> FixtureServer().use { second ->
            val firstFile = File.createTempFile("adaptive-parallel-a-", ".webm", context.cacheDir)
            val secondFile = File.createTempFile("adaptive-parallel-b-", ".webm", context.cacheDir)
            try {
                coroutineScope {
                    val jobs = listOf(Triple(first, firstFile, 1080), Triple(second, secondFile, 720)).map { (server, file, height) ->
                        async {
                            val link = newExtractorLink("Fixture", "Studio B", "${server.origin}/dash/manifest.mpd", ExtractorLinkType.DASH) { quality = height }
                            withTimeout(60_000) { AdaptiveDownload.exportToFile(context, link, file, selection(height, 4)) }
                        }
                    }
                    jobs.forEach { assertTrue(it.await()) }
                }
                assertTracks(firstFile, 1920, 1080)
                assertTracks(secondFile, 1280, 720)
                for (server in listOf(first, second)) assertEquals(1, server.requests.count { it.endsWith("manifest.mpd") })
            } finally { firstFile.delete(); secondFile.delete() }
        } }
    }

    @Test fun aNetworkWaitDoesNotTriggerTheLocalExportWatchdog() = runBlocking {
        val delayed = AtomicBoolean(false)
        FixtureServer { path ->
            if (path.contains("/chunk-1-00002.") && delayed.compareAndSet(false, true)) {
                Thread.sleep(28_000)
            }
        }.use { server ->
            val output = File.createTempFile("adaptive-network-wait-", ".webm", context.cacheDir)
            try {
                val link = newExtractorLink("Fixture", "Studio B", "${server.origin}/dash/manifest.mpd", ExtractorLinkType.DASH) { quality = 1080 }
                assertTrue(withTimeout(90_000) { AdaptiveDownload.exportToFile(context, link, output, selection(1080, 4)) })
                assertTrue(delayed.get())
                assertTracks(output, 1920, 1080)
                assertEquals(1, server.requests.count { it.endsWith("manifest.mpd") })
            } finally { output.delete() }
        }
    }

    @Test fun hlsWithSeparateAudioRecoversAndSavesTheSelectedVoice() = runBlocking {
        FixtureServer(disconnectOn = "/video-1.ts").use { server ->
            val output = File.createTempFile("adaptive-hls-", ".mp4", context.cacheDir)
            try {
                val link = newExtractorLink("Fixture", "Studio B", "${server.origin}/hls/master.m3u8", ExtractorLinkType.M3U8) { quality = 1080 }
                assertTrue(withTimeout(90_000) { AdaptiveDownload.exportToFile(context, link, output, selection(1080, 4)) })
                assertTracks(output, 1920, 1080, "video/avc", "audio/mp4a-latm")
                assertTrue(server.requests.any { it.contains("audio-b-") })
                assertFalse(server.requests.any { it.contains("audio-a") })
                assertTrue("HLS did not resume the interrupted segment", server.ranges.any { it.first.endsWith("/video-1.ts") && it.second > 0 })
                assertEquals(1, server.requests.count { it.endsWith("/master.m3u8") })
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
                val originalBytes = file.readBytes()
                val inflated = requireNotNull(saved).copy(totalBytes = file.length() + 1_000_000)
                CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), inflated)
                val repaired = VideoDownloadManager.getDownloadFileInfo(context, id)
                assertEquals(file.length(), repaired?.totalBytes)
                assertEquals(file.length(), repaired?.fileLength)
                assertArrayEquals("Repair changed the saved movie", originalBytes, file.readBytes())
                val brokenId = id + 100
                val broken = File(directory, "Broken [$brokenId-${System.currentTimeMillis()}].webm")
                broken.writeBytes(originalBytes.copyOf(originalBytes.size / 2))
                try {
                    CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, brokenId.toString(), inflated.copy(
                        displayName = broken.name, totalBytes = file.length()))
                    val incomplete = VideoDownloadManager.getDownloadFileInfo(context, brokenId)
                    assertEquals("A truncated movie was incorrectly marked complete", file.length(), incomplete?.totalBytes)
                    assertTrue(requireNotNull(incomplete).fileLength < incomplete.totalBytes)
                } finally {
                    CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, brokenId.toString())
                    broken.delete()
                }
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
