package com.sloop.go.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveStoreTest {
    private val rate = 44100

    /** Writes [frames] frames of a [hz] sine of [amp], the same in both channels, in blocks like the recorder's. */
    private fun fill(s: WaveStore, hz: Double, amp: Float, frames: Int, from: Int = 0) {
        var at = from
        val end = from + frames
        while (at < end) {
            val n = minOf(882, end - at)
            val f = FloatArray(n * 2)
            for (i in 0 until n) { val v = (amp * sin(2 * PI * hz * (at + i) / rate)).toFloat(); f[2 * i] = v; f[2 * i + 1] = v }
            s.write(f, n)
            at += n
        }
    }

    @Test fun anOverviewShowsTheEnvelopeOfWhatWasPlayed() {
        val s = WaveStore()
        fill(s, 220.0, 0.8f, rate * 5)
        val mn = FloatArray(100)
        val mx = FloatArray(100)
        s.columns(s.count.toDouble(), rate * 5.0, 100, mn, mx)
        for (c in 0 until 100) {
            assertEquals(0.8f, mx[c], 0.02f)
            assertEquals(-0.8f, mn[c], 0.02f)
        }
    }

    @Test fun aCloseLookFollowsTheWaveItself() {
        val s = WaveStore()
        fill(s, 100.0, 0.5f, rate)
        // 20 ms is two cycles of 100 Hz across 80 columns: the columns climb and fall with the sine
        val window = rate * 0.02
        val mn = FloatArray(80)
        val mx = FloatArray(80)
        s.columns(s.count.toDouble(), window, 80, mn, mx)
        val mid = (0 until 80).map { (mn[it] + mx[it]) / 2f }
        assertTrue("crests $mid", mid.max() > 0.45f && mid.min() < -0.45f)
        // a column holds the span between neighbouring samples, so the line is unbroken
        for (c in 1 until 80) assertTrue(mx[c] >= mn[c - 1] - 0.2f && mn[c] <= mx[c - 1] + 0.2f)
    }

    @Test fun whatIsNotThereIsNaN() {
        val s = WaveStore()
        fill(s, 440.0, 0.5f, rate)
        val mn = FloatArray(10)
        val mx = FloatArray(10)
        // a window that ends half a second after the newest frame: its second half is empty
        s.columns(s.count + rate / 2.0, rate.toDouble(), 10, mn, mx)
        for (c in 0 until 5) assertTrue(!mn[c].isNaN())
        for (c in 6 until 10) assertTrue(mn[c].isNaN() && mx[c].isNaN())
        // and before the start of the run
        s.columns(rate / 2.0, rate.toDouble(), 10, mn, mx)
        for (c in 0 until 4) assertTrue(mn[c].isNaN())
        assertTrue(!mn[9].isNaN())
    }

    @Test fun oldAudioIsStillThereInTheOverviewAfterTheRawRingMovedOn() {
        val s = WaveStore()
        fill(s, 220.0, 0.6f, rate * 30)
        val mn = FloatArray(20)
        val mx = FloatArray(20)
        // the first second, 30 s ago: past the raw ring (about 11 s) but inside the overview
        s.columns(rate.toDouble(), rate.toDouble(), 20, mn, mx)
        for (c in 0 until 20) assertEquals(0.6f, mx[c], 0.03f)
    }

    @Test fun resetEmptiesIt() {
        val s = WaveStore()
        fill(s, 220.0, 0.6f, 5000)
        s.reset()
        assertEquals(0L, s.count)
        val mn = FloatArray(4)
        val mx = FloatArray(4)
        s.columns(5000.0, 5000.0, 4, mn, mx)
        assertTrue(mn.all { it.isNaN() })
    }
}
