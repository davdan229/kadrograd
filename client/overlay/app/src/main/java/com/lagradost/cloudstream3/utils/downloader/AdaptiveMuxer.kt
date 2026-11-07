package com.lagradost.cloudstream3.utils.downloader

import android.media.MediaCodec
import android.media.MediaMuxer
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.InAppMp4Muxer
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/** Selects the container from the actual track formats, before the first sample. */
internal class AdaptiveMuxer(private val path: String, private val container: (String) -> Unit) : Muxer {
    class Factory(private val container: (String) -> Unit) : Muxer.Factory {
        override fun create(path: String): Muxer = AdaptiveMuxer(path, container)
        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            when (trackType) {
                C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1,
                    MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION)
                C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS, MimeTypes.AUDIO_AAC,
                    MimeTypes.AUDIO_AMR_NB, MimeTypes.AUDIO_AMR_WB)
                else -> ImmutableList.of()
            }
    }

    private val formats = mutableListOf<Format>()
    private val metadata = mutableListOf<Metadata.Entry>()
    private var mp4: Muxer? = null
    private var webm: MediaMuxer? = null
    private var tracks = emptyList<Int>()
    private var started = false
    private var closed = false

    override fun addTrack(format: Format): Int {
        check(!started && !closed)
        formats.add(format)
        return formats.lastIndex
    }

    private fun start() {
        check(!closed && formats.isNotEmpty())
        val useWebm = formats.any { it.sampleMimeType in setOf(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9) }
        if (useWebm) {
            check(Build.VERSION.SDK_INT >= 29) { "Saving VP9 with Opus requires Android 10 or newer" }
            require(formats.all { it.sampleMimeType in setOf(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9,
                MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS) }) { "Unsupported WebM track combination" }
            val writer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
            webm = writer
            tracks = formats.map { writer.addTrack(MediaFormatUtil.createMediaFormatFromFormat(it)) }
            writer.start()
            container("webm")
        } else {
            val writer = InAppMp4Muxer.Factory().create(path)
            mp4 = writer
            tracks = formats.map(writer::addTrack)
            metadata.forEach(writer::addMetadataEntry)
            container("mp4")
        }
        started = true
    }

    override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
        if (!started) start()
        mp4?.let { it.writeSampleData(tracks[trackId], byteBuffer, bufferInfo); return }
        val info = MediaCodec.BufferInfo().apply {
            set(byteBuffer.position(), bufferInfo.size, bufferInfo.presentationTimeUs,
                (if (bufferInfo.flags and C.BUFFER_FLAG_KEY_FRAME != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) or
                    (if (bufferInfo.flags and C.BUFFER_FLAG_END_OF_STREAM != 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0))
        }
        webm!!.writeSampleData(tracks[trackId], byteBuffer, info)
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
        mp4?.addMetadataEntry(metadataEntry) ?: metadata.add(metadataEntry)
    }

    override fun close() {
        if (closed) return
        closed = true
        try { mp4?.close() }
        finally {
            val writer = webm
            if (writer != null) {
                try { if (started) writer.stop() }
                finally { writer.release() }
            }
        }
    }
}
