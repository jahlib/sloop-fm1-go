package com.sloop.go.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The algorithm graphs against the DX7 manual's charts (numbers here are 0-based: 0 = algorithm 1). */
class Fm6AlgTest {
    @Test fun algorithmOne() {
        val g = Fm6.alg(0)
        assertEquals(listOf(1, 3), g.carriers)
        assertEquals(listOf(2), g.mods[1]); assertEquals(listOf(4), g.mods[3])
        assertEquals(listOf(5), g.mods[4]); assertEquals(listOf(6), g.mods[5])
        assertEquals(6, g.fbFrom); assertEquals(6, g.fbTo)
        val l = Fm6.algLayout(0)
        assertEquals(2f, l.cols); assertEquals(4, l.rows)
        assertEquals(0, l.row[1]); assertEquals(1, l.row[2]); assertEquals(3, l.row[6])
        assertEquals(l.x[3], l.x[6])
    }

    @Test fun algorithmFourLoopsBackFromFourToSix() {
        val g = Fm6.alg(3)
        assertEquals(listOf(1, 4), g.carriers)
        assertEquals(4, g.fbFrom); assertEquals(6, g.fbTo)
    }

    @Test fun algorithmSixteenHasThreeModulatorsOnOne() {
        val g = Fm6.alg(15)
        assertEquals(listOf(1), g.carriers)
        assertEquals(listOf(2, 3, 5), g.mods[1]); assertEquals(listOf(4), g.mods[3]); assertEquals(listOf(6), g.mods[5])
        val l = Fm6.algLayout(15)
        assertEquals(1f, l.x[1])                                    // centred over 2, 3, 5
    }

    @Test fun algorithmNineteenSharesOperatorSix() {
        val g = Fm6.alg(18)
        assertEquals(listOf(1, 4, 5), g.carriers)
        assertEquals(listOf(4, 5), g.targets(6))
        val l = Fm6.algLayout(18)
        assertEquals((l.x.getValue(4) + l.x.getValue(5)) / 2, l.x[6])
    }

    @Test fun algorithmThirtyTwoIsSixCarriers() {
        val g = Fm6.alg(31)
        assertEquals((1..6).toList(), g.carriers)
        assertTrue(g.mods.isEmpty())
        assertEquals(6, g.fbFrom); assertEquals(6, g.fbTo)
        assertEquals(6f, Fm6.algLayout(31).cols)
    }

    @Test fun everyAlgorithmPlacesAllSixWithoutOverlap() {
        for (a in 0 until 32) {
            val l = Fm6.algLayout(a)
            assertEquals("alg ${a + 1}", (1..6).toSet(), l.x.keys)
            assertEquals("alg ${a + 1} carriers", Fm6.carriers(a), l.alg.carriers)
            for (p in 1..6) for (q in p + 1..6)
                if (l.row[p] == l.row[q]) assertTrue("alg ${a + 1}: OP$p / OP$q", kotlin.math.abs(l.x.getValue(p) - l.x.getValue(q)) >= 0.5f)
            for ((t, ms) in l.alg.mods) for (m in ms) assertTrue("alg ${a + 1}: OP$m above OP$t", l.row.getValue(m) > l.row.getValue(t))
        }
    }
}
