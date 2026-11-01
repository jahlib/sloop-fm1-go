package com.sloop.go.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicsTest {
    private val rate = 44100

    /** A 1 kHz sine of [amp] on both channels, [frames] long, interleaved. */
    private fun sine(amp: Float, frames: Int) = FloatArray(frames * 2).also {
        for (i in 0 until frames) {
            val v = (amp * sin(2 * PI * 1000 * i / rate)).toFloat()
            it[2 * i] = v; it[2 * i + 1] = v
        }
    }

    private fun peak(b: FloatArray, from: Int) = (from until b.size).maxOf { abs(b[it]) }

    @Test fun bypassLeavesTheSamplesAlone() {
        val x = sine(0.9f, 4000)
        val y = x.copyOf()
        Dynamics(rate).process(y, 4000, DynConfig())
        assertTrue(x.contentEquals(y))
    }

    @Test fun limiterHoldsTheCeilingWithoutSquashingTheSignal() {
        val ceil = 10f.pow(-6f / 20f)
        val y = sine(1f, rate / 2)
        Dynamics(rate).process(y, rate / 2, DynConfig(limOn = true, ceil10 = -60))
        val last = peak(y, y.size - 8000)
        assertTrue("peak $last", last <= ceil * 1.0001f)
        assertTrue("peak $last", last >= ceil * 0.95f)
        assertTrue(peak(y, 0) <= ceil * 1.0001f)
    }

    @Test fun limiterCatchesALoudBurstInQuietMaterial() {
        val frames = 8000
        val y = FloatArray(frames * 2) { 0.05f * sin(2 * PI * 440 * (it / 2) / rate).toFloat() }
        for (i in 4000 until 4200) { y[2 * i] = 1f; y[2 * i + 1] = -1f }
        Dynamics(rate).process(y, frames, DynConfig(limOn = true, ceil10 = -60))
        assertTrue(peak(y, 0) <= 10f.pow(-6f / 20f) * 1.0001f)
    }

    @Test fun compressorSettlesOnItsRatio() {
        // -6 dB in, threshold -18, 4:1, hard knee: 12 dB over becomes 3 dB over, so 9 dB of reduction
        val y = sine(0.5f, rate)
        val d = Dynamics(rate)
        d.process(y, rate, DynConfig(compOn = true, thr = -18, ratio10 = 40, atk = 10, rel10 = 15, knee = 0, makeup = 0))
        val want = 0.5f * 10f.pow(-9f / 20f)
        assertEquals(want, peak(y, y.size - 8000), want * 0.1f)
        assertTrue(d.reduction < -8f)
    }

    @Test fun makeupGainRaisesTheCompressedSignal() {
        val a = sine(0.5f, rate)
        val b = sine(0.5f, rate)
        Dynamics(rate).process(a, rate, DynConfig(compOn = true, ratio10 = 40, knee = 0))
        Dynamics(rate).process(b, rate, DynConfig(compOn = true, ratio10 = 40, knee = 0, makeup = 6))
        assertEquals(2f, peak(b, b.size - 8000) / peak(a, a.size - 8000), 0.05f)
    }

    @Test fun quietMaterialBelowTheThresholdIsUntouched() {
        val x = sine(0.05f, rate / 2)
        val y = x.copyOf()
        Dynamics(rate).process(y, rate / 2, DynConfig(compOn = true, thr = -18, knee = 0))
        assertEquals(peak(x, 0), peak(y, 0), 0.0005f)
    }
}
