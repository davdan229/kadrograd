package com.lagradost.cloudstream3

import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class KadrogradHomeTest {
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
            for ((key, name) in listOf("vost" to "Кадроград · AnimeVost", "libria" to "Кадроград · AniLibria", "zona" to "Кадроград · Zona")) {
                model.loadAndCancel(name, forceReload = true, fromUI = true)
                withTimeout(100_000) {
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
                }
                delay(3_000)
                scenario.onActivity { activity ->
                    val pager = activity.findViewById<View>(R.id.home_preview_viewpager)
                    val play = activity.findViewById<View>(R.id.home_preview_play)
                    assertTrue("$name: hero missing", pager != null && pager.isShown && pager.height > 0)
                    assertTrue("$name: play button missing", play != null && play.isShown)
                }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(directory, "home-$key.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }
}
