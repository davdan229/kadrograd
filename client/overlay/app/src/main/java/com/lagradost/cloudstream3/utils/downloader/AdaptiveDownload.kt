package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.media.metrics.LogSessionId
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.HttpDataSource
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.CreateNotificationMetadata
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadStatus
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadMetaData
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Copies clear adaptive streams, preserving compressed audio and video samples. */
object AdaptiveDownload {
    private val activeFiles = ConcurrentHashMap<Int, DownloadObjects.DownloadedFileInfoResult>()
    private data class Progress(val percentage: Int, val estimate: Long)
    private val activeProgress = ConcurrentHashMap<Int, Progress>()

    internal fun progressPercentage(id: Int?): Int? = id?.let { activeProgress[it]?.percentage }
    internal fun progressText(context: Context, id: Int?, downloadedBytes: Long): String? {
        val progress = id?.let { activeProgress[it] } ?: return null
        val received = android.text.format.Formatter.formatShortFileSize(context, downloadedBytes)
        val amount = if (progress.estimate > 0) "$received / ≈${android.text.format.Formatter.formatShortFileSize(context, progress.estimate)}" else received
        return "${progress.percentage}% · $amount"
    }

    internal fun activeFileInfo(id: Int): DownloadObjects.DownloadedFileInfoResult? = activeFiles[id]
    @JvmStatic fun isSupported(): Boolean = true

    private class TransferGate {
        private val monitor = Object()
        @Volatile var paused = false
            private set
        private var cancelled = false

        fun pause(value: Boolean) = synchronized(monitor) {
            paused = value
            if (!value) monitor.notifyAll()
        }

        fun cancel() = synchronized(monitor) {
            cancelled = true
            monitor.notifyAll()
        }

        fun awaitReady() = synchronized(monitor) {
            try {
                while (paused && !cancelled) monitor.wait()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Adaptive download interrupted").apply { initCause(interrupted) }
            }
            if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Adaptive download cancelled")
        }

        fun retryDelay(milliseconds: Long) = synchronized(monitor) {
            val deadline = SystemClock.elapsedRealtime() + milliseconds
            while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
                try { monitor.wait((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) }
                catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Adaptive download interrupted").apply { initCause(interrupted) }
                }
            }
            awaitReady()
        }
    }

    private class PausableDataSource(private val upstream: DataSource, private val gate: TransferGate) : DataSource by upstream {
        private var spec: DataSpec? = null
        private var received = 0L
        private var expected = C.LENGTH_UNSET.toLong()
        private var attempts = 0

        private fun retry(error: IOException) {
            gate.awaitReady()
            if (attempts >= 3 || Thread.currentThread().isInterrupted ||
                (error is InterruptedIOException && error !is SocketTimeoutException) ||
                (error is HttpDataSource.InvalidResponseCodeException &&
                    error.responseCode !in setOf(408, 429) && error.responseCode !in 500..599)) throw error
            attempts++
            runCatching { upstream.close() }
            val retryAfter = (error as? HttpDataSource.InvalidResponseCodeException)?.headerFields?.entries
                ?.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()?.toLongOrNull()
            gate.retryDelay(retryAfter?.coerceIn(1, 30)?.times(1_000) ?: (250L shl (attempts - 1)))
        }

        private fun connect(): Long {
            while (true) {
                gate.awaitReady()
                try { return upstream.open(requireNotNull(spec).subrange(received)) }
                catch (error: IOException) { retry(error) }
            }
        }

        override fun open(dataSpec: DataSpec): Long {
            spec = dataSpec
            received = 0
            attempts = 0
            expected = connect()
            return expected
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (true) {
                gate.awaitReady()
                if (expected != C.LENGTH_UNSET.toLong() && received >= expected) return C.RESULT_END_OF_INPUT
                try {
                    val count = upstream.read(buffer, offset, length)
                    if (count == C.RESULT_END_OF_INPUT && expected != C.LENGTH_UNSET.toLong() && received < expected) {
                        throw java.io.EOFException("Media response ended after $received of $expected bytes")
                    }
                    if (count > 0) received += count
                    return count
                } catch (error: IOException) {
                    val path = spec?.uri?.path.orEmpty()
                    // Manifests may change between requests. Resume only the immutable media data.
                    if (path.endsWith(".mpd") || path.endsWith(".m3u8")) throw error
                    retry(error)
                    connect()
                }
            }
        }
    }

    // Downloads must never silently re-encode or reduce the selected resolution.
    private val copyOnlyEncoder = object : Codec.EncoderFactory {
        override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
            throw IllegalStateException("Audio requires transcoding: ${format.sampleMimeType}")
        override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec =
            throw IllegalStateException("Video requires transcoding: ${format.sampleMimeType}")
    }

    internal suspend fun exportToFile(
        context: Context,
        link: ExtractorLink,
        output: File,
        interceptor: Interceptor?,
        keepRunning: () -> Boolean = { true },
        isPaused: () -> Boolean = { false },
        progress: (Int, Long, Long) -> Unit = { _, _, _ -> },
        container: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        val estimate = AdaptiveSizeEstimate()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.isSuccessful && response.request.url.encodedPath.let { it.endsWith(".mpd") || it.endsWith(".m3u8") }) {
                    runCatching { estimate.observe(response.request.url.toString(), response.peekBody(1_048_576).string()) }
                }
                response
            }
            .apply { interceptor?.let(::addInterceptor) }
            .build()
        val headers = buildMap {
            put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            if (link.referer.isNotBlank()) put("Referer", link.referer)
            putAll(link.headers)
        }
        val upstream = DefaultDataSource.Factory(context,
            OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers))
        val gate = TransferGate().apply { pause(isPaused()) }
        val transferred = AtomicLong()
        val transferListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) transferred.addAndGet(bytesTransferred.toLong())
            }
        }
        val dataSource = DataSource.Factory {
            PausableDataSource(upstream.createDataSource(), gate).apply { addTransferListener(transferListener) }
        }
        val mediaSource = DefaultMediaSourceFactory(dataSource)
        val loader = ExoPlayerAssetLoader.Factory(context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT, mediaSource)
        val finished = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .setAssetLoaderFactory(loader)
            .setMuxerFactory(AdaptiveMuxer.Factory(container))
            .setEncoderFactory(copyOnlyEncoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (exportResult.videoFrameCount <= 0 || exportResult.audioMimeType == null) {
                        finished.completeExceptionally(IOException("Downloaded stream has no video or audio"))
                    } else finished.complete(Unit)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finished.completeExceptionally(exportException)
                }
            }).build()
        try {
            val item = MediaItem.Builder().setUri(link.url)
                .setMimeType(if (link.type == ExtractorLinkType.DASH) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)
                .build()
            transformer.start(item, output.absolutePath)
            val holder = ProgressHolder()
            var lastProgress = -1
            var lastReport = 0L
            while (!finished.isCompleted) {
                coroutineContext.ensureActive()
                if (!keepRunning()) return@withContext false
                gate.pause(isPaused())
                if (!gate.paused) {
                    val available = transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                    val changed = available && holder.progress != lastProgress
                    if (changed) lastProgress = holder.progress
                    val now = SystemClock.elapsedRealtime()
                    if (changed || now - lastReport >= 1_000) {
                        lastReport = now
                        progress(lastProgress.coerceIn(0, 99), transferred.get(), estimate.bytes)
                    }
                }
                delay(200)
            }
            finished.await()
            require(output.length() > 0) { "Empty downloaded file" }
            true
        } finally {
            gate.cancel()
            try { transformer.cancel() }
            finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    suspend fun download(
        context: Context, link: ExtractorLink, apiName: String?, name: String, folder: String,
        id: Int, callback: (CreateNotificationMetadata) -> Unit,
    ): DownloadStatus = withContext(Dispatchers.Main.immediate) {
        val directory = File(context.filesDir, "adaptive-downloads").apply { mkdirs() }
        val output = File.createTempFile("$id-", ".mp4", directory)
        val metadata = DownloadMetaData(id = id, linkHash = link.url.hashCode(), createNotificationCallback = callback, isHLS = true)
        val temporaryUri = Uri.fromFile(output)
        var destination: DownloadObjects.StreamData? = null
        var complete = false
        var extension = "mp4"
        try {
            metadata.hlsTotal = null
            metadata.totalBytes = 0
            // A running export must not replace the record for a previously saved copy.
            activeFiles[id] = DownloadObjects.DownloadedFileInfoResult(0, 0, temporaryUri)
            activeProgress[id] = Progress(0, 0)
            metadata.type = DownloadType.IsDownloading
            val interceptor = getApiFromNameNull(apiName)?.getVideoInterceptor(link)
                ?: getApiFromNameNull(link.source)?.getVideoInterceptor(link)
            output.delete()
            metadata.setResumeLength(0)
            metadata.hlsProgress = 0
            val exported = exportToFile(context, link, output, interceptor,
                keepRunning = { metadata.type != DownloadType.IsStopped },
                isPaused = { metadata.type == DownloadType.IsPaused },
                progress = { value, bytes, estimate ->
                    metadata.bytesDownloaded = bytes
                    metadata.bytesWritten = output.length()
                    metadata.hlsProgress = if (value > 0) (value * 98 / 100).coerceAtLeast(1) else 0
                    metadata.hlsTotal = if (metadata.hlsProgress > 0) 100 else null
                    metadata.hlsWrittenProgress = metadata.hlsProgress
                    // The estimate stays fixed; a timestamp percentage is not a byte-size measurement.
                    metadata.totalBytes = estimate
                    val percentage = maxOf(activeProgress[id]?.percentage ?: 0, metadata.hlsProgress)
                    activeProgress[id] = Progress(percentage, estimate)
                    activeFiles[id] = DownloadObjects.DownloadedFileInfoResult(bytes, metadata.approxTotalBytes, temporaryUri)
                    metadata.type = metadata.type
                }, container = { extension = it })
            if (!exported) return@withContext DownloadStatus(false, false, true)
            val (baseFile, basePath) = context.getBasePath()
            requireNotNull(baseFile) { "Download folder is unavailable" }
            val length = output.length()
            metadata.totalBytes = length
            metadata.hlsTotal = 100
            metadata.hlsProgress = 99
            activeProgress[id] = Progress(99, length)
            // A unique destination keeps any existing completed download intact until this one succeeds.
            val uniqueName = "$name [$id-${System.currentTimeMillis()}]"
            val stream = withContext(Dispatchers.IO) {
                VideoDownloadManager.setupStream(baseFile, uniqueName, folder, extension, false)
            }
            destination = stream
            var stopped = false
            withContext(Dispatchers.IO) {
                output.inputStream().use { input ->
                    stream.open().use { target ->
                        val buffer = ByteArray(1 shl 20)
                        while (true) {
                            coroutineContext.ensureActive()
                            val state = withContext(Dispatchers.Main.immediate) { metadata.type }
                            if (state == DownloadType.IsStopped) { stopped = true; break }
                            if (state == DownloadType.IsPaused) { delay(200); continue }
                            val count = input.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                        }
                    }
                }
            }
            coroutineContext.ensureActive()
            if (stopped || metadata.type == DownloadType.IsStopped) return@withContext DownloadStatus(false, false, true)
            metadata.bytesDownloaded = length
            metadata.bytesWritten = length
            metadata.hlsProgress = 100
            metadata.hlsWrittenProgress = 100
            metadata.setDownloadFileInfoTemplate(DownloadedFileInfo(length, folder, "$uniqueName.$extension", basePath = basePath))
            activeFiles.remove(id)
            activeProgress.remove(id)
            complete = true
            metadata.type = DownloadType.IsDone
            DownloadStatus(false, false, true)
        } catch (cancelled: CancellationException) {
            metadata.type = DownloadType.IsStopped
            throw cancelled
        } catch (error: Exception) {
            logError(error)
            metadata.type = DownloadType.IsFailed
            // Starting the entire export again discards the received movie. Transient segment
            // failures are retried in place by PausableDataSource instead.
            DownloadStatus(false, false, false)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try {
                    if (!complete) destination?.delete()
                    output.delete()
                } finally {
                    activeFiles.remove(id)
                    activeProgress.remove(id)
                    metadata.close()
                }
            }
        }
    }
}
