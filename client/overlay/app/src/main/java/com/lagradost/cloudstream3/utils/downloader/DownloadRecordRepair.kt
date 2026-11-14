package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.HlsPlaylistParser
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Checks saved media independently of its provider; never rewrites or deletes the file. */
internal object DownloadRecordRepair {
    internal enum class State { COMPLETE, PARTIAL, UNVERIFIED }
    private data class Checked(val uri: Uri, val length: Long, val info: DownloadedFileInfo,
        val state: State, val indexed: Boolean, val time: Long = android.os.SystemClock.elapsedRealtime())
    private data class Task(val context: Context, val id: Int, val info: DownloadedFileInfo, val uri: Uri, val length: Long)
    private val checked = object : LinkedHashMap<Int, Checked>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Checked>?) = size > 512
    }
    private val pending = ConcurrentHashMap.newKeySet<Int>()
    private val tasks = Channel<Task>(128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { app.baseClient.newBuilder().callTimeout(12, TimeUnit.SECONDS).build() }

    init {
        repeat(2) {
            scope.launch {
                for (task in tasks) {
                    try {
                        verifyLegacyHls(task.context, task.id, task.info, task.uri, task.length)
                    } catch (_: Exception) {
                        // Offline or expired source: retain the bytes and the unverified state.
                    } finally { pending.remove(task.id) }
                }
            }
        }
    }

    fun reconcile(context: Context, id: Int, info: DownloadedFileInfo, uri: Uri, length: Long): DownloadedFileInfo {
        if (length <= 0 || VideoDownloadManager.downloadStatus.containsKey(id)) return info
        synchronized(checked) {
            checked[id]?.takeIf { it.uri == uri && it.length == length && it.info == info &&
                (it.state != State.UNVERIFIED || android.os.SystemClock.elapsedRealtime() - it.time < 30_000)
            }?.let { return info }
        }
        // Successful transfers record the exact final byte count in every download path.
        if (info.completedBytes == length) return remember(id, info, uri, length, State.COMPLETE)
        val probe = inspect(context, uri)
        if (probe.indexed && probe.completeTail) return complete(context, id, info, uri, length)
        val hls = info.extraInfo?.toIntOrNull()?.let { it > 0 } == true && !probe.indexed
        remember(id, info, uri, length, if (probe.indexed) State.PARTIAL else State.UNVERIFIED, probe.indexed)
        if (hls && pending.add(id) && !tasks.trySend(Task(context.applicationContext, id, info, uri, length)).isSuccess) {
            pending.remove(id)
            synchronized(checked) { checked.remove(id) }
        }
        return info
    }

    fun presentationTotal(id: Int, info: DownloadedFileInfo, length: Long): Long {
        if (VideoDownloadManager.downloadStatus.containsKey(id)) return info.totalBytes
        val result = synchronized(checked) { checked[id]?.takeIf { it.length == length } }
        return when {
            result?.state == State.COMPLETE -> length
            result != null && !result.indexed -> 0L // HLS estimates are not exact missing-byte counts.
            else -> info.totalBytes
        }
    }

    fun status(id: Int, length: Long): VideoDownloadManager.DownloadType? =
        when (synchronized(checked) { checked[id]?.takeIf { it.length == length }?.state }) {
            State.COMPLETE -> VideoDownloadManager.DownloadType.IsDone
            State.PARTIAL, State.UNVERIFIED -> VideoDownloadManager.DownloadType.IsPaused
            null -> null
        }

    fun savedProgressText(context: Context, id: Int?, length: Long): String? {
        val result = synchronized(checked) { checked[id]?.takeIf { it.length == length } } ?: return null
        if (result.indexed || result.state == State.COMPLETE) return null
        val size = android.text.format.Formatter.formatShortFileSize(context, length)
        return context.getString(if (result.state == State.PARTIAL) R.string.saved_download_incomplete else R.string.saved_download_unverified, size)
    }

    internal fun verifyLegacyHls(context: Context, id: Int, info: DownloadedFileInfo, uri: Uri, length: Long) {
        if (VideoDownloadManager.downloadStatus.containsKey(id)) return
        val written = info.extraInfo?.toIntOrNull()?.takeIf { it > 0 } ?: return
        val resume = VideoDownloadManager.getDownloadResumePackage(context, id) ?: return
        val link = resume.item.links.getOrNull(resume.linkIndex ?: 0)?.takeIf {
            it.type == ExtractorLinkType.M3U8 && (info.linkHash == null || info.linkHash == it.url.hashCode())
        } ?: return
        val manifest = mediaPlaylist(link) ?: return
        if (written != manifest.first) {
            remember(id, info, uri, length, State.PARTIAL, false)
            return
        }
        if (!inspect(context, uri, manifest.second).completeTail) return
        val result = complete(context, id, info, uri, length)
        if (result.completedBytes == length) {
            VideoDownloadManager.downloadProgressEvent(Triple(id, length, length))
            VideoDownloadManager.downloadStatusEvent(id to VideoDownloadManager.DownloadType.IsDone)
        }
    }

    private fun mediaPlaylist(link: ExtractorLink): Pair<Int, Long>? {
        var url = link.url
        repeat(4) {
            val request = Request.Builder().url(url).apply {
                link.headers.forEach { (key, value) -> header(key, value) }
                if (link.referer.isNotBlank()) header("Referer", link.referer)
            }.build()
            val text = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = ByteArrayOutputStream()
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > 1024 * 1024) return null
                        bytes.write(buffer, 0, count)
                    }
                }
                url = response.request.url.toString()
                bytes.toString("UTF-8")
            }
            if (!text.trimStart().startsWith("#EXTM3U")) return null
            val master = HlsPlaylistParser.parse(url, text)
            if (master != null) {
                // Match the rendition order used by the original downloader.
                val best = master.variants.filter { it.isPlayableStandalone(master) }.maxByOrNull {
                    it.format.width.toLong() * it.format.height * 1000 + it.format.averageBitrate
                } ?: return null
                url = best.url.toString()
            } else {
                if (!text.lineSequence().any { it.trim() == "#EXT-X-ENDLIST" }) return null
                val durations = ArrayList<Double>()
                for (line in text.lineSequence().map(String::trim).filter { it.startsWith("#EXTINF:") }) {
                    val duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: return null
                    if (!duration.isFinite() || duration <= 0) return null
                    durations.add(duration)
                }
                if (durations.isEmpty()) return null
                return durations.size to (durations.sum() * 1_000_000).toLong()
            }
        }
        return null
    }

    private fun remember(id: Int, info: DownloadedFileInfo, uri: Uri, length: Long, state: State, indexed: Boolean = true): DownloadedFileInfo {
        synchronized(checked) { checked[id] = Checked(uri, length, info, state, indexed) }
        return info
    }

    private fun complete(context: Context, id: Int, info: DownloadedFileInfo, uri: Uri, length: Long): DownloadedFileInfo {
        val currentLength = try { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } } catch (_: Exception) { null }
        // Do not repair a replacement or a file that is still being written.
        if (currentLength != length || VideoDownloadManager.downloadStatus.containsKey(id) ||
            CloudStreamApp.getKey<DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString()) != info) return info
        val repaired = info.copy(totalBytes = length, completedBytes = length)
        CloudStreamApp.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), repaired)
        return remember(id, repaired, uri, length, State.COMPLETE)
    }

    private data class Probe(val indexed: Boolean, val completeTail: Boolean)
    private fun inspect(context: Context, uri: Uri, expectedDuration: Long? = null): Probe {
        val extractor = MediaExtractor()
        var indexed = false
        return try {
            // TS duration comes from surviving timestamps and cannot prove completeness.
            // MP4/WebM retain the original indexed duration when their payload is truncated.
            indexed = context.contentResolver.openInputStream(uri)?.use { input ->
                val magic = ByteArray(12)
                val count = input.read(magic)
                count >= 8 && (String(magic, 4, 4, Charsets.US_ASCII) == "ftyp" ||
                    magic.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte()))
            } == true
            extractor.setDataSource(context, uri, null)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val media = formats.indices.filter { formats[it].getString(MediaFormat.KEY_MIME)?.let { mime ->
                mime.startsWith("audio/") || mime.startsWith("video/")
            } == true }
            if (media.none { formats[it].getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ||
                media.none { formats[it].getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }) return Probe(indexed, false)
            for (track in media) {
                val format = formats[track]
                val duration = expectedDuration ?: if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
                if (duration <= 0) return Probe(indexed, false)
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val firstTime = extractor.sampleTime.coerceAtLeast(0)
                val endTime = firstTime + duration
                extractor.seekTo((endTime - 2_000_000).coerceAtLeast(firstTime), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var samples = 0
                var lastTime = -1L
                var previousTime = -1L
                var buffer = ByteBuffer.allocate(64 * 1024)
                while (extractor.sampleTime >= 0 && samples++ < 4096) {
                    val size = if (Build.VERSION.SDK_INT >= 28) extractor.sampleSize else -1L
                    if ((Build.VERSION.SDK_INT >= 28 && size < 0) || size > 16 * 1024 * 1024) return Probe(indexed, false)
                    if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size.toInt())
                    if (size < 0 && buffer.capacity() < 4 * 1024 * 1024) buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                    buffer.clear()
                    // Advancing the index alone can accept entries whose payload is missing.
                    val read = extractor.readSampleData(buffer, 0).toLong()
                    if (read < 0 || (size >= 0 && read != size)) return Probe(indexed, false)
                    previousTime = lastTime
                    lastTime = extractor.sampleTime
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(track)
                val sampleInterval = if (previousTime >= 0) (lastTime - previousTime).coerceIn(1, 1_000_000) else 100_000L
                if (lastTime < endTime - sampleInterval - 100_000) return Probe(indexed, false)
            }
            Probe(indexed, true)
        } catch (_: Exception) { Probe(indexed, false) }
        finally { extractor.release() }
    }
}
