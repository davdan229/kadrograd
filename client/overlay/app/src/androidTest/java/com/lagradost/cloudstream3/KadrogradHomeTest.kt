package com.lagradost.cloudstream3

import android.app.Instrumentation
import android.graphics.ImageFormat
import android.media.ImageReader
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.action.ViewActions.click
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.BundledSources
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.selectHomepage
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.instanceOf
import java.io.File

class KadrogradHomeTest {
    @Test fun sourcePickerListsAllBundledSourcesAndKeepsLaterFilterChanges() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("rebuild_preference", 0).edit().putString("HAS_DONE_SETUP", "true").commit()
        val settings = context.getSharedPreferences("kadrograd_client", 0)
        val previousTypes = DataStoreHelper.homePreference
        DataStoreHelper.homePreference = listOf(TvType.Movie, TvType.TvSeries)
        settings.edit().remove("home_types_migrated").commit()
        try {
            BundledSources.prepare(context, PluginManager.getPluginsOnline())
            assertTrue(DataStoreHelper.homePreference.contains(TvType.Anime))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                withTimeout(60_000) {
                    while (getApiFromNameNull("Кадроград · AnimeVost") == null) delay(100)
                }
                for (name in listOf("Кадроград · AnimeVost", "Кадроград · AniLibria", "Кадроград · Zona")) {
                    val selected = CompletableDeferred<String>()
                    scenario.onActivity { it.selectHomepage(null) { picked -> selected.complete(picked) } }
                    onData(allOf(instanceOf(String::class.java), containsString(name))).perform(click())
                    assertEquals(name, withTimeout(5_000) { selected.await() })
                }
                DataStoreHelper.homePreference = listOf(TvType.Movie)
                BundledSources.prepare(context, PluginManager.getPluginsOnline())
                assertEquals(listOf(TvType.Movie), DataStoreHelper.homePreference)
            }
        } finally { DataStoreHelper.homePreference = previousTypes }
    }

    @Test fun allBundledSourcesHaveAVisibleHeroAndPlayButton() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("rebuild_preference", 0).edit()
            .putString("HAS_DONE_SETUP", "true")
            .putString("0/home_api_used", "\"Кадроград · AnimeVost\"").commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt("app_layout_key", 0).putBoolean("auto_update_plugins", false).commit()
        val directory = File(context.getExternalFilesDir(null), "android-checks").apply { mkdirs() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: HomeViewModel
            scenario.onActivity { model = ViewModelProvider(it)[HomeViewModel::class.java] }
            withTimeout(60_000) {
                while (getApiFromNameNull("Кадроград · AnimeVost") == null) delay(250)
            }
            val repo = "https://raw.githubusercontent.com/davdan229/kadrograd/main/repo.json"
            withTimeout(10_000) {
                while (RepositoryManager.getRepositories().none { it.url == repo }) delay(100)
            }
            val plugin = PluginManager.getPluginsOnline().single { it.internalName == "Kadrograd" }
            assertEquals(PluginManager.getPluginPath(context, "Kadrograd", repo).absolutePath, plugin.filePath)
            assertTrue(File(plugin.filePath).isFile)
            assertTrue(getApiFromNameNull("Кадроград · Zona")!!.hasDownloadSupport)
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                if (navHost.navController.currentDestination?.id != R.id.navigation_home) {
                    navHost.navController.navigate(R.id.navigation_home)
                }
            }
            val failures = mutableListOf<String>()
            for ((key, name) in listOf("vost" to "Кадроград · AnimeVost", "libria" to "Кадроград · AniLibria", "zona" to "Кадроград · Zona")) {
                model.loadAndCancel(name, forceReload = true, fromUI = true)
                val ready = withTimeoutOrNull(65_000) {
                    while (true) {
                        var ready = false
                        scenario.onActivity {
                            val preview = model.preview.value
                            ready = model.apiName.value == name && preview is Resource.Success &&
                                preview.value.second.isNotEmpty() && preview.value.second.all { it.apiName == name }
                        }
                        if (ready) break
                        delay(500)
                    }
                    true
                }
                if (ready != true) {
                    failures.add("$name: no preview; page=${model.page.value?.javaClass?.simpleName}, preview=${model.preview.value?.javaClass?.simpleName}")
                } else {
                    delay(3_000)
                    scenario.onActivity { activity ->
                        val pager = activity.findViewById<View>(R.id.home_preview_viewpager)
                        val play = activity.findViewById<View>(R.id.home_preview_play)
                        if (pager == null || !pager.isShown || pager.height <= 0) failures.add("$name: hero missing")
                        if (play == null || !play.isShown) failures.add("$name: play button missing")
                    }
                }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(directory, "home-$key.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            try {
                renderZonaUhd(instrumentation)
            } catch (failure: Exception) {
                failures.add("Zona UHD playback: ${failure.message}")
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }
    }

    private suspend fun renderZonaUhd(instrumentation: Instrumentation) {
        val api = requireNotNull(getApiFromNameNull("Кадроград · Zona"))
        val shelf = requireNotNull(api.getMainPage(1, MainPageRequest("Фильмы в 4K", "verified-4k", false)))
        val card = shelf.items.flatMap { it.list }.single { it.url.endsWith("/movies/slezy-stali") }
        check(card.quality == SearchQuality.FourK) { "Card has no verified 4K badge" }
        val movie = api.load(card.url) as MovieLoadResponse
        val links = mutableListOf<ExtractorLink>()
        api.loadLinks(movie.dataUrl, false, {}, links::add)
        val link = links.single { it.quality == 2160 && it.name.startsWith("Blender Foundation") }
        val source = ProgressiveMediaSource.Factory(
            DefaultHttpDataSource.Factory().setDefaultRequestProperties(link.headers).setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(8_000)
        ).createMediaSource(MediaItem.fromUri(link.url))
        val firstFrame = CompletableDeferred<VideoSize>()
        val reader = ImageReader.newInstance(3840, 1714, ImageFormat.PRIVATE, 2)
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val active = ExoPlayer.Builder(instrumentation.targetContext).build()
                player = active
                active.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        firstFrame.complete(active.videoSize)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        firstFrame.completeExceptionally(error)
                    }
                })
                active.setVideoSurface(reader.surface)
                active.setMediaSource(source)
                active.prepare()
                active.play()
            }
            val size = withTimeout(35_000) { firstFrame.await() }
            check(size.width == 3840 && size.height == 1714) { "Expected 3840×1714, got ${size.width}×${size.height}" }
            android.util.Log.i("KadrogradUhdTest", "First frame rendered at ${size.width}×${size.height}")
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            reader.close()
        }
    }
}
