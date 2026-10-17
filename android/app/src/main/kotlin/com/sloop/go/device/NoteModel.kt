// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

import com.sloop.go.proto.Step
import com.sloop.go.proto.StepTime

/**
 * One pitch's sounding span. On a POLY track bars may overlap (a pitch CONT'd at a later NOTE
 * keeps sounding under it); on MONO/LEGATO/UNISON they never do. CONT affects playback but a
 * step-set write can never carry it, so written overlaps degrade to honest NOTE steps.
 */
data class PNote(val start: Int, val len: Int, val pitch: Int) {
    val end: Int get() = start + len - 1
}

/** A note's identity inside one pattern (pitch + first step), stable while the note is only moved or cut at its end. */
fun noteKey(pitch: Int, start: Int): Int = pitch * 1024 + start

/** Bounds for resizing. On POLY a right edge runs free to the pattern end (crossing a NOTE makes
 * the device release this bar there) and a left edge slides under tails down to the previous
 * same-pitch attack, where the bars merge. On a single-voice track neither may cross another note:
 * the right edge still stops at the next NOTE and the left edge stops after the previous note's end. */
fun resizeDeltaBounds(sel: List<PNote>, others: List<PNote>, length: Int, edge: Int, poly: Boolean): Pair<Int, Int> {
    if (sel.isEmpty()) return 0 to 0
    val all = sel + others
    var lo = Int.MIN_VALUE
    var hi = Int.MAX_VALUE
    for (n in sel) {
        if (edge > 0) {
            val maxEnd = if (poly) length - 1
                else all.filter { it.start > n.end }.minOfOrNull { it.start - 1 } ?: (length - 1)
            lo = maxOf(lo, 1 - n.len); hi = minOf(hi, maxEnd - n.end)
        } else {
            // POLY: free down to the previous same-pitch bar's start (it merges there — a pitch
            // cannot overlap itself). Single-voice: stop after the previous note's end.
            val minStart = if (poly)
                all.filter { it.start < n.start && it.pitch == n.pitch }.maxOfOrNull { it.start } ?: 0
                else (all.filter { it.end < n.start }.maxOfOrNull { it.end } ?: -1) + 1
            lo = maxOf(lo, minStart - n.start); hi = minOf(hi, n.len - 1)
        }
    }
    return lo to hi
}

/** The nearest shift to [ds] at which no selected span intersects another note's span. With
 * [samePitchDp] only notes the shift lands on the same pitch count (a pitch cannot overlap itself). */
private fun clampShiftNoOverlap(selected: List<PNote>, others: List<PNote>, ds: Int,
                                samePitchDp: Int? = null): Int {
    var d = ds
    repeat(16) {
        var moved = false
        for (m in selected) for (o in others) {
            if (samePitchDp != null && m.pitch + samePitchDp != o.pitch) continue
            if (m.start + d <= o.end && m.end + d >= o.start) {
                d = if (ds > 0) minOf(d, o.start - m.end - 1) else maxOf(d, o.end - m.start + 1)
                moved = true
            }
        }
        if (!moved) return d
    }
    return d
}

/** A pitch's bar may pass step [j] on the device only if the stored step CONT-carries it:
 * a NOTE step's slot flagged for it (the live recorder's overlap chains). */
private fun hasCont(st: Step?, pitch: Int): Boolean {
    if (st == null || st.time != StepTime.NOTE) return false
    val k = st.notes.take(st.n.coerceIn(0, 4)).indexOf(pitch)
    return k >= 0 && st.flags and (CONT0 shl k) != 0
}

/** Canonical writable form. On a single-voice track every chord shares one tail clipped at the next
 * NOTE (the web editor's ROLL model). On POLY each drawn bar keeps its own span and is cut only at a
 * drawn attack the stored step does not CONT-carry it through: the attack's written NOTE releases
 * it — the honest device-side shape of an overlap a step write cannot express. */
fun normalizeNotes(notes: List<PNote>, length: Int, poly: Boolean, old: List<Step> = emptyList()): List<PNote> {
    val groups = notes.filter { it.start in 0 until length && it.pitch in 0..127 && it.len > 0 }
        .groupBy { it.start }.toSortedMap().entries.toList()
    if (!poly) return groups.flatMapIndexed { i, (start, chord) ->
        val next = groups.getOrNull(i + 1)?.key ?: length
        val duration = minOf(chord.maxOf { it.len }, next - start)
        chord.distinctBy { it.pitch }.map { it.copy(len = duration) }
    }
    val newStarts = groups.map { it.key }.toSet()
    return groups.flatMap { (start, chord) ->
        chord.distinctBy { it.pitch }.map { n ->
            // A drawn attack releases every drawn-through pitch the stored step does not CONT-carry:
            // the only writable forms are the release itself or a re-attack, and the release is honest.
            var end = n.end.coerceAtMost(length - 1)
            var j = n.start + 1
            while (j <= end && !(j in newStarts && !hasCont(old.getOrNull(j), n.pitch))) j++
            n.copy(len = if (j <= end) j - n.start else end - n.start + 1)
        }
    }
}

/** Moves the starts of the notes named by [keys] (all notes when null) to the nearest multiple of
 * [grid] steps; lengths are kept. [encodeNotes] still applies the voice mode's rules afterwards. */
fun quantizePianoNotes(notes: List<PNote>, keys: Set<Int>?, grid: Double, length: Int): List<PNote> {
    if (grid <= 0.0) return notes
    return notes.map { n ->
        if (keys != null && noteKey(n.pitch, n.start) !in keys) n else {
            val s = Math.round(Math.round(n.start / grid) * grid).toInt().coerceIn(0, length - 1)
            n.copy(start = s, len = minOf(n.len, length - s))
        }
    }
}

/** ROLL.place: an existing NOTE keeps its duration when another pitch joins it.
 * Placing inside a span starts a new note there. On a single-voice track the step keeps one
 * pitch, so a placed note replaces whatever starts there (firmware step_add). */
fun placePianoNotes(base: List<PNote>, added: List<PNote>, length: Int, poly: Boolean): List<PNote> {
    val durations = base.groupBy { it.start }.mapValues { (_, g) -> g.minOf { it.len } }
    val placed = added.map { n -> durations[n.start]?.let { n.copy(len = it) } ?: n }
    val starts = placed.map { it.start }.toSet()
    return base.filter { b ->
        if (poly) placed.none { it.start == b.start && it.pitch == b.pitch } else b.start !in starts
    } + placed
}

/** Shared by the controller and drag preview, including copy and partial-chord moves. On POLY the
 * shifted notes may land anywhere; on a single-voice track the shift stops before overlapping
 * another note's span. */
fun movePianoNotes(notes: List<PNote>, keys: Set<Int>, dStep: Int, dPitch: Int,
                   copy: Boolean, length: Int, poly: Boolean): List<PNote> {
    val selected = notes.filter { noteKey(it.pitch, it.start) in keys }
    if (selected.isEmpty()) return notes
    var ds = dStep.coerceIn(-selected.minOf { it.start }, length - 1 - selected.maxOf { it.end })
    val dp = dPitch.coerceIn(-selected.minOf { it.pitch }, 127 - selected.maxOf { it.pitch })
    val others = notes.filter { it !in selected }.let { if (copy) it + selected else it }
    // POLY: only a same-pitch landing is refused; a single-voice track refuses every overlap.
    ds = clampShiftNoOverlap(selected, others, ds, if (poly) dp else null)
    if (!copy && ds == 0 && dp == 0) return notes
    return placePianoNotes(if (copy) notes else notes - selected.toSet(),
        selected.map { it.copy(start = it.start + ds, pitch = it.pitch + dp) }, length, poly)
}

/** On a single-voice track pulling any pitch's edge resizes its whole stored chord; on POLY each
 * drawn bar has its own length and only the selected bars move. */
fun resizePianoNotes(notes: List<PNote>, keys: Set<Int>, edge: Int, delta: Int, length: Int,
                     poly: Boolean): List<PNote> {
    if (delta == 0 || edge == 0) return notes
    val starts = notes.filter { noteKey(it.pitch, it.start) in keys }.map { it.start }.toSet()
    val selected = notes.filter { if (poly) noteKey(it.pitch, it.start) in keys else it.start in starts }
    val others = notes - selected.toSet()
    val (lo, hi) = resizeDeltaBounds(selected, others, length, edge, poly)
    val d = if (lo <= hi) delta.coerceIn(lo, hi) else 0
    return notes.map { n ->
        if (n !in selected) n
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
fun transposeSelectedSteps(steps: List<Step>, length: Int, keys: Set<Int>, delta: Int,
                           poly: Boolean = false): List<Step>? {
    val notes = decodeNotes(steps, length, poly)
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
    return after.takeIf { decodeNotes(it, length, poly).toSet() == desired.toSet() }
}

/** ROLL.chords: a NOTE plus the steps its pitches keep sounding through.
 * On POLY the firmware's CONT slots draw as true overlaps: a CONT'd pitch extends its bar under
 * the later NOTE and bars end where the step sequence releases them — exactly what seq_step plays.
 * On a single-voice track a CONT slot of a sounding pitch is drawn as released (recorded late
 * releases stretch it, but the roll shows the bar ending before the next NOTE).
 * Untouched, CONT steps are never rewritten. */
fun decodeNotes(steps: List<Step>, length: Int, poly: Boolean = false): List<PNote> {
    if (!poly) return decodeNotesFlat(steps, length)
    val out = mutableListOf<PNote>()
    val open = HashMap<Int, Int>()
    for (i in 0 until minOf(length, steps.size)) {
        val st = steps[i]
        when {
            st.time == StepTime.TIE -> open.values.forEach { j ->
                out[j] = out[j].copy(len = i - out[j].start + 1)
            }
            st.time != StepTime.NOTE || st.n <= 0 -> open.clear()
            else -> {
                val slots = st.notes.take(st.n.coerceIn(0, 4))
                val cont = slots.withIndex()
                    .filter { st.flags and (CONT0 shl it.index) != 0 }.map { it.value }.toSet()
                open.keys.retainAll(cont)
                slots.forEachIndexed { k, p ->
                    if (slots.indexOf(p) != k) return@forEachIndexed
                    val j = open[p]
                    if (p in cont && j != null) out[j] = out[j].copy(len = i - out[j].start + 1)
                    else { open[p] = out.size; out.add(PNote(i, 1, p)) }
                }
            }
        }
    }
    return out
}

private fun decodeNotesFlat(steps: List<Step>, length: Int): List<PNote> = buildList {
    var sounding = emptySet<Int>()
    for (i in 0 until minOf(length, steps.size)) {
        val st = steps[i]
        when {
            st.time == StepTime.TIE -> continue
            st.time != StepTime.NOTE || st.n <= 0 -> { sounding = emptySet(); continue }
        }
        var duration = 1
        while (i + duration < length && steps.getOrNull(i + duration)?.time == StepTime.TIE) duration++
        val pitches = st.notes.take(st.n.coerceIn(0, 4))
        pitches.forEachIndexed { k, p ->
            val held = st.flags and (CONT0 shl k) != 0 && p in sounding
            if (!held && pitches.indexOf(p) == k) add(PNote(i, duration, p))
        }
        sounding = pitches.toSet()
    }
}

/** For each NOTE step holding hidden CONT slots: the drawn notes those slots carry on. Chains of
 * CONT-only steps lead back to the same drawn note. With released decoding these pitches are
 * invisible; with overlap decoding the root bar spans the step itself. */
private fun hiddenRoots(steps: List<Step>, length: Int, drawn: List<PNote>): Map<Int, Set<PNote>> {
    val out = HashMap<Int, MutableSet<PNote>>()
    var sounding = emptyMap<Int, PNote>()
    for (i in 0 until minOf(length, steps.size)) {
        val st = steps[i]
        if (st.time == StepTime.TIE) continue
        if (st.time != StepTime.NOTE || st.n <= 0) { sounding = emptyMap(); continue }
        val next = HashMap<Int, PNote>()
        st.notes.take(st.n.coerceIn(0, 4)).forEachIndexed { k, p ->
            val root = sounding[p]?.takeIf { st.flags and (CONT0 shl k) != 0 }
            if (root != null) { out.getOrPut(i) { HashSet() }.add(root); next[p] = root }
            else drawn.firstOrNull { it.start == i && it.pitch == p }?.let { next[p] = it }
        }
        sounding = next
    }
    return out
}

/**
 * Steps that must change so that the pattern plays [notes]; null if a step would need more than four notes.
 * Each written chord is one NOTE and its TIE tail; a drawn bar running under a later attack is released
 * there because a step write cannot carry CONT. Untouched raw steps, including recorded CONT and dynamics,
 * are preserved.
 */
fun encodeNotes(old: List<Step>, length: Int, notes: List<PNote>, poly: Boolean = false): List<Step>? {
    val normalized = normalizeNotes(notes, length, poly, old)
    val original = decodeNotes(old, length, poly)
    val oldSets = List(length) { i -> original.filter { i in it.start..it.end }.map { it.pitch }.toSet() }
    val oldStarts = List(length) { i -> original.filter { it.start == i }.map { it.pitch }.toSet() }
    val newSets = List(length) { i -> normalized.filter { i in it.start..it.end }.map { it.pitch }.toSet() }
    val newStarts = List(length) { i -> normalized.filter { it.start == i }.map { it.pitch }.toSet() }
    // With flat decoding an edited note's hidden continuations are dropped so the device then plays
    // exactly what is drawn. With overlap decoding the CONT steps sit inside the drawn bar itself and
    // the per-step sets already rewrite them exactly when the bar changes — nothing extra needed.
    val kept = normalized.toSet()
    val forced = if (poly) emptySet()
        else hiddenRoots(old, length, original).filterValues { roots -> roots.any { it !in kept } }.keys
    val wants = ArrayList<Step>()
    for (i in 0 until length) {
        val cur = old.getOrNull(i) ?: return null
        val set = newSets[i]
        if (set.size > 4) return null
        val starts = newStarts[i].isNotEmpty()
        val want = when {
            // An orphan TIE after a newly placed note would otherwise silently extend it.
            cur.time == StepTime.TIE && set.isEmpty() && i > 0 &&
                (newSets[i - 1].isNotEmpty() || (wants[i - 1].time == StepTime.REST && wants[i - 1] != old[i - 1])) ->
                blank(cur, StepTime.REST)
            // Do not rewrite untouched NOTE/CONT steps, TIE dynamics, or orphan TIEs.
            i !in forced && set == oldSets[i] && newStarts[i] == oldStarts[i] -> cur
            set.isEmpty() -> blank(cur, StepTime.REST)
            // TIE holds everything that sounds; a smaller set needs a new NOTE (re-attack).
            !starts && i > 0 && set == newSets[i - 1] -> blank(cur, StepTime.TIE)
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
