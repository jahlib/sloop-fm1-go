// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sloop.go.audio.Audio
import com.sloop.go.proto.Smp

/** The Samples page draft: staged files or one recording to chop; kept in the ViewModel. */
class SamplesEditor {
    enum class Mode { FILES, CHOP }

    var mode by mutableStateOf(Mode.CHOP)
    var slot by mutableIntStateOf(0)
    var name by mutableStateOf("")
    var files by mutableStateOf<List<Smp.ZoneIn>>(emptyList())
    var busy by mutableStateOf(false)
    var progress by mutableFloatStateOf(-1f)     // <0: no upload in flight
    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)

    fun say(text: String, error: Boolean = false) { message = text; isError = error }

    // ---- recording (shared by both modes) ----
    val recorder = Audio.Recorder()
    var mic by mutableStateOf<Audio.MicOption?>(null)   // null: default ("Mic" preset)
    var recSeconds by mutableFloatStateOf(0f)
    var recLevel by mutableFloatStateOf(0f)
    var recording by mutableStateOf(false)

    fun startRecording(): Boolean {
        if (!recorder.start(mic ?: Audio.micDefault())) { say("Cannot open the microphone", true); return false }
        recording = true
        return true
    }

    /** Stops and returns the recording at 44100 Hz mono. */
    fun stopRecording(): DoubleArray {
        recording = false
        recSeconds = 0f; recLevel = 0f
        return recorder.stop()
    }

    // ---- chop state (editor.html CH) ----
    var src by mutableStateOf<DoubleArray?>(null)   // the recording/file, mono at Smp.RATE
    var srcName by mutableStateOf("")
    var nov by mutableStateOf<Smp.Novelty?>(null)
    var marks by mutableStateOf<List<Smp.Mark>>(emptyList())
    var sel by mutableIntStateOf(-1)
    var key0 by mutableIntStateOf(36)
    var chopMode by mutableIntStateOf(0)            // 0: one key per chop, 1: the selected chop everywhere
    var maxLen by mutableFloatStateOf(0f)           // seconds; 0 = up to the next marker
    var sens by mutableIntStateOf(5)

    fun loadChopSource(x: DoubleArray, name: String) {
        src = x
        srcName = name
        nov = Smp.chopNovelty(x)
        marks = emptyList()
        sel = -1
    }

    val chops: List<Smp.Chop>
        get() = src?.let { Smp.chopList(marks, it.size, (maxLen * Smp.RATE).toInt()) } ?: emptyList()

    val usedChops: List<Smp.Chop> get() = Smp.chopPick(chops, chopMode, sel.coerceAtLeast(0))

    /** Samples (not bytes) the kept chops occupy. */
    val usedSamples: Int get() = usedChops.fold(0) { a, c -> a + c.len }

    private var rr = 0                              // round-robin slot: adds past MAX recreate marks in turn

    fun setMarkers(m: List<Int>) {
        val x = src ?: return
        rr = 0
        val was = marks.associateBy({ it.start }, { it })
        marks = m.map { v ->
            val p = v.coerceIn(0, x.size - 1)
            val o = was[p]
            if (o != null) Smp.Mark(p, o.off, o.len) else Smp.Mark(p)
        }.distinctBy { it.start }.sortedBy { it.start }
        if (sel >= marks.size) sel = marks.size - 1
    }

    fun addMark(pos: Int) {                         /* a new marker; one within 30 ms moves there */
        val x = src ?: return
        val near = marks.indexOfFirst { kotlin.math.abs(it.start - pos) < 0.03 * Smp.RATE }
        val m = marks.map { it.start }.toMutableList()
        if (near >= 0) m[near] = pos
        else if (m.size < Smp.Chop.MAX) m.add(pos)
        else {
            val i = rr % m.size                     /* full: the tap recreates mark 1, then 2, ... */
            m[i] = pos
            setMarkers(m)
            rr = i + 1
            sel = marks.indexOfFirst { it.start == pos.coerceIn(0, x.size - 1) }
            return
        }
        setMarkers(m)
        sel = marks.indexOfFirst { it.start == pos.coerceIn(0, x.size - 1) }
    }

    fun removeMark(i: Int) {
        setMarkers(marks.map { it.start }.filterIndexed { j, _ -> j != i })
        sel = -1
    }

    fun moveMark(i: Int, pos: Int) {
        val x = src ?: return
        val lo = if (i > 0) marks[i - 1].start + 1 else 0
        val hi = if (i + 1 < marks.size) marks[i + 1].start - 1 else x.size - 1
        val m = marks.map { it.start }.toMutableList()
        m[i] = pos.coerceIn(lo, hi)
        setMarkers(m)
        sel = marks.indexOfFirst { it.start == m[i] }.takeIf { it >= 0 } ?: i.coerceAtMost(marks.size - 1)
    }

    fun keep(i: Int, keep: Boolean? = null) {
        if (i !in marks.indices) return
        marks = marks.mapIndexed { j, m ->
            if (j == i) Smp.Mark(m.start, keep?.let { !it } ?: !m.off, m.len) else m
        }
    }

    fun setChopLen(i: Int, len: Int) {              /* 0 = up to the next marker */
        if (i !in marks.indices) return
        marks = marks.mapIndexed { j, m -> if (j == i) Smp.Mark(m.start, m.off, len.coerceAtLeast(0)) else m }
    }

    /** Shortens the longest kept chops so they all fit the slot (chopFitAll). */
    fun fitAll() {
        val use = usedChops
        val l = Smp.chopFit(use, Smp.MAX_DATA * 2 - use.size)
        if (l == Int.MAX_VALUE) return
        for (c in use) if (c.len > l) setChopLen(c.i, l)
    }
}
