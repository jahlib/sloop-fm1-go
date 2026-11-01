// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The recorder's compressor and limiter, in the whole units their knobs turn in. */
data class DynConfig(
    val compOn: Boolean = false,
    /** Compressor threshold, dB. */
    val thr: Int = -18,
    /** Ratio in tenths: 30 is 3.0 : 1. */
    val ratio10: Int = 30,
    /** Attack, ms. */
    val atk: Int = 10,
    /** Release in 10 ms steps: 15 is 150 ms. */
    val rel10: Int = 15,
    /** Soft knee width, dB. */
    val knee: Int = 6,
    /** Make-up gain after the compression, dB. */
    val makeup: Int = 0,
    val limOn: Boolean = false,
    /** Limiter ceiling in tenths of a dB: -10 is -1.0 dBFS. */
    val ceil10: Int = -10,
    /** Limiter release in 10 ms steps: 10 is 100 ms. */
    val limRel10: Int = 10,
) {
    val any: Boolean get() = compOn || limOn
}

/**
 * A stereo-linked compressor followed by a look-ahead brick-wall limiter, run on the interleaved float stereo the recorder
 * reads, before it is metered and written. The compressor is a feed-forward peak design with a soft knee and attack /
 * release smoothing of the gain reduction in dB. The limiter looks 2 ms ahead: the gain needed per sample is held at its
 * minimum over that window, released slowly, then averaged over the window, which brings the gain down exactly as a
 * peak arrives at the delayed audio and never lets one through. With the limiter on the audio is 2 ms late.
 */
class Dynamics(private val rate: Int) {
    /** The gain reduction of the last block in dB (0 or negative), falling back slowly. */
    @Volatile var reduction = 0f; private set

    private var gr = 0f                                             // compressor gain reduction, dB
    private var env = 0f                                            // compressor level follower, linear
    private var compActive = false
    private var limActive = false

    private val n = max(8, rate * 2 / 1000)                         // look-ahead, frames
    private val gain = FloatArray(n)                                // gain needed per frame
    private val rel = FloatArray(n)                                 // released minimum per frame
    private val delay = FloatArray(n * 2)
    private var pos = 0
    private var relState = 1f
    private var sum = n.toDouble()

    private fun coef(seconds: Float) = if (seconds <= 0f) 0f else exp(-1f / (seconds * rate))

    private fun resetLimiter() {
        gain.fill(1f); rel.fill(1f); delay.fill(0f); pos = 0; relState = 1f; sum = n.toDouble()
    }

    /** Processes [frames] interleaved stereo frames of [buf] in place. */
    fun process(buf: FloatArray, frames: Int, c: DynConfig) {
        if (!c.compOn) { compActive = false; gr = 0f; env = 0f }
        else if (!compActive) { compActive = true; gr = 0f; env = 0f }
        if (!c.limOn) limActive = false
        else if (!limActive) { limActive = true; resetLimiter() }
        if (!c.any) { reduction *= 0.92f; return }

        val thr = c.thr.toFloat()
        val ratio = max(c.ratio10 / 10f, 1f)
        val knee = c.knee.toFloat()
        val atk = coef(c.atk / 1000f)
        val rls = coef(c.rel10 * 10 / 1000f)
        val det = coef(0.01f)                                       // the level follower lets go over 10 ms
        val makeup = 10f.pow(c.makeup / 20f)
        val ceil = 10f.pow(c.ceil10 / 200f)
        val lrel = coef(c.limRel10 * 10 / 1000f)
        var worst = 0f
        for (i in 0 until frames) {
            var l = buf[2 * i]
            var r = buf[2 * i + 1]
            if (c.compOn) {
                env = max(max(abs(l), abs(r)), env * det)
                val lvl = 20f * log10(max(env, 1e-6f))
                val over = lvl - thr
                val out = when {
                    2f * over < -knee -> lvl
                    knee > 0f && 2f * abs(over) <= knee -> lvl + (1f / ratio - 1f) * (over + knee / 2f).let { it * it } / (2f * knee)
                    else -> thr + over / ratio
                }
                val target = out - lvl
                gr = if (target < gr) atk * gr + (1f - atk) * target else rls * gr + (1f - rls) * target
                val g = 10f.pow(gr / 20f) * makeup
                l *= g; r *= g
            }
            var limDb = 0f
            if (c.limOn) {
                val peak = max(abs(l), abs(r))
                gain[pos] = if (peak > ceil) ceil / peak else 1f
                var m = 1f
                for (k in 0 until n) m = min(m, gain[k])
                relState = if (m < relState) m else relState + (m - relState) * (1f - lrel)
                sum += relState - rel[pos]
                rel[pos] = relState
                val g = (sum / n).toFloat()
                delay[2 * pos] = l; delay[2 * pos + 1] = r
                val rd = (pos + 1) % n
                l = (delay[2 * rd] * g).coerceIn(-ceil, ceil)
                r = (delay[2 * rd + 1] * g).coerceIn(-ceil, ceil)
                pos = rd
                limDb = 20f * log10(max(g, 1e-6f))
            }
            buf[2 * i] = l; buf[2 * i + 1] = r
            worst = min(worst, (if (c.compOn) gr else 0f) + limDb)
        }
        reduction = if (worst < reduction) worst else reduction * 0.92f
    }
}
