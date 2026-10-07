// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.proto

/** Decoded reply payloads. Only the commands the native editor uses are modelled. */

data class Info(
    val version: String,
    val nengines: Int,
    val pcount: Int,
    val gcount: Int,
    val nstep: Int,
    val pe0: Int,
    val engines: List<String>,
    val ntrk: Int,   // v3: 0 = older firmware, one instrument
    val proto: Int,  // v5: protocol version (0 = older firmware)
)

data class ParamValue(val scope: Int, val id: Int, val value: Int)

data class Dump(val engine: Int, val preset: Int, val p: IntArray, val g: IntArray) {
    override fun equals(other: Any?) = other is Dump &&
        engine == other.engine && preset == other.preset &&
        p.contentEquals(other.p) && g.contentEquals(other.g)
    override fun hashCode() = (31 * engine + preset) * 31 + p.contentHashCode()
}

data class Desc(
    val scope: Int,
    val id: Int,
    val fmt: Int,
    val min: Int,
    val max: Int,
    val def: Int,
    val label: String,
    val unit: String,
    val names: List<String>,
)

/** A synth sequencer step. v5 adds lvl/rat (2 bits per note, packed). */
data class Step(
    val index: Int,
    val n: Int,
    val notes: IntArray,  // 4 slots
    val time: Int,
    val flags: Int,       // 1 accent, 2 slide
    val vel: Int,
    val lvl: Int = 0,
    val rat: Int = 0,
) {
    override fun equals(other: Any?) = other is Step && index == other.index && n == other.n &&
        notes.contentEquals(other.notes) && time == other.time && flags == other.flags &&
        vel == other.vel && lvl == other.lvl && rat == other.rat
    override fun hashCode() = index
}

data class Preset(val engine: Int, val preset: Int)
data class PresetNames(val engine: Int, val names: List<String>)
data class Reload(val engine: Int, val preset: Int, val track: Int)
data class StepChanged(val index: Int, val track: Int)
data class WatchReply(val on: Int)

data class TrackEntry(val engine: Int, val preset: Int, val level: Int, val mute: Int, val armed: Int)
data class Tracks(val sel: Int, val ntrk: Int, val tracks: List<TrackEntry>, val solo: Int)
data class TrackMix(val track: Int, val level: Int, val mute: Int)
data class TrackDump(val track: Int, val engine: Int, val preset: Int, val p: IntArray) {
    override fun equals(other: Any?) = other is TrackDump && track == other.track &&
        engine == other.engine && preset == other.preset && p.contentEquals(other.p)
    override fun hashCode() = 31 * track + p.contentHashCode()
}
data class TrackStep(val track: Int, val step: Step)
data class TrackParam(val track: Int, val id: Int, val value: Int)

/** One FM6 patch slot of FM6_LIST (factory slots first, then the bank). */
data class Fm6Slot(val used: Boolean, val name: String)
data class Fm6List(val factory: Int, val bank: Int, val slots: List<Fm6Slot>)

/** FM6_GET reply: rc 0 ok, 1 bad index, 2 empty bank slot; [packed] = the 128-value record when rc == 0. */
data class Fm6Got(val target: Int, val index: Int, val rc: Int, val packed: IntArray?)

/** FM6_PUT / FM6_ERASE reply: rc 0 ok, 1 bad arguments, 2 flash error, 3 stop the song first. */
data class Fm6Rc(val index: Int, val rc: Int)

/** A parameter lock (v7): on `step` the track's `param` takes `value`. */
data class StepLock(val step: Int, val param: Int, val value: Int)

/** LOCK_SET reply; `value` = the lock's stored value, null when it was deleted. */
data class LockSet(val track: Int, val step: Int, val param: Int, val rc: Int, val value: Int?)

/** The drum track's step: 16 lanes on/off with a level and ratchet each (v5). */
data class DrumStep(
    val index: Int,
    val on: Int,          // 16 bits, bit l = lane l
    val lvl: IntArray,    // 16 entries, 0..3
    val rat: IntArray,    // 16 entries, 0..3
) {
    override fun equals(other: Any?) = other is DrumStep && index == other.index &&
        on == other.on && lvl.contentEquals(other.lvl) && rat.contentEquals(other.rat)
    override fun hashCode() = 31 * index + on
}

object Parse {
    fun info(a: IntArray): Info {
        val r = Reader(a)
        val version = r.s()
        val nengines = r.b()
        val pcount = r.b()
        val gcount = r.b()
        val nstep = r.b()
        val pe0 = r.b()
        val engines = ArrayList<String>(nengines)
        for (i in 0 until nengines) engines.add(r.s())
        val ntrk = if (r.remaining) r.b() else 0
        val proto = if (r.remaining) r.b() else 0
        return Info(version, nengines, pcount, gcount, nstep, pe0, engines, ntrk, proto)
    }

    fun param(a: IntArray): ParamValue {
        val r = Reader(a)
        return ParamValue(r.b(), r.b(), r.v())
    }

    fun dump(a: IntArray, info: Info): Dump {
        val r = Reader(a)
        val engine = r.b()
        val preset = r.b()
        val p = IntArray(info.pcount) { r.v() }
        val g = IntArray(info.gcount) { r.v() }
        return Dump(engine, preset, p, g)
    }

    fun desc(a: IntArray): Desc {
        val r = Reader(a)
        val scope = r.b(); val id = r.b(); val fmt = r.b()
        val min = r.v(); val max = r.v(); val def = r.v()
        val label = r.s(); val unit = r.s()
        val names = ArrayList<String>()
        if (fmt == Fmt.ENUM) {
            var i = 0
            while (i <= max - min && i < 64 && r.remaining) { names.add(r.s()); i++ }
        }
        return Desc(scope, id, fmt, min, max, def, label, unit, names)
    }

    private fun stepExt(r: Reader, a: IntArray, base: Step): Step {
        if (r.i + 3 <= a.size) {
            val lo = r.b(); val hi = r.b(); val rt = r.b()
            return base.copy(lvl = lo or ((hi and 1) shl 7), rat = rt or (((hi shr 1) and 1) shl 7))
        }
        return base
    }

    fun step(a: IntArray): Step {
        val r = Reader(a)
        val base = Step(
            index = r.b(), n = r.b(),
            notes = intArrayOf(r.b(), r.b(), r.b(), r.b()),
            time = r.b(), flags = r.b(), vel = r.b(),
        )
        return stepExt(r, a, base)
    }

    fun names(a: IntArray): PresetNames {
        val r = Reader(a)
        val engine = r.b()
        val count = r.b()
        return PresetNames(engine, List(count) { r.s() })
    }

    fun preset(a: IntArray): Preset { val r = Reader(a); return Preset(r.b(), r.b()) }

    fun reload(a: IntArray): Reload {
        val r = Reader(a)
        val e = r.b(); val p = r.b()
        return Reload(e, p, if (r.remaining) r.b() else 0)
    }

    fun stepChanged(a: IntArray): StepChanged {
        val r = Reader(a)
        val i = r.b()
        return StepChanged(i, if (r.remaining) r.b() else 0)
    }

    fun watch(a: IntArray): WatchReply = WatchReply(Reader(a).b())

    fun tracks(a: IntArray): Tracks {
        val r = Reader(a)
        val sel = r.b(); val ntrk = r.b()
        val list = ArrayList<TrackEntry>(ntrk)
        for (i in 0 until ntrk) list.add(TrackEntry(r.b(), r.b(), r.v(), r.b(), r.b()))
        val solo = if (r.remaining) r.b() else 0
        return Tracks(sel, ntrk, list, solo)
    }

    fun trackMix(a: IntArray): TrackMix { val r = Reader(a); return TrackMix(r.b(), r.v(), r.b()) }

    fun trackDump(a: IntArray, info: Info): TrackDump {
        val r = Reader(a)
        val track = r.b(); val engine = r.b(); val preset = r.b()
        val p = IntArray(info.pcount) { r.v() }
        return TrackDump(track, engine, preset, p)
    }

    fun trackStep(a: IntArray): TrackStep {
        val r = Reader(a)
        val track = r.b()
        val base = Step(
            index = r.b(), n = r.b(),
            notes = intArrayOf(r.b(), r.b(), r.b(), r.b()),
            time = r.b(), flags = r.b(), vel = r.b(),
        )
        return TrackStep(track, stepExt(r, a, base))
    }

    fun trackParam(a: IntArray): TrackParam { val r = Reader(a); return TrackParam(r.b(), r.b(), r.v()) }

    /** MICRO_GET reply: track, then nstep bytes (nudge + 64). Returns nudges -64..63. */
    fun micro(a: IntArray): List<Int> {
        val r = Reader(a)
        r.b()
        val out = ArrayList<Int>()
        while (r.remaining) out.add(r.b() - 64)
        return out
    }

    /** FILL_GET reply: track, then pack7 of nstep/4 bytes, 2 bits a step (as stored). */
    fun fill(a: IntArray, nstep: Int): List<Int> {
        val r = Reader(a)
        r.b()
        val raw = ArrayList<Int>()
        val need = nstep / 4
        while (raw.size < need && r.remaining) {
            val m = r.b()
            val k = minOf(7, need - raw.size)
            for (i in 0 until k) raw.add(r.b() or (((m shr i) and 1) shl 7))
        }
        return List(nstep) { i -> (raw.getOrElse(i / 4) { 0 } shr (2 * (i % 4))) and 3 }
    }

    /** LOCK_GET reply: track, n, then n x (step, param, v14). */
    fun locks(a: IntArray): List<StepLock> {
        val r = Reader(a)
        r.b()
        val n = r.b()
        return List(minOf(n, 24)) { StepLock(r.b(), r.b(), r.v()) }
    }

    fun fm6List(a: IntArray): Fm6List {
        val r = Reader(a)
        val factory = r.b(); val bank = r.b()
        val slots = List(factory + bank) { val used = r.b() != 0; Fm6Slot(used, r.s()) }
        return Fm6List(factory, bank, slots)
    }

    fun fm6Get(a: IntArray): Fm6Got {
        val r = Reader(a)
        val target = r.b(); val index = r.b(); val rc = r.b()
        return Fm6Got(target, index, rc, if (rc == 0) IntArray(Fm6.PACKED) { r.b() } else null)
    }

    /** FM6_PUT reply: target, index, rc. */
    fun fm6Put(a: IntArray): Fm6Rc { val r = Reader(a); r.b(); return Fm6Rc(r.b(), r.b()) }

    /** FM6_ERASE reply: index, rc. */
    fun fm6Erase(a: IntArray): Fm6Rc { val r = Reader(a); return Fm6Rc(r.b(), r.b()) }

    /** LOCK_SET reply: track, step, param, rc, has, v14. */
    fun lockSet(a: IntArray): LockSet {
        val r = Reader(a)
        return LockSet(r.b(), r.b(), r.b(), r.b(), if (r.b() != 0) r.v() else null)
    }

    fun drumStep(a: IntArray): DrumStep {
        val r = Reader(a)
        val index = r.b()
        val on = r.b() or (r.b() shl 7) or ((r.b() and 3) shl 14)
        var lv = 0L
        var rt = 0L
        for (k in 0 until 5) lv += r.b().toLong() shl (7 * k)
        for (k in 0 until 5) rt += r.b().toLong() shl (7 * k)
        val lvl = IntArray(16)
        val rat = IntArray(16)
        for (l in 0 until 16) {
            val bit = (on shr l) and 1
            if (bit == 1) {
                lvl[l] = ((lv shr (2 * l)) and 3L).toInt()
                rat[l] = ((rt shr (2 * l)) and 3L).toInt()
            }
        }
        return DrumStep(index, on, lvl, rat)
    }
}
