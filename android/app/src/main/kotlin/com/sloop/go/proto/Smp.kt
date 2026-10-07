// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.truncate

/**
 * User sample slots (USR1..4): a port of tools/sampleio.py and the samples/chop blocks of
 * web/editor.html (github.com/isod89/sloop-fm1). A slot is a 480-byte header (magic "FSMP",
 * up to 16 zones) plus IMA ADPCM data, 4 bits a sample, everything mono at 22050 Hz.
 */
object Smp {
    const val SLOT_SIZE = 0x14000
    const val DATA_OFF = 512
    const val RATE = 22050
    const val HDR_LEN = 32 + 16 * 28
    const val MAX_ZONES = 16
    const val MAX_DATA = SLOT_SIZE - DATA_OFF

    const val MAGIC = 0x504D5346L  // "FSMP"

    class SlotError(message: String) : Exception(message)

    /** A file/recording staged for a slot: mono samples at RATE, its root key, optional fixed range. */
    class ZoneIn(val name: String, val s: ShortArray, var root: Int, val lo: Int? = null, val hi: Int? = null)

    // ---------------------------------------------------------------- pack7 ---

    /** Groups of up to 7 bytes, each preceded by their top bits (editor.html pack7). */
    fun pack7(b: ByteArray): IntArray {
        val out = ArrayList<Int>(b.size + b.size / 7 + 8)
        var i = 0
        while (i < b.size) {
            val n = min(7, b.size - i)
            var m = 0
            for (j in 0 until n) m = m or (((b[i + j].toInt() shr 7) and 1) shl j)
            out.add(m)
            for (j in 0 until n) out.add(b[i + j].toInt() and 0x7F)
            i += n
        }
        return out.toIntArray()
    }

    // ---------------------------------------------------------------- WAV ---

    /** PCM 8/16/24/32 or float, any channel count, mixed to mono -> (rate, samples). */
    fun parseWav(d: ByteArray): Pair<Int, DoubleArray> {
        fun id(i: Int) = String(byteArrayOf(d[i], d[i + 1], d[i + 2], d[i + 3]))
        if (d.size < 12 || id(0) != "RIFF" || id(8) != "WAVE") throw SlotError("not a WAV file")
        val v = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        var i = 12
        var fmt: IntArray? = null
        var data: IntArray? = null
        while (i + 8 <= d.size) {
            val cid = id(i)
            val n = v.getInt(i + 4)
            if (cid == "fmt ") {
                var tag = v.getShort(i + 8).toInt() and 0xFFFF
                val ch = v.getShort(i + 10).toInt() and 0xFFFF
                val sr = v.getInt(i + 12)
                val bits = v.getShort(i + 22).toInt() and 0xFFFF
                if (tag == 0xFFFE) tag = v.getShort(i + 32).toInt() and 0xFFFF
                fmt = intArrayOf(tag, ch, sr, bits)
            } else if (cid == "data") {
                data = intArrayOf(i + 8, min(d.size, i + 8 + n))
                break
            }
            i += 8 + n + (n and 1)
        }
        if (fmt == null || data == null) throw SlotError("WAV without fmt/data")
        val (tag, ch, sr, bits) = fmt
        val bps = bits shr 3
        val frame = bps * ch
        if (!(tag == 1 || tag == 3) || bps == 0 || ch == 0) throw SlotError("unsupported WAV format")
        val nfr = (data[1] - data[0]) / frame
        val x = DoubleArray(nfr)
        for (f in 0 until nfr) {
            var acc = 0.0
            for (c in 0 until ch) {
                val o = data[0] + f * frame + c * bps
                val s = when {
                    tag == 3 -> if (bps == 4) v.getFloat(o).toDouble() else v.getDouble(o)
                    bps == 2 -> v.getShort(o).toDouble() / 32768
                    bps == 3 -> {
                        val w = (d[o].toInt() and 0xFF) or
                            ((d[o + 1].toInt() and 0xFF) shl 8) or ((d[o + 2].toInt() and 0xFF) shl 16)
                        ((w shl 8) shr 8).toDouble() / 8388608
                    }
                    bps == 4 -> v.getInt(o).toDouble() / 2147483648
                    else -> ((d[o].toInt() and 0xFF) - 128) / 128.0
                }
                acc += s
            }
            x[f] = acc / ch
        }
        return sr to x
    }

    // ---------------------------------------------------------------- DSP ---

    /** resample: a moving average over the ratio, then linear interpolation (gen_samples.resample). */
    fun resample(x: DoubleArray, sr: Int, to: Int): DoubleArray {
        var a = x
        if (sr == to) return a
        if (sr > to) {
            val k = max(1, pyRound(sr.toDouble() / to))
            if (k > 1) {
                val y = DoubleArray(a.size)
                for (i in a.indices) {
                    var s = 0.0
                    var j = i
                    while (j < min(a.size, i + k)) { s += a[j]; j++ }
                    y[i] = s / k
                }
                a = y
            }
        }
        val step = sr.toDouble() / to
        val out = ArrayList<Double>((a.size * to / sr) + 2)
        var p = 0.0
        while (p < a.size - 1) {
            val i = floor(p).toInt()
            val f = p - i
            out.add(a[i] * (1 - f) + a[i + 1] * f)
            p += step
        }
        return out.toDoubleArray()
    }

    /** Python's round(): half to even. */
    private fun pyRound(v: Double): Int {
        val f = floor(v)
        val r = v - f
        return (if (r > 0.5) f + 1 else if (r < 0.5) f else if (f.toLong() % 2L != 0L) f + 1 else f).toInt()
    }

    /** Mono float -> int16, peak normalised to 30000. */
    fun normalize(x: DoubleArray): ShortArray {
        var pk = 1e-9
        for (v in x) pk = max(pk, abs(v))
        return ShortArray(x.size) { i ->
            max(-32768.0, min(32767.0, truncate(x[i] / pk * 30000))).toInt().toShort()
        }
    }

    /** Root note from a file name like "piano_C4" or "F#3" (C4 = 60), else 60. */
    fun rootFromName(name: String): Int {
        val m = Regex("(?<![A-Za-z])([A-G])([#b]?)(-?\\d)(?!\\d)").find(name) ?: return 60
        val pc = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
            .getValue(m.groupValues[1][0]) +
            (if (m.groupValues[2] == "#") 1 else if (m.groupValues[2] == "b") -1 else 0)
        return (pc + (m.groupValues[3].toInt() + 1) * 12).coerceIn(0, 127)
    }

    /** Sorted root notes -> (lo, hi) per zone: halfway to the neighbours, outer zones to the ends. */
    fun keySplit(roots: List<Int>): List<IntArray> = roots.mapIndexed { j, r ->
        intArrayOf(
            if (j == 0) 0 else (roots[j - 1] + r) / 2 + 1,
            if (j == roots.size - 1) 127 else (r + roots[j + 1]) / 2,
        )
    }

    // ---------------------------------------------------------------- IMA ADPCM ---

    private val IMA_STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88,
        97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
        724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660,
        4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818,
        18500, 20350, 22385, 24623, 27086, 29794, 32767)
    private val IMA_IDX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

    /** IMA ADPCM, 4 bit, low nibble first, from predictor 0 / index 0 -> (data, state at loopStart). */
    fun imaEncode(s: ShortArray, loopStart: Int = 0): Pair<ByteArray, IntArray> {
        var pred = 0
        var idx = 0
        var atLoop = intArrayOf(0, 0)
        val nib = ByteArray(s.size + (s.size and 1))
        for (n in s.indices) {
            if (n == loopStart) atLoop = intArrayOf(pred, idx)
            val step = IMA_STEP[idx]
            var diff = s[n].toInt() - pred
            var code = 0
            if (diff < 0) { code = 8; diff = -diff }
            var vd = step shr 3
            if (diff >= step) { code = code or 4; diff -= step; vd += step }
            if (diff >= step shr 1) { code = code or 2; diff -= step shr 1; vd += step shr 1 }
            if (diff >= step shr 2) { code = code or 1; vd += step shr 2 }
            pred = max(-32768, min(32767, if (code and 8 != 0) pred - vd else pred + vd))
            idx = max(0, min(88, idx + IMA_IDX[code and 7]))
            nib[n] = code.toByte()
        }
        val out = ByteArray(nib.size shr 1)
        for (k in nib.indices step 2) out[k shr 1] = ((nib[k].toInt() and 0xF) or (nib[k + 1].toInt() shl 4)).toByte()
        return out to atLoop
    }

    // ---------------------------------------------------------------- slot build ---

    /** zones -> (480-byte header, ADPCM data). Zones without lo/hi split the keyboard by their roots. */
    fun buildSlot(name: String, zonesIn: List<ZoneIn>): Pair<ByteArray, ByteArray> {
        class Z(
            val off: Int, val n: Int, val root: Int, var lo: Int?, var hi: Int?,
            val pred: Int, val idx: Int,
        )
        val zones = ArrayList<Z>()
        val parts = ArrayList<ByteArray>()
        var len = 0
        for (z in zonesIn) {
            val e = imaEncode(z.s, 0)
            zones.add(Z(len, z.s.size, z.root, z.lo, z.hi, e.second[0], e.second[1]))
            parts.add(e.first)
            len += e.first.size
        }
        if (len > MAX_DATA) throw SlotError("too long: $len B (max $MAX_DATA B)")
        if (zones.isEmpty() || zones.size > MAX_ZONES) throw SlotError("1..$MAX_ZONES files per slot")
        zones.sortBy { it.root }
        zones.forEachIndexed { j, z ->
            if (z.lo == null) {
                z.lo = if (j == 0) 0 else (zones[j - 1].root + z.root) / 2 + 1
                z.hi = if (j == zones.size - 1) 127 else (z.root + zones[j + 1].root) / 2
            }
        }
        val data = ByteArray(len)
        var o = 0
        for (p in parts) { p.copyInto(data, o); o += p.size }
        val crc = CRC32().also { it.update(data) }.value
        val hdr = ByteBuffer.allocate(HDR_LEN).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(MAGIC.toInt())
        hdr.putShort(1)
        hdr.put(zones.size.toByte())
        hdr.put(0)
        val nm = name.uppercase().filter { it.code in 32..126 }.take(8)
        repeat(8) { i -> hdr.put(if (i < nm.length) nm[i].code.toByte() else 0) }
        hdr.putInt(len)
        hdr.putInt(crc.toInt())
        hdr.putLong(0)                          // rsv2[2]
        val rate = pyRound(RATE / 44100.0 * 65536)
        for (z in zones) {
            hdr.putInt(z.off); hdr.putInt(z.n); hdr.putInt(0); hdr.putInt(z.n - 1); hdr.putInt(rate)
            hdr.putShort((z.root * 16).toShort())
            hdr.putShort(z.pred.toShort())
            hdr.put(z.idx.toByte()); hdr.put(z.lo!!.toByte()); hdr.put(z.hi!!.toByte()); hdr.put(0)
        }
        return hdr.array() to data
    }

    // ---------------------------------------------------------------- chop ---

    /** A chop: start..end, index among markers, off = left out, full = up to the next marker. */
    class Chop(val start: Int, val end: Int, val i: Int, val off: Boolean, val full: Int) {
        val len get() = end - start

        companion object {
            const val HOP = 128      /* 5.8 ms frames */
            const val MAX = 16       /* chops a slot takes */
            const val FADE_IN = 22   /* 1 ms */
            const val FADE_OUT = 132 /* 6 ms */
            const val PRE = 32       /* a hit starts 1.5 ms before its frame */
        }
    }

    /** Onset novelty per frame + the log power per frame (level, 0 = the loudest). */
    class Novelty(val nov: DoubleArray, val level: DoubleArray)

    /* onset novelty per frame: the rise of the log energy of the signal's first difference over the
       louder of the two frames before */
    fun chopNovelty(x: DoubleArray): Novelty {
        val h = Chop.HOP
        val f0 = ceil(x.size.toDouble() / h).toInt()
        val l = DoubleArray(f0)
        val p = DoubleArray(f0)
        val nov = DoubleArray(f0)
        var top = 1e-12
        var ptop = 1e-12
        for (f in 0 until f0) {
            var e = 0.0
            var pp = 0.0
            var i = f * h
            val n = min(x.size, i + h)
            while (i < n) {
                val d = x[i] - (if (i > 0) x[i - 1] else 0.0)
                e += d * d; pp += x[i] * x[i]; i++
            }
            l[f] = e; p[f] = pp
            if (e > top) top = e
            if (pp > ptop) ptop = pp
        }
        for (f in 0 until f0) {
            l[f] = log10(l[f] / top + 1e-9)
            p[f] = log10(p[f] / ptop + 1e-9)
        }
        for (f in 0 until f0) {
            val before = max(if (f > 0) l[f - 1] else -9.0, if (f > 1) l[f - 2] else -9.0)
            nov[f] = if (l[f] > -3.5) max(0.0, l[f] - before) else 0.0
        }
        return Novelty(nov, p)
    }

    /* the first sample of the hit in frame f, PRE samples before where it reaches 30 % of its peak */
    fun chopAttack(x: DoubleArray, f: Int): Int {
        val h = Chop.HOP
        val a = max(0, (f - 1) * h)
        val b = min(x.size, (f + 3) * h)
        var pk = 0.0
        for (i in a until b) pk = max(pk, abs(x[i]))
        for (i in a until b) if (abs(x[i]) >= 0.3 * pk) return max(0, i - Chop.PRE)
        return a
    }

    /* hits: sens 1 (hardest and loudest) .. 10 (every small one); at least 60 ms apart */
    fun chopHits(x: DoubleArray, nv: Novelty, sens: Int): IntArray {
        val k = max(1, min(10, sens))
        val thr = 0.15 + (10 - k) * 0.08
        val gate = -(0.5 + k * 0.35)
        val gap = (0.06 * RATE / Chop.HOP).roundToInt()
        val nov = nv.nov
        val l = nv.level
        val out = ArrayList<Int>()
        var last = -1_000_000_000
        for (f in 0 until nov.size - 1) {
            if (nov[f] < thr || (f > 0 && nov[f] < nov[f - 1]) || nov[f] < nov[f + 1]) continue
            if (max(l[f], max(l[min(l.size - 1, f + 1)], l[min(l.size - 1, f + 2)])) < gate) continue
            if (f - last < gap) {
                if (out.isNotEmpty() && nov[f] > nov[last]) {
                    out[out.size - 1] = chopAttack(x, f)
                    last = f
                }
                continue
            }
            out.add(chopAttack(x, f))
            last = f
        }
        return out.toIntArray()
    }

    /* a tap at pos moved to the strongest attack within +-win s, if any */
    fun chopSnap(x: DoubleArray, nv: Novelty, pos: Double, win: Double = 0.05): Int {
        val w = (win * RATE / Chop.HOP).roundToInt()
        val c = (pos / Chop.HOP).roundToInt()
        var best = -1
        var bv = 0.12
        var f = max(1, c - w)
        val hi = min(nv.nov.size - 1, c + w)
        while (f <= hi) { if (nv.nov[f] > bv) { bv = nv.nov[f]; best = f }; f++ }
        return if (best < 0) max(0, min(x.size - 1, pos.roundToInt())) else chopAttack(x, best)
    }

    /* markers every `beats` beats at bpm from start to end */
    fun chopGrid(start: Int, end: Int, bpm: Double, beats: Double): IntArray {
        val step = RATE * 60 / bpm * beats
        val out = ArrayList<Int>()
        var p = start.toDouble()
        while (p < end - step * 0.25 && out.size < 64) { out.add(p.roundToInt()); p += step }
        return out.toIntArray()
    }

    /* n equal parts of start..end */
    fun chopEqual(start: Int, end: Int, n: Int): IntArray =
        IntArray(n) { (start + (end - start).toDouble() * it / n).roundToInt() }

    class Mark(val start: Int, var off: Boolean = false, var len: Int = 0)

    /* the chops of sorted markers, each to the next marker (the last to the end), cut at maxLen or
       its own opt len; a chop is never longer than up to the next marker (full) */
    fun chopList(marks: List<Mark>, total: Int, maxLen: Int = 0): List<Chop> =
        marks.mapIndexed { i, m ->
            val full = max(m.start + 1, if (i + 1 < marks.size) marks[i + 1].start else total)
            var e = full
            if (m.len > 0) e = min(e, m.start + m.len)
            else if (maxLen > 0) e = min(e, m.start + maxLen)
            Chop(m.start, max(m.start + 1, e), i, m.off, full)
        }

    /* the chops the slot gets: mode 0 the kept ones, the first MAX of them; mode 1 the chop sel */
    fun chopPick(chops: List<Chop>, mode: Int = 0, sel: Int = 0): List<Chop> {
        if (mode == 1) return if (chops.isNotEmpty())
            listOf(chops[max(0, min(chops.size - 1, sel))]) else emptyList()
        return chops.filter { !it.off }.take(Chop.MAX)
    }

    /* the longest common length L (samples) so the chops, each cut at L, fit in room; MAX_VALUE if they fit */
    fun chopFit(chops: List<Chop>, room: Int): Int {
        val len = chops.map { it.end - it.start }
        fun sum(l: Int) = len.fold(0L) { a, n -> a + min(n, l) }
        if (sum(Int.MAX_VALUE / 4) <= room || len.isEmpty()) return Int.MAX_VALUE
        var lo = 0
        var hi = len.max()
        while (lo < hi) {
            val l = (lo + hi + 1) / 2
            if (sum(l) <= room) lo = l else hi = l - 1
        }
        return lo
    }

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun noteName(n: Int): String = NOTE_NAMES[((n % 12) + 12) % 12] + (n / 12 - 1)

    /* chops -> slot zones (one gain for all: their levels are kept). mode 0: one key each from key0;
       mode 1: the chop sel alone over the whole keyboard */
    fun chopZones(x: DoubleArray, chops: List<Chop>, key0: Int, mode: Int = 0, sel: Int = 0): List<ZoneIn> {
        val use = chopPick(chops, mode, sel)
        var pk = 1e-9
        for (c in use) for (i in c.start until c.end) pk = max(pk, abs(x[i]))
        return use.mapIndexed { j, c ->
            val n = c.end - c.start
            val s = ShortArray(n)
            val fi = min(Chop.FADE_IN, n shr 2)
            val fo = min(Chop.FADE_OUT, n shr 2)
            for (i in 0 until n) {
                var g = 1.0
                if (i < fi) g = i.toDouble() / fi
                if (i >= n - fo) g = min(g, (n - i).toDouble() / fo)
                s[i] = max(-32768.0, min(32767.0, truncate(x[c.start + i] / pk * 30000 * g))).toInt().toShort()
            }
            val key = min(127, key0 + (if (mode == 1) 0 else j))
            val num = if (c.i >= 0) c.i else if (mode == 1) sel else j
            ZoneIn("CHOP${(num + 1).toString().padStart(2, '0')}_${noteName(key)}.wav", s,
                key, if (mode == 1) 0 else key, if (mode == 1) 127 else key)
        }
    }

    /** A mono 16-bit WAV at RATE (export). */
    fun wavFile(s: ShortArray): ByteArray {
        val b = ByteBuffer.allocate(44 + s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + s.size * 2); b.put("WAVEfmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2)
        b.putShort(2); b.putShort(16); b.put("data".toByteArray()); b.putInt(s.size * 2)
        for (v in s) b.putShort(v)
        return b.array()
    }
}
