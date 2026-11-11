package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.download.button.DownloadMetadata
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressTest {
    @Test fun unknownTotalNeverLooksComplete() {
        assertEquals(0L, DownloadMetadata(1, 4_000_000, 0, DownloadType.IsDownloading).progressPercentage)
        assertEquals(0L, DownloadMetadata(1, 4_000_000, -1, DownloadType.IsDownloading).progressPercentage)
    }

    @Test fun knownTotalsStillShowTheirProgress() {
        assertEquals(49L, DownloadMetadata(1, 5_000, 10_000, DownloadType.IsDownloading).progressPercentage)
        assertEquals(100L, DownloadMetadata(1, 10_000, 9_000, DownloadType.IsDone).progressPercentage)
    }
}
