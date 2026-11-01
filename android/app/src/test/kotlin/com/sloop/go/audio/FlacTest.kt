package com.sloop.go.audio

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Encodes with [FlacEncoder] and decodes with an independent reader written from the FLAC format spec. */
class FlacTest {
    private class Decoded(val rate: Int, val channels: Int, val bits: Int, val total: Long, val md5: ByteArray,
                          val pcm: Array<IntArray>)

    private class Bits(val b: ByteArray, var bit: Long) {
        fun u(n: Int): Long {
            var v = 0L
            repeat(n) {
                val byte = b[(bit ushr 3).toInt()].toInt()
                v = (v shl 1) or ((byte shr (7 - (bit and 7).toInt())) and 1).toLong()
                bit++
            }
            return v
        }
        fun s(n: Int): Long { val v = u(n); return if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v }
        fun unary(): Long { var q = 0L; while (u(1) == 0L) q++; return q }
        fun align() { bit = (bit + 7) and 7L.inv() }
        val byte: Int get() = (bit ushr 3).toInt()
    }

    private fun decode(file: ByteArray): Decoded {
        assertEquals("fLaC", String(file, 0, 4))
        val r = Bits(file, 32)
        assertEquals(1L, r.u(1)); assertEquals(0L, r.u(7)); assertEquals(34L, r.u(24))
        r.u(16); r.u(16); r.u(24); r.u(24)
        val rate = r.u(20).toInt()
        val ch = r.u(3).toInt() + 1
        val bits = r.u(5).toInt() + 1
        val total = r.u(36)
        val md5 = ByteArray(16) { r.u(8).toByte() }
        val out = Array(ch) { ArrayList<Int>() }
        while (r.byte < file.size) {
            val start = r.byte
            assertEquals(0x3FFEL, r.u(14)); assertEquals(0L, r.u(1)); assertEquals(0L, r.u(1))
            val bsCode = r.u(4).toInt()
            r.u(4)
            val assign = r.u(4).toInt()
            assertEquals(if (bits == 16) 4L else 6L, r.u(3)); r.u(1)
            val first = r.u(8).toInt()
            var extra = 0
            while (first and (0x80 shr extra) != 0) extra++
            repeat(maxOf(0, extra - 1)) { assertEquals(2L, r.u(2)); r.u(6) }
            val n = when (bsCode) { 12 -> 4096; 7 -> r.u(16).toInt() + 1; else -> error("block size code $bsCode") }
            assertEquals(FlacEncoder.crc8(file.copyOfRange(start, r.byte), r.byte - start).toLong(), r.u(8))
            val sub = Array(if (assign == 0) 1 else 2) { c ->
                val side = (assign == 8 && c == 1) || (assign == 9 && c == 0) || (assign == 10 && c == 1)
                subframe(r, n, bits + if (side) 1 else 0)
            }
            r.align()
            assertEquals(FlacEncoder.crc16(file.copyOfRange(start, r.byte), r.byte - start).toLong(), r.u(16))
            for (i in 0 until n) {
                when (assign) {
                    0 -> out[0].add(sub[0][i])
                    1 -> { out[0].add(sub[0][i]); out[1].add(sub[1][i]) }
                    8 -> { out[0].add(sub[0][i]); out[1].add(sub[0][i] - sub[1][i]) }
                    9 -> { out[0].add(sub[1][i] + sub[0][i]); out[1].add(sub[1][i]) }
                    10 -> {
                        val m = (sub[0][i] shl 1) or (sub[1][i] and 1)
                        out[0].add((m + sub[1][i]) shr 1); out[1].add((m - sub[1][i]) shr 1)
                    }
                    else -> error("channel assignment $assign")
                }
            }
        }
        return Decoded(rate, ch, bits, total, md5, Array(ch) { out[it].toIntArray() })
    }

    private fun subframe(r: Bits, n: Int, bps: Int): IntArray {
        assertEquals(0L, r.u(1))
        val type = r.u(6).toInt()
        assertEquals(0L, r.u(1))
        val x = IntArray(n)
        when {
            type == 0 -> { val v = r.s(bps).toInt(); x.fill(v) }
            type == 1 -> for (i in 0 until n) x[i] = r.s(bps).toInt()
            type in 8..12 -> {
                val order = type - 8
                for (i in 0 until order) x[i] = r.s(bps).toInt()
                val method = r.u(2).toInt()
                val pw = if (method == 1) 5 else 4
                val p = r.u(4).toInt()
                var i = order
                for (j in 0 until (1 shl p)) {
                    val k = r.u(pw).toInt()
                    repeat((n shr p) - if (j == 0) order else 0) {
                        val u = (r.unary() shl k) or r.u(k)
                        val e = ((u ushr 1) xor -(u and 1)).toInt()
                        x[i] = e + when (order) {
                            0 -> 0
                            1 -> x[i - 1]
                            2 -> 2 * x[i - 1] - x[i - 2]
                            3 -> 3 * x[i - 1] - 3 * x[i - 2] + x[i - 3]
                            else -> 4 * x[i - 1] - 6 * x[i - 2] + 4 * x[i - 3] - x[i - 4]
                        }
                        i++
                    }
                }
            }
            else -> error("subframe type $type")
        }
        return x
    }

    /** Encodes [l]/[r] (integers at [bits]) through the float path the recorder uses, in uneven chunks. */
    private fun roundTrip(l: IntArray, r: IntArray, rate: Int, channels: Int, bits: Int) {
        val scale = if (bits == 16) 32768f else 8388608f
        val src = FloatArray(l.size * 2) { if (it % 2 == 0) l[it / 2] / scale else r[it / 2] / scale }
        val bos = ByteArrayOutputStream()
        val enc = FlacEncoder(rate, channels, bits, bos)
        val head0 = enc.header()
        bos.write(head0)
        var at = 0
        var chunk = 1
        while (at < l.size) {
            val k = minOf(chunk, l.size - at)
            enc.write(src.copyOfRange(2 * at, 2 * (at + k)), k)
            at += k
            chunk = chunk * 3 + 7
        }
        val head = enc.finish()
        val file = bos.toByteArray()
        assertEquals(head0.size, head.size)
        head.copyInto(file)
        assertEquals(enc.bytes + head.size, file.size.toLong())

        val d = decode(file)
        assertEquals(rate, d.rate); assertEquals(channels, d.channels); assertEquals(bits, d.bits)
        assertEquals(l.size.toLong(), d.total)
        val want = if (channels == 1) arrayOf(IntArray(l.size) { Wav.quant((l[it] / scale + r[it] / scale) * 0.5f, bits) })
            else arrayOf(l, r)
        for (c in 0 until channels) assertArrayEquals("channel $c", want[c], d.pcm[c])
        val md5 = MessageDigest.getInstance("MD5")
        for (i in l.indices) for (c in 0 until channels) {
            val v = want[c][i]
            for (k in 0 until bits / 8) md5.update((v shr (8 * k)).toByte())
        }
        assertArrayEquals(md5.digest(), d.md5)
    }

    private fun music(n: Int, bits: Int, seed: Int): Pair<IntArray, IntArray> {
        val rnd = Random(seed)
        val amp = if (bits == 16) 20000.0 else 5_000_000.0
        val l = IntArray(n) { (amp * (0.6 * sin(2 * PI * 110 * it / 44100) + 0.2 * sin(2 * PI * 1234 * it / 44100)) +
            rnd.nextInt(-200, 200)).toInt() }
        val r = IntArray(n) { (l[it] * 0.8).toInt() + rnd.nextInt(-300, 300) }
        return l to r
    }

    @Test fun stereoSixteenBit() {
        val (l, r) = music(4096 * 5 + 1234, 16, 1)
        roundTrip(l, r, 44100, 2, 16)
    }

    @Test fun stereoTwentyFourBitAt48k() {
        val (l, r) = music(4096 * 3 + 77, 24, 2)
        roundTrip(l, r, 48000, 2, 24)
    }

    @Test fun monoBothDepths() {
        val (l, r) = music(9000, 16, 3)
        roundTrip(l, r, 44100, 1, 16)
        val (l2, r2) = music(9000, 24, 4)
        roundTrip(l2, r2, 48000, 1, 24)
    }

    @Test fun silenceFullScaleNoiseAndIdenticalChannels() {
        val n = 4096 * 2 + 500
        roundTrip(IntArray(n), IntArray(n), 44100, 2, 16)                                  // CONSTANT
        val rnd = Random(5)
        roundTrip(IntArray(n) { rnd.nextInt(-32768, 32768) }, IntArray(n) { rnd.nextInt(-32768, 32768) },
            44100, 2, 16)                                                                   // VERBATIM
        roundTrip(IntArray(n) { rnd.nextInt(-8388608, 8388608) }, IntArray(n) { rnd.nextInt(-8388608, 8388608) },
            48000, 2, 24)
        val same = IntArray(n) { (30000 * sin(it * 0.01)).toInt() }
        roundTrip(same, same.copyOf(), 44100, 2, 16)                                        // side = 0
        roundTrip(IntArray(n) { 32767 }, IntArray(n) { -32768 }, 44100, 2, 16)              // clipped extremes
    }

    @Test fun shortTakeAndCompression() {
        val (l, r) = music(1000, 16, 6)
        roundTrip(l, r, 44100, 2, 16)
        val (l2, r2) = music(44100, 16, 7)
        val bos = ByteArrayOutputStream()
        val enc = FlacEncoder(44100, 2, 16, bos)
        enc.write(FloatArray(l2.size * 2) { if (it % 2 == 0) l2[it / 2] / 32768f else r2[it / 2] / 32768f }, l2.size)
        enc.finish()
        assertTrue("FLAC ${enc.bytes} vs WAV ${l2.size * 4}", enc.bytes < l2.size * 4 * 0.8)
    }
}
