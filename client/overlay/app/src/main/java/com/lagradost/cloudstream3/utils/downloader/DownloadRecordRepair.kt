package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo

/** Reconciles old finalized adaptive exports without fetching or replacing their media. */
internal object DownloadRecordRepair {
    fun reconcile(context: Context, id: Int, info: DownloadedFileInfo, uri: Uri, length: Long): DownloadedFileInfo {
        if (length <= 0 || length == info.totalBytes || info.extraInfo != "100" ||
            !Regex(".* \\[${id}-\\d{13}\\]\\.(?:mp4|webm)").matches(info.displayName) ||
            VideoDownloadManager.downloadStatus.containsKey(id)) return info
        if (!hasCompleteTail(context, uri)) return info
        val repaired = info.copy(totalBytes = length)
        // Another download may have replaced the entry while the file was being inspected.
        if (CloudStreamApp.getKey<DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString()) == info) {
            CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), repaired)
            return repaired
        }
        return info
    }

    private fun hasCompleteTail(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            if (formats.none { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }) return false
            val video = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            if (video < 0 || !formats[video].containsKey(MediaFormat.KEY_DURATION)) return false
            val duration = formats[video].getLong(MediaFormat.KEY_DURATION)
            if (duration <= 0) return false
            extractor.selectTrack(video)
            extractor.seekTo((duration - 1_000_000).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var samples = 0
            var lastTime = -1L
            while (extractor.sampleTime >= 0 && samples++ < 4096) {
                lastTime = extractor.sampleTime
                if (!extractor.advance()) break
            }
            lastTime >= (duration - 1_500_000).coerceAtLeast(0)
        } catch (_: Exception) { false }
        finally { extractor.release() }
    }
}
