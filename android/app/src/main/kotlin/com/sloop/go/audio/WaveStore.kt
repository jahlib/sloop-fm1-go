// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.audio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * What the recorder page's waveform draws from: the recent audio mixed to mono, kept two ways. The last [RAW] frames
 * (about 11 s) as they came, for a close look at the wave itself, and the lowest and highest sample of every [BUCKET]
 * frames over the last [BK] of them (about 95 s), for the overview. [columns] reads either as a row of min / max pairs,
 * whatever span of time it is asked for. One thread writes ([write]); the page reads, a race at the newest frames only
 * costs a column drawn a little early.
 */
class WaveStore {
    private val raw = FloatArray(RAW)
    private val mn = FloatArray(BK)
    private val mx = FloatArray(BK)
    private var cMin = Float.MAX_VALUE
    private var cMax = -Float.MAX_VALUE
    private var cFill = 0

    /** Frames written so far. */
    @Volatile var count = 0L; private set
    @Volatile private var buckets = 0L

    fun reset() {
        count = 0; buckets = 0; cFill = 0; cMin = Float.MAX_VALUE; cMax = -Float.MAX_VALUE
    }

    /** Appends [frames] interleaved stereo frames of [f], mixed to mono. */
    fun write(f: FloatArray, frames: Int) {
        var n = count
        var b = buckets
        for (i in 0 until frames) {
            val v = (f[2 * i] + f[2 * i + 1]) * 0.5f
            raw[(n % RAW).toInt()] = v
            n++
            if (v < cMin) cMin = v
            if (v > cMax) cMax = v
            if (++cFill == BUCKET) {
                mn[(b % BK).toInt()] = cMin; mx[(b % BK).toInt()] = cMax
                b++
                cFill = 0; cMin = Float.MAX_VALUE; cMax = -Float.MAX_VALUE
            }
        }
        buckets = b
        count = n
    }

    /**
     * Fills [outMin] / [outMax] with the lowest and highest sample of each of [cols] equal slices of the [window] frames
     * that end at frame [end] (frames are counted from the start of the run). A slice with no audio, before the start,
     * after the newest frame or older than what is kept, is NaN in both.
     */
    fun columns(end: Double, window: Double, cols: Int, outMin: FloatArray, outMax: FloatArray) =
        columnsAt(end - window, window / cols, cols, outMin, outMax)

    /**
     * The same with the caller choosing the slice layout: [start] is the first frame of slice 0 and each slice is [per]
     * frames wide. The page aligns these to bucket boundaries, so a column's span stays the same while it scrolls
     * across the screen and its value does not recompute every frame.
     */
    fun columnsAt(start: Double, per: Double, cols: Int, outMin: FloatArray, outMax: FloatArray) {
        val live = count
        val liveB = buckets
        for (c in 0 until cols) {
            val a = start + c * per
            val b = a + per
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            if (per < BUCKET * 2 && a >= live - RAW) {
                // a close look: the samples themselves, one before the slice so the line goes on from the last column
                val first = max(floor(a).toLong() - 1, max(0L, live - RAW))
                val last = min(ceil(b).toLong(), live - 1)
                for (i in first..last) {
                    val v = raw[(i % RAW).toInt()]
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
            } else {
                val first = max(floor(a / BUCKET).toLong(), max(0L, liveB - BK))
                val last = min(floor(b / BUCKET).toLong(), liveB - 1)
                for (k in first..last) {
                    val i = (k % BK).toInt()
                    if (mn[i] < lo) lo = mn[i]
                    if (mx[i] > hi) hi = mx[i]
                }
            }
            if (lo > hi) { outMin[c] = Float.NaN; outMax[c] = Float.NaN } else { outMin[c] = lo; outMax[c] = hi }
        }
    }

    companion object {
        const val RAW = 1 shl 19
        const val BUCKET = 64
        const val BK = 1 shl 16
    }
}
