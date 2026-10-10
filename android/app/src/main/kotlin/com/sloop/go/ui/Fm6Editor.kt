// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sloop.go.proto.Fm6

/** The FM6 patch being edited on the FM6 page; kept in the ViewModel so it survives tab switches. */
class Fm6Editor {
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

    fun set(i: Int, value: Int) {
        val v = value.coerceIn(0, Fm6.max(i))
        if (voice[i] == v) return
        voice = voice.copyOf().also { it[i] = v }
        rev++
    }

    fun rename(name: String) {
        voice = Fm6.setName(voice, name)
        rev++
    }

    fun load(v: IntArray) { voice = Fm6.sanitize(v) }

    fun reset() { voice = Fm6.init() }

    fun say(text: String, error: Boolean = false) { message = text; isError = error }
}
