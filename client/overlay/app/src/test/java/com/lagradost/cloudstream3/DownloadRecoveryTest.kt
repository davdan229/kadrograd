package com.lagradost.cloudstream3

import android.app.ApplicationExitInfo
import com.lagradost.cloudstream3.utils.downloader.DownloadRecovery
import org.junit.Assert.*
import org.junit.Test

class DownloadRecoveryTest {
    @Test fun fatalExitsHoldTheRestoredQueue() {
        for (reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR)) assertTrue(DownloadRecovery.isCrash(reason, 0))
        assertTrue(DownloadRecovery.isCrash(ApplicationExitInfo.REASON_SIGNALED, 6))
        assertTrue(DownloadRecovery.isCrash(ApplicationExitInfo.REASON_SIGNALED, 11))
        assertTrue(DownloadRecovery.isCrash(ApplicationExitInfo.REASON_EXIT_SELF, 1))
    }

    @Test fun normalStopsKeepExistingQueueBehaviour() {
        assertFalse(DownloadRecovery.isCrash(ApplicationExitInfo.REASON_USER_REQUESTED, 0))
        assertFalse(DownloadRecovery.isCrash(ApplicationExitInfo.REASON_LOW_MEMORY, 0))
        assertFalse(DownloadRecovery.isCrash(ApplicationExitInfo.REASON_EXIT_SELF, 0))
        assertFalse(DownloadRecovery.isCrash(ApplicationExitInfo.REASON_SIGNALED, 9))
    }
}
