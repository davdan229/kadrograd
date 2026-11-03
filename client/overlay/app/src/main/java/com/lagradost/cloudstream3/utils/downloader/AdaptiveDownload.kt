package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.media.metrics.LogSessionId
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Copies clear adaptive streams to MP4, preserving compressed audio and video samples. */
object AdaptiveDownload {
    @JvmStatic fun isSupported(): Boolean = true

    // Downloads must never silently re-encode or reduce the selected resolution.
    private val copyOnlyEncoder = object : Codec.EncoderFactory {
        override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
            throw IllegalStateException("Audio cannot be copied to MP4: ${format.sampleMimeType}")
        override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec =
            throw IllegalStateException("Video cannot be copied to MP4: ${format.sampleMimeType}")
    }

    internal suspend fun exportToFile(
        context: Context,
        link: ExtractorLink,
        output: File,
        interceptor: Interceptor?,
        keepRunning: () -> Boolean = { true },
        progress: (Int) -> Unit = {},
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply { interceptor?.let(::addInterceptor) }
            .build()
        val headers = buildMap {
            put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            if (link.referer.isNotBlank()) put("Referer", link.referer)
            putAll(link.headers)
        }
        val dataSource = DefaultDataSource.Factory(context,
            OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers))
        val mediaSource = DefaultMediaSourceFactory(dataSource)
        val loader = ExoPlayerAssetLoader.Factory(context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT, mediaSource)
        val finished = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .setAssetLoaderFactory(loader)
            .setMuxerFactory(InAppMp4Muxer.Factory())
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
            while (!finished.isCompleted) {
                coroutineContext.ensureActive()
                if (!keepRunning()) return@withContext false
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE && holder.progress != lastProgress) {
                    lastProgress = holder.progress
                    progress(holder.progress.coerceIn(0, 99))
                }
                delay(200)
            }
            finished.await()
            require(output.length() > 0) { "Empty downloaded file" }
            true
        } finally {
            transformer.cancel()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    suspend fun download(
        context: Context, link: ExtractorLink, apiName: String?, name: String, folder: String,
        id: Int, callback: (CreateNotificationMetadata) -> Unit,
    ): DownloadStatus = withContext(Dispatchers.Main.immediate) {
        val metadata = DownloadMetaData(id = id, linkHash = link.url.hashCode(), createNotificationCallback = callback, isHLS = true)
        val directory = File(context.filesDir, "adaptive-downloads").apply { mkdirs() }
        val output = File.createTempFile("$id-", ".mp4", directory)
        var destination: DownloadObjects.StreamData? = null
        var complete = false
        try {
            metadata.hlsTotal = 100
            metadata.type = DownloadType.IsDownloading
            val interceptor = getApiFromNameNull(apiName)?.getVideoInterceptor(link)
                ?: getApiFromNameNull(link.source)?.getVideoInterceptor(link)
            while (true) {
                while (metadata.type == DownloadType.IsPaused) delay(200)
                if (metadata.type == DownloadType.IsStopped) return@withContext DownloadStatus(false, false, true)
                output.delete()
                metadata.setResumeLength(0)
                metadata.hlsProgress = 0
                val exported = exportToFile(context, link, output, interceptor,
                    keepRunning = { metadata.type == DownloadType.IsDownloading },
                    progress = { value ->
                        metadata.bytesDownloaded = output.length()
                        metadata.hlsProgress = value * 98 / 100
                        metadata.type = metadata.type
                    })
                if (exported) break
            }
            val (baseFile, basePath) = context.getBasePath()
            requireNotNull(baseFile) { "Download folder is unavailable" }
            val length = output.length()
            metadata.totalBytes = length
            metadata.hlsProgress = 99
            // A unique destination keeps any existing completed download intact until this one succeeds.
            val uniqueName = "$name [$id-${System.currentTimeMillis()}]"
            val stream = withContext(Dispatchers.IO) {
                VideoDownloadManager.setupStream(baseFile, uniqueName, folder, "mp4", false)
            }
            destination = stream
            withContext(Dispatchers.IO) {
                output.inputStream().use { input ->
                    stream.open().use { target ->
                        val buffer = ByteArray(1 shl 20)
                        while (true) {
                            coroutineContext.ensureActive()
                            val state = withContext(Dispatchers.Main.immediate) { metadata.type }
                            if (state == DownloadType.IsStopped) throw CancellationException("Download stopped")
                            if (state == DownloadType.IsPaused) { delay(200); continue }
                            val count = input.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                        }
                    }
                }
            }
            metadata.bytesDownloaded = length
            metadata.bytesWritten = length
            metadata.hlsProgress = 100
            metadata.hlsWrittenProgress = 100
            metadata.setDownloadFileInfoTemplate(DownloadedFileInfo(length, folder, "$uniqueName.mp4", basePath = basePath))
            complete = true
            metadata.type = DownloadType.IsDone
            DownloadStatus(false, false, true)
        } catch (cancelled: CancellationException) {
            metadata.type = DownloadType.IsStopped
            throw cancelled
        } catch (error: Exception) {
            logError(error)
            metadata.type = DownloadType.IsFailed
            DownloadStatus(true, true, false)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                if (!complete) destination?.delete()
                output.delete()
            }
            metadata.close()
        }
    }
}
