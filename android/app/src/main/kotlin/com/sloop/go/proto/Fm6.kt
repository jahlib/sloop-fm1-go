// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.proto

/**
 * The FM6 engine's patch (firmware protocol v9, SLOOP 2.4): a DX7 six-operator voice.
 * voice: 155 ints, 6 x 21 operator values (the sixth operator first), 19 voice values, a 10-character name.
 * packed: the 128-value record of a DX7 32-voice bank (every value 7-bit) that travels in FM6_GET / FM6_PUT.
 * A port of the web editor's `FM6` object (web/editor.html in https://github.com/isod89/sloop-fm1), the reference for unpack / pack / parseSysex.
 */
object Fm6 {
    val OP = listOf("R1" to 99, "R2" to 99, "R3" to 99, "R4" to 99, "L1" to 99, "L2" to 99, "L3" to 99, "L4" to 99,
        "BP" to 99, "LD" to 99, "RD" to 99, "LC" to 3, "RC" to 3, "RS" to 7, "AMS" to 3, "KVS" to 7, "OL" to 99,
        "MODE" to 1, "FC" to 31, "FF" to 99, "DET" to 14)
    val VOICE = listOf("PR1" to 99, "PR2" to 99, "PR3" to 99, "PR4" to 99, "PL1" to 99, "PL2" to 99, "PL3" to 99,
        "PL4" to 99, "ALG" to 31, "FB" to 7, "OKS" to 1, "LFS" to 99, "LFD" to 99, "LPMD" to 99, "LAMD" to 99,
        "LKS" to 1, "LFW" to 5, "LPMS" to 7, "TRNSP" to 48)

    const val NAME = 145
    const val SIZE = 155
    const val PACKED = 128
    const val OPS = 6

    private const val V0 = 126
    val PR1 = V0; val PL1 = V0 + 4; val ALG = V0 + 8; val FB = V0 + 9; val OKS = V0 + 10
    val LFS = V0 + 11; val LFD = V0 + 12; val LPMD = V0 + 13; val LAMD = V0 + 14; val LKS = V0 + 15
    val LFW = V0 + 16; val LPMS = V0 + 17; val TRNSP = V0 + 18

    val LFO_WAVES = listOf("TRI", "SAW-", "SAW+", "SQR", "SIN", "S&H")
    val CURVES = listOf("-LIN", "-EXP", "+EXP", "+LIN")

    private val OPI = OP.mapIndexed { i, p -> p.first to i }.toMap()

    /** Highest value of voice byte [i]. */
    fun max(i: Int) = when {
        i < V0 -> OP[i % 21].second
        i < NAME -> VOICE[i - V0].second
        else -> 126
    }

    /** Index of [field] of operator [n] (1..6). */
    fun at(n: Int, field: String) = (OPS - n) * 21 + OPI.getValue(field)

    fun sanitize(v: IntArray): IntArray {
        val o = IntArray(SIZE) { v.getOrElse(it) { 0 } }
        for (i in 0 until SIZE) o[i] = if (i >= NAME) (if (o[i] < 32 || o[i] > 126) 32 else o[i]) else minOf(o[i], max(i))
        return o
    }

    fun unpack(b: IntArray): IntArray {
        val p = IntArray(PACKED) { (b.getOrElse(it) { 0 }) and 127 }
        val v = IntArray(SIZE)
        for (k in 0 until 6) {
            val o = k * 17; val d = k * 21
            for (i in 0 until 11) v[d + i] = p[o + i]
            v[d + 11] = p[o + 11] and 3; v[d + 12] = (p[o + 11] shr 2) and 3
            v[d + 13] = p[o + 12] and 7; v[d + 20] = (p[o + 12] shr 3) and 15
            v[d + 14] = p[o + 13] and 3; v[d + 15] = (p[o + 13] shr 2) and 7
            v[d + 16] = p[o + 14]; v[d + 17] = p[o + 15] and 1
            v[d + 18] = (p[o + 15] shr 1) and 31; v[d + 19] = p[o + 16]
        }
        for (i in 0 until 9) v[V0 + i] = p[102 + i]
        v[134] = v[134] and 31; v[135] = p[111] and 7; v[136] = (p[111] shr 3) and 1
        for (i in 0 until 4) v[137 + i] = p[112 + i]
        v[141] = p[116] and 1; v[142] = (p[116] shr 1) and 7; v[143] = (p[116] shr 4) and 7; v[144] = p[117]
        for (i in 0 until 10) v[NAME + i] = p[118 + i]
        return sanitize(v)
    }

    fun pack(v: IntArray): IntArray {
        val b = IntArray(PACKED)
        for (k in 0 until 6) {
            val o = k * 17; val d = k * 21
            for (i in 0 until 11) b[o + i] = v[d + i] and 127
            b[o + 11] = (v[d + 11] and 3) or ((v[d + 12] and 3) shl 2)
            b[o + 12] = (v[d + 13] and 7) or ((v[d + 20] and 15) shl 3)
            b[o + 13] = (v[d + 14] and 3) or ((v[d + 15] and 7) shl 2)
            b[o + 14] = v[d + 16] and 127
            b[o + 15] = (v[d + 17] and 1) or ((v[d + 18] and 31) shl 1)
            b[o + 16] = v[d + 19] and 127
        }
        for (i in 0 until 9) b[102 + i] = v[V0 + i] and 127
        b[110] = b[110] and 31
        b[111] = (v[135] and 7) or ((v[136] and 1) shl 3)
        for (i in 0 until 4) b[112 + i] = v[137 + i] and 127
        b[116] = (v[141] and 1) or ((v[142] and 7) shl 1) or ((v[143] and 7) shl 4)
        b[117] = v[144] and 127
        for (i in 0 until 10) b[118 + i] = v[NAME + i] and 127
        return b
    }

    fun name(v: IntArray): String =
        String(CharArray(10) { v[NAME + it].toChar() }).trimEnd()

    fun setName(v: IntArray, s: String): IntArray {
        val t = s.uppercase().map { if (it.code in 32..126) it else ' ' }.joinToString("").take(10).padEnd(10, ' ')
        return v.copyOf().also { for (i in 0 until 10) it[NAME + i] = t[i].code }
    }

    fun checksum(a: IntArray): Int = (128 - (a.sum() and 127)) and 127

    class Voice(val name: String, val v: IntArray)

    class Parsed(
        val voices: List<Voice>, val badSum: Int, val short: Int, val skipped: Int,
        val kinds: List<String>, val sysex: Boolean,
    )

    /** Every voice in a file: single voice or 32-voice bank SysEx, or raw 155 / 128 / 4096-byte data. */
    fun parseSysex(bytes: ByteArray): Parsed {
        val d = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
        val voices = ArrayList<Voice>()
        val kinds = ArrayList<String>()
        var badSum = 0; var short = 0; var skipped = 0
        fun add(v: IntArray) { voices.add(Voice(name(v), v)) }
        fun kind(k: String) { skipped++; if (k !in kinds) kinds.add(k) }
        val sysex = d.contains(0xF0)
        if (d.isEmpty() || (d[0] != 0xF0 && !sysex)) {
            if (d.isNotEmpty() && d.size % 4096 == 0) {
                for (o in d.indices step 128) add(unpack(d.copyOfRange(o, o + 128)))
            } else if (d.size == SIZE) add(sanitize(d))
            else if (d.size == PACKED) add(unpack(d))
            return Parsed(voices, badSum, short, skipped, kinds, sysex)
        }
        var i = 0
        while (i < d.size) {
            if (d[i] != 0xF0) { i++; continue }
            val id = d.getOrNull(i + 1); val st = d.getOrNull(i + 2); val f = d.getOrNull(i + 3)
            if (id == 0x43 && st != null && (st and 0xF0) == 0 && (f == 0 || f == 9) && i + 6 <= d.size) {
                val want = if (f == 0) SIZE else 4096
                val s0 = i + 6
                var e = s0
                while (e < d.size && e < s0 + want && d[e] < 0x80) e++
                val data = d.copyOfRange(s0, e)
                if (data.size < want) short++
                else if (e < d.size && d[e] < 0x80 && checksum(data) != d[e]) badSum++
                if (f == 0) {
                    if (data.size >= NAME) add(sanitize(data))
                } else {
                    var o = 0
                    while (o + 118 <= data.size) {
                        add(unpack(data.copyOfRange(o, minOf(o + 128, data.size))))
                        o += 128
                    }
                }
                i = e
                continue
            }
            if (id == 0x43) kind(if (f == 3 || f == 4) "fm4" else "other43")
            else if (id == 0x7E || id == 0x7F) kind("universal")
            else if (id != null && id < 0x80) kind("maker:" + (if (id == 0) listOf(0, st ?: 0, f ?: 0) else listOf(id))
                .joinToString(" ") { "%02X".format(it) })
            i++
        }
        return Parsed(voices, badSum, short, skipped, kinds, sysex)
    }

    fun singleSysex(v: IntArray, ch: Int = 0): ByteArray {
        val data = sanitize(v)
        val out = intArrayOf(0xF0, 0x43, ch and 15, 0, 0x01, 0x1B) + data + intArrayOf(checksum(data), 0xF7)
        return ByteArray(out.size) { out[it].toByte() }
    }

    /** Up to 32 voices (155 values each); the rest of the 32 slots the init voice. */
    fun bankSysex(voices: List<IntArray?>, ch: Int = 0): ByteArray {
        val data = ArrayList<Int>(4096)
        for (k in 0 until 32) data.addAll((voices.getOrNull(k)?.let { pack(it) } ?: INIT_PK).toList())
        val d = data.toIntArray()
        val out = intArrayOf(0xF0, 0x43, ch and 15, 9, 0x20, 0) + d + intArrayOf(checksum(d), 0xF7)
        return ByteArray(out.size) { out[it].toByte() }
    }

    // the 32 algorithms (msfa's tables; fm6_core.c FM6_ALG): per operator, the sixth first
    private val ALGS = arrayOf(
        intArrayOf(0xc1, 0x11, 0x11, 0x14, 0x01, 0x14), intArrayOf(0x01, 0x11, 0x11, 0x14, 0xc1, 0x14),
        intArrayOf(0xc1, 0x11, 0x14, 0x01, 0x11, 0x14), intArrayOf(0xc1, 0x11, 0x94, 0x01, 0x11, 0x14),
        intArrayOf(0xc1, 0x14, 0x01, 0x14, 0x01, 0x14), intArrayOf(0xc1, 0x94, 0x01, 0x14, 0x01, 0x14),
        intArrayOf(0xc1, 0x11, 0x05, 0x14, 0x01, 0x14), intArrayOf(0x01, 0x11, 0xc5, 0x14, 0x01, 0x14),
        intArrayOf(0x01, 0x11, 0x05, 0x14, 0xc1, 0x14), intArrayOf(0x01, 0x05, 0x14, 0xc1, 0x11, 0x14),
        intArrayOf(0xc1, 0x05, 0x14, 0x01, 0x11, 0x14), intArrayOf(0x01, 0x05, 0x05, 0x14, 0xc1, 0x14),
        intArrayOf(0xc1, 0x05, 0x05, 0x14, 0x01, 0x14), intArrayOf(0xc1, 0x05, 0x11, 0x14, 0x01, 0x14),
        intArrayOf(0x01, 0x05, 0x11, 0x14, 0xc1, 0x14), intArrayOf(0xc1, 0x11, 0x02, 0x25, 0x05, 0x14),
        intArrayOf(0x01, 0x11, 0x02, 0x25, 0xc5, 0x14), intArrayOf(0x01, 0x11, 0x11, 0xc5, 0x05, 0x14),
        intArrayOf(0xc1, 0x14, 0x14, 0x01, 0x11, 0x14), intArrayOf(0x01, 0x05, 0x14, 0xc1, 0x14, 0x14),
        intArrayOf(0x01, 0x14, 0x14, 0xc1, 0x14, 0x14), intArrayOf(0xc1, 0x14, 0x14, 0x14, 0x01, 0x14),
        intArrayOf(0xc1, 0x14, 0x14, 0x01, 0x14, 0x04), intArrayOf(0xc1, 0x14, 0x14, 0x14, 0x04, 0x04),
        intArrayOf(0xc1, 0x14, 0x14, 0x04, 0x04, 0x04), intArrayOf(0xc1, 0x05, 0x14, 0x01, 0x14, 0x04),
        intArrayOf(0x01, 0x05, 0x14, 0xc1, 0x14, 0x04), intArrayOf(0x04, 0xc1, 0x11, 0x14, 0x01, 0x14),
        intArrayOf(0xc1, 0x14, 0x01, 0x14, 0x04, 0x04), intArrayOf(0x04, 0xc1, 0x11, 0x14, 0x04, 0x04),
        intArrayOf(0xc1, 0x14, 0x04, 0x04, 0x04, 0x04), intArrayOf(0xc4, 0x04, 0x04, 0x04, 0x04, 0x04),
    )

    /** Operators 1..6 that are carriers (on the output bus) of algorithm [a] (0..31). */
    fun carriers(a: Int) = (1..6).filter { ALGS[a and 31][6 - it] and 3 == 0 }

    /** The operator (1..6) with the feedback loop of algorithm [a]. */
    fun feedbackOp(a: Int) = (1..6).firstOrNull { ALGS[a and 31][6 - it] and 0xc0 == 0xc0 }

    /**
     * An algorithm as a graph: [mods] maps an operator to the operators that modulate it, [carriers] go to the output,
     * the feedback loop runs from [fbFrom] into [fbTo] (the same operator for a self loop; DX7 algorithms 4 and 6 loop
     * back over three and two operators).
     */
    class Alg(val mods: Map<Int, List<Int>>, val carriers: List<Int>, val fbFrom: Int?, val fbTo: Int?) {
        /** The operators [op] modulates. */
        fun targets(op: Int): List<Int> = mods.filterValues { op in it }.keys.sorted()
    }

    /**
     * Reads algorithm [a] (0..31) from its bus flags, as msfa's FmCore runs it, operator 6 first: bits 0-1 the output
     * (0 the mix, 1 / 2 a bus), bit 2 add to the bus instead of replacing it, bits 4-5 the input bus, bits 6-7 feedback
     * in / out.
     */
    fun alg(a: Int): Alg {
        val f = ALGS[a and 31]
        var bus1 = emptyList<Int>()
        var bus2 = emptyList<Int>()
        val mods = HashMap<Int, List<Int>>()
        val carriers = ArrayList<Int>()
        var fbFrom: Int? = null
        var fbTo: Int? = null
        for (op in 6 downTo 1) {
            val b = f[6 - op]
            val input = when { b and 0x10 != 0 -> bus1; b and 0x20 != 0 -> bus2; else -> emptyList() }
            if (input.isNotEmpty()) mods[op] = input.sorted()
            if (b and 0x80 != 0) fbFrom = op
            if (b and 0x40 != 0) fbTo = op
            when (b and 3) {
                0 -> carriers += op
                1 -> bus1 = if (b and 4 != 0) bus1 + op else listOf(op)
                else -> bus2 = if (b and 4 != 0) bus2 + op else listOf(op)
            }
        }
        return Alg(mods, carriers.sorted(), fbFrom, fbTo)
    }

    /**
     * Where algorithm [a]'s operators sit in the usual DX7 drawing: the carriers on row 0 left to right in operator
     * order, each modulator a row above what it modulates, centred over several of them ([x] in columns, maybe halves).
     */
    class AlgLayout(val alg: Alg, val x: Map<Int, Float>, val row: Map<Int, Int>) {
        val cols: Float get() = x.values.max() + 1
        val rows: Int get() = row.values.max() + 1
    }

    fun algLayout(a: Int): AlgLayout {
        val g = alg(a)
        val x = HashMap<Int, Float>()
        val row = HashMap<Int, Int>()
        // a row above the highest operator it modulates, so every line runs upwards
        fun depth(op: Int): Int = row[op] ?: (g.targets(op).maxOfOrNull { depth(it) + 1 } ?: 0).also { row[op] = it }
        for (op in 1..6) depth(op)
        var next = 0f
        fun place(op: Int): Float {
            x[op]?.let { return it }
            val xs = g.mods[op].orEmpty().filter { it !in x }.map { place(it) }
            val at = if (xs.isEmpty()) next++ else xs.average().toFloat()
            x[op] = at
            return at
        }
        for (c in g.carriers) place(c)
        for (op in 1..6) place(op)
        // a modulator of several operators (with none of its own) moves over their middle when that spot is free
        for (op in 1..6) {
            val t = g.targets(op)
            if (t.size < 2 || g.mods[op] != null) continue
            val want = t.map { x.getValue(it) }.average().toFloat()
            if (x.none { (o, ox) -> o != op && row[o] == row[op] && kotlin.math.abs(ox - want) < 1f }) x[op] = want
        }
        return AlgLayout(g, x, row)
    }

    /** An operator's frequency as text: x ratio, or Hz for a fixed one. */
    fun freqText(v: IntArray, n: Int): String {
        val fc = v[at(n, "FC")]; val ff = v[at(n, "FF")]
        if (v[at(n, "MODE")] != 0) {
            val hz = Math.pow(10.0, (fc and 3) + ff / 100.0)
            return String.format(java.util.Locale.US, "%.4g Hz", hz)
        }
        return String.format(java.util.Locale.US, "x%.2f", (if (fc != 0) fc.toDouble() else 0.5) * (1 + ff / 100.0))
    }

    val INIT_PK = intArrayOf(99,99,99,99,99,99,99,0,39,0,0,0,56,0,0,2,0,99,99,99,99,99,99,99,0,39,0,0,0,56,0,0,2,0,99,99,99,99,99,99,99,0,39,0,0,0,56,0,0,2,0,99,99,99,99,99,99,99,0,39,0,0,0,56,0,0,2,0,99,99,99,99,99,99,99,0,39,0,0,0,56,0,0,2,0,99,99,99,99,99,99,99,0,39,0,0,0,56,0,99,2,0,99,99,99,99,50,50,50,50,0,8,35,0,0,0,49,24,73,78,73,84,32,86,79,73,67,69)

    fun init(): IntArray = unpack(INIT_PK)

    /** Number of bank slots on the device (fm6_bank.c). */
    const val BANK_N = 27
}
