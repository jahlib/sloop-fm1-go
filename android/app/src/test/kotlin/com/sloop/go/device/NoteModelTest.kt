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
    private fun tie(i: Int) = rest(i).copy(time = StepTime.TIE)

    private fun apply(steps: List<Step>, notes: List<PNote>): List<Step> =
        steps.toMutableList().also { out ->
            encodeNotes(steps, steps.size, notes)!!.forEach { out[it.index] = it }
        }

    private fun recordedOverlap() = listOf(
        note(0, listOf(60)),
        note(1, listOf(60, 64), CONT0),
        note(2, listOf(60), CONT0),
        rest(3),
        rest(4),
    )

    @Test fun storedNoteBoundariesStayVisibleWithSlideAndContinuationFlags() {
        val steps = listOf(
            note(0, listOf(60, 64), 2),
            note(1, listOf(60, 64), CONT0),
            rest(2),
        )
        assertEquals(setOf(PNote(0, 1, 60), PNote(0, 1, 64), PNote(1, 1, 60), PNote(1, 1, 64)),
            decodeNotes(steps, steps.size).toSet())
    }

    @Test fun unchangedRecordedOverlapDoesNotGenerateWrites() {
        val steps = recordedOverlap()
        val notes = decodeNotes(steps, steps.size)
        assertEquals(setOf(PNote(0, 1, 60), PNote(1, 1, 60), PNote(1, 1, 64), PNote(2, 1, 60)), notes.toSet())
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

    @Test fun slideDoesNotHideAnotherStoredNoteOrExtendItsRectangle() {
        val steps = listOf(note(0, listOf(60), 2), note(1, listOf(60)), rest(2))
        val notes = decodeNotes(steps, steps.size)
        assertEquals(listOf(PNote(0, 1, 60), PNote(1, 1, 60)), notes)
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

    @Test fun selectionTransposeKeepsSlideButOnlyChangesTheSelectedNoteStep() {
        val steps = listOf(
            note(0, listOf(60), 2),
            note(1, listOf(60, 64)),
            rest(2),
        )
        val shifted = transposeSelectedSteps(steps, steps.size, setOf(noteKey(60, 0)), 12)!!
        assertEquals(2, shifted[0].flags)
        assertEquals(listOf(60, 64), shifted[1].notes.take(2))
        assertEquals(setOf(PNote(0, 1, 72), PNote(1, 1, 60), PNote(1, 1, 64)), decodeNotes(shifted, steps.size).toSet())
    }

    @Test fun selectionTransposeLeavesOtherContinuationChainsUntouched() {
        val steps = recordedOverlap() + note(5, listOf(67)) + rest(6).copy(time = StepTime.TIE)
        val shifted = transposeSelectedSteps(steps, steps.size, setOf(noteKey(67, 5)), 12)!!
        assertEquals(steps.take(5), shifted.take(5))
        assertEquals(steps[6], shifted[6])
        assertEquals(listOf(79), shifted[5].notes.take(1))
    }

    @Test fun selectionTransposeRejectsChordCollisionButKeepsAdjacentRepeatedNotes() {
        val chord = listOf(note(0, listOf(60, 72)), rest(1))
        assertNull(transposeSelectedSteps(chord, chord.size, setOf(noteKey(60, 0)), 12))
        val legato = listOf(note(0, listOf(60), 2), note(1, listOf(72)), rest(2))
        val shifted = transposeSelectedSteps(legato, legato.size, setOf(noteKey(72, 1)), -12)!!
        assertEquals(listOf(PNote(0, 1, 60), PNote(1, 1, 60)), decodeNotes(shifted, shifted.size))
    }

    // Reference semantics: original_os/sloop-fm1/web/editor.html, ROLL.place/remove/resize.
    @Test fun placingInsideATailCutsItWithoutCopyingTheOldPitchOrResumingIt() {
        val steps = listOf(note(0, listOf(60)), tie(1), tie(2), tie(3), tie(4), tie(5), rest(6))
        val notes = placePianoNotes(decodeNotes(steps, steps.size), listOf(PNote(2, 2, 64)), steps.size)
        val after = apply(steps, notes)
        assertEquals(listOf(note(0, listOf(60)), tie(1), note(2, listOf(64)), tie(3), rest(4), rest(5), rest(6)), after)
        assertEquals(listOf(PNote(0, 2, 60), PNote(2, 2, 64)), decodeNotes(after, after.size))
        assertTrue(after.all { it.flags and 2 == 0 }) // no invented SLIDE to hide another attack
    }

    @Test fun placingOverTheNextNoteStopsBeforeItEvenOnAnotherPitch() {
        val steps = listOf(rest(0), rest(1), note(2, listOf(67)), tie(3), tie(4), rest(5))
        val notes = placePianoNotes(decodeNotes(steps, steps.size), listOf(PNote(0, 6, 60)), steps.size)
        val after = apply(steps, notes)
        assertEquals(listOf(PNote(0, 2, 60), PNote(2, 3, 67)), decodeNotes(after, after.size))
        assertEquals(steps.drop(2), after.drop(2))
    }

    @Test fun joiningAChordKeepsItsLengthRegardlessOfTheNewNoteLength() {
        val steps = listOf(note(0, listOf(60)), tie(1), tie(2), rest(3), rest(4), rest(5))
        for (length in listOf(1, 6)) {
            val notes = placePianoNotes(decodeNotes(steps, steps.size), listOf(PNote(0, length, 64)), steps.size)
            val after = apply(steps, notes)
            assertEquals(listOf(PNote(0, 3, 60), PNote(0, 3, 64)), decodeNotes(after, after.size))
            assertEquals(steps.drop(1), after.drop(1))
        }
    }

    @Test fun stretchingStopsAtAnyNextNoteAndDoesNotAddOneStepOnContact() {
        val steps = listOf(note(0, listOf(60)), tie(1), rest(2), note(3, listOf(64)), tie(4), tie(5))
        val notes = decodeNotes(steps, steps.size)
        val resized = resizePianoNotes(notes, setOf(noteKey(60, 0)), 1, 20, steps.size)
        val after = apply(steps, resized)
        assertEquals(listOf(PNote(0, 3, 60), PNote(3, 3, 64)), decodeNotes(after, after.size))
        assertEquals(steps.drop(3), after.drop(3))
        assertTrue(encodeNotes(after, after.size,
            resizePianoNotes(resized, setOf(noteKey(60, 0)), 1, 1, after.size))!!.isEmpty())
    }

    @Test fun shorteningOneChordPitchShortensTheSharedTieTail() {
        val steps = listOf(note(0, listOf(60, 64)), tie(1), tie(2), tie(3), rest(4))
        val resized = resizePianoNotes(decodeNotes(steps, steps.size), setOf(noteKey(64, 0)), 1, -2, steps.size)
        val after = apply(steps, resized)
        assertEquals(listOf(note(0, listOf(60, 64)), tie(1), rest(2), rest(3), rest(4)), after)
        assertEquals(listOf(PNote(0, 2, 60), PNote(0, 2, 64)), decodeNotes(after, after.size))
    }

    @Test fun shorteningARecordedChordOnlyWritesItsTieTail() {
        val steps = listOf(note(0, listOf(60)), note(1, listOf(60, 64), CONT0 or 1), tie(2), tie(3), rest(4))
        val resized = resizePianoNotes(decodeNotes(steps, steps.size), setOf(noteKey(64, 1)), 1, -1, steps.size)
        assertEquals(listOf(rest(3)), encodeNotes(steps, steps.size, resized))
        val after = apply(steps, resized)
        assertEquals(steps[1], after[1])
        assertEquals(listOf(PNote(0, 1, 60), PNote(1, 2, 60), PNote(1, 2, 64)), decodeNotes(after, after.size))
    }

    @Test fun groupResizeStopsAtSelectedNeighboursToo() {
        val notes = listOf(PNote(0, 2, 60), PNote(0, 2, 64), PNote(3, 2, 67))
        val keys = setOf(noteKey(60, 0), noteKey(67, 3))
        assertEquals(listOf(PNote(0, 3, 60), PNote(0, 3, 64), PNote(3, 3, 67)),
            resizePianoNotes(notes, keys, 1, 10, 8))
        assertEquals(notes, resizePianoNotes(notes, keys, 1, 0, 8))
    }

    @Test fun leftResizeMovesTheWholeChordWithoutOverlappingThePreviousChord() {
        val notes = listOf(PNote(0, 2, 60), PNote(4, 3, 64), PNote(4, 3, 67))
        assertEquals(listOf(PNote(0, 2, 60), PNote(2, 5, 64), PNote(2, 5, 67)),
            resizePianoNotes(notes, setOf(noteKey(64, 4)), -1, -4, 8))
    }

    @Test fun moveAndCopyIntoATailUseTheSamePlacementRules() {
        val steps = listOf(note(0, listOf(60)), tie(1), tie(2), tie(3), rest(4), note(5, listOf(64)), tie(6), rest(7))
        val notes = decodeNotes(steps, steps.size)
        for (copy in listOf(false, true)) {
            val moved = movePianoNotes(notes, setOf(noteKey(64, 5)), -3, 0, copy, steps.size)
            val after = apply(steps, moved)
            val expected = listOf(PNote(0, 2, 60), PNote(2, 2, 64)) +
                if (copy) listOf(PNote(5, 2, 64)) else emptyList()
            assertEquals(expected, decodeNotes(after, after.size))
            assertEquals(listOf(64), after[2].notes.take(after[2].n))
            assertTrue(after.all { it.flags and 2 == 0 })
        }
    }

    @Test fun deletingAChordPitchLeavesItsOtherPitchesAndPackedDynamics() {
        val head = note(0, listOf(60, 64), 1).copy(vel = 78, lvl = 2 or (3 shl 2), rat = 1 or (2 shl 2))
        val steps = listOf(head, tie(1), tie(2), rest(3))
        val after = apply(steps, decodeNotes(steps, steps.size).filter { it.pitch != 60 })
        assertEquals(note(0, listOf(64), 1).copy(vel = 78, lvl = 3, rat = 2), after[0])
        assertEquals(steps.drop(1), after.drop(1))
        assertEquals(List(4) { rest(it) }, apply(after, emptyList()))
    }

    @Test fun orphanTiesCannotLengthenANewNote() {
        val steps = listOf(rest(0), tie(1), tie(2), rest(3), note(4, listOf(67)))
        assertTrue(encodeNotes(steps, steps.size, decodeNotes(steps, steps.size))!!.isEmpty())
        val after = apply(steps, placePianoNotes(decodeNotes(steps, steps.size), listOf(PNote(0, 1, 60)), steps.size))
        assertEquals(listOf(note(0, listOf(60)), rest(1), rest(2), rest(3), steps[4]), after)
    }

    @Test fun importedOverlapsUseOneNoteAndTieChainPerStart() {
        val steps = List(8) { rest(it) }
        val after = apply(steps, listOf(PNote(0, 6, 60), PNote(2, 3, 64), PNote(2, 1, 67)))
        assertEquals(listOf(PNote(0, 2, 60), PNote(2, 3, 64), PNote(2, 3, 67)), decodeNotes(after, after.size))
        assertEquals(listOf(note(0, listOf(60)), tie(1), note(2, listOf(64, 67)), tie(3), tie(4), rest(5), rest(6), rest(7)), after)
    }

    @Test fun fivePitchesAreRejectedAsOneEditAndRoundTripKeepsStepPositions() {
        val steps = listOf(note(0, listOf(60, 64, 67, 72)), tie(1), rest(2))
        val notes = decodeNotes(steps, steps.size)
        assertNull(encodeNotes(steps, steps.size, placePianoNotes(notes, listOf(PNote(0, 1, 76)), steps.size)))
        val shorter = resizePianoNotes(notes, setOf(noteKey(60, 0)), 1, -1, steps.size)
        val after = apply(steps, shorter)
        assertEquals(steps, apply(after, notes)) // the reverse edit restores the original NOTE + TIE steps
    }
}
