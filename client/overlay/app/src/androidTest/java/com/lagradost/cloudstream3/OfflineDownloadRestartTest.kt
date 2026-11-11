package com.lagradost.cloudstream3

import android.net.ConnectivityManager
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfflineDownloadRestartTest {
    @Test fun savedDownloadPlaysAfterProcessRestartWithoutNetwork() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val checkpoint = context.getSharedPreferences("kadrograd_offline_check", 0)
        val id = checkpoint.getInt("download_id", 0)
        assertTrue("The normal download did not leave a completed file", id != 0)
        assertNotEquals("The app process was not restarted", checkpoint.getInt("pid", 0), Process.myPid())
        assertNull("The app must start without a network", context.getSystemService(ConnectivityManager::class.java).activeNetwork)
        assertNull(AdaptiveDownload.activeFileInfo(id))
        val folder = File(requireNotNull(checkpoint.getString("directory", null))).canonicalFile
        require(folder.parentFile == context.getExternalFilesDir(null)?.canonicalFile && folder.name.startsWith("adaptive-ui-"))
        try {
            assertNotNull(CloudStreamApp.getKey<DownloadObjects.DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString()))
            assertTrue(folder.walkTopDown().any { it.isFile && it.length() > 0 })
            AdaptiveDownloadTest().playFromDownloadsOffline(1920, 1080)
        } finally {
            CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
            folder.deleteRecursively()
            checkpoint.edit().clear().commit()
        }
    }
}
