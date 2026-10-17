// SPDX-License-Identifier: GPL-3.0-only
package com.sloop.go.device

import com.sloop.go.proto.Step
import com.sloop.go.proto.StepTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun rawOctaveTransposePreservesTieContinuationAndDynamics() {
        val steps = recordedOverlap().toMutableList()
        steps[1] = steps[1].copy(flags = CONT0 or 1, vel = 73, lvl = 9, rat = 4)
        steps[3] = rest(3).copy(time = StepTime.TIE, flags = 2)
        val shifted = transposeRawSteps(steps, 12)!!
        assertEquals(listOf(72, 76), shifted[1].notes.take(2))
        for (i in steps.indices) {
            assertEquals(steps[i].copy(notes = shifted[i].notes), shifted[i])
        }
        assertEquals(steps, transposeRawSteps(shifted, -12))
        assertEquals(listOf(60, 64), steps[1].notes.take(2)) // the source was not mutated
    }

    @Test fun retryFindsAlreadyAppliedSemitonesInEitherDirection() {
        val steps = recordedOverlap()
        assertEquals(0, rawTransposeProgress(steps, steps, 12))
        assertEquals(5, rawTransposeProgress(steps, transposeRawSteps(steps, 5)!!, 12))
        assertEquals(12, rawTransposeProgress(steps, transposeRawSteps(steps, 12)!!, 12))
        assertEquals(7, rawTransposeProgress(steps, transposeRawSteps(steps, -7)!!, -12))
        assertNull(rawTransposeProgress(steps, transposeRawSteps(steps, -1)!!, 12))
        val changed = transposeRawSteps(steps, 5)!!.toMutableList()
        changed[1] = changed[1].copy(flags = 0)
        assertNull(rawTransposeProgress(steps, changed, 12))
    }

    @Test fun rawTransposeRejectsClippingToKeepUndoExact() {
        assertNull(transposeRawSteps(listOf(note(0, listOf(120))), 12))
        assertNull(transposeRawSteps(listOf(note(0, listOf(7))), -12))
        assertEquals(listOf(127), transposeRawSteps(listOf(note(0, listOf(115))), 12)!![0].notes.take(1))
    }

    @Test fun selectionTransposeKeepsTieAndUnselectedChordSlots() {
        val steps = listOf(
            note(0, listOf(60, 64), 1).copy(lvl = 9, rat = 4, vel = 82),
            rest(1).copy(time = StepTime.TIE),
            note(2, listOf(60)),
            rest(3).copy(time = StepTime.TIE),
            rest(4),
        )
        val shifted = transposeSelectedSteps(steps, steps.size, setOf(noteKey(60, 0)), 12)!!
        assertEquals(listOf(72, 64), shifted[0].notes.take(2))
        assertEquals(steps[0].copy(notes = shifted[0].notes), shifted[0])
        assertEquals(steps.drop(1), shifted.drop(1))
        assertEquals(steps, transposeSelectedSteps(shifted, steps.size, setOf(noteKey(72, 0)), -12))
    }

    @Test fun selectionTransposePreservesSlideChain() {
        val steps = listOf(
            note(0, listOf(60), 2),
            note(1, listOf(60, 64)),
            rest(2),
        )
        val shifted = transposeSelectedSteps(steps, steps.size, setOf(noteKey(60, 0)), 12)!!
        assertEquals(2, shifted[0].flags)
        assertEquals(listOf(72, 64), shifted[1].notes.take(2))
        assertEquals(setOf(PNote(0, 2, 72), PNote(1, 1, 64)), decodeNotes(shifted, steps.size).toSet())
    }

    @Test fun selectionTransposeLeavesOtherContinuationChainsUntouched() {
        val steps = recordedOverlap() + note(5, listOf(67)) + rest(6).copy(time = StepTime.TIE)
        val shifted = transposeSelectedSteps(steps, steps.size, setOf(noteKey(67, 5)), 12)!!
        assertEquals(steps.take(5), shifted.take(5))
        assertEquals(steps[6], shifted[6])
        assertEquals(listOf(79), shifted[5].notes.take(1))
    }

    @Test fun selectionTransposeRejectsCollisionAndUnintendedLegatoMerge() {
        val chord = listOf(note(0, listOf(60, 72)), rest(1))
        assertNull(transposeSelectedSteps(chord, chord.size, setOf(noteKey(60, 0)), 12))
        val legato = listOf(note(0, listOf(60), 2), note(1, listOf(72)), rest(2))
        assertNull(transposeSelectedSteps(legato, legato.size, setOf(noteKey(72, 1)), -12))
    }
}
