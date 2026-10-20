package com.sloop.go.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LfoTest {
    private fun near(a: Float, b: Float) = assertEquals(b, a, 1e-4f)

    @Test fun shapesHitTheirKeyPoints() {
        near(lfoWave(0, 0.0), 0f); near(lfoWave(0, 0.25), 1f); near(lfoWave(0, 0.75), -1f)
        near(lfoWave(1, 0.0), 0f); near(lfoWave(1, 0.25), 1f); near(lfoWave(1, 0.5), 0f); near(lfoWave(1, 0.75), -1f)
        near(lfoWave(2, 0.0), -1f); near(lfoWave(2, 0.5), 0f)
        near(lfoWave(3, 0.0), 1f); near(lfoWave(3, 0.5), 0f)
        near(lfoWave(4, 0.1), 1f); near(lfoWave(4, 0.6), -1f)
    }

    @Test fun sampleAndHoldIsStepwisePerCycleAndInRange() {
        assertEquals(lfoWave(5, 3.1), lfoWave(5, 3.9), 0f)
        assertTrue((0 until 50).all { lfoWave(5, it + 0.5) in -1f..1f })
        assertTrue((0 until 50).map { lfoWave(5, it + 0.5) }.toSet().size > 10)
    }

    @Test fun rateAndPhaseSetTheCycle() {
        val quarter = LfoCfg(shape = 0, rate = LFO_RATES.indexOfFirst { it.first == "1/4" })
        near(lfoCycle(quarter, 1.0).toFloat(), 1f)
        near(lfoCycle(LfoCfg(rate = LFO_RATE_BAR), 4.0).toFloat(), 1f)
        near(lfoCycle(LfoCfg(rate = LFO_RATES.indexOfFirst { it.first == "3 BAR" }), 12.0).toFloat(), 1f)
        near(lfoCycle(LfoCfg(rate = 6, phase = 2), 0.0).toFloat(), 0.5f)
    }

    @Test fun curveSharpensAndSoftensWithoutMovingThePeaks() {
        near(lfoWave(0, 0.25, 6), 1f); near(lfoWave(0, 0.75, 0), -1f); near(lfoWave(0, 0.0, 6), 0f)
        assertTrue(lfoWave(0, 0.1, 6) < lfoWave(0, 0.1, 3))    // sharper: lower away from the peak
        assertTrue(lfoWave(0, 0.1, 0) > lfoWave(0, 0.1, 3))    // softer: fatter
        assertTrue(lfoWave(0, 0.6, 6) > lfoWave(0, 0.6, 3))    // negative half mirrors
    }
}
