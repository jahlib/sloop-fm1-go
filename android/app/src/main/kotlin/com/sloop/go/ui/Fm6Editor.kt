// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sloop.go.device.Change
import com.sloop.go.device.EditHistory
import com.sloop.go.proto.Fm6

/** The FM6 patch being edited on the FM6 page; kept in the ViewModel so it survives tab switches. */
class Fm6Editor(private val history: EditHistory? = null) {
    var voice by mutableStateOf(Fm6.init())
        private set
    var imported by mutableStateOf<List<Fm6.Voice>>(emptyList())
    var track by mutableIntStateOf(-1)
    var slot by mutableIntStateOf(0)
    var live by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)

    /** Bumped by every edit the user makes (not by loads), so live sending only follows real edits. */
    var rev by mutableIntStateOf(0)
    var sentRev by mutableIntStateOf(0)

    /** Edits not yet on the track (Store mode keeps them here until SEND). */
    val dirty get() = rev != sentRev

    /** Called after an undo/redo put a voice back (Live mode sends it to the track from here). */
    var onRestored: () -> Unit = {}

    private suspend fun restore(v: IntArray) {
        voice = v.copyOf()
        rev++
        onRestored()
    }

    private fun record(key: String?, before: IntArray) {
        history?.record(Change.Custom("fm6" + (key ?: ""), before, voice, this::restore))
    }

    fun set(i: Int, value: Int) {
        val v = value.coerceIn(0, Fm6.max(i))
        if (voice[i] == v) return
        val old = voice
        voice = voice.copyOf().also { it[i] = v }
        rev++
        record(":$i", old)
    }

    fun rename(name: String) {
        val old = voice
        voice = Fm6.setName(voice, name)
        rev++
        if (!old.contentEquals(voice)) record(":name", old)
    }

    /** A voice read from the device or a file replaces the edited one: older undo steps no longer fit it. */
    fun load(v: IntArray) {
        voice = Fm6.sanitize(v)
        history?.dropKey("fm6")
    }

    fun reset() {
        val old = voice
        voice = Fm6.init()
        record(null, old)
    }

    fun say(text: String, error: Boolean = false) { message = text; isError = error }
}
