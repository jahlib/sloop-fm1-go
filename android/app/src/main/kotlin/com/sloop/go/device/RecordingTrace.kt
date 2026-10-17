// SPDX-License-Identifier: GPL-3.0-only
package com.sloop.go.device

import android.os.SystemClock
import com.sloop.go.proto.Cmd
import com.sloop.go.proto.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opt-in, bounded diagnostic capture. It never edits notes or estimates their lengths. */
class RecordingTrace {
    private val rows = ArrayDeque<String>()
    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()
    private var started = 0L
    private var dropped = 0

    @Synchronized fun start(state: DeviceState) {
        rows.clear()
        dropped = 0
        started = SystemClock.elapsedRealtime()
        _active.value = true
        event("START firmware=${state.info?.version} proto=${state.info?.proto} track=${state.selectedTrack}")
        event("PARAMS ${state.dump?.p?.joinToString(",")}")
        event("GLOBALS ${state.dump?.g?.joinToString(",")}")
    }

    @Synchronized fun stop() {
        event("STOP")
        _active.value = false
    }

    @Synchronized private fun event(text: String) {
        if (!_active.value) return
        if (rows.size == 8192) { rows.removeFirst(); dropped++ }
        rows.addLast("${SystemClock.elapsedRealtime() - started}ms $text")
    }

    fun note(channel: Int, pitch: Int, on: Boolean) =
        event("KEY ${if (on) "ON" else "OFF"} ch=$channel pitch=$pitch")

    fun frame(direction: String, cmd: Int, args: IntArray) {
        if (!_active.value || cmd !in commands) return
        event("$direction cmd=$cmd args=${args.joinToString(",")}")
    }

    @Synchronized fun text(): String = buildString {
        appendLine("Times are MIDI receive/send times, not estimated sequencer positions.")
        appendLine("Track/channel/step indices are zero-based. Dropped oldest events: $dropped")
        rows.forEach { appendLine(it) }
    }

    companion object {
        private val commands = setOf(Cmd.STEP_GET, Cmd.STEP_SET, Cmd.TRACK_STEP, Cmd.STEP_CHANGED,
            Cmd.LIVE, Cmd.TRACK, Cmd.RELOAD, Cmd.DUMP, Cmd.TRACK_DUMP, Cmd.CHANGED, Cmd.TRACK_CHANGED,
            Cmd.MICRO_GET, Cmd.MICRO_SET, Cmd.FILL_GET, Cmd.FILL_SET, Cmd.LOCK_GET, Cmd.LOCK_SET)

        fun steps(title: String, steps: List<Step>, length: Int): String = buildString {
            appendLine(title)
            steps.forEach { s ->
                appendLine("step=${s.index} time=${s.time} n=${s.n} notes=${s.notes.joinToString(",")} " +
                    "flags=${s.flags} vel=${s.vel} lvl=${s.lvl} rat=${s.rat}")
            }
            appendLine("Decoded piano roll:")
            decodeNotes(steps, length).sortedWith(compareBy({ it.start }, { it.pitch })).forEach {
                appendLine("start=${it.start} length=${it.len} pitch=${it.pitch}")
            }
        }
    }
}
