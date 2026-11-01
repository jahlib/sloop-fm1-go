// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.audio

import java.io.OutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.min

/**
 * A streaming FLAC encoder for the USB recorder, in plain Kotlin (Android has no FLAC writer before 8.0 and none that
 * makes a .flac file). Lossless, about `flac -1`: 4096-sample blocks, the fixed predictors (order 0..4), partitioned
 * Rice residuals, and per block the cheapest of left/right, left/side, right/side and mid/side.
 * 16 or 24 bit, mono or stereo. [header] is the stream's start (fLaC + STREAMINFO); [finish] gives it again with the
 * length, frame sizes and MD5 filled in, to write over the first one (left as written, they read as "unknown").
 */
class FlacEncoder(private val rate: Int, private val channels: Int, private val bits: Int,
                  private val out: OutputStream) {
    init { require(channels in 1..2 && (bits == 16 || bits == 24)) }

    private val pcm = Array(channels) { IntArray(BLOCK) }
    private var fill = 0
    private var frameNo = 0L
    private val mid = IntArray(BLOCK)
    private val side = IntArray(BLOCK)
    private val e = IntArray(BLOCK)
    private val kOf = IntArray(1 shl MAX_P)
    private var planP = 0
    private val w = BitWriter(BLOCK * channels * 4 + 64)
    private val md5 = MessageDigest.getInstance("MD5")
    private val md5buf = ByteArray(BLOCK * channels * (bits / 8))
    private var digest = ByteArray(16)
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0

    /** Samples per channel encoded so far. */
    var total = 0L; private set
    /** Bytes of frames written so far (the header not counted). */
    var bytes = 0L; private set

    fun header(): ByteArray {
        val b = BitWriter(42)
        for (c in "fLaC") b.bits(c.code, 8)
        b.bits(1, 1); b.bits(0, 7); b.bits(34, 24)                 // the last metadata block, STREAMINFO, 34 bytes
        val bs = if (total in 1 until BLOCK) total.toInt() else BLOCK
        b.bits(bs, 16); b.bits(bs, 16)
        b.bits(if (maxFrame == 0) 0 else minFrame, 24); b.bits(maxFrame, 24)
        b.bits(rate, 20); b.bits(channels - 1, 3); b.bits(bits - 1, 5)
        b.bitsL(total, 36)
        for (d in digest) b.bits(d.toInt(), 8)
        return b.buf.copyOf(b.pos)
    }

    /** Interleaved stereo floats in, scaled as [Wav.quant] does (mono: the mean of L and R); returns bytes written. */
    fun write(src: FloatArray, frames: Int): Int {
        val before = bytes
        for (f in 0 until frames) {
            val l = src[2 * f]
            val r = src[2 * f + 1]
            if (channels == 1) pcm[0][fill] = Wav.quant((l + r) * 0.5f, bits)
            else { pcm[0][fill] = Wav.quant(l, bits); pcm[1][fill] = Wav.quant(r, bits) }
            if (++fill == BLOCK) frame()
        }
        return (bytes - before).toInt()
    }

    /** Writes the last, shorter block and returns the completed header. */
    fun finish(): ByteArray {
        if (fill > 0) frame()
        digest = md5.digest()
        return header()
    }

    private fun frame() {
        val n = fill
        digestBlock(n)
        w.reset()
        w.bits(0xFFF8, 16)                                          // sync, fixed block size
        val bsCode = if (n == BLOCK) 12 else 7
        w.bits(bsCode, 4)
        w.bits(when (rate) { 44100 -> 9; 48000 -> 10; 32000 -> 8; 96000 -> 11; else -> 0 }, 4)
        var assign = 0
        if (channels == 2) {
            val l = pcm[0]
            val r = pcm[1]
            for (i in 0 until n) { mid[i] = (l[i] + r[i]) shr 1; side[i] = l[i] - r[i] }
            val cl = bestOrder(l, n).second
            val cr = bestOrder(r, n).second
            val cm = bestOrder(mid, n).second
            val cs = bestOrder(side, n).second
            assign = listOf(1 to cl + cr, 8 to cl + cs, 9 to cr + cs, 10 to cm + cs).minBy { it.second }.first
        }
        w.bits(assign, 4)
        w.bits(if (bits == 16) 4 else 6, 3); w.bits(0, 1)
        utf8(frameNo)
        if (bsCode == 7) w.bits(n - 1, 16)
        w.bits(crc8(w.buf, w.pos), 8)
        when (assign) {
            0 -> sub(pcm[0], n, bits)
            1 -> { sub(pcm[0], n, bits); sub(pcm[1], n, bits) }
            8 -> { sub(pcm[0], n, bits); sub(side, n, bits + 1) }
            9 -> { sub(side, n, bits + 1); sub(pcm[1], n, bits) }
            else -> { sub(mid, n, bits); sub(side, n, bits + 1) }
        }
        w.align()
        w.bits(crc16(w.buf, w.pos), 16)
        out.write(w.buf, 0, w.pos)
        minFrame = min(minFrame, w.pos); maxFrame = maxOf(maxFrame, w.pos)
        bytes += w.pos
        total += n
        frameNo++
        fill = 0
    }

    private fun digestBlock(n: Int) {
        val nb = bits / 8
        var o = 0
        for (i in 0 until n) for (c in 0 until channels) {
            val v = pcm[c][i]
            for (k in 0 until nb) md5buf[o++] = (v shr (8 * k)).toByte()
        }
        md5.update(md5buf, 0, o)
    }

    private fun utf8(v: Long) {
        if (v < 0x80) { w.bits(v.toInt(), 8); return }
        val n = when { v < 0x800 -> 2; v < 0x10000 -> 3; v < 0x200000 -> 4; v < 0x4000000 -> 5; v < 0x80000000L -> 6; else -> 7 }
        w.bits(((0xFF shl (8 - n)) and 0xFF) or (v ushr (6 * (n - 1))).toInt(), 8)
        for (i in n - 2 downTo 0) w.bits(0x80 or ((v ushr (6 * i)) and 0x3F).toInt(), 8)
    }

    /** One channel's subframe: CONSTANT, the cheapest FIXED, or VERBATIM when that is no bigger. */
    private fun sub(x: IntArray, n: Int, bps: Int) {
        var constant = true
        for (i in 1 until n) if (x[i] != x[0]) { constant = false; break }
        if (constant) { w.bits(0, 8); w.bits(x[0], bps); return }
        val order = bestOrder(x, n).first
        residual(x, n, order)
        val method = if (bps > 16) 1 else 0                         // 5-bit Rice parameters for wide samples
        val riceBits = plan(n, order, method)
        if (order.toLong() * bps + riceBits >= n.toLong() * bps) {
            w.bits(1 shl 1, 8)                                      // 0 000001 0: VERBATIM
            for (i in 0 until n) w.bits(x[i], bps)
            return
        }
        w.bits((8 or order) shl 1, 8)                               // 0 001ooo 0: FIXED, order ooo
        for (i in 0 until order) w.bits(x[i], bps)
        val pw = if (method == 1) 5 else 4
        w.bits(method, 2); w.bits(planP, 4)
        var i = 0
        for (j in 0 until (1 shl planP)) {
            val k = kOf[j]
            w.bits(k, pw)
            repeat((n shr planP) - if (j == 0) order else 0) {
                val v = e[i++]
                val u = (v shl 1) xor (v shr 31)
                w.zeros(u ushr k)
                w.bits(1, 1)
                if (k > 0) w.bits(u, k)
            }
        }
    }

    /** The fixed predictor order with the smallest sum of |residual|, and that sum. */
    private fun bestOrder(x: IntArray, n: Int): Pair<Int, Long> {
        var best = 0
        var bestSum = Long.MAX_VALUE
        for (o in 0..min(MAX_ORDER, n - 1)) {
            residual(x, n, o)
            var s = 0L
            for (i in 0 until n - o) s += abs(e[i])
            if (s < bestSum) { bestSum = s; best = o }
        }
        return best to bestSum
    }

    private fun residual(x: IntArray, n: Int, order: Int) {
        when (order) {
            0 -> for (i in 0 until n) e[i] = x[i]
            1 -> for (i in 1 until n) e[i - 1] = x[i] - x[i - 1]
            2 -> for (i in 2 until n) e[i - 2] = x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> for (i in 3 until n) e[i - 3] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            else -> for (i in 4 until n) e[i - 4] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }
    }

    /** Picks the Rice partition order and parameters for [e] (into planP / kOf); returns the residual's bits. */
    private fun plan(n: Int, order: Int, method: Int): Long {
        var maxP = 0
        while (maxP < MAX_P && n % (2 shl maxP) == 0 && (n shr (maxP + 1)) > order) maxP++
        var sums = LongArray(1 shl maxP)
        var i = 0
        for (j in sums.indices) {
            var s = 0L
            repeat((n shr maxP) - if (j == 0) order else 0) {
                val v = e[i++]
                s += ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL
            }
            sums[j] = s
        }
        val kMax = if (method == 1) 30 else 14
        val pw = if (method == 1) 5 else 4
        var best = Long.MAX_VALUE
        var p = maxP
        val ks = IntArray(1 shl maxP)
        while (true) {
            var b = 0L
            for (j in 0 until (1 shl p)) {
                val len = (n shr p) - if (j == 0) order else 0
                val mean = sums[j] / len
                val k = (if (mean > 0) 63 - java.lang.Long.numberOfLeadingZeros(mean) else 0).coerceAtMost(kMax)
                ks[j] = k
                b += pw + len.toLong() * (k + 1) + (sums[j] shr k)
            }
            if (b < best) { best = b; planP = p; ks.copyInto(kOf, 0, 0, 1 shl p) }
            if (p == 0) break
            val s = sums
            sums = LongArray(1 shl (p - 1)) { s[2 * it] + s[2 * it + 1] }
            p--
        }
        return best + 6
    }

    private class BitWriter(cap: Int) {
        var buf = ByteArray(cap)
        var pos = 0
        private var acc = 0L
        private var n = 0

        fun reset() { pos = 0; acc = 0; n = 0 }

        fun bits(v: Int, count: Int) = bitsL(v.toLong(), count)

        fun bitsL(v: Long, count: Int) {
            if (count > 32) { bitsL(v ushr 32, count - 32); bitsL(v and 0xFFFFFFFFL, 32); return }
            acc = (acc shl count) or (v and ((1L shl count) - 1))
            n += count
            while (n >= 8) {
                n -= 8
                if (pos == buf.size) buf = buf.copyOf(buf.size * 2)
                buf[pos++] = (acc ushr n).toByte()
            }
            acc = acc and ((1L shl n) - 1)
        }

        fun zeros(q: Int) {
            var r = q
            while (r >= 32) { bitsL(0, 32); r -= 32 }
            if (r > 0) bitsL(0, r)
        }

        fun align() { if (n > 0) bitsL(0, 8 - n) }
    }

    companion object {
        const val BLOCK = 4096
        const val MIME = "audio/flac"
        private const val MAX_ORDER = 4
        private const val MAX_P = 8

        fun crc8(b: ByteArray, len: Int): Int {
            var c = 0
            for (i in 0 until len) {
                c = c xor (b[i].toInt() and 0xFF)
                repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
            }
            return c
        }

        fun crc16(b: ByteArray, len: Int): Int {
            var c = 0
            for (i in 0 until len) {
                c = c xor ((b[i].toInt() and 0xFF) shl 8)
                repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
            }
            return c
        }
    }
}
