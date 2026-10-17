// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

/**
 * The notes the FM-1 is sounding right now, as its USB MIDI out reports them: 16 channels of 128 notes. The keys always go
 * out (each track on its own channel), the sequencer too with GLO > MIDI 2 > OUT = SEQ. Immutable, a new instance per change.
 */
class LiveNotes private constructor(private val bits: LongArray) {
    constructor() : this(LongArray(32))

    fun has(ch: Int, note: Int): Boolean =
        ch in 0..15 && note in 0..127 && (bits[ch * 2 + (note ushr 6)] ushr (note and 63)) and 1L != 0L

    /** Any key or sounded note still on. */
    fun anyHeld(): Boolean = bits.any { it != 0L }

    fun with(ch: Int, note: Int, on: Boolean): LiveNotes {
        if (ch !in 0..15 || note !in 0..127 || has(ch, note) == on) return this
        val b = bits.copyOf()
        val i = ch * 2 + (note ushr 6)
        val m = 1L shl (note and 63)
        b[i] = if (on) b[i] or m else b[i] and m.inv()
        return LiveNotes(b)
    }

    companion object {
        /**
         * The wire channel (0..15) a track's notes go out on, -1 when it is OFF. SLOOP 2.6 keeps them in GLO > MIDI
         * (CH1..CH3 = globals 33..35, the drums' CH4 = 24); before, synth tracks 1..3 had channels 1..3 and the drums' CH.
         */
        fun channelOf(state: DeviceState): Int {
            val info = state.info ?: return -1
            val t = state.selectedTrack
            val g = when {
                state.isDrum -> state.globalValue(24)
                info.pe0 >= 63 -> state.globalValue(33 + t)
                else -> t + 1
            }
            return if (g in 1..16) g - 1 else -1
        }
    }
}
