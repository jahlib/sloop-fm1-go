// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

import com.sloop.go.proto.Step
import com.sloop.go.proto.StepTime

/**
 * A piano-roll note: one pitch held for [len] steps. FM-1 stores chords per step, so independent
 * (overlapping) note lengths are encoded by re-triggering the notes that continue whenever the set
 * of sounding notes changes.
 */
data class PNote(val start: Int, val len: Int, val pitch: Int) {
    val end: Int get() = start + len - 1
}

/** A note's identity inside one pattern (pitch + first step), stable while the note is only moved or cut at its end. */
fun noteKey(pitch: Int, start: Int): Int = pitch * 1024 + start

/**
 * The parts of this note that stay free of [others] (existing notes win). Same-pitch notes conflict when they
 * overlap in time; in a monophonic voice mode every note does. A note covered in the middle comes back as two parts.
 */
fun PNote.freeParts(others: List<PNote>, mono: Boolean): List<PNote> {
    var parts = listOf(this)
    for (o in others) {
        if (!mono && o.pitch != pitch) continue
        if (o.end < start || o.start > end) continue
        parts = parts.flatMap { p ->
            if (o.end < p.start || o.start > p.end) listOf(p) else buildList {
                if (p.start < o.start) add(p.copy(len = o.start - p.start))
                if (p.end > o.end) add(p.copy(start = o.end + 1, len = p.end - o.end))
            }
        }
    }
    return parts
}

/**
 * How far one [edge] (1 = right, -1 = left) of every note in [sel] may shift together, as the inclusive
 * delta range (empty when lo > hi). The group keeps its shape: the range stops where any note would drop
 * below one step, leave the pattern or run over a same-pitch neighbour in [others]. [ratcheted] blocks
 * growth over a ratcheted step (extend right is capped at length 1, extend left is forbidden).
 */
fun resizeDeltaBounds(sel: List<PNote>, others: List<PNote>, length: Int, edge: Int,
                      ratcheted: (PNote) -> Boolean): Pair<Int, Int> {
    var lo = Int.MIN_VALUE
    var hi = Int.MAX_VALUE
    for (n in sel) {
        if (edge > 0) {
            val maxEnd = others.filter { it.pitch == n.pitch && it.start > n.start }
                .minOfOrNull { it.start - 1 } ?: (length - 1)
            lo = maxOf(lo, 1 - n.len); hi = minOf(hi, maxEnd - n.end)
            if (ratcheted(n)) hi = minOf(hi, 1 - n.len)
        } else {
            val minStart = others.filter { it.pitch == n.pitch && it.end < n.start }
                .maxOfOrNull { it.end + 1 } ?: 0
            lo = maxOf(lo, minStart - n.start); hi = minOf(hi, n.len - 1)
            if (ratcheted(n)) lo = maxOf(lo, 0)
        }
    }
    return lo to hi
}

/** P_VOICE: 0 POLY, 1 MONO, 2 LEG, 3 UNI (firmware enum V_POLY..V_UNISON). */
const val V_POLY = 0


/**
 * In monophonic voice modes (MONO / LEG / UNI) only one pitch can sound, so every note ends where a
 * different pitch starts; chords collapse to their lowest pitch. POLY keeps overlaps.
 */
fun normalizeNotes(state: DeviceState, notes: List<PNote>, length: Int): List<PNote> {
    if (voiceMode(state) == V_POLY) return mergeSamePitch(notes.filter { it.start < length })
    val sorted = notes.filter { it.start < length }
        .groupBy { it.start }.mapValues { (_, g) -> g.minBy { it.pitch } }.values
        .sortedBy { it.start }
    val out = ArrayList<PNote>(sorted.size)
    for ((i, n) in sorted.withIndex()) {
        val next = sorted.subList(i + 1, sorted.size).firstOrNull { it.pitch != n.pitch }
        val last = minOf(n.end, next?.let { it.start - 1 } ?: Int.MAX_VALUE, length - 1)
        if (n.start <= last) out.add(n.copy(len = last - n.start + 1))
    }
    return out
}

/** One pitch can only exist once on a step, so overlapping notes of the same pitch merge. */
private fun mergeSamePitch(notes: List<PNote>): List<PNote> =
    notes.groupBy { it.pitch }.values.flatMap { g ->
        val out = ArrayList<PNote>()
        g.sortedBy { it.start }.forEach { n ->
            val last = out.lastOrNull()
            if (last != null && n.start <= last.end)
                out[out.lastIndex] = last.copy(len = maxOf(last.end, n.end) - last.start + 1)
            else out.add(n)
        }
        out
    }

/**
 * Mono-mode edit semantics: the note just edited wins — parts of other pitches covered by [n] are
 * erased (a fully covered note disappears, a covered end or start is cut off).
 */
fun List<PNote>.carveCovered(n: PNote): List<PNote> = flatMap { o ->
    if (o.pitch == n.pitch || o.end < n.start || o.start > n.end) listOf(o)
    else buildList {
        if (o.start < n.start) add(o.copy(len = n.start - o.start))
        if (o.end > n.end) add(o.copy(start = n.end + 1, len = o.end - n.end))
    }
}

fun voiceMode(state: DeviceState): Int {
    val idx = state.pdesc.indexOfFirst { it?.label == "VCE" }
    return if (idx >= 0) state.paramValue(idx) else V_POLY
}

private const val SLIDE = 2

fun decodeNotes(steps: List<Step>, length: Int): List<PNote> {
    val out = ArrayList<PNote>()
    val open = HashMap<Int, Int>()
    fun close(at: Int, keep: Set<Int>) {
        val done = open.filter { it.key !in keep }
        done.forEach { (pitch, start) -> out.add(PNote(start, at - start + 1, pitch)); open.remove(pitch) }
    }
    for (i in 0 until length) {
        val st = steps.getOrNull(i)
        when {
            st == null || st.time == StepTime.REST || (st.time == StepTime.NOTE && st.n == 0) -> close(i - 1, emptySet())
            st.time == StepTime.TIE -> {}
            else -> {
                val set = st.notes.take(st.n.coerceIn(0, 4)).toSet()
                val legato = i > 0 && ((steps.getOrNull(i - 1)?.flags ?: 0) and SLIDE) != 0
                close(i - 1, if (legato) set else emptySet())
                set.forEach { if (it !in open) open[it] = i }
            }
        }
    }
    close(length - 1, emptySet())
    return out
}

/**
 * Steps that must change so that the pattern plays [notes]; null if a step would need more than four notes.
 * A note that continues over a change of the sounding set is re-triggered and marked as legato by the SLIDE
 * flag of the step before, which [decodeNotes] merges back into one note.
 */
fun encodeNotes(old: List<Step>, length: Int, notes: List<PNote>): List<Step>? {
    val sets = ArrayList<Set<Int>>()
    val wants = ArrayList<Step>()
    for (i in 0 until length) {
        val cur = old.getOrNull(i) ?: return null
        val set = notes.filter { i in it.start..it.end }.map { it.pitch }.toSet()
        if (set.size > 4) return null
        val starts = notes.any { it.start == i }
        val want = when {
            set.isEmpty() -> blank(cur, StepTime.REST)
            !starts && i > 0 && set == sets[i - 1] -> blank(cur, StepTime.TIE)
            else -> noteStep(cur, set)
        }
        sets.add(set)
        wants.add(if (sameShape(cur, want)) cur else want)
    }
    for (i in 1 until length) {
        if (wants[i].time != StepTime.NOTE) continue
        val prev = wants[i - 1]
        if (notes.any { it.start <= i - 1 && it.end >= i }) wants[i - 1] = prev.copy(flags = prev.flags or SLIDE)
        else if (sets[i - 1].any { it in sets[i] }) wants[i - 1] = prev.copy(flags = prev.flags and SLIDE.inv())
    }
    return (0 until length).filter { wants[it] != old[it] }.map { wants[it] }
}

private fun blank(cur: Step, time: Int) = cur.copy(n = 0, notes = IntArray(4), time = time,
    flags = 0, vel = 0, lvl = 0, rat = 0)

private fun noteStep(cur: Step, set: Set<Int>): Step {
    val isNote = cur.time == StepTime.NOTE
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
        flags = if (isNote) cur.flags else 0, vel = if (isNote && cur.vel > 0) cur.vel else 100,
        lvl = levels, rat = ratchets)
}

private fun sameShape(cur: Step, want: Step): Boolean = cur.time == want.time && when (want.time) {
    StepTime.NOTE -> cur.n == want.n && cur.notes.take(cur.n.coerceIn(0, 4)).toSet() ==
        want.notes.take(want.n).toSet()
    else -> cur.n == 0
}
