// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

import com.sloop.go.proto.Live

/**
 * A playhead that runs on its own between the FM-1's answers: every track's step moves at a steady rate, so the
 * moments the polled step changes are fitted with a line (steps against time); the line says where the playhead is
 * now. Each new answer refines it, and it starts over when the pattern, the tempo or the rate jumps, and on stop.
 *
 * A step change is only seen at the next poll, somewhere between the previous answer and this one: the midpoint of the
 * two is taken as its moment. Missing answers (a busy link) do not reset the line: it keeps running until an answer
 * really disagrees with it. What is drawn ([position] with smooth) follows the line by speeding up or slowing down,
 * never stepping back, unless the device really jumped.
 */
class PlayClock {
    private class Track {
        val ts = DoubleArray(N)
        val ss = DoubleArray(N)
        var n = 0
        var last = -1
        var cycles = 0
        var len = 0
        var rate = 0.0
        var a = 0.0
        var b = 0.0
        var raw = 0
        var disp = Double.NaN      // the drawn playhead, unwrapped (steps)
        var dispAt = 0.0
        fun clear() { n = 0; last = -1; cycles = 0; rate = 0.0; disp = Double.NaN }
    }

    private val tr = Array(4) { Track() }
    private var bpm = 0
    private var prevAnswer = 0.0
    @Volatile var playing = false
        private set

    @Synchronized fun reset() { playing = false; tr.forEach { it.clear() }; bpm = 0; prevAnswer = 0.0 }

    /** An answer from the FM-1, received at [now] (seconds). */
    @Synchronized fun feed(lv: Live, now: Double) {
        if (!lv.playing) { reset(); return }
        playing = true
        if (lv.bpm != bpm) { tr.forEach { it.clear() }; bpm = lv.bpm }
        val gap = if (prevAnswer > 0.0) (now - prevAnswer).coerceIn(0.0, MAX_GAP) else 0.0
        prevAnswer = now
        val t = now - gap / 2           // the change happened between the previous answer and this one
        for (i in lv.step.indices.take(tr.size)) {
            val x = tr[i]
            val s = lv.step[i]
            val len = lv.len[i].coerceAtLeast(1)
            if (len != x.len) { x.clear(); x.len = len }
            x.raw = s
            if (s == x.last) continue
            if (x.last >= 0 && s < x.last) x.cycles++
            x.last = s
            var u = s + x.cycles * len.toDouble()
            if (x.rate > 0.0 && Math.abs(u - (x.a + x.b * t)) > JUMP) {    // off the line: a jump or a new rate
                x.n = 0; x.cycles = 0; x.rate = 0.0; u = s.toDouble()
            }
            if (x.n == N) {
                System.arraycopy(x.ts, 1, x.ts, 0, N - 1); System.arraycopy(x.ss, 1, x.ss, 0, N - 1); x.n--
            }
            x.ts[x.n] = t; x.ss[x.n] = u; x.n++
            fit(x)
        }
    }

    private fun fit(x: Track) {
        if (x.n < MIN) { x.rate = 0.0; return }
        var mt = 0.0; var ms = 0.0
        for (i in 0 until x.n) { mt += x.ts[i]; ms += x.ss[i] }
        mt /= x.n; ms /= x.n
        var num = 0.0; var den = 0.0
        for (i in 0 until x.n) { val dt = x.ts[i] - mt; num += dt * (x.ss[i] - ms); den += dt * dt }
        if (den <= 0.0 || num <= 0.0) { x.rate = 0.0; return }
        x.rate = num / den
        x.b = x.rate
        x.a = ms - x.rate * mt
    }

    /**
     * The playhead of [track] at [now] (seconds) in steps, fractions included; -1 when stopped. [smooth]: the drawn
     * playhead (for one caller, the screen); without it the fitted line itself.
     */
    @Synchronized fun position(track: Int, now: Double, smooth: Boolean = false): Float {
        val x = tr.getOrNull(track) ?: return -1f
        if (!playing || x.last < 0 || x.len <= 0) return -1f
        val target = if (x.rate > 0.0) x.a + x.b * (now + LEAD) else x.last + x.cycles * x.len.toDouble()
        val u = if (!smooth || x.rate <= 0.0) { x.disp = Double.NaN; target } else {
            val d = x.disp
            val dt = (now - x.dispAt).coerceIn(0.0, 0.1)
            x.disp = if (d.isNaN() || Math.abs(target - d) > JUMP) target
                else d + x.b * dt * (1.0 + ((target - d) * GAIN).coerceIn(-0.6, 0.6))   // catch up / ease off, never back
            x.dispAt = now
            x.disp
        }
        val m = u % x.len
        return (if (m < 0) m + x.len else m).toFloat()
    }

    private companion object {
        const val N = 12
        const val MIN = 3
        const val LEAD = 0.02      // an answer leaves the FM-1 about this much before it is read here
        const val MAX_GAP = 0.4    // a longer silence between answers: the change is not placed more than 0.2 s back
        const val JUMP = 1.5       // steps off the line that mean a real jump, not timing noise
        const val GAIN = 2.0       // how hard the drawn playhead leans toward the line (per step of error)
    }
}
