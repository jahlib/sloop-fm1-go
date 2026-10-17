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

    private fun apply(steps: List<Step>, notes: List<PNote>, poly: Boolean = false): List<Step> =
        steps.toMutableList().also { out ->
            encodeNotes(steps, steps.size, notes, poly)!!.forEach { out[it.index] = it }
        }

    private fun recordedOverlap() = listOf(
        note(0, listOf(60)),
        note(1, listOf(60, 64), CONT0),
        note(2, listOf(60), CONT0),
        rest(3),
        rest(4),
    )

    @Test fun heldContSlotIsDrawnAsReleasedAndOtherSlotsStart() {
        val steps = listOf(
            note(0, listOf(60, 64), 2),
            note(1, listOf(60, 64), CONT0),
            rest(2),
        )
        assertEquals(setOf(PNote(0, 1, 60), PNote(0, 1, 64), PNote(1, 1, 64)),
            decodeNotes(steps, steps.size).toSet())
    }

    @Test fun unchangedRecordedOverlapDoesNotGenerateWrites() {
        val steps = recordedOverlap()
        val notes = decodeNotes(steps, steps.size)
        assertEquals(setOf(PNote(0, 1, 60), PNote(1, 1, 64)), notes.toSet())
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
        val notes = placePianoNotes(decodeNotes(steps, steps.size), listOf(PNote(2, 2, 64)), steps.size, false)
        val after = apply(steps, notes)
        assertEquals(listOf(note(0, listOf(60)), tie(1), note(2, listOf(64)), tie(3), rest(4), rest(5), rest(6)), after)
        assertEquals(listOf(PNote(0, 2, 60), PNote(2, 2, 64)), decodeNotes(after, after.size))
        assertTrue(after.all { it.flags and 2 == 0 }) // no invented SLIDE to hide another attack
    }

    @Test fun placingOverTheNextNoteStopsBeforeItEvenOnAnotherPitch() {
        val steps = listOf(rest(0), rest(1), note(2, listOf(67)), tie(3), tie(4), rest(5))
        val notes = placePianoNotes(decodeNotes(steps, steps.size), listOf(PNote(0, 6, 60)), steps.size, false)
        val after = apply(steps, notes)
        assertEquals(listOf(PNote(0, 2, 60), PNote(2, 3, 67)), decodeNotes(after, after.size))
        assertEquals(steps.drop(2), after.drop(2))
    }

    @Test fun joiningAChordKeepsItsLengthRegardlessOfTheNewNoteLength() {
        val steps = listOf(note(0, listOf(60)), tie(1), tie(2), rest(3), rest(4), rest(5))
        for (length in listOf(1, 6)) {
            val notes = placePianoNotes(decodeNotes(steps, steps.size), listOf(PNote(0, length, 64)), steps.size, true)
            val after = apply(steps, notes, true)
            assertEquals(listOf(PNote(0, 3, 60), PNote(0, 3, 64)), decodeNotes(after, after.size, true))
            assertEquals(steps.drop(1), after.drop(1))
        }
    }

    @Test fun stretchingStopsAtAnyNextNoteAndDoesNotAddOneStepOnContact() {
        val steps = listOf(note(0, listOf(60)), tie(1), rest(2), note(3, listOf(64)), tie(4), tie(5))
        val notes = decodeNotes(steps, steps.size)
        val resized = resizePianoNotes(notes, setOf(noteKey(60, 0)), 1, 20, steps.size, false)
        val after = apply(steps, resized)
        assertEquals(listOf(PNote(0, 3, 60), PNote(3, 3, 64)), decodeNotes(after, after.size))
        assertEquals(steps.drop(3), after.drop(3))
        assertTrue(encodeNotes(after, after.size,
            resizePianoNotes(resized, setOf(noteKey(60, 0)), 1, 1, after.size, false))!!.isEmpty())
    }

    @Test fun shorteningOneChordPitchShortensTheSharedTieTail() {
        val steps = listOf(note(0, listOf(60, 64)), tie(1), tie(2), tie(3), rest(4))
        val resized = resizePianoNotes(decodeNotes(steps, steps.size), setOf(noteKey(64, 0)), 1, -2, steps.size, false)
        val after = apply(steps, resized)
        assertEquals(listOf(note(0, listOf(60, 64)), tie(1), rest(2), rest(3), rest(4)), after)
        assertEquals(listOf(PNote(0, 2, 60), PNote(0, 2, 64)), decodeNotes(after, after.size))
    }

    @Test fun shorteningARecordedChordOnlyWritesItsTieTail() {
        val steps = listOf(note(0, listOf(60)), note(1, listOf(60, 64), CONT0 or 1), tie(2), tie(3), rest(4))
        val resized = resizePianoNotes(decodeNotes(steps, steps.size), setOf(noteKey(64, 1)), 1, -1, steps.size, false)
        assertEquals(listOf(rest(3)), encodeNotes(steps, steps.size, resized))
        val after = apply(steps, resized)
        assertEquals(steps[1], after[1])
        assertEquals(listOf(PNote(0, 1, 60), PNote(1, 2, 64)), decodeNotes(after, after.size))
    }

    @Test fun groupResizeStopsAtSelectedNeighboursToo() {
        val notes = listOf(PNote(0, 2, 60), PNote(0, 2, 64), PNote(3, 2, 67))
        val keys = setOf(noteKey(60, 0), noteKey(67, 3))
        assertEquals(listOf(PNote(0, 3, 60), PNote(0, 3, 64), PNote(3, 3, 67)),
            resizePianoNotes(notes, keys, 1, 10, 8, false))
        assertEquals(notes, resizePianoNotes(notes, keys, 1, 0, 8, false))
    }

    @Test fun polyLeftResizeSlidesUnderThePreviousTailAndMergesAtItsStart() {
        val notes = listOf(PNote(0, 3, 60), PNote(4, 3, 64), PNote(4, 3, 67))
        assertEquals(listOf(PNote(0, 3, 60), PNote(1, 6, 64), PNote(4, 3, 67)),
            resizePianoNotes(notes, setOf(noteKey(64, 4)), -1, -3, 8, true))
        assertEquals(listOf(PNote(0, 3, 60), PNote(0, 7, 64), PNote(4, 3, 67)),
            resizePianoNotes(notes, setOf(noteKey(64, 4)), -1, -9, 8, true))
    }

    @Test fun monoLeftResizeStopsAfterThePreviousNote() {
        val notes = listOf(PNote(0, 3, 60), PNote(4, 3, 64), PNote(4, 3, 67))
        assertEquals(listOf(PNote(0, 3, 60), PNote(3, 4, 64), PNote(3, 4, 67)),
            resizePianoNotes(notes, setOf(noteKey(64, 4)), -1, -3, 8, false))
    }

    @Test fun slidingTheLeftEdgeUnderATailCutsItOnTheDevice() {
        val steps = listOf(note(0, listOf(60)), tie(1), tie(2), rest(3),
            note(4, listOf(64)), tie(5), tie(6), rest(7))
        val notes = resizePianoNotes(decodeNotes(steps, 8), setOf(noteKey(64, 4)), -1, -3, 8, true)
        assertEquals(listOf(note(1, listOf(64)), tie(3), tie(4)),
            encodeNotes(steps, 8, notes, true))
        assertEquals(listOf(PNote(0, 1, 60), PNote(1, 6, 64)), decodeNotes(apply(steps, notes, true), 8))
    }

    @Test fun moveAndCopyIntoATailUseTheSamePlacementRules() {
        val steps = listOf(note(0, listOf(60)), tie(1), tie(2), tie(3), rest(4), note(5, listOf(64)), tie(6), rest(7))
        val notes = decodeNotes(steps, steps.size)
        // a single-voice track refuses the overlap and lands at the nearest free spot
        assertEquals(listOf(PNote(0, 4, 60), PNote(4, 2, 64)),
            movePianoNotes(notes, setOf(noteKey(64, 5)), -3, 0, false, steps.size, false).sortedBy { it.start })
        // POLY lands inside the tail: the device releases the held pitch at the attack
        for (copy in listOf(false, true)) {
            val moved = movePianoNotes(notes, setOf(noteKey(64, 5)), -3, 0, copy, steps.size, true)
            val after = apply(steps, moved, true)
            val expected = listOf(PNote(0, 2, 60), PNote(2, 2, 64)) +
                if (copy) listOf(PNote(5, 2, 64)) else emptyList()
            assertEquals(expected.toSet(), decodeNotes(after, after.size, true).toSet())
            assertEquals(listOf(64), after[2].notes.take(after[2].n))
            assertTrue(after.all { it.flags and CONT_ALL == 0 })
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
        val after = apply(steps, placePianoNotes(decodeNotes(steps, steps.size), listOf(PNote(0, 1, 60)), steps.size, false))
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
        assertNull(encodeNotes(steps, steps.size, placePianoNotes(notes, listOf(PNote(0, 1, 76)), steps.size, true), true))
        val shorter = resizePianoNotes(notes, setOf(noteKey(60, 0)), 1, -1, steps.size, false)
        val after = apply(steps, shorter)
        assertEquals(steps, apply(after, notes)) // the reverse edit restores the original NOTE + TIE steps
    }

    // The screenshot case: F#4 held, A#3 pressed over its tail, F#4 released late.
    private fun lateRelease() = listOf(note(0, listOf(66)), tie(1), tie(2),
        note(3, listOf(66, 58), CONT0), tie(4), tie(5), rest(6))

    @Test fun lateReleaseIsDrawnEndingBeforeTheNextNoteWithoutADuplicate() {
        val steps = lateRelease()
        assertEquals(listOf(PNote(0, 3, 66), PNote(3, 3, 58)), decodeNotes(steps, steps.size))
        assertTrue(encodeNotes(steps, steps.size, decodeNotes(steps, steps.size))!!.isEmpty())
    }

    @Test fun aContNoteThatWasNotSoundingStartsThere() {
        val steps = listOf(rest(0), note(1, listOf(60), CONT0), rest(2))
        assertEquals(listOf(PNote(1, 1, 60)), decodeNotes(steps, steps.size))
    }

    @Test fun stretchingTheReleasedNoteStopsAtTheNextNote() {
        val steps = lateRelease()
        val notes = decodeNotes(steps, steps.size)
        assertTrue(encodeNotes(steps, steps.size,
            resizePianoNotes(notes, setOf(noteKey(66, 0)), 1, 5, steps.size, false))!!.isEmpty())
    }

    @Test fun editsElsewhereKeepTheRawContinuationStep() {
        val steps = lateRelease()
        val notes = placePianoNotes(decodeNotes(steps, steps.size), listOf(PNote(6, 1, 70)), steps.size, false)
        assertEquals(listOf(6), encodeNotes(steps, steps.size, notes)!!.map { it.index })
    }

    @Test fun editingTheReleasedNoteDropsItsHiddenContinuation() {
        val steps = lateRelease()
        val notes = decodeNotes(steps, steps.size)
        val shorter = resizePianoNotes(notes, setOf(noteKey(66, 0)), 1, -1, steps.size, false)
        assertEquals(listOf(rest(2), note(3, listOf(58))), encodeNotes(steps, steps.size, shorter))
        val moved = movePianoNotes(notes, setOf(noteKey(66, 0)), 0, 1, false, steps.size, false)
        val after = apply(steps, moved)
        assertEquals(note(3, listOf(58)), after[3])
        assertEquals(listOf(PNote(0, 3, 67), PNote(3, 3, 58)), decodeNotes(after, after.size))
        assertEquals(listOf(rest(0), rest(1), rest(2), note(3, listOf(58))),
            encodeNotes(steps, steps.size, notes.filter { it.pitch != 66 }))
    }

    @Test fun deletingAChainRootClearsEveryContOnlyStepItHeld() {
        val steps = recordedOverlap()
        val notes = decodeNotes(steps, steps.size).filter { it.pitch != 60 }
        assertEquals(listOf(rest(0), note(1, listOf(64)), rest(2)), encodeNotes(steps, steps.size, notes))
    }

    @Test fun quantizeMovesStartsKeepsLengths() {
        val notes = listOf(PNote(1, 2, 60), PNote(6, 1, 64), PNote(13, 3, 67))
        assertEquals(listOf(PNote(0, 2, 60), PNote(8, 1, 64), PNote(12, 3, 67)),
            quantizePianoNotes(notes, null, 4.0, 16))
        assertEquals(listOf(PNote(1, 2, 60), PNote(8, 1, 64), PNote(13, 3, 67)),
            quantizePianoNotes(notes, setOf(noteKey(64, 6)), 4.0, 16))
        assertEquals(listOf(PNote(15, 1, 60)), quantizePianoNotes(listOf(PNote(15, 1, 60)), null, 1.0, 16))
    }

    @Test fun quantizeToTheEndClipsTheLengthToThePattern() {
        assertEquals(listOf(PNote(15, 1, 60)), quantizePianoNotes(listOf(PNote(15, 3, 60)), null, 1.0, 16))
        val q = quantizePianoNotes(listOf(PNote(15, 2, 60)), null, 4.0, 16)
        assertEquals(listOf(PNote(15, 1, 60)), q) // rounds to 16, clamped to the last step
    }
}

// ---- voice-mode-aware behaviour (SLOOP 2.7 V_POLY vs single-voice modes) ----
class VoiceModeTest {
    private fun note(i: Int, pitches: List<Int>, flags: Int = 0) =
        Step(i, pitches.size, IntArray(4) { pitches.getOrElse(it) { 0 } }, StepTime.NOTE, flags, 96)

    private fun rest(i: Int) = Step(i, 0, IntArray(4), StepTime.REST, 0, 0)
    private fun tie(i: Int) = rest(i).copy(time = StepTime.TIE)

    private fun apply(steps: List<Step>, notes: List<PNote>, poly: Boolean): List<Step> =
        steps.toMutableList().also { out ->
            encodeNotes(steps, steps.size, notes, poly)!!.forEach { out[it.index] = it }
        }

    /** 60 held under 64's attack, then alone once more (the recorder's POLY chain). */
    private fun recordedOverlap() = listOf(
        note(0, listOf(60)),
        note(1, listOf(60, 64), CONT0),
        note(2, listOf(60), CONT0),
        rest(3),
        rest(4),
    )

    @Test fun polyDecodeDrawsTrueOverlappingBars() {
        val steps = recordedOverlap()
        assertEquals(setOf(PNote(0, 3, 60), PNote(1, 1, 64)),
            decodeNotes(steps, steps.size, true).toSet())
        assertTrue(encodeNotes(steps, steps.size, decodeNotes(steps, steps.size, true), true)!!.isEmpty())
    }

    @Test fun polyMoveInsideATailDrawsTheOverlapButTheDeviceCutsIt() {
        val steps = listOf(note(0, listOf(60)), tie(1), tie(2), tie(3), rest(4),
            note(5, listOf(64)), tie(6), rest(7))
        val all = decodeNotes(steps, steps.size, true)
        val moved = movePianoNotes(all, setOf(noteKey(64, 5)), -3, 0, false, steps.size, true)
        // The intent keeps both bars as placed; nothing merges or clips on screen.
        assertEquals(listOf(PNote(0, 4, 60), PNote(2, 2, 64)), moved)
        val after = apply(steps, moved, true)
        // On the device the later attack wins: the held pitch is released, never re-attacked.
        assertEquals(listOf(64), after[2].notes.take(after[2].n))
        assertEquals(listOf(PNote(0, 2, 60), PNote(2, 2, 64)), decodeNotes(after, after.size, true))
        assertTrue(after.all { it.flags and CONT_ALL == 0 })
    }

    @Test fun polySamePitchCannotOverlapItself() {
        val notes = listOf(PNote(0, 3, 60), PNote(5, 2, 60))
        assertEquals(listOf(PNote(0, 3, 60), PNote(3, 2, 60)),
            movePianoNotes(notes, setOf(noteKey(60, 5)), -3, 0, false, 8, true))
        // a different pitch lands freely inside the span
        assertEquals(listOf(PNote(0, 3, 60), PNote(1, 2, 64)),
            movePianoNotes(listOf(PNote(0, 3, 60), PNote(5, 2, 64)),
                setOf(noteKey(64, 5)), -4, 0, false, 8, true))
    }

    @Test fun monoMoveNeverOverlaps() {
        val notes = listOf(PNote(0, 2, 60), PNote(5, 2, 64))
        assertEquals(listOf(PNote(0, 2, 60), PNote(2, 2, 64)),
            movePianoNotes(notes, setOf(noteKey(64, 5)), -3, 0, false, 8, false))
        assertEquals(setOf(PNote(3, 2, 60), PNote(5, 2, 64)),
            movePianoNotes(notes, setOf(noteKey(60, 0)), 6, 0, false, 8, false).toSet())
    }

    @Test fun monoPlaceReplacesTheOccupiedStep() {
        val notes = listOf(PNote(0, 3, 60))
        assertEquals(listOf(PNote(0, 3, 64)),
            placePianoNotes(notes, listOf(PNote(0, 1, 64)), 8, false))
        assertEquals(listOf(PNote(0, 3, 60), PNote(0, 3, 64)),
            placePianoNotes(notes, listOf(PNote(0, 1, 64)), 8, true))
    }

    @Test fun polyRecordedChainSurvivesUnrelatedEditsAndResizes() {
        val steps = recordedOverlap() + rest(5)
        // drawing it back unchanged: nothing is rewritten
        assertTrue(encodeNotes(steps, 6, decodeNotes(steps, 6, true), true)!!.isEmpty())
        // shortening the held bar to end inside the chain: the kept CONT step still holds it
        val resized = resizePianoNotes(decodeNotes(steps, 6, true), setOf(noteKey(60, 0)), 1, -1, 6, true)
        assertEquals(listOf(rest(2)), encodeNotes(steps, 6, resized, true))
        val after = apply(steps, resized, true)
        assertEquals(setOf(PNote(0, 2, 60), PNote(1, 1, 64)), decodeNotes(after, 6, true).toSet())
        assertEquals(steps[1], after[1])
    }

    @Test fun polyEdgeBoundsAreFreeAndMonoOnesBlock() {
        val sel = listOf(PNote(4, 3, 64))
        val others = listOf(PNote(0, 3, 60), PNote(7, 1, 72))
        assertEquals(-2 to 1, resizeDeltaBounds(sel, others, 8, 1, true))
        assertEquals(-2 to 0, resizeDeltaBounds(sel, others, 8, 1, false))
        assertEquals(-4 to 2, resizeDeltaBounds(sel, others, 8, -1, true))
        assertEquals(-1 to 2, resizeDeltaBounds(sel, others, 8, -1, false))
    }

    @Test fun polyResizeMovesOnlyTheSelectedBar() {
        val notes = listOf(PNote(0, 4, 60), PNote(0, 3, 64))
        assertEquals(listOf(PNote(0, 4, 60), PNote(1, 2, 64)),
            resizePianoNotes(notes, setOf(noteKey(64, 0)), -1, 1, 8, true))
        // the same drag on a single-voice track resizes the whole chord
        assertEquals(listOf(PNote(1, 3, 60), PNote(1, 2, 64)),
            resizePianoNotes(notes, setOf(noteKey(64, 0)), -1, 1, 8, false))
    }
}
