// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.proto

import java.util.zip.CRC32

/** Backup objects (protocol v6, BK_LIST / BK_GET / BK_PUT): number encodings and the objects the app edits. */
object Backup {
    const val SETTINGS = 1
    const val FM6_BANK = 8

    /** u35: 5 x 7 bit, LSB first. */
    fun u35(v: Long): IntArray = IntArray(5) { ((v shr (7 * it)) and 0x7F).toInt() }

    fun u35(r: Reader): Long {
        var v = 0L
        for (k in 0 until 5) v += r.b().toLong() shl (7 * k)
        return v
    }

    fun crc32(b: ByteArray): Long = CRC32().also { it.update(b) }.value

    /** Inverse of [Smp.pack7]. */
    fun unpack7(a: IntArray, from: Int): ByteArray {
        val out = ArrayList<Byte>()
        var i = from
        while (i < a.size) {
            val m = a[i++]
            var j = 0
            while (j < 7 && i < a.size) { out.add((a[i++] or (((m shr j) and 1) shl 7)).toByte()); j++ }
        }
        return out.toByteArray()
    }
}

data class BkItem(val id: Int, val len: Int, val crc: Long)
data class BkList(val rc: Int, val items: List<BkItem>)
data class BkGot(val id: Int, val rc: Int, val off: Long, val data: ByteArray)
data class BkRc(val op: Int, val id: Int, val rc: Int)

/**
 * The song order (SLOOP 2.5): bytes 48..83 of the settings object (backup object 1), firmware arranger.h `arr_config_t`:
 * count (1..16), loop (0/1), 2 reserved, then 16 x { section 0..3 = A..D, bars 1..64 }. The magic "PER3" opens the object.
 */
object Arr {
    const val OFF = 48
    const val STEPS = 16
    const val SIZE = 36
    const val MAX_BARS = 64
    private const val MAGIC = 0x50455233L

    data class Entry(val scene: Int, val bars: Int)
    data class Row(val scene: Int, val bars: Int, val times: Int)
    data class Song(val loop: Boolean, val entries: List<Entry>)

    fun ok(b: ByteArray): Boolean = b.size >= OFF + SIZE &&
        ((b[0].toLong() and 255) or ((b[1].toLong() and 255) shl 8) or ((b[2].toLong() and 255) shl 16) or
            ((b[3].toLong() and 255) shl 24)) == MAGIC

    fun decode(b: ByteArray): Song {
        val count = b[OFF].toInt() and 255
        val entries = (0 until minOf(count, STEPS)).map { Entry(b[OFF + 4 + 2 * it].toInt() and 255, b[OFF + 5 + 2 * it].toInt() and 255) }
        return Song((b[OFF + 1].toInt() and 255) == 1, entries)
    }

    /** The settings bytes with this song order (the rest kept as it was). */
    fun encode(b: ByteArray, song: Song): ByteArray {
        val o = b.copyOf()
        val e = song.entries
        o[OFF] = e.size.toByte(); o[OFF + 1] = if (song.loop) 1 else 0; o[OFF + 2] = 0; o[OFF + 3] = 0
        for (i in 0 until STEPS) {
            o[OFF + 4 + 2 * i] = (if (i < e.size) e[i].scene else i % 4).toByte()
            o[OFF + 5 + 2 * i] = (if (i < e.size) e[i].bars else 4).toByte()
        }
        return o
    }

    fun valid(s: Song) = s.entries.size in 1..STEPS && s.entries.all { it.scene in 0..3 && it.bars in 1..MAX_BARS }

    /** The same section with the same bars in a row is one row played `times`. */
    fun group(entries: List<Entry>): List<Row> {
        val rows = ArrayList<Row>()
        for (e in entries) {
            val r = rows.lastOrNull()
            if (r != null && r.scene == e.scene && r.bars == e.bars) rows[rows.size - 1] = r.copy(times = r.times + 1)
            else rows.add(Row(e.scene, e.bars, 1))
        }
        return rows
    }

    fun expand(rows: List<Row>): List<Entry> = rows.flatMap { r -> List(maxOf(1, r.times)) { Entry(r.scene, r.bars) } }
    fun count(rows: List<Row>) = rows.sumOf { maxOf(1, it.times) }
}

/** The FM6 bank as the firmware stores it (backup object 8, fm6_bank.c `fm6_bank_t`, 3472 bytes). */
object Fm6Bank {
    const val SLOTS = 27
    private const val HEAD = 16
    private val MAGIC = intArrayOf(0x46, 0x4D, 0x36, 0x42)

    fun obj(slots: List<IntArray?>): ByteArray {
        val o = ByteArray(HEAD + SLOTS * Fm6.PACKED)
        var used = 0L
        MAGIC.forEachIndexed { i, m -> o[i] = m.toByte() }
        o[4] = 1; o[6] = SLOTS.toByte()
        for (k in 0 until SLOTS) slots.getOrNull(k)?.let { p ->
            for (i in 0 until Fm6.PACKED) o[HEAD + k * Fm6.PACKED + i] = (p[i] and 0x7F).toByte()
            used = used or (1L shl k)
        }
        for (i in 0 until 4) o[8 + i] = ((used shr (8 * i)) and 255).toByte()
        return o
    }

    /** bytes -> 27 packed records or null; an empty object = all empty; anything else (not a bank) = null. */
    fun unobj(b: ByteArray): List<IntArray?>? {
        if (b.isEmpty()) return List(SLOTS) { null }
        if (b.size != HEAD + SLOTS * Fm6.PACKED || b[4].toInt() != 1 || b[6].toInt() != SLOTS) return null
        if (MAGIC.indices.any { (b[it].toInt() and 255) != MAGIC[it] }) return null
        val used = (0 until 4).sumOf { (b[8 + it].toLong() and 255) shl (8 * it) }
        if ((used ushr SLOTS) != 0L || (HEAD until b.size).any { b[it] < 0 }) return null
        return List(SLOTS) { k ->
            if ((used shr k) and 1L == 1L) IntArray(Fm6.PACKED) { b[HEAD + k * Fm6.PACKED + it].toInt() } else null
        }
    }
}
