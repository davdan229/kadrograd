package ru.kadrograd

import java.nio.ByteBuffer

/** Reads encoded dimensions from a bounded window of MP4 metadata. */
internal object Mp4Dimensions {
    data class Window(val variant: ZonaTracks.Variant?, val nextBox: Long?)
    private data class Box(val type: String, val payload: Int, val end: Long)
    private val videoSampleEntries = setOf("avc1", "avc3", "hvc1", "hev1", "av01", "vp08", "vp09", "mp4v")

    fun inspect(bytes: ByteArray): Window {
        val data = ByteBuffer.wrap(bytes)
        var nextBox: Long? = null
        for (box in boxes(data, 0, Long.MAX_VALUE)) {
            if (box.type == "moov") return Window(videoDimensions(data, box), null)
            nextBox = box.end
        }
        return Window(null, nextBox)
    }

    private fun videoDimensions(data: ByteBuffer, movie: Box): ZonaTracks.Variant? {
        for (track in boxes(data, movie.payload, movie.end).filter { it.type == "trak" }) {
            val media = child(data, track, "mdia") ?: continue
            val handler = child(data, media, "hdlr") ?: continue
            if (handler.payload + 12 > minOf(handler.end, data.limit().toLong()) ||
                type(data, handler.payload + 8) != "vide") continue
            val information = child(data, media, "minf") ?: continue
            val table = child(data, information, "stbl") ?: continue
            val descriptions = child(data, table, "stsd") ?: continue
            // A full-box header and entry count precede the visual sample entries.
            for (entry in boxes(data, descriptions.payload + 8, descriptions.end)) {
                if (entry.type !in videoSampleEntries ||
                    entry.payload + 28 > minOf(entry.end, data.limit().toLong())) continue
                val width = data.getShort(entry.payload + 24).toInt() and 0xffff
                val height = data.getShort(entry.payload + 26).toInt() and 0xffff
                if (width in 1..8192 && height in 1..8192) return ZonaTracks.Variant(width, height)
            }
        }
        return null
    }

    private fun child(data: ByteBuffer, parent: Box, name: String): Box? =
        boxes(data, parent.payload, parent.end).firstOrNull { it.type == name }

    private fun boxes(data: ByteBuffer, start: Int, end: Long): Sequence<Box> = sequence {
        val readableEnd = minOf(end, data.limit().toLong()).toInt()
        var offset = start
        while (offset <= readableEnd - 8) {
            val size32 = data.getInt(offset).toLong() and 0xffffffffL
            val headerSize = if (size32 == 1L) 16 else 8
            if (offset > readableEnd - headerSize) break
            val size = when (size32) {
                0L -> end - offset
                1L -> data.getLong(offset + 8)
                else -> size32
            }
            if (size < headerSize || size > end - offset) break
            val next = offset + size
            yield(Box(type(data, offset + 4), offset + headerSize, next))
            if (next > readableEnd) break
            offset = next.toInt()
        }
    }

    private fun type(data: ByteBuffer, offset: Int) =
        String(data.array(), offset, 4, Charsets.US_ASCII)
}
