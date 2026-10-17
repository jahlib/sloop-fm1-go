// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

import com.sloop.go.proto.Step
import com.sloop.go.proto.StepTime

/**
 * One pitch of a stored NOTE step, followed by [len] - 1 TIE steps. All pitches at the same
 * start share a length. This is the web editor's ROLL model, not a reconstruction of voice
 * envelopes: SLIDE and CONT affect playback but never hide a stored NOTE boundary.
 */
data class PNote(val start: Int, val len: Int, val pitch: Int) {
    val end: Int get() = start + len - 1
}

/** A note's identity inside one pattern (pitch + first step), stable while the note is only moved or cut at its end. */
fun noteKey(pitch: Int, start: Int): Int = pitch * 1024 + start

/** Bounds for resizing whole chords. A right edge stops at the next NOTE of any pitch;
 * a left edge cannot cross the previous chord's end. Selected neighbours count too. */
fun resizeDeltaBounds(sel: List<PNote>, others: List<PNote>, length: Int, edge: Int): Pair<Int, Int> {
    if (sel.isEmpty()) return 0 to 0
    val all = sel + others
    var lo = Int.MIN_VALUE
    var hi = Int.MAX_VALUE
    for (n in sel.distinctBy { it.start }) {
        if (edge > 0) {
            val maxEnd = all.filter { it.start > n.start }
                .minOfOrNull { it.start - 1 } ?: (length - 1)
            lo = maxOf(lo, 1 - n.len); hi = minOf(hi, maxEnd - n.end)
        } else {
            val minStart = all.filter { it.start < n.start }
                .maxOfOrNull { it.end + 1 } ?: 0
            lo = maxOf(lo, minStart - n.start); hi = minOf(hi, n.len - 1)
        }
    }
    return lo to hi
}

/** Canonical NOTE + TIE chords. New starts cut preceding tails; a cut tail never resumes.
 * Used for imported clips too. Voice mode changes sound, not the stored sequence. */
fun normalizeNotes(notes: List<PNote>, length: Int): List<PNote> {
    val groups = notes.filter { it.start in 0 until length && it.pitch in 0..127 && it.len > 0 }
        .groupBy { it.start }.toSortedMap().entries.toList()
    return groups.flatMapIndexed { i, (start, chord) ->
        val next = groups.getOrNull(i + 1)?.key ?: length
        val duration = minOf(chord.maxOf { it.len }, next - start)
        chord.distinctBy { it.pitch }.map { it.copy(len = duration) }
    }
}

/** ROLL.place: an existing NOTE keeps its duration when another pitch joins it.
 * Placing inside a TIE starts a new chord and cuts the old tail, without a copied attack. */
fun placePianoNotes(base: List<PNote>, added: List<PNote>, length: Int): List<PNote> {
    val durations = base.associate { it.start to it.len }
    return normalizeNotes(base + added.map { n ->
        durations[n.start]?.let { n.copy(len = it) } ?: n
    }, length)
}

/** Shared by the controller and drag preview, including copy and partial-chord moves. */
fun movePianoNotes(notes: List<PNote>, keys: Set<Int>, dStep: Int, dPitch: Int,
                   copy: Boolean, length: Int): List<PNote> {
    val selected = notes.filter { noteKey(it.pitch, it.start) in keys }
    if (selected.isEmpty()) return notes
    val ds = dStep.coerceIn(-selected.minOf { it.start }, length - 1 - selected.maxOf { it.end })
    val dp = dPitch.coerceIn(-selected.minOf { it.pitch }, 127 - selected.maxOf { it.pitch })
    if (!copy && ds == 0 && dp == 0) return notes
    return placePianoNotes(if (copy) notes else notes - selected.toSet(),
        selected.map { it.copy(start = it.start + ds, pitch = it.pitch + dp) }, length)
}

/** Pulling any pitch's edge resizes its whole stored chord, just like ROLL.resize. */
fun resizePianoNotes(notes: List<PNote>, keys: Set<Int>, edge: Int, delta: Int, length: Int): List<PNote> {
    if (delta == 0 || edge == 0) return notes
    val starts = notes.filter { noteKey(it.pitch, it.start) in keys }.map { it.start }.toSet()
    val selected = notes.filter { it.start in starts }
    val others = notes.filter { it.start !in starts }
    val (lo, hi) = resizeDeltaBounds(selected, others, length, edge)
    val d = if (lo <= hi) delta.coerceIn(lo, hi) else 0
    return notes.map { n ->
        if (n.start !in starts) n
        else if (edge > 0) n.copy(len = n.len + d)
        else n.copy(start = n.start + d, len = n.len - d)
    }
}

/** SLOOP 2.7: bits 3..6 of a NOTE step's flags; bit 3 + k = note k goes on from the step before (not played again). */
const val CONT0 = 8
const val CONT_ALL = 15 * CONT0

fun Step.hasContinuations(): Boolean = time == StepTime.NOTE && flags and CONT_ALL != 0

/** The firmware's pattern transpose changes only the occupied note slots, across
 * all stored steps. Refuse clipping so the inverse operation remains exact. */
fun transposeRawSteps(steps: List<Step>, delta: Int): List<Step>? {
    if (steps.any { s -> s.notes.take(s.n.coerceIn(0, 4)).any { it + delta !in 0..127 } }) return null
    return steps.map { s ->
        s.copy(notes = s.notes.copyOf().also { notes ->
            for (i in 0 until s.n.coerceIn(0, minOf(4, notes.size))) notes[i] += delta
        })
    }
}

/** Reconcile an interrupted series of one-semitone device commands before retrying.
 * A lost acknowledgement must not cause the same semitone to be sent twice. */
fun rawTransposeProgress(before: List<Step>, actual: List<Step>, delta: Int): Int? =
    (0..kotlin.math.abs(delta)).firstOrNull { done ->
        transposeRawSteps(before, if (delta < 0) -done else done) == actual
    }

/** Shift selected note identities in their existing slots, without rebuilding
 * NOTE/TIE/CONT steps or reordering the per-slot dynamics. Null means clipping
 * or a pitch collision inside a chord. */
fun transposeSelectedSteps(steps: List<Step>, length: Int, keys: Set<Int>, delta: Int): List<Step>? {
    val notes = decodeNotes(steps, length)
    val selected = notes.filter { noteKey(it.pitch, it.start) in keys }
    if (selected.isEmpty()) return steps
    if (selected.any { it.pitch + delta !in 0..127 }) return null
    val desired = notes.map { if (it in selected) it.copy(pitch = it.pitch + delta) else it }
    for ((i, n) in desired.withIndex()) {
        if (desired.drop(i + 1).any { it.pitch == n.pitch && it.start <= n.end && n.start <= it.end }) return null
    }
    val after = steps.mapIndexed { i, s ->
        if (i >= length || s.time != StepTime.NOTE) s else {
            val pitches = s.notes.copyOf()
            for (slot in 0 until s.n.coerceIn(0, minOf(4, pitches.size))) {
                if (selected.any { it.pitch == pitches[slot] && i in it.start..it.end })
                    pitches[slot] += delta
            }
            if (pitches.contentEquals(s.notes)) s else s.copy(notes = pitches)
        }
    }
    return after.takeIf { decodeNotes(it, length).toSet() == desired.toSet() }
}

/** ROLL.chords: every NOTE has its own start; only following TIEs add to its length.
 * CONT is still shown as a continuation marker, but never stretches the preceding rectangle. */
fun decodeNotes(steps: List<Step>, length: Int): List<PNote> = buildList {
    for (i in 0 until minOf(length, steps.size)) {
        val st = steps[i]
        if (st.time != StepTime.NOTE || st.n <= 0) continue
        var duration = 1
        while (i + duration < length && steps.getOrNull(i + duration)?.time == StepTime.TIE) duration++
        st.notes.take(st.n.coerceIn(0, 4)).forEach { add(PNote(i, duration, it)) }
    }
}

/**
 * Steps that must change so that the pattern plays [notes]; null if a step would need more than four notes.
 * Each chord is one NOTE and its TIE tail. Never manufacture NOTE+SLIDE steps to simulate overlaps.
 * Untouched raw steps, including recorded CONT and dynamics, are preserved.
 */
fun encodeNotes(old: List<Step>, length: Int, notes: List<PNote>): List<Step>? {
    val normalized = normalizeNotes(notes, length)
    val original = decodeNotes(old, length)
    val oldSets = List(length) { i -> original.filter { i in it.start..it.end }.map { it.pitch }.toSet() }
    val oldStarts = List(length) { i -> original.filter { it.start == i }.map { it.pitch }.toSet() }
    val newSets = List(length) { i -> normalized.filter { i in it.start..it.end }.map { it.pitch }.toSet() }
    val newStarts = List(length) { i -> normalized.filter { it.start == i }.map { it.pitch }.toSet() }
    val wants = ArrayList<Step>()
    for (i in 0 until length) {
        val cur = old.getOrNull(i) ?: return null
        val set = newSets[i]
        if (set.size > 4) return null
        val starts = newStarts[i].isNotEmpty()
        val want = when {
            // An orphan TIE after a newly placed note would otherwise silently extend it.
            cur.time == StepTime.TIE && set.isEmpty() && i > 0 &&
                (newSets[i - 1].isNotEmpty() || (old[i - 1].time == StepTime.TIE && wants[i - 1].time == StepTime.REST)) ->
                blank(cur, StepTime.REST)
            // Do not rewrite untouched NOTE/CONT steps, TIE dynamics, or orphan TIEs.
            set == oldSets[i] && newStarts[i] == oldStarts[i] -> cur
            set.isEmpty() -> blank(cur, StepTime.REST)
            !starts -> blank(cur, StepTime.TIE)
            else -> noteStep(cur, set)
        }
        wants.add(if (sameShape(cur, want)) cur else want)
    }
    return (0 until length).filter { wants[it] != old[it] }.map { wants[it] }
}

private fun blank(cur: Step, time: Int) = cur.copy(n = 0, notes = IntArray(4), time = time,
    flags = 0, vel = 0, lvl = 0, rat = 0)

private fun noteStep(cur: Step, set: Set<Int>): Step {
    val isNote = cur.time == StepTime.NOTE && cur.n > 0
    val existing = if (isNote) cur.notes.take(cur.n.coerceIn(0, 4)) else emptyList()
    val list = existing.filter { it in set }.distinct() + set.filter { it !in existing }.sorted()
    var levels = 0
    var ratchets = 0
    list.forEachIndexed { slot, note ->
        val prev = existing.indexOf(note)
        if (prev >= 0) {
            levels = levels or (((cur.lvl shr (prev * 2)) and 3) shl (slot * 2))
            ratchets = ratchets or (((cur.rat shr (prev * 2)) and 3) shl (slot * 2))
        }
    }
    return cur.copy(n = list.size, notes = IntArray(4) { list.getOrElse(it) { 0 } }, time = StepTime.NOTE,
        flags = if (isNote) cur.flags and CONT_ALL.inv() else 0, vel = if (isNote) cur.vel else 96,
        lvl = levels, rat = ratchets)
}

private fun sameShape(cur: Step, want: Step): Boolean = cur.time == want.time && when (want.time) {
    StepTime.NOTE -> cur.n == want.n && (cur.flags and CONT_ALL) == (want.flags and CONT_ALL) &&
        cur.notes.take(cur.n.coerceIn(0, 4)).toSet() ==
        want.notes.take(want.n).toSet()
    else -> cur.n == 0
}
