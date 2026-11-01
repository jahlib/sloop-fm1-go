// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A spectrum analyzer for the recorder page: a Hann-windowed FFT of the latest [size] samples, turned into [POINTS]
 * levels in dBFS spaced evenly on a log frequency axis from [F_MIN] to [F_MAX], the way an EQ's analyzer draws it. A
 * sine of amplitude A reads A (within the 1.4 dB a Hann window can lose between two bins). Bands wider than a bin take
 * their loudest bin, narrower ones interpolate between the bins, so low notes and high hiss both draw cleanly. A gentle
 * tilt of [TILT] dB per octave (0 dB at 1 kHz) lifts the highs so ordinary music lies flat rather than sloping down.
 */
class Spectrum(private val rate: Int, val size: Int = 4096) {
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }
    private val gain = 2f / window.sum()
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val cosT = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { (-sin(2 * PI * it / size)).toFloat() }
    private val rev = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val db = FloatArray(size / 2 + 1)

    // per display point: the bin range it covers, or the fractional bin it sits on
    private val lo = IntArray(POINTS)
    private val hi = IntArray(POINTS)
    private val at = FloatArray(POINTS)
    private val tilt = FloatArray(POINTS)

    init {
        require(size >= 64 && size and (size - 1) == 0) { "size must be a power of two" }
        val step = (F_MAX / F_MIN).pow(0.5f / (POINTS - 1))
        for (p in 0 until POINTS) {
            val f = freq(p)
            val bin = f * size / rate
            at[p] = bin.coerceIn(0f, size / 2f - 1f)
            lo[p] = ceilInt(f / step * size / rate)
            hi[p] = floor(f * step * size / rate).toInt().coerceAtMost(size / 2)
            tilt[p] = TILT * ln(f / 1000f) / ln(2f)
        }
    }

    private fun ceilInt(x: Float) = kotlin.math.ceil(x).toInt()

    /** Fills [out] ([POINTS] long) with the levels of the last [size] samples of [x], in dBFS. */
    fun analyze(x: FloatArray, out: FloatArray) {
        for (i in 0 until size) { re[rev[i]] = x[i] * window[i]; im[rev[i]] = 0f }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                for (j in 0 until half) {
                    val wr = cosT[j * step]
                    val wi = sinT[j * step]
                    val a = i + j
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                i += len
            }
            len *= 2
        }
        for (k in 0..size / 2) db[k] = 20f * log10(max(sqrt(re[k] * re[k] + im[k] * im[k]) * gain, 1e-9f))
        for (p in 0 until POINTS) {
            val v = if (hi[p] - lo[p] >= 1) {
                var m = -200f
                for (k in lo[p]..hi[p]) m = max(m, db[k])
                m
            } else {
                val k0 = at[p].toInt()
                val fr = at[p] - k0
                db[k0] + (db[k0 + 1] - db[k0]) * fr
            }
            out[p] = max(v + tilt[p], FLOOR)
        }
    }

    companion object {
        const val POINTS = 192
        const val F_MIN = 20f
        const val F_MAX = 20000f
        const val TILT = 3f
        const val FLOOR = -120f

        /** The frequency of display point [p], Hz. */
        fun freq(p: Int): Float = F_MIN * (F_MAX / F_MIN).pow(p.toFloat() / (POINTS - 1))
    }
}
