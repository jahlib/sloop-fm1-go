// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

const val LFO_COUNT = 4
val LFO_SHAPES = listOf("SIN", "TRI", "SAW", "RSAW", "SQR", "S&H")

/** One cycle's length in beats (a beat = a quarter note of the device's tempo): note values, triplets and bars. */
val LFO_RATES = listOf("1/32" to 0.125, "1/16T" to 1.0 / 6, "1/16" to 0.25, "1/8T" to 1.0 / 3, "1/8" to 0.5,
    "1/4T" to 2.0 / 3, "1/4" to 1.0, "1/2T" to 4.0 / 3, "1/2" to 2.0, "1 BAR" to 4.0, "2 BAR" to 8.0, "3 BAR" to 12.0,
    "4 BAR" to 16.0, "6 BAR" to 24.0, "8 BAR" to 32.0, "16 BAR" to 64.0)
const val LFO_RATE_BAR = 9
val LFO_PHASES = listOf(0, 90, 180, 270)

/** Steps in a 4/4 bar for each DIV value (params.c N_SDIV). */
private val DIV_PER_BAR = doubleArrayOf(4.0, 8.0, 16.0, 32.0, 12.0, 24.0, 2.0, 1.0, 0.5)

/** [curve] 0..6 (3 = as is): above it the wave is sharper (narrow peaks), below it softer (fat, flat-topped). */
const val LFO_CURVE_NEUTRAL = 3
val LFO_CURVES = listOf("SOFT 3", "SOFT 2", "SOFT 1", "NORM", "SHARP 1", "SHARP 2", "SHARP 3")

data class LfoCfg(val shape: Int = 0, val rate: Int = LFO_RATE_BAR, val phase: Int = 0, val curve: Int = LFO_CURVE_NEUTRAL)

/** A knob an LFO drives: [track] is -1 for a global parameter ([scope] 1). */
data class LfoTarget(val track: Int, val scope: Int, val id: Int)

data class LfoState(
    val cfg: List<LfoCfg> = List(LFO_COUNT) { LfoCfg() },
    /** Per LFO: target -> amount, -100..100 % (100 % at a centred knob sweeps its whole range). */
    val amounts: List<Map<LfoTarget, Int>> = List(LFO_COUNT) { emptyMap() },
    /** The knob's own value under the LFOs; the device gets this plus the LFOs' offset. */
    val bases: Map<LfoTarget, Int> = emptyMap(),
) {
    fun modulated(t: LfoTarget) = amounts.any { t in it }
}

/** [cyc]: position in cycles; the result is -1..1. */
fun lfoWave(shape: Int, cyc: Double, curve: Int = LFO_CURVE_NEUTRAL): Float {
    val v = rawWave(shape, cyc)
    if (curve == LFO_CURVE_NEUTRAL) return v
    val k = Math.pow(2.0, (curve - LFO_CURVE_NEUTRAL) * 0.6)
    return (Math.signum(v) * Math.pow(abs(v).toDouble(), k)).toFloat()
}

private fun rawWave(shape: Int, cyc: Double): Float {
    val c = floor(cyc)
    val ph = cyc - c
    return when (shape) {
        0 -> sin(2 * PI * ph).toFloat()
        1 -> (1.0 - 4.0 * abs(((ph + 0.25) % 1.0) - 0.5)).toFloat()
        2 -> (2 * ph - 1).toFloat()
        3 -> (1 - 2 * ph).toFloat()
        4 -> if (ph < 0.5) 1f else -1f
        else -> {
            var x = c.toLong() + 0x632BE5ABL
            x = (x xor (x ushr 15)) * 0x2C1B3C6DL
            x = (x xor (x ushr 12)) * 0x297A2D39L
            x = x xor (x ushr 15)
            ((x and 0xFFFF).toInt() / 32767.5f) - 1f
        }
    }
}

fun lfoCycle(cfg: LfoCfg, beat: Double): Double =
    beat / LFO_RATES[cfg.rate.coerceIn(0, LFO_RATES.lastIndex)].second +
        LFO_PHASES[cfg.phase.coerceIn(0, LFO_PHASES.lastIndex)] / 360.0

fun lfoValue(cfg: LfoCfg, beat: Double): Float = lfoWave(cfg.shape, lfoCycle(cfg, beat), cfg.curve)

/**
 * Four LFOs that live in the app. They run on the device's tempo and take the playhead's position when play starts,
 * and while a knob is assigned the app writes base + LFO to the device through the normal parameter queue.
 */
class LfoEngine(
    private val ctl: DeviceController,
    private val prefs: SharedPreferences,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<LfoState> = _state.asStateFlow()

    @Volatile private var beat = 0.0
    @Volatile private var beatTime = System.nanoTime() / 1e9
    @Volatile private var bpm = 120.0
    /** Beats per second the phase really advances: the device's tempo times a gentle correction (see [tick]). */
    @Volatile private var rate = 2.0
    private var mult = 1.0
    private var haveRef = false
    private var refKey = 0L
    private var lastP = -1.0
    private var cycles = 0
    private var offset = 0.0
    private var errF = 0.0
    /** The LFOs only move (and write) while the FM-1 plays; stopped, they stand still and the queue stays free. */
    @Volatile private var running = false
    private val sent = java.util.concurrent.ConcurrentHashMap<LfoTarget, Int>()
    private val recent = HashMap<LfoTarget, ArrayDeque<Pair<Int, Double>>>()

    init {
        ctl.onParamPush = ::onDeviceChange
        scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(40)
            }
        }
    }

    /** The tempo position (beats) at [now] seconds. */
    fun beatAt(now: Double): Double = if (running) beat + (now - beatTime) * rate else beat

    fun cycleAt(i: Int, now: Double): Double = lfoCycle(_state.value.cfg[i], beatAt(now))

    /** The sum of the LFOs' pull on [t] now (each LFO's -1..1 times its amount). */
    fun modAt(t: LfoTarget, now: Double): Float {
        val st = _state.value
        val b = beatAt(now)
        var m = 0f
        for (i in 0 until LFO_COUNT) {
            val pct = st.amounts[i][t] ?: continue
            m += lfoValue(st.cfg[i], b) * pct / 100f
        }
        return m
    }

    fun setCfg(i: Int, cfg: LfoCfg) {
        _state.update { s -> s.copy(cfg = s.cfg.toMutableList().also { it[i] = cfg }) }
        save()
    }

    /** Sets LFO [i]'s amount on [t]; the first assignment takes [current] (the knob's value now) as its base. */
    fun setAmount(i: Int, t: LfoTarget, pct: Int, current: Int) {
        _state.update { s ->
            val am = s.amounts.toMutableList()
            am[i] = am[i] + (t to pct.coerceIn(-100, 100))
            s.copy(amounts = am, bases = if (t in s.bases) s.bases else s.bases + (t to current))
        }
        save()
    }

    /** Takes LFO [i] off [t]; when no LFO drives it any more, the knob goes back to its base. */
    fun remove(i: Int, t: LfoTarget) {
        val before = _state.value
        if (t !in before.amounts[i]) return
        var after = before.copy(amounts = before.amounts.toMutableList().also { it[i] = it[i] - t })
        val still = after.modulated(t)
        if (!still) after = after.copy(bases = after.bases - t)
        _state.value = after
        if (!still) {
            sent.remove(t)
            val cs = ctl.state.value
            val reachable = t.scope != 0 || t.track == cs.selectedTrack ||
                ((cs.info?.proto ?: 0) >= 4 && t.id < (cs.info?.pe0 ?: 0))   // as apply(): engine params only while selected
            if (reachable) before.bases[t]?.let { b ->
                ctl.setParam(t.scope, t.id, b, record = false,
                    trackOverride = if (t.scope == 0) t.track else null, glide = false)
            }
        }
        save()
    }

    /**
     * Takes LFO [i] (all of them when null) off every knob it drives, whether the knob is on screen or not (an LFO set on
     * another track keeps driving that track while the page shows this one; an engine change moves ids): the knobs no LFO
     * drives any more go back to their base. Clearing all also drops every leftover base and write record.
     */
    fun removeAll(i: Int? = null) {
        for (k in 0 until LFO_COUNT)
            if (i == null || i == k) for (t in _state.value.amounts[k].keys.toList()) remove(k, t)
        if (i == null) {
            _state.update { s -> s.copy(amounts = List(LFO_COUNT) { emptyMap() }, bases = emptyMap()) }
            sent.clear()
            synchronized(recent) { recent.clear() }
            save()
        }
    }

    /** The user turned a driven knob: that is its new base. */
    fun setBase(t: LfoTarget, v: Int) {
        _state.update { s -> s.copy(bases = s.bases + (t to v)) }
        save()
        if (!running) apply(ctl.state.value)      // stopped: the knob keeps the frozen LFO offset on its new base
    }

    private fun writeValue(t: LfoTarget, v: Int) {
        sent[t] = v
        noteSent(t, v)
        ctl.setParam(t.scope, t.id, v, record = false, trackOverride = if (t.scope == 0) t.track else null,
            glide = false, optimistic = false)
    }

    /**
     * A CHANGED push from the FM-1. The echo of our own write (it may arrive late, after newer ones) is swallowed so it
     * neither moves the base nor repaints the page; a value we never sent is a change made on the FM-1 and becomes the
     * knob's new base.
     */
    private fun onDeviceChange(scope: Int, id: Int, value: Int): Boolean {
        val t = LfoTarget(if (scope == 0) ctl.state.value.selectedTrack else -1, scope, id)
        if (!_state.value.modulated(t)) return false
        val now = System.nanoTime() / 1e9
        val echo = synchronized(recent) { recent[t]?.any { now - it.second < 3.0 && it.first == value } == true }
        if (echo) return true
        _state.update { s -> s.copy(bases = s.bases + (t to value)) }
        return false
    }

    private fun noteSent(t: LfoTarget, v: Int) {
        val now = System.nanoTime() / 1e9
        synchronized(recent) {
            val q = recent.getOrPut(t) { ArrayDeque() }
            q.addLast(v to now)
            while (q.size > 32 || (q.isNotEmpty() && now - q.first().second > 3.0)) q.removeFirst()
        }
    }

    /**
     * The phase advances at the device's BPM and never jumps. To stay in step with the FM-1 (its BPM is a whole number and
     * its clock drifts a little) the playhead is followed as a reference: the offset between the LFO and the playhead is
     * taken when play starts, so the LFO carries on from where it was. Any drift from that offset, low-pass filtered
     * (the polled playhead is quantised to steps), speeds the LFO up or slows it down by at most 4 %, changing the speed
     * only a hair per tick. A real jump of the playhead just takes a new offset.
     */
    private fun tick() {
        val now = System.nanoTime() / 1e9
        val s = ctl.state.value
        val lv = ctl.transport.value
        lv?.bpm?.takeIf { it > 0 }?.let { bpm = it.toDouble() }
        if (!(s.playing || ctl.clock.playing)) {
            running = false      // stopped: everything stands exactly where it is, the phase included
            haveRef = false; lastP = -1.0
            return
        }
        if (!running) beatTime = now      // play again: carry on from the same phase, no jump across the pause
        beat = beatAt(now)
        beatTime = now
        running = true

        var target = 1.0
        val track = s.selectedTrack
        val pos = if (ctl.clock.playing) ctl.clock.position(track, now) else -1f
        if (pos >= 0f) {
            val divIdx = s.pdesc.indexOfFirst { it?.label == "DIV" }
            val div = if (divIdx >= 0) s.paramValue(divIdx) else 2
            val perStep = 4.0 / DIV_PER_BAR.getOrElse(div) { 16.0 }
            val len = (lv?.len?.getOrNull(track) ?: 0).takeIf { it > 0 } ?: s.patternLength
            val total = len * perStep
            val key = track * 100000L + len * 100L + div
            if (key != refKey) { refKey = key; haveRef = false; lastP = -1.0; cycles = 0 }
            val p = pos * perStep
            if (lastP >= 0 && p < lastP - total / 2) cycles++
            lastP = p
            val u = p + cycles * total
            if (!haveRef) { offset = beat - u; errF = 0.0; haveRef = true }
            else {
                val e = u + offset - beat
                if (abs(e) > 1.0) { offset = beat - u; errF = 0.0 }       // the playhead jumped: new reference, no phase jump
                else {
                    errF += (e - errF) * 0.05
                    target = 1.0 + (errF * 0.15).coerceIn(-0.04, 0.04)
                }
            }
        } else haveRef = false
        mult += (target - mult).coerceIn(-0.0005, 0.0005)     // ease the speed, never switch it
        rate = bpm / 60.0 * mult
        apply(s)
    }

    private fun apply(s: DeviceState) {
        if (s.link != Link.READY || s.dump == null) return
        val st = _state.value
        val proto = s.info?.proto ?: 0
        val pe0 = s.info?.pe0 ?: 0
        val mods = HashMap<LfoTarget, Float>()
        for (i in 0 until LFO_COUNT) {
            if (st.amounts[i].isEmpty()) continue
            val w = lfoValue(st.cfg[i], beat)
            for ((t, pct) in st.amounts[i]) mods[t] = (mods[t] ?: 0f) + w * pct / 100f
        }
        for ((t, m) in mods) {
            // other tracks' parameters are written by number; engine-specific ones only while that track is selected
            if (t.scope == 0 && t.track != s.selectedTrack && (proto < 4 || t.id >= pe0)) continue
            val base = st.bases[t] ?: continue
            val desc = (if (t.scope == 0) s.pdesc else s.gdesc).getOrNull(t.id) ?: continue
            val range = desc.max - desc.min
            // The bigger the LFO's reach on this knob, the coarser the pieces it is sent in: a tiny swing goes out
            // value by value, a reach of about 6 in steps of 2, 12 in 4, 24 in 8, 36 in 12 (about 3 pieces per
            // half swing), so a wide LFO does not flood the queue. The LFO's speed counts too: that table is for
            // a 1 BAR cycle at 120 BPM; a slower LFO (4 BAR) sweeps the same reach over more time, so it can go
            // in finer pieces, a faster one needs coarser ones. Pieces never exceed 12.
            val cps = bpm / 60.0
            var reach = 0.0
            for (i in 0 until LFO_COUNT) {
                val a = abs(st.amounts[i][t] ?: continue)
                val speed = cps / LFO_RATES[st.cfg[i].rate.coerceIn(0, LFO_RATES.lastIndex)].second / 0.5
                reach += a / 100.0 * range * 0.5 * speed
            }
            val piece = (reach / 3.0).roundToInt().coerceIn(1, 12)
            val off = (m * range * 0.5f).roundToInt()
            val v = (base + Math.round(off.toDouble() / piece).toInt() * piece).coerceIn(desc.min, desc.max)
            if (sent[t] == v) continue
            writeValue(t, v)
        }
    }

    private fun load(): LfoState {
        val cfg = MutableList(LFO_COUNT) { LfoCfg() }
        prefs.getString("cfg2", null)?.split(";")?.forEachIndexed { i, p ->
            val a = p.split(",").mapNotNull { it.toIntOrNull() }
            if (i < LFO_COUNT && a.size == 4) cfg[i] = LfoCfg(a[0].coerceIn(0, LFO_SHAPES.lastIndex),
                a[1].coerceIn(0, LFO_RATES.lastIndex), a[2].coerceIn(0, LFO_PHASES.lastIndex),
                a[3].coerceIn(0, LFO_CURVES.lastIndex))
        }
        val am = List(LFO_COUNT) { HashMap<LfoTarget, Int>() }
        prefs.getString("amt", null)?.split(";")?.forEach { p ->
            val a = p.split(":").mapNotNull { it.toIntOrNull() }
            if (a.size == 5 && a[0] in 0 until LFO_COUNT) am[a[0]][LfoTarget(a[1], a[2], a[3])] = a[4].coerceIn(-100, 100)
        }
        val bases = HashMap<LfoTarget, Int>()
        prefs.getString("base", null)?.split(";")?.forEach { p ->
            val a = p.split(":").mapNotNull { it.toIntOrNull() }
            if (a.size == 4) bases[LfoTarget(a[0], a[1], a[2])] = a[3]
        }
        return LfoState(cfg, am, bases.filterKeys { k -> am.any { k in it } })
    }

    private fun save() {
        val s = _state.value
        prefs.edit()
            .putString("cfg2", s.cfg.joinToString(";") { "${it.shape},${it.rate},${it.phase},${it.curve}" })
            .putString("amt", s.amounts.withIndex().flatMap { (i, m) ->
                m.map { (t, p) -> "$i:${t.track}:${t.scope}:${t.id}:$p" }
            }.joinToString(";"))
            .putString("base", s.bases.map { (t, b) -> "${t.track}:${t.scope}:${t.id}:$b" }.joinToString(";"))
            .apply()
    }
}
