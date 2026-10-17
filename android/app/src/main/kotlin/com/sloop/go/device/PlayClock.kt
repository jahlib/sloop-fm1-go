// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

import com.sloop.go.proto.Live

/**
 * A playhead that runs on its own between the FM-1's answers: every track's step moves at a steady rate, so the
 * moments the polled step changes are fitted with a line (steps against time); the line says where the playhead is
 * now. Each new answer refines it, and it starts over when the pattern, the tempo or the rate jumps, and on stop.
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
        fun clear() { n = 0; last = -1; cycles = 0; rate = 0.0 }
    }

    private val tr = Array(4) { Track() }
    private var bpm = 0
    @Volatile var playing = false
        private set

    @Synchronized fun reset() { playing = false; tr.forEach { it.clear() }; bpm = 0 }

    /** An answer from the FM-1, received at [now] (seconds). */
    @Synchronized fun feed(lv: Live, now: Double) {
        if (!lv.playing) { reset(); return }
        playing = true
        if (lv.bpm != bpm) { tr.forEach { it.clear() }; bpm = lv.bpm }
        for (i in lv.step.indices.take(tr.size)) {
            val x = tr[i]
            val s = lv.step[i]
            val len = lv.len[i].coerceAtLeast(1)
            if (len != x.len) { x.clear(); x.len = len }
            x.raw = s
            if (s == x.last) continue
            if (x.last >= 0 && s < x.last) x.cycles++
            x.last = s
            val u = s + x.cycles * len.toDouble()
            if (x.n == N) {
                System.arraycopy(x.ts, 1, x.ts, 0, N - 1); System.arraycopy(x.ss, 1, x.ss, 0, N - 1); x.n--
            }
            x.ts[x.n] = now; x.ss[x.n] = u; x.n++
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

    /** The playhead of [track] at [now] (seconds) in steps, fractions included; -1 when stopped. */
    @Synchronized fun position(track: Int, now: Double): Float {
        val x = tr.getOrNull(track) ?: return -1f
        if (!playing || x.last < 0 || x.len <= 0) return -1f
        if (x.rate <= 0.0) return x.raw.toFloat()
        val p = x.a + x.b * (now + LEAD)
        val since = p - (x.last + x.cycles * x.len.toDouble())
        if (since < -0.6 || since > 2.2) {            // off the line (a new rate, a jump): trust the device until it refits
            val l = x.len; x.clear(); x.len = l
            return x.raw.toFloat()
        }
        val m = p % x.len
        return (if (m < 0) m + x.len else m).toFloat()
    }

    /** The fitted speed of [track] in steps per second (0 while unknown) and its pattern length, for display smoothing. */
    @Synchronized fun speed(track: Int): Double = tr.getOrNull(track)?.takeIf { playing }?.rate ?: 0.0
    @Synchronized fun length(track: Int): Int = tr.getOrNull(track)?.len ?: 0

    private companion object {
        const val N = 10
        const val MIN = 3
        const val LEAD = 0.07      // an answer comes about this much after the step it reports changed (polling + link)
    }
}
