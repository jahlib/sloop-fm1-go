// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

import com.sloop.go.proto.Desc
import com.sloop.go.proto.DrumStep
import com.sloop.go.proto.Dump
import com.sloop.go.proto.Fm6List
import com.sloop.go.proto.Info
import com.sloop.go.proto.Step
import com.sloop.go.proto.StepLock
import com.sloop.go.proto.Tracks

enum class Link { DISCONNECTED, CONNECTING, LOADING, READY }
enum class SequencerMode { NOW, LATE }

/** Immutable snapshot of everything the UI renders. */
data class DeviceState(
    val link: Link = Link.DISCONNECTED,
    val status: String = "Not connected",
    val deviceName: String = "",
    val info: Info? = null,
    val pdesc: List<Desc?> = emptyList(),
    val gdesc: List<Desc?> = emptyList(),
    val dump: Dump? = null,                    // selected track parameters + globals
    val steps: List<Step> = emptyList(),       // synth steps of the selected track
    val drumSteps: List<DrumStep> = emptyList(), // drum steps (drum track + v5 firmware)
    val micro: List<Int> = emptyList(),        // per-step nudge -32..31 (proto v7, SLOOP 2.4)
    val fill: List<Int> = emptyList(),         // per-step condition 0 always / 1 fill / 2 no fill (v8)
    val locks: List<StepLock> = emptyList(),   // parameter locks of the selected track (v7)
    val fm6: Fm6List? = null,                  // FM6 patch slots (proto v9, SLOOP 2.4)
    val tracks: Tracks? = null,
    val selectedTrack: Int = 0,
    val presetNames: Map<Int, List<String>> = emptyMap(),
    val sequencerMode: SequencerMode = SequencerMode.NOW,
    val draftTracks: Set<Int> = emptySet(),
    val queuedEdits: Int = 0,
    val playing: Boolean = false,
    val queueError: String? = null,
) {
    val isDrum: Boolean
        get() = info != null && dump != null && dump.engine >= info.nengines

    val drumGrid: Boolean
        get() = isDrum && (info?.proto ?: 0) >= 5

    /** Index of the FM6 engine in INFO's engine list, -1 when the firmware has none (before 2.4). */
    val fm6Engine: Int get() = info?.engines?.indexOf("FM6") ?: -1
    val fm6Supported: Boolean get() = (info?.proto ?: 0) >= 9 && fm6Engine >= 0

    /** The engine each track plays (from TRACK), -1 when unknown. */
    fun trackEngine(track: Int): Int = tracks?.tracks?.getOrNull(track)?.engine ?: -1

    val nstep: Int get() = info?.nstep ?: 16
    val patternLength: Int get() = paramValue(29).coerceIn(1, nstep)
    val soundLabel: String get() {
        val d = dump ?: return "TRACK ${selectedTrack + 1}"
        val i = info ?: return "TRACK ${selectedTrack + 1}"
        if (isDrum) {
            val kit = pdesc.getOrNull(i.pe0)?.names?.getOrNull(paramValue(i.pe0)) ?: "KIT"
            return "TRACK ${selectedTrack + 1} · DRUM · $kit"
        }
        val engine = i.engines.getOrNull(d.engine) ?: "SYNTH"
        val preset = presetNames[d.engine]?.getOrNull(d.preset) ?: "Preset ${d.preset + 1}"
        return "TRACK ${selectedTrack + 1} · $engine · $preset"
    }

    fun paramValue(id: Int): Int = dump?.p?.getOrNull(id) ?: 0
    fun globalValue(id: Int): Int = dump?.g?.getOrNull(id) ?: 0

    fun withTrackMix(track: Int, level: Int, mute: Boolean): DeviceState {
        val t = tracks ?: return this
        if (track !in t.tracks.indices) return this
        val list = t.tracks.toMutableList()
        list[track] = list[track].copy(level = level, mute = if (mute) 1 else 0)
        return copy(tracks = t.copy(tracks = list))
    }

    /** Returns a new state with one parameter changed (fresh arrays, so StateFlow emits). */
    fun withParam(scope: Int, id: Int, value: Int): DeviceState {
        val d = dump ?: return this
        return when (scope) {
            0 -> if (id < d.p.size) copy(dump = d.copy(p = d.p.copyOf().also { it[id] = value })) else this
            1 -> if (id < d.g.size) copy(dump = d.copy(g = d.g.copyOf().also { it[id] = value })) else this
            else -> this
        }
    }
}
