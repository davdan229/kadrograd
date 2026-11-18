package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs

/** Checks the timestamp span of a legacy concatenated HLS transport stream. */
internal object TransportStreamExtent {
    private const val PACKET_SIZE = 188
    private const val SCAN_PACKETS = 16_384
    private const val TIMESTAMP_WRAP = 1L shl 33

    fun matches(context: Context, uri: Uri, length: Long, expectedUs: Long): Boolean {
        if (length <= 0 || length % PACKET_SIZE != 0L || expectedUs !in 1L until (TIMESTAMP_WRAP * 100 / 9)) return false
        return try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val channel = input.channel
                val bytes = ByteArray(minOf(length, SCAN_PACKETS.toLong() * PACKET_SIZE).toInt())
                val first = readTimestamps(input, bytes, last = false)
                channel.position(length - bytes.size)
                val last = readTimestamps(input, bytes, last = true)
                val expected = expectedUs * 9 / 100
                // PTS marks the start of a sample. Allow for the final frame and playlist rounding.
                listOf(true, false).all { video ->
                    first.any { (pid, head) ->
                        val tail = last[pid]
                        if (head.video != video || tail == null || tail.video != video) false
                        else {
                            val span = (tail.pts - head.pts + TIMESTAMP_WRAP) % TIMESTAMP_WRAP
                            abs(span - expected) <= 45_000L
                        }
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private data class Timestamp(val pts: Long, val video: Boolean)

    private fun readTimestamps(input: InputStream, bytes: ByteArray, last: Boolean): Map<Int, Timestamp> {
        DataInputStream(input).readFully(bytes)
        val tracks = mutableMapOf<Int, Timestamp>()
        fun byte(index: Int) = bytes[index].toInt() and 0xff
        for (offset in bytes.indices step PACKET_SIZE) {
            if (byte(offset) != 0x47) return emptyMap()
            val flags = byte(offset + 1)
            if ((flags and 0xc0) != 0x40) continue
            val pid = ((flags and 0x1f) shl 8) or byte(offset + 2)
            val adaptation = byte(offset + 3) and 0x30
            if ((adaptation and 0x10) == 0) continue
            var start = offset + 4
            if ((adaptation and 0x20) != 0) start += byte(start) + 1
            if (start + 14 > offset + PACKET_SIZE) continue
            if (byte(start) != 0 || byte(start + 1) != 0 || byte(start + 2) != 1) continue
            val stream = byte(start + 3)
            if (stream !in 0xc0..0xef || (byte(start + 7) and 0x80) == 0 || byte(start + 8) < 5) continue
            val p = start + 9
            val a = byte(p)
            val b = byte(p + 1)
            val c = byte(p + 2)
            val d = byte(p + 3)
            val e = byte(p + 4)
            if ((a and c and e and 1) == 0) continue
            val pts = ((a and 14).toLong() shl 29) or (b.toLong() shl 22) or
                ((c and 254).toLong() shl 14) or (d.toLong() shl 7) or (e.toLong() shr 1)
            if (last || pid !in tracks) tracks[pid] = Timestamp(pts, stream >= 0xe0)
        }
        return tracks
    }
}
