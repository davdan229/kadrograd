package ru.kadrograd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class Mp4DimensionsTest {
    @Test fun reportsEncodedDimensionsFromTheVideoTrack() {
        val prefix = atom("ftyp", "isom".toByteArray())
        val movie = atom("moov", track("soun", 1920, 1080) + track("vide", 640, 282))

        assertEquals(ZonaTracks.Variant(640, 282), Mp4Dimensions.inspect(prefix + movie).variant)
        assertNull(Mp4Dimensions.inspect(prefix + movie.copyOf(movie.size - 2)).variant)
    }

    @Test fun skipsADataBoxWithoutReadingItsPayload() {
        val prefix = atom("ftyp", "isom".toByteArray())
        val dataHeader = ByteBuffer.allocate(16).putInt(1).put("mdat".toByteArray()).putLong(900_000_000L).array()
        val head = Mp4Dimensions.inspect(prefix + dataHeader)
        assertNull(head.variant)
        assertEquals(900_000_000L + prefix.size, head.nextBox)

        val tail = Mp4Dimensions.inspect(atom("moov", track("vide", 1280, 720)))
        assertEquals(ZonaTracks.Variant(1280, 720), tail.variant)
    }

    private fun track(handler: String, width: Int, height: Int): ByteArray {
        val sample = ByteBuffer.allocate(28).putShort(24, width.toShort()).putShort(26, height.toShort()).array()
        val table = atom("stsd", ByteBuffer.allocate(8).putInt(0).putInt(1).array() + atom("avc1", sample))
        return atom("trak", atom("mdia", atom("hdlr", ByteArray(8) + handler.toByteArray()) +
            atom("minf", atom("stbl", table))))
    }

    private fun atom(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
}
