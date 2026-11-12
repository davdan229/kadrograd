package com.lagradost.cloudstream3

import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.DownloadRecovery
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DownloadCrashRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = 876543210

    @Test fun recordQueueAndTriggerNativeCrash() {
        context.getSharedPreferences("download_recovery", 0).edit().clear().commit()
        val metadata = DownloadObjects.DownloadEpisodeMetadata(id, id, "Crash recovery fixture",
            "Fixture", null, null, null, null, TvType.Movie)
        val item = DownloadObjects.DownloadItem(null, "Movies", metadata, emptyList())
        val wrapper = DownloadObjects.DownloadResumePackage(item, 0).toWrapper()
        CloudStreamApp.setKey(DownloadQueueManager.QUEUE_KEY, arrayOf(wrapper))
        check(context.getSharedPreferences("rebuild_preference", 0).edit()
            .putInt("native_crash_check_pid", Process.myPid()).commit())
        check(context.getSharedPreferences("download_crash_check", 0).edit()
            .putInt("pid", Process.myPid()).commit())
        println("Queue persisted before intentional SIGABRT")
        System.out.flush()
        Process.sendSignal(Process.myPid(), 6)
        Thread.sleep(10_000)
        fail("The intentional native crash did not stop the process")
    }

    @Test fun queueCanBeCancelledAfterNativeCrashWithoutRestartingIt() = runBlocking<Unit> {
        assertNotEquals(context.getSharedPreferences("download_crash_check", 0).getInt("pid", 0), Process.myPid())
        assertTrue("A native crash must hold automatic queue restoration", DownloadRecovery.holdRestoredQueue(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(10_000) {
                while (VideoDownloadManager.downloadStatus[id] != VideoDownloadManager.DownloadType.IsPaused) delay(100)
            }
            assertTrue(DownloadQueueManager.queue.value.any { it.id == id })
            assertFalse(DownloadQueueService.isRunning)
            assertTrue(VideoDownloadManager.currentDownloads.value.isEmpty())
            delay(2_000)
            scenario.onActivity { assertFalse(it.isFinishing) }
            DownloadQueueManager.cancelDownload(id)
            withTimeout(5_000) { while (DownloadQueueManager.queue.value.any { it.id == id }) delay(100) }
            assertFalse(DownloadQueueService.isRunning)
            assertTrue(DownloadRecovery.holdRestoredQueue(context))
            DownloadRecovery.allowUserRequest(context)
            assertFalse(DownloadRecovery.holdRestoredQueue(context))
        }
    }
}
