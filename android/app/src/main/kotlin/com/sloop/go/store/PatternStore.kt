// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.store

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File

enum class ClipKind(val dir: String, val title: String) { PIANO("piano", "Piano roll"), DRUM("drum", "Drums") }

/** One note of a clip. Piano: [pitch] is the MIDI note, [len] steps. Drum: [pitch] is the lane 0..15, [len] is 1. */
data class ClipNote(val start: Int, val len: Int, val pitch: Int, val vel: Int = 100)

/** A pattern as the app stores it: a Standard MIDI File of [length] 16th-note steps. */
class Clip(val kind: ClipKind, val length: Int, val notes: List<ClipNote>)

class Saved(val name: String, val clip: Clip)

/** Standard MIDI File writer / reader for clips: format 0, 480 PPQ, one 16th = 120 ticks, the pattern's end = End of Track. */
object Smf {
    private const val PPQ = 480
    private const val STEP = PPQ / 4
    private const val MAX_STEPS = 64

    /** The GM note each drum lane plays (the firmware's LANE_NOTE order, as the drum grid's lanes). */
    val LANE_NOTE = intArrayOf(36, 35, 38, 39, 42, 46, 44, 37, 40, 43, 48, 49, 51, 70, 63, 56)

    fun lvlVel(l: Int) = when (l) { 1 -> 45; 2 -> 70; 3 -> 127; else -> 100 }
    fun velLvl(v: Int) = when { v >= 118 -> 3; v >= 85 -> 0; v >= 55 -> 2; else -> 1 }

    private fun ByteArrayOutputStream.vlq(v: Int) {
        var x = v
        val parts = ArrayList<Int>()
        parts.add(x and 0x7F)
        x = x shr 7
        while (x > 0) { parts.add((x and 0x7F) or 0x80); x = x shr 7 }
        for (i in parts.indices.reversed()) write(parts[i])
    }

    private fun ByteArrayOutputStream.int32(v: Int) { for (s in intArrayOf(24, 16, 8, 0)) write((v shr s) and 255) }
    private fun ByteArrayOutputStream.int16(v: Int) { write((v shr 8) and 255); write(v and 255) }

    fun write(c: Clip, title: String): ByteArray {
        val drum = c.kind == ClipKind.DRUM
        val ch = if (drum) 9 else 0
        class Ev(val tick: Int, val off: Boolean, val note: Int, val vel: Int)
        val evs = ArrayList<Ev>()
        for (n in c.notes) {
            val note = if (drum) Smf.LANE_NOTE.getOrElse(n.pitch) { 36 } else n.pitch.coerceIn(0, 127)
            val on = n.start * STEP
            val dur = if (drum) STEP / 2 else n.len * STEP
            evs.add(Ev(on, false, note, n.vel.coerceIn(1, 127)))
            evs.add(Ev(on + dur, true, note, 0))
        }
        evs.sortWith(compareBy<Ev> { it.tick }.thenByDescending { it.off })
        val t = ByteArrayOutputStream()
        val name = title.toByteArray(Charsets.US_ASCII)
        t.vlq(0); t.write(0xFF); t.write(0x03); t.vlq(name.size); t.write(name)
        t.vlq(0); t.write(0xFF); t.write(0x51); t.write(3); t.write(0x07); t.write(0xA1); t.write(0x20)
        t.vlq(0); t.write(0xFF); t.write(0x58); t.write(4); t.write(4); t.write(2); t.write(24); t.write(8)
        var last = 0
        for (e in evs) {
            t.vlq(e.tick - last); last = e.tick
            t.write((if (e.off) 0x80 else 0x90) or ch); t.write(e.note); t.write(e.vel)
        }
        t.vlq(maxOf(0, c.length * STEP - last)); t.write(0xFF); t.write(0x2F); t.write(0)
        val o = ByteArrayOutputStream()
        o.write("MThd".toByteArray()); o.int32(6); o.int16(0); o.int16(1); o.int16(PPQ)
        o.write("MTrk".toByteArray()); o.int32(t.size()); o.write(t.toByteArray())
        return o.toByteArray()
    }

    /** Reads any format 0/1 file with a PPQ division into a clip of [kind]; null when it is not usable. */
    fun read(b: ByteArray, kind: ClipKind): Clip? = try { parse(b, kind) } catch (_: Exception) { null }

    private fun parse(b: ByteArray, kind: ClipKind): Clip? {
        var p = 0
        fun u8() = b[p++].toInt() and 255
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16() shl 16) or u16()
        fun tag() = String(b, p, 4, Charsets.US_ASCII).also { p += 4 }
        fun vlq(): Int { var v = 0; do { val x = u8(); v = (v shl 7) or (x and 0x7F) } while (x and 0x80 != 0 && p < b.size); return v }
        if (tag() != "MThd") return null
        val hl = u32(); u16(); val ntrk = u16(); val div = u16()
        p = 8 + hl
        if (div and 0x8000 != 0 || div == 0) return null
        val step = maxOf(1, div / 4)
        class On(val tick: Int, val note: Int, val vel: Int)
        val notes = ArrayList<ClipNote>()
        var endTick = 0
        repeat(ntrk) {
            if (p + 8 > b.size) return@repeat
            val id = tag(); val len = u32(); val end = minOf(b.size, p + len)
            if (id != "MTrk") { p = end; return@repeat }
            var tick = 0; var status = 0
            val open = HashMap<Int, On>()
            while (p < end) {
                tick += vlq()
                var s = u8()
                if (s < 0x80) { p--; s = status } else if (s < 0xF0) status = s
                when {
                    s == 0xFF -> { val ty = u8(); val l = vlq(); p += l; if (ty == 0x2F) endTick = maxOf(endTick, tick) }
                    s == 0xF0 || s == 0xF7 -> p += vlq()
                    else -> {
                        val hi = s and 0xF0
                        val d1 = u8()
                        val d2 = if (hi == 0xC0 || hi == 0xD0) 0 else u8()
                        if (hi == 0x90 && d2 > 0) {
                            if (kind == ClipKind.DRUM || !open.containsKey(d1)) open[d1] = On(tick, d1, d2)
                            if (kind == ClipKind.DRUM) { notes.add(drumNote(tick, step, d1, d2)); open.remove(d1) }
                        } else if (hi == 0x80 || hi == 0x90) {
                            open.remove(d1)?.let {
                                val st = (it.tick + step / 2) / step
                                val ln = maxOf(1, (tick - it.tick + step / 2) / step)
                                notes.add(ClipNote(st, ln, it.note, it.vel))
                            }
                        }
                    }
                }
            }
            for (o in open.values) notes.add(ClipNote((o.tick + step / 2) / step, 1, o.note, o.vel))
            p = end
        }
        val steps = maxOf(notes.maxOfOrNull { it.start + it.len } ?: 1, (endTick + step / 2) / step).coerceIn(1, MAX_STEPS)
        val kept = notes.filter { it.start < steps }.map { it.copy(len = minOf(it.len, steps - it.start)) }
        return Clip(kind, steps, kept)
    }

    private fun drumNote(tick: Int, step: Int, note: Int, vel: Int): ClipNote {
        val lane = LANE_NOTE.indices.minByOrNull { Math.abs(LANE_NOTE[it] - note) } ?: 0
        return ClipNote((tick + step / 2) / step, 1, lane, vel)
    }
}

/** The patterns saved inside the app: `files/midi/<piano|drum>/<name>.mid`, with a set of ready-made ones on first run. */
class PatternStore(private val ctx: Context) {
    /** Bumped on every change, so screens listing the files refresh. */
    var rev by mutableIntStateOf(0)
        private set

    private fun dir(kind: ClipKind) = File(ctx.filesDir, "midi/${kind.dir}").also { it.mkdirs() }
    private fun file(kind: ClipKind, name: String) = File(dir(kind), "$name.mid")

    fun clean(name: String): String =
        name.filter { it.isLetterOrDigit() || it in " -_().&+#" }.trim().replace(Regex(" +"), " ").take(40)

    fun exists(kind: ClipKind, name: String) = file(kind, name).exists()

    fun list(kind: ClipKind): List<Saved> = dir(kind).listFiles { f -> f.extension == "mid" }.orEmpty()
        .sortedWith(compareBy<File>({ it.lastModified() }, { it.name.lowercase() }))
        .mapNotNull { f -> Smf.read(f.readBytes(), kind)?.let { Saved(f.nameWithoutExtension, it) } }

    /** Saves [clip] (replacing a clip of that name). Returns the stored name, null when the name is empty. */
    fun save(kind: ClipKind, name: String, clip: Clip): String? {
        val n = clean(name)
        if (n.isEmpty()) return null
        file(kind, n).writeBytes(Smf.write(clip, n))
        rev++
        return n
    }

    fun rename(kind: ClipKind, old: String, new: String): Boolean {
        val n = clean(new)
        if (n.isEmpty() || (n != old && exists(kind, n))) return false
        if (n != old && !file(kind, old).renameTo(file(kind, n))) return false
        rev++
        return true
    }

    fun delete(kind: ClipKind, name: String) { file(kind, name).delete(); rev++ }

    /** Writes the ready-made patterns once; what the user deletes or renames afterwards stays that way. */
    fun seedDefaults() {
        val prefs = ctx.getSharedPreferences("sloop_midi", Context.MODE_PRIVATE)
        val seeded = prefs.getInt("seeded", 0)
        if (seeded >= 3) return
        val base = System.currentTimeMillis() - 100_000
        var i = 0
        // installs that already got the 50 ready patterns receive only the newer ones (deleted ones stay deleted)
        val sets = if (seeded == 2) listOf(ClipKind.DRUM to DefaultClips.drumsNew(), ClipKind.PIANO to DefaultClips.pianoNew())
            else listOf(ClipKind.DRUM to DefaultClips.drums(), ClipKind.PIANO to DefaultClips.piano())
        for ((kind, clips) in sets) {
            for ((name, clip) in clips) {
                val f = file(kind, name)
                if (!f.exists()) { f.writeBytes(Smf.write(clip, name)); f.setLastModified(base + 1000L * i++) }
            }
        }
        prefs.edit().putInt("seeded", 3).apply()
        rev++
    }
}
