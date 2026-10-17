// SPDX-License-Identifier: GPL-3.0-only
package com.sloop.go.device

import com.sloop.go.proto.Step
import com.sloop.go.proto.StepTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteModelTest {
    private fun note(i: Int, pitches: List<Int>, flags: Int = 0) =
        Step(i, pitches.size, IntArray(4) { pitches.getOrElse(it) { 0 } }, StepTime.NOTE, flags, 96)

    private fun rest(i: Int) = Step(i, 0, IntArray(4), StepTime.REST, 0, 0)

    private fun recordedOverlap() = listOf(
        note(0, listOf(60)),
        note(1, listOf(60, 64), CONT0),
        note(2, listOf(60), CONT0),
        rest(3),
        rest(4),
    )

    @Test fun continuationFlagsTakePriorityOverIncomingSlide() {
        val steps = listOf(
            note(0, listOf(60, 64), 2),
            note(1, listOf(60, 64), CONT0),
            rest(2),
        )
        assertEquals(setOf(PNote(0, 2, 60), PNote(0, 1, 64), PNote(1, 1, 64)),
            decodeNotes(steps, steps.size).toSet())
    }

    @Test fun unchangedRecordedOverlapDoesNotGenerateWrites() {
        val steps = recordedOverlap()
        val notes = decodeNotes(steps, steps.size)
        assertEquals(setOf(PNote(0, 3, 60), PNote(1, 1, 64)), notes.toSet())
        assertTrue(encodeNotes(steps, steps.size, notes)!!.isEmpty())
    }

    @Test fun editingElsewhereKeepsRawContinuationSteps() {
        val steps = recordedOverlap()
        val writes = encodeNotes(steps, steps.size,
            decodeNotes(steps, steps.size) + PNote(4, 1, 67))!!
        assertEquals(listOf(4), writes.map { it.index })
        val after = steps.toMutableList().also { list -> writes.forEach { list[it.index] = it } }
        assertEquals(steps.take(4), after.take(4))
        assertEquals(PNote(4, 1, 67), decodeNotes(after, after.size).single { it.pitch == 67 })
    }

    @Test fun ordinarySlideStillJoinsTheSamePitch() {
        val steps = listOf(note(0, listOf(60), 2), note(1, listOf(60)), rest(2))
        val notes = decodeNotes(steps, steps.size)
        assertEquals(listOf(PNote(0, 2, 60)), notes)
        assertTrue(encodeNotes(steps, steps.size, notes)!!.isEmpty())
    }

    @Test fun repeatedNoteWithoutSlideStaysTwoAttacks() {
        val steps = listOf(note(0, listOf(60)), note(1, listOf(60)), rest(2))
        assertEquals(listOf(PNote(0, 1, 60), PNote(1, 1, 60)), decodeNotes(steps, steps.size))
        assertFalse(steps[0].hasContinuations())
    }
}
