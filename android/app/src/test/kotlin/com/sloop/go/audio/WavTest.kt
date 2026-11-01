package com.sloop.go.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavTest {
    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun headerDescribesThePcmFormat() {
        val h = le(Wav.header(48000, 2, 24, 600))
        assertEquals(Wav.HEADER, h.capacity())
        assertEquals(36 + 600, h.getInt(4))
        assertEquals(1, h.getShort(20).toInt())
        assertEquals(2, h.getShort(22).toInt())
        assertEquals(48000, h.getInt(24))
        assertEquals(48000 * 6, h.getInt(28))
        assertEquals(6, h.getShort(32).toInt())
        assertEquals(24, h.getShort(34).toInt())
        assertEquals(600, h.getInt(40))
    }

    @Test fun floatHeaderAndTheUnknownLengthPlaceholder() {
        val h = le(Wav.header(44100, 1, 32, Wav.MAX_DATA))
        assertEquals(3, h.getShort(20).toInt())
        assertEquals(0xFFFFFFFFL, h.getInt(4).toLong() and 0xFFFFFFFFL)
        assertEquals(Wav.MAX_DATA, h.getInt(40).toLong() and 0xFFFFFFFFL)
    }

    @Test fun sixteenBitSourceRoundTripsExactly() {
        val ints = intArrayOf(-32768, -1, 0, 1, 12345, 32767)
        val src = FloatArray(ints.size * 2) { ints[it / 2] / 32768f }
        val out = ByteArray(ints.size * 4)
        assertEquals(out.size, Wav.encode(src, ints.size, 2, 16, out))
        val b = le(out)
        for (i in ints.indices) {
            assertEquals(ints[i], b.getShort(i * 4).toInt())
            assertEquals(ints[i], b.getShort(i * 4 + 2).toInt())
        }
    }

    @Test fun twentyFourBitKeepsTheSixteenBitValueShifted() {
        val src = floatArrayOf(12345 / 32768f, -1f)
        val out = ByteArray(3)
        assertEquals(3, Wav.encode(src, 1, 1, 24, out))   // mono: (L + R) / 2
        val v = (out[0].toInt() and 0xFF) or ((out[1].toInt() and 0xFF) shl 8) or (out[2].toInt() shl 16)
        assertEquals(Math.round((12345 / 32768.0 - 1.0) / 2 * 8388608.0).toInt(), v)
    }

    @Test fun clipsAndWritesFloatsRaw() {
        val out16 = ByteArray(4)
        Wav.encode(floatArrayOf(1.5f, -1.5f), 1, 2, 16, out16)
        assertEquals(32767, le(out16).getShort(0).toInt())
        assertEquals(-32768, le(out16).getShort(2).toInt())
        val outF = ByteArray(8)
        assertEquals(8, Wav.encode(floatArrayOf(1.5f, -0.25f), 1, 2, 32, outF))
        assertEquals(1.5f, le(outF).getFloat(0), 0f)
        assertEquals(-0.25f, le(outF).getFloat(4), 0f)
    }
}
