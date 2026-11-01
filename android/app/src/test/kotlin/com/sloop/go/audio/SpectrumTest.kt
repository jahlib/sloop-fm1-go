package com.sloop.go.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumTest {
    private fun sine(rate: Int, hz: Double, amp: Float, n: Int) =
        FloatArray(n) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun peakPoint(out: FloatArray) = out.indices.maxByOrNull { out[it] }!!

    @Test fun aSineShowsAtItsFrequencyAndLevel() {
        for (rate in listOf(44100, 48000)) for (hz in listOf(100.0, 1000.0, 7000.0)) {
            val sp = Spectrum(rate)
            val out = FloatArray(Spectrum.POINTS)
            sp.analyze(sine(rate, hz, 0.5f, sp.size), out)
            val p = peakPoint(out)
            val f = Spectrum.freq(p)
            assertTrue("$hz Hz read at $f Hz", abs(f - hz) / hz < 0.08)
            // 0.5 is -6.02 dBFS, up to 1.4 dB lost between bins, the tilt adds 3 dB per octave off 1 kHz
            val tilt = 3.0 * Math.log(hz / 1000.0) / Math.log(2.0)
            assertEquals("$hz Hz at $rate", -6.02 + tilt, out[p].toDouble(), 2.0)
        }
    }

    @Test fun silenceStaysBelowEverything() {
        val sp = Spectrum(44100)
        val out = FloatArray(Spectrum.POINTS)
        sp.analyze(FloatArray(sp.size), out)
        assertTrue(out.all { it < -90f })
    }

    @Test fun twoTonesGiveTwoPeaksAndAQuietGapBetween() {
        val sp = Spectrum(44100)
        val a = sine(44100, 200.0, 0.4f, sp.size)
        val b = sine(44100, 3000.0, 0.4f, sp.size)
        val out = FloatArray(Spectrum.POINTS)
        sp.analyze(FloatArray(sp.size) { a[it] + b[it] }, out)
        val low = (0 until Spectrum.POINTS).first { Spectrum.freq(it) >= 200f }
        val high = (0 until Spectrum.POINTS).first { Spectrum.freq(it) >= 3000f }
        val mid = (0 until Spectrum.POINTS).first { Spectrum.freq(it) >= 800f }
        assertTrue(out[low] > out[mid] + 40f)
        assertTrue(out[high] > out[mid] + 40f)
    }
}
