// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.proto

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The SYN1..SYN4 drum kits of SLOOP 2.5 (protocol v10, firmware drum_synth.c `dsnd_t`).
 * A sound is 22 bytes on the wire; the page edits the unpacked values (an [IntArray] indexed by the K_* keys).
 * A port of the web editor's `DSYN` object and DS_GROUPS table (web/editor.html in isod89/sloop-fm1).
 */
object Dsyn {
    const val USER = 64
    const val NUSER = 4
    const val LANES = 16
    const val SIZE = 22

    private val FIELDS = listOf("wave", "src", "pitch", "fine", "bend", "btime", "hold", "decay", "tlev", "t2", "t2lev",
        "click", "nlev", "nhold", "ndec", "flt", "fcut", "fenv", "hpf", "chip", "drive", "level")

    val LANE_NAMES = listOf("KICK", "SNARE", "CLAP", "HAT", "OPEN HAT", "LOW TOM", "HI TOM", "CRASH", "RIDE", "SHAKER",
        "CONGA", "RIM", "COWBELL", "CLAVE", "KICK 2", "SNARE 2")
    val WAVES = listOf("OFF", "SINE", "TRI", "SQUARE", "FM", "BELL")
    val NOISES = listOf("OFF", "WHITE", "METAL", "CYM", "CHIP")
    val FMODES = listOf("OFF", "LP", "BP", "HP")
    private val STEPS_PER_OCT = 127 / (ln(16000.0 / 30) / ln(2.0))

    // the unpacked value keys
    const val WAVE = 0; const val NOISE = 1; const val CLAP = 2; const val PITCH = 3; const val FINE = 4
    const val BEND = 5; const val BTIME = 6; const val HOLD = 7; const val DECAY = 8; const val TLEV = 9
    const val T2 = 10; const val T2LEV = 11; const val CLICK = 12; const val NLEV = 13; const val NHOLD = 14
    const val NDEC = 15; const val FMODE = 16; const val FALL = 17; const val RES = 18; const val FCUT = 19
    const val FENV = 20; const val HPF = 21; const val CHIP = 22; const val DRIVE = 23; const val LEVEL = 24
    const val NKEYS = 25

    /** A knob of the page: key, label, range, and the text it shows (value, unit). */
    class Knob(val key: Int, val label: String, val min: Int, val max: Int, val names: List<String>? = null,
               val fmt: (Int, IntArray) -> Pair<String, String>)

    class Group(val title: String, val knobs: List<Knob>)

    private fun note(n: Int) = "${listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[n % 12]}${n / 12 - 1}"
    private fun hz(note: Int, fine: Int = 0) = 440.0 * 2.0.pow((note + fine / 16.0 - 69) / 12)
    private fun decayMs(i: Int) = 5.0 * 800.0.pow(i / 127.0)
    private fun cutHz(i: Int) = 30.0 * (16000.0 / 30).pow(i / 127.0)
    private fun dB(level: Int) = (level - 128) / 4.0
    private fun fHz(h: Double) = if (h >= 1000) "${trim(h / 1000, if (h >= 10000) 0 else 1)}k" else "${h.roundToInt()}"
    private fun trim(x: Double, d: Int) = if (d == 0) "${x.roundToInt()}" else "%.${d}f".format(x)
    private fun fMs(ms: Double) = if (ms >= 1000) trim(ms / 1000, if (ms >= 10000) 0 else 1) else "${ms.roundToInt()}"
    private fun msUnit(i: Int) = if (decayMs(i) >= 1000) "s" else "ms"
    private fun plain(v: Int) = v.toString() to ""

    val GROUPS = listOf(
        Group("Tone", listOf(
            Knob(WAVE, "WAVE", 0, 5, WAVES) { v, _ -> WAVES[v] to "" },
            Knob(PITCH, "PITCH", 0, 127) { v, o -> note(v) to "${fHz(hz(v, o[FINE]))} Hz" },
            Knob(FINE, "FINE", 0, 15) { v, _ -> "+$v" to "/16" },
            Knob(BEND, "BEND", 0, 96) { v, _ -> "$v" to "st" },
            Knob(BTIME, "B.TIME", 0, 127) { v, _ -> fMs(decayMs(v)) to msUnit(v) },
            Knob(HOLD, "HOLD", 0, 255) { v, _ -> "${v * 2}" to "ms" },
            Knob(DECAY, "DECAY", 0, 127) { v, _ -> fMs(decayMs(v)) to msUnit(v) },
            Knob(TLEV, "LEVEL", 0, 127) { v, _ -> plain(v) },
            Knob(T2, "2ND", 0, 255) { v, _ -> if (v != 0) "×%.2f".format(v / 32.0) to "" else "OFF" to "" },
            Knob(T2LEV, "2ND LVL", 0, 127) { v, _ -> plain(v) },
            Knob(CLICK, "CLICK", 0, 127) { v, _ -> plain(v) },
        )),
        Group("Noise", listOf(
            Knob(NOISE, "NOISE", 0, 4, NOISES) { v, _ -> NOISES[v] to "" },
            Knob(CLAP, "CLAP", 0, 1, listOf("OFF", "ON")) { v, _ -> (if (v != 0) "ON" else "OFF") to "" },
            Knob(NLEV, "LEVEL", 0, 127) { v, _ -> plain(v) },
            Knob(NHOLD, "HOLD", 0, 255) { v, _ -> "${v * 2}" to "ms" },
            Knob(NDEC, "DECAY", 0, 127) { v, _ -> fMs(decayMs(v)) to msUnit(v) },
            Knob(HPF, "HPF", 0, 127) { v, _ -> if (v != 0) fHz(cutHz(v)) to "Hz" else "OFF" to "" },
            Knob(CHIP, "CHIP", 0, 127) { v, _ -> if (v != 0) fHz(hz(v) * 8) to "Hz" else "-" to "" },
        )),
        Group("Filter", listOf(
            Knob(FMODE, "FILTER", 0, 3, FMODES) { v, _ -> FMODES[v] to "" },
            Knob(FALL, "ON", 0, 1, listOf("NOISE", "ALL")) { v, _ -> (if (v != 0) "ALL" else "NOISE") to "" },
            Knob(FCUT, "CUTOFF", 0, 127) { v, _ -> fHz(cutHz(v)) to "Hz" },
            Knob(RES, "RES", 0, 31) { v, _ -> "${(v * 100 / 31.0).roundToInt()}" to "%" },
            Knob(FENV, "ENV", -128, 127) { v, _ -> "${if (v > 0) "+" else ""}%.1f".format(v / STEPS_PER_OCT) to "oct" },
        )),
        Group("Output", listOf(
            Knob(DRIVE, "DRIVE", 0, 127) { v, _ -> plain(v) },
            Knob(LEVEL, "LEVEL", 0, 255) { v, _ -> "${if (v >= 128) "+" else ""}%.1f".format(dB(v)) to "dB" },
        )),
    )

    private fun clamp(v: Int, lo: Int, hi: Int) = v.coerceIn(lo, hi)

    /** 22 bytes -> the values the page edits. */
    fun decode(b: ByteArray): IntArray {
        fun g(k: String) = b[FIELDS.indexOf(k)].toInt() and 255
        val flt = g("flt"); val src = g("src"); val fe = g("fenv")
        val o = IntArray(NKEYS)
        o[WAVE] = g("wave"); o[NOISE] = src and 15; o[CLAP] = (src shr 4) and 1; o[PITCH] = g("pitch")
        o[FINE] = g("fine"); o[BEND] = g("bend"); o[BTIME] = g("btime"); o[HOLD] = g("hold"); o[DECAY] = g("decay")
        o[TLEV] = g("tlev"); o[T2] = g("t2"); o[T2LEV] = g("t2lev"); o[CLICK] = g("click"); o[NLEV] = g("nlev")
        o[NHOLD] = g("nhold"); o[NDEC] = g("ndec"); o[FMODE] = flt and 3; o[FALL] = (flt shr 2) and 1
        o[RES] = flt shr 3; o[FCUT] = g("fcut"); o[FENV] = if (fe > 127) fe - 256 else fe; o[HPF] = g("hpf")
        o[CHIP] = g("chip"); o[DRIVE] = g("drive"); o[LEVEL] = g("level")
        return o
    }

    /** The values -> 22 bytes, into the ranges the firmware keeps (editor_dsyn.c dsu_fix_sound). */
    fun encode(o: IntArray): ByteArray {
        val v = HashMap<String, Int>()
        v["wave"] = clamp(o[WAVE], 0, 5); v["src"] = clamp(o[NOISE], 0, 4) or (if (o[CLAP] != 0) 16 else 0)
        v["pitch"] = clamp(o[PITCH], 0, 127); v["fine"] = clamp(o[FINE], 0, 15); v["bend"] = clamp(o[BEND], 0, 96)
        v["btime"] = clamp(o[BTIME], 0, 127); v["hold"] = clamp(o[HOLD], 0, 255); v["decay"] = clamp(o[DECAY], 0, 127)
        v["tlev"] = clamp(o[TLEV], 0, 127); v["t2"] = clamp(o[T2], 0, 255); v["t2lev"] = clamp(o[T2LEV], 0, 127)
        v["click"] = clamp(o[CLICK], 0, 127); v["nlev"] = clamp(o[NLEV], 0, 127); v["nhold"] = clamp(o[NHOLD], 0, 255)
        v["ndec"] = clamp(o[NDEC], 0, 127)
        v["flt"] = clamp(o[FMODE], 0, 3) or (if (o[FALL] != 0) 4 else 0) or (clamp(o[RES], 0, 31) shl 3)
        v["fcut"] = clamp(o[FCUT], 0, 127); v["fenv"] = clamp(o[FENV], -128, 127) and 255
        v["hpf"] = clamp(o[HPF], 0, 127); v["chip"] = clamp(o[CHIP], 0, 127); v["drive"] = clamp(o[DRIVE], 0, 127)
        v["level"] = clamp(o[LEVEL], 0, 255)
        return ByteArray(SIZE) { v.getValue(FIELDS[it]).toByte() }
    }

    fun name8(s: String): ByteArray {
        val b = ByteArray(8)
        s.filter { it.code in 0x20..0x7E }.take(8).forEachIndexed { i, c -> b[i] = c.code.toByte() }
        return b
    }

    /** A kit: its name, crush (bits dropped in the low nibble, sample-and-hold - 1 in the high one), the factory kit it started from. */
    class Kit(var name: String, var crush: Int, var src: Int, val sounds: Array<ByteArray>) {
        fun copy() = Kit(name, crush, src, Array(sounds.size) { sounds[it].copyOf() })
    }
}

/** DSYN_LIST reply: factory kit names, user kit names with the kit each started from, whether they are stored. */
data class DsynList(val factory: Int, val user: Int, val stored: Boolean, val names: List<String>,
                    val kits: List<Pair<String, Int>>)

/** DSYN_GET reply; rc 0 ok, 1 no such kit, 2 short data. */
data class DsynGot(val which: Int, val rc: Int, val kit: Dsyn.Kit?)
