package ru.kadrograd

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import java.security.SecureRandom

/** Public web-player request/response envelope; no account credentials are used. */
@OptIn(ExperimentalEncodingApi::class)
internal object ZonaTransport {
    private val random = SecureRandom()

    fun encode(value: String, key: Int = 17, noise: ByteArray = ByteArray(12).also(random::nextBytes)): String {
        require(noise.size in 8..15)
        val input = value.toByteArray(Charsets.UTF_8)
        val result = ByteArray(1 + noise.size + input.size)
        result[0] = noise.size.toByte()
        noise.copyInto(result, 1)
        input.forEachIndexed { i, b ->
            result[1 + noise.size + i] = ((b.toInt() and 255) + (noise[i % noise.size].toInt() and 255) + i + key % 256 + i * (key % 7)).toByte()
        }
        return Base64.encode(result)
    }

    fun decode(value: String, key: Int = 31): String {
        require(value.length in 4..4_000_000)
        val raw = Base64.decode(value.trim())
        val count = raw.firstOrNull()?.toInt()?.and(255) ?: error("Empty response")
        require(count in 8..15 && raw.size > count + 1)
        return ByteArray(raw.size - count - 1) { i ->
            ((raw[count + 1 + i].toInt() and 255) - (raw[1 + i % count].toInt() and 255) - i - key % 256 - i * (key % 7)).toByte()
        }.toString(Charsets.UTF_8)
    }

    fun clientTime(now: Long, userAgent: String): String {
        val seconds = now / 1_000
        val hash = (seconds.toString() + userAgent + "qARLl3").hashCode().toLong()
        val stamp = seconds * 1_000 + kotlin.math.abs(hash) % 1_000
        return "$stamp.${10 xor (999 - stamp % 1_000).toInt()}"
    }
}
