package ru.kadrograd

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Full Blender Foundation releases, matched to Zona's exact catalog IDs. */
internal object BlenderUhd {
    private const val origin = "https://upload.wikimedia.org/wikipedia/commons"
    private const val prefixSize = 65_536
    private val widths = mapOf(1440 to 2560, 1080 to 1920, 720 to 1280, 480 to 854, 360 to 640, 240 to 426)
    private val verified = ConcurrentHashMap<String, Pair<Long, Video>>()
    val headers = mapOf("User-Agent" to "Kadrograd/1.1 (https://github.com/davdan229/kadrograd)")

    data class Film(val slug: String, val kpId: Int, val path: String, val seconds: Int)
    data class Video(val url: String, val variant: ZonaTracks.Variant, val quality: Int)
    val films = listOf(
        Film("bolshoi-bak", 420145, "c/c0/Big_Buck_Bunny_4K.webm", 635),
        Film("sintel", 566766, "f/f1/Sintel_movie_4K.webm", 888),
        Film("slezy-stali", 694072, "1/10/Tears_of_Steel_in_4k_-_Official_Blender_Foundation_release.webm", 734),
    )

    suspend fun uhd(film: Film): Video? = verify("$origin/${film.path}", 2160, film.seconds)

    suspend fun choices(film: Film): List<Video> = coroutineScope {
        val file = film.path.substringAfterLast('/')
        suspend fun transcode(quality: Int) =
            verify("$origin/transcoded/${film.path}/$file.${quality}p.vp9.webm", quality, film.seconds)
        val primary = listOf(async { uhd(film) }, async { transcode(1080) }, async { transcode(720) })
            .awaitAll()
        val smaller = listOf(1440, 480, 360, 240).chunked(2).flatMap { batch ->
            batch.map { quality -> async { transcode(quality) } }.awaitAll()
        }
        (primary + smaller).filterNotNull().sortedByDescending { it.quality }
    }

    private suspend fun verify(url: String, quality: Int, duration: Int): Video? {
        verified[url]?.let { (seen, video) ->
            if (System.nanoTime() - seen in 0..TimeUnit.MINUTES.toNanos(5)) return video
        }
        return try {
            SourceHttp.readEndpoint(url, headers + ("Range" to "bytes=0-${prefixSize - 1}"), 4) { response ->
                val bytes = response.body.source()
                if (response.code != 206 || response.header("Content-Range")?.startsWith("bytes 0-${prefixSize - 1}/") != true ||
                    response.header("Content-Type")?.substringBefore(';') != "video/webm") {
                    throw IOException("Expected a WebM byte range")
                }
                val variant = WebmPrefix(bytes.readByteArray(prefixSize.toLong())).video(duration)
                    ?: throw IOException("Expected full film with supported audio and video tracks")
                val correctSize = if (quality == 2160) variant.isFourK else variant.width == widths[quality]
                if (!correctSize) throw IOException("Unexpected WebM resolution")
                Video(url, variant, quality)
            }.also { verified[url] = System.nanoTime() to it }
        } catch (_: IOException) { null }
    }

    private class WebmPrefix(private val data: ByteArray) {
        private data class Element(val id: Int, val start: Int, val end: Int)

        private fun number(offset: Int, limit: Int, isId: Boolean): Pair<Long, Int>? {
            if (offset >= limit) return null
            val first = data[offset].toInt() and 255
            if (first == 0) return null
            val length = Integer.numberOfLeadingZeros(first) - 23
            if (offset + length > limit || (isId && length > 4)) return null
            var result = (if (isId) first else first and (255 shr length)).toLong()
            for (index in offset + 1 until offset + length) result = (result shl 8) or (data[index].toLong() and 255)
            return result to (offset + length)
        }

        private fun children(start: Int, end: Int): Sequence<Element> = sequence {
            var cursor = start
            while (cursor < end) {
                val (id, sizeOffset) = number(cursor, end, true) ?: break
                val (size, body) = number(sizeOffset, end, false) ?: break
                val stop = (body + size).coerceAtMost(end.toLong()).toInt()
                yield(Element(id.toInt(), body, stop))
                cursor = stop
            }
        }

        private fun Element.children() = children(start, end)
        private fun Element.child(id: Int) = children().firstOrNull { it.id == id }
        private fun Element.unsigned(): Long? {
            if (end - start !in 1..8) return null
            var value = 0L
            for (index in start until end) value = (value shl 8) or (data[index].toLong() and 255)
            return value
        }

        private fun Element.decimal(): Double? {
            val bits = unsigned() ?: return null
            return when (end - start) {
                4 -> Float.fromBits(bits.toInt()).toDouble()
                8 -> Double.fromBits(bits)
                else -> null
            }
        }

        private fun Element.codec(): String? = child(0x86)?.let {
            String(data, it.start, it.end - it.start, Charsets.US_ASCII)
        }

        fun video(expectedSeconds: Int): ZonaTracks.Variant? {
            val root = children(0, data.size).iterator()
            if (!root.hasNext() || root.next().id != 0x1a45dfa3 || !root.hasNext()) return null
            val segment = root.next().takeIf { it.id == 0x18538067 } ?: return null
            val info = segment.child(0x1549a966) ?: return null
            val timeScale = info.child(0x2ad7b1)?.unsigned() ?: 1_000_000L
            val duration = (info.child(0x4489)?.decimal() ?: return null) * timeScale / 1_000_000_000
            if (duration !in expectedSeconds - 5.0..expectedSeconds + 5.0) return null
            val cluster = segment.child(0x1f43b675) ?: return null
            if (cluster.end - cluster.start < 32) return null
            val tracks = segment.child(0x1654ae6b)?.children()?.filter { it.id == 0xae }?.toList() ?: return null
            if (tracks.none { it.child(0x83)?.unsigned() == 2L && it.codec() in setOf("A_VORBIS", "A_OPUS") }) return null
            val video = tracks.firstOrNull { it.child(0x83)?.unsigned() == 1L && it.codec() in setOf("V_VP8", "V_VP9") }
                ?.child(0xe0) ?: return null
            val width = video.child(0xb0)?.unsigned()?.takeIf { it in 1..8192 }?.toInt() ?: return null
            val height = video.child(0xba)?.unsigned()?.takeIf { it in 1..8192 }?.toInt() ?: return null
            return ZonaTracks.Variant(width, height)
        }
    }
}
