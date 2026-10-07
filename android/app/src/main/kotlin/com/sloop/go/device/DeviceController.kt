// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sloop.go.midi.MidiConnection
import com.sloop.go.midi.MidiEngine
import com.sloop.go.proto.Cmd
import com.sloop.go.proto.Desc
import com.sloop.go.proto.DrumStep
import com.sloop.go.proto.Dump
import com.sloop.go.proto.Info
import com.sloop.go.proto.Parse
import com.sloop.go.proto.Req
import com.sloop.go.proto.Requests
import com.sloop.go.proto.Step
import com.sloop.go.proto.StepLock
import com.sloop.go.proto.frame
import com.sloop.go.proto.replyMatches
import com.sloop.go.proto.unframe
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the live link to the device: a strict one-request-in-flight queue (as the protocol
 * requires), coalesced parameter writes for an instant feel, WATCH + PING keep-alive, and
 * push handling. The whole UI observes [state].
 */
class DeviceController(context: Context) {

    private val appContext = context.applicationContext
    private val engine = MidiEngine(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    val midiAvailable: Boolean get() = engine.available
    fun devices() = engine.devices()
    fun preferredDevice() = engine.preferredDevice()

    private var conn: MidiConnection? = null

    // ---- request/reply link (one in flight) ----
    private class Inflight(val cmd: Int, val args: IntArray, val deferred: CompletableDeferred<IntArray>)
    private val linkMutex = Mutex()
    @Volatile private var inflight: Inflight? = null
    @Volatile private var lastSent = 0L

    // ---- coalesced parameter writes ----
    private sealed interface UserEdit {
        data class Parameter(val scope: Int, val id: Int, val value: Int, val track: Int) : UserEdit
        data class Mix(val track: Int, val level: Int, val mute: Boolean) : UserEdit
        data class Synth(val track: Int, val index: Int, val step: Step) : UserEdit
        data class Drum(val index: Int, val step: DrumStep) : UserEdit
    }
    private val edits = ArrayDeque<UserEdit>()
    private val pendingLock = Any()
    private val editSignal = Channel<Unit>(Channel.CONFLATED)
    private var sending = false
    private var editGeneration = 0
    private var queuePaused = false
    private val maxEdits = 16384
    private val draftLock = Any()
    private val draftSynth = mutableMapOf<Int, List<Step>>()
    private val draftDrum = mutableMapOf<Int, List<DrumStep>>()

    private var keepAlive: Job? = null

    init {
        scope.launch { editWorker() }
    }

    private fun clearDrafts(): Int = synchronized(draftLock) {
        val count = (draftSynth.keys + draftDrum.keys).size
        draftSynth.clear(); draftDrum.clear()
        count
    }

    private fun draftIds(): Set<Int> = synchronized(draftLock) { draftSynth.keys + draftDrum.keys }

    // ---------------------------------------------------------------- connect ---

    fun connect(deviceId: Int) {
        scope.launch {
            disconnectInternal(silent = true)
            _state.value = DeviceState(link = Link.CONNECTING, status = "Connecting…")
            engine.requestUsbPermission(appContext)
            val c = engine.open(deviceId, onReceive = ::onFrame, onLost = { onLost() })
            if (c == null) {
                _state.update { it.copy(link = Link.DISCONNECTED, status = "Could not open device") }
                return@launch
            }
            conn = c
            val name = engine.devices().firstOrNull { it.id == deviceId }?.name ?: "device"
            _state.update { it.copy(deviceName = name, link = Link.LOADING, status = "Loading…") }
            try {
                load()
                _state.update { it.copy(link = Link.READY, status = "Connected") }
                startKeepAlive()
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
                disconnectInternal(silent = true)
                _state.update { it.copy(link = Link.DISCONNECTED, status = "No reply from device") }
            }
        }
    }

    fun disconnect() {
        scope.launch { disconnectInternal(silent = false) }
    }

    private suspend fun disconnectInternal(silent: Boolean) {
        keepAlive?.cancel(); keepAlive = null
        inflight?.deferred?.cancel()
        inflight = null
        linkMutex.withLockSafe { }
        try { if (conn != null) runCatching { request(Requests.watch(0), quiet = true) } } catch (_: Exception) {}
        conn?.close(); conn = null
        val unsent = synchronized(pendingLock) {
            val count = edits.size
            edits.clear(); sending = false; queuePaused = false; editGeneration++
            count
        }
        val drafts = clearDrafts()
        if (!silent) _state.value = DeviceState(status =
            if (unsent == 0 && drafts == 0) "Disconnected"
            else "Disconnected: $unsent queued edits and $drafts Late patterns discarded")
    }

    private fun onLost() {
        scope.launch {
            conn?.close(); conn = null
            keepAlive?.cancel(); keepAlive = null
            val unsent = synchronized(pendingLock) {
                val count = edits.size
                edits.clear(); sending = false; queuePaused = false; editGeneration++
                count
            }
            val drafts = clearDrafts()
            _state.value = DeviceState(status = if (unsent == 0 && drafts == 0) "Device unplugged"
                else "Device unplugged: $unsent queued edits and $drafts Late patterns discarded")
        }
    }

    // ---------------------------------------------------------------- load ---

    private suspend fun load() {
        val info = Parse.info(request(Requests.info()))
        val pdesc = arrayOfNulls<Desc>(info.pcount)
        val gdesc = arrayOfNulls<Desc>(info.gcount)
        for (id in 0 until info.pcount) pdesc[id] = runCatching { Parse.desc(request(Requests.desc(0, id))) }.getOrNull()
        for (id in 0 until info.gcount) gdesc[id] = runCatching { Parse.desc(request(Requests.desc(1, id))) }.getOrNull()
        val dump = Parse.dump(request(Requests.dump()), info)
        val tracks = runCatching { Parse.tracks(request(Requests.track())) }.getOrNull()
        val names = if (dump.engine < info.nengines)
            runCatching { Parse.names(request(Requests.names(dump.engine))) }.getOrNull() else null

        _state.update {
            it.copy(
                info = info,
                pdesc = pdesc.toList(),
                gdesc = gdesc.toList(),
                dump = dump,
                tracks = tracks,
                selectedTrack = tracks?.sel ?: 0,
                presetNames = if (names != null) mapOf(names.engine to names.names) else emptyMap(),
            )
        }
        loadSteps(info, dump)
        loadStepExtras(info)
        fm6Refresh()
        // Ask the device to push live changes (v4 WATCH 3; v2/v3 answer 1).
        runCatching { request(Requests.watch(3)) }
    }

    private suspend fun ensurePresetNames(engine: Int, info: Info) {
        if (engine >= info.nengines || engine in _state.value.presetNames) return
        runCatching { Parse.names(request(Requests.names(engine))) }.getOrNull()?.let { reply ->
            _state.update { it.copy(presetNames = it.presetNames + (reply.engine to reply.names)) }
        }
    }

    private suspend fun loadSteps(info: Info, dump: Dump) {
        val drum = dump.engine >= info.nengines && info.proto >= 5
        if (drum) {
            val ds = ArrayList<DrumStep>(info.nstep)
            for (i in 0 until info.nstep) ds.add(Parse.drumStep(request(Requests.drumStepGet(i))))
            _state.update { it.copy(drumSteps = ds, steps = emptyList()).withQueuedEdits().withDraft() }
        } else {
            val st = ArrayList<Step>(info.nstep)
            for (i in 0 until info.nstep) st.add(Parse.step(request(Requests.stepGet(i))))
            _state.update { it.copy(steps = st, drumSteps = emptyList()).withQueuedEdits().withDraft() }
        }
    }

    /** Per-step nudges, fill conditions and parameter locks (proto v7/v8, SLOOP 2.4). */
    private suspend fun loadStepExtras(info: Info) {
        if (info.proto < 7) {
            _state.update { it.copy(micro = emptyList(), fill = emptyList(), locks = emptyList()) }
            return
        }
        val tr = _state.value.selectedTrack
        val micro = runCatching { Parse.micro(request(Requests.microGet(tr))) }.getOrNull()
        val fill = if (info.proto >= 8)
            runCatching { Parse.fill(request(Requests.fillGet(tr)), info.nstep) }.getOrNull() else null
        val locks = runCatching { Parse.locks(request(Requests.lockGet(tr))) }.getOrNull()
        _state.update {
            it.copy(micro = micro ?: emptyList(), fill = fill ?: emptyList(), locks = locks ?: emptyList())
        }
    }

    // ---------------------------------------------------------------- params ---

    private val lastParamSent = HashMap<String, Int>()
    private fun paramKey(scope: Int, id: Int, track: Int) = "$scope:$id:$track"

    /**
     * Parameter write (selected track). scope 0 = P_*, 1 = G_*. The UI updates at once, while the queue
     * carries the value in up to 12 steps from the last value the device got, so big sweeps glide.
     */
    fun setParam(scope: Int, id: Int, value: Int) {
        val s = _state.value
        if (s.link != Link.READY) return
        val track = s.selectedTrack
        val desc = (if (scope == 0) s.pdesc else s.gdesc).getOrNull(id)
        val chunk = maxOf(1, ((desc?.max ?: 127) - (desc?.min ?: 0)) / 12)
        val ok = synchronized(pendingLock) {
            val base = edits.filterIsInstance<UserEdit.Parameter>()
                .lastOrNull { it.scope == scope && it.id == id && it.track == track }?.value
                ?: lastParamSent[paramKey(scope, id, track)]
                ?: if (scope == 0) s.paramValue(id) else s.globalValue(id)
            for (i in edits.lastIndex downTo if (sending) 1 else 0) {
                val p = edits[i]
                if (p is UserEdit.Parameter && p.scope == scope && p.id == id && p.track == track)
                    edits.removeAt(i)
            }
            val count = kotlin.math.abs(value - base) / chunk
            val values = if (count <= 1) listOf(value)
                else (1..count.coerceAtMost(12)).map { base + (value - base) * it / count.coerceAtMost(12) }
            if (edits.size + values.size > maxEdits) null else {
                values.forEach { edits.addLast(UserEdit.Parameter(scope, id, it, track)) }
                Unit
            }
        }
        if (ok == null) {
            _state.update { it.copy(queueError = "Queue full — wait for MIDI or retry") }
            return
        }
        _state.update { it.copy(queuedEdits = synchronized(pendingLock) { edits.size }) }
        editSignal.trySend(Unit)
        _state.update { it.withParam(scope, id, value) } // optimistic UI
    }

    /** Loads a factory preset of [engine] into the selected synth track (the device pushes RELOAD afterwards). */
    fun selectPreset(engine: Int, preset: Int) {
        val s = _state.value
        if (s.link != Link.READY || s.isDrum || s.dump == null) return
        _state.update { cur -> cur.dump?.let { cur.copy(dump = it.copy(preset = preset)) } ?: cur }
        scope.launch {
            runCatching { request(Requests.preset(engine, preset)) }
                .onFailure { _state.update { it.copy(queueError = "Preset change failed") } }
        }
    }

    /** song.g index of SYNC (INT/USB/TRS): USB realtime transport is honoured only when SYNC == USB. */
    private val G_SYNC = 13

    /** Sends a MIDI realtime transport byte: FA = start, FC = stop (no reply expected). */
    fun setPlaying(play: Boolean) {
        val c = conn ?: return
        scope.launch {
            runCatching {
                if (_state.value.globalValue(G_SYNC) != 1)
                    request(Requests.set(1, G_SYNC, 1))
                c.send(byteArrayOf(if (play) 0xFA.toByte() else 0xFC.toByte()))
            }.onSuccess { _state.update { it.copy(playing = play) } }
                .onFailure { _state.update { it.copy(queueError = "Transport send failed") } }
        }
    }

    // ---------------------------------------------------------------- tracks ---

    fun selectTrack(track: Int) {
        if (_state.value.link != Link.READY) return
        scope.launch {
            _state.update { it.copy(link = Link.LOADING, status = "Loading track…") }
            val result = runCatching {
                val t = Parse.tracks(request(Requests.track(track)))
                _state.update { it.copy(tracks = t, selectedTrack = t.sel) }
                val info = _state.value.info ?: return@launch
                // Re-read the selected track's engine descriptors, dump and steps.
                val dump = Parse.dump(request(Requests.dump()), info)
                ensurePresetNames(dump.engine, info)
                for (id in info.pe0 until info.pcount) {
                    runCatching { Parse.desc(request(Requests.desc(0, id))) }.getOrNull()?.let { d ->
                        _state.update { s ->
                            val list = s.pdesc.toMutableList(); if (id < list.size) list[id] = d; s.copy(pdesc = list)
                        }
                    }
                }
                _state.update { it.copy(tracks = t, selectedTrack = t.sel, dump = dump).withQueuedEdits() }
                loadSteps(info, dump)
                loadStepExtras(info)
            }
            _state.update { it.copy(link = Link.READY,
                status = if (result.isSuccess) "Connected" else "Track load failed — select it again") }
        }
    }

    fun setTrackMix(track: Int, level: Int, mute: Boolean) {
        if (!enqueue(UserEdit.Mix(track, level, mute), continuous = true)) return
        _state.update { s -> s.withTrackMix(track, level, mute) }
    }

    // ------------------------------------------------- FM6 patches (proto v9) ---

    /** Re-reads the slot names (FM6_LIST) into the state. False when the device has no FM6 patches. */
    suspend fun fm6Refresh(): Boolean {
        if (!_state.value.fm6Supported) return false
        val list = runCatching { Parse.fm6List(request(Requests.fm6List())) }.getOrNull() ?: return false
        _state.update { it.copy(fm6 = list) }
        return true
    }

    /** Reads a patch: target 0 a track's own, 1 a bank slot, 2 a factory patch. */
    suspend fun fm6Get(target: Int, index: Int) = Parse.fm6Get(request(Requests.fm6Get(target, index)))

    /** Writes a patch; to a track it plays at once, to a bank slot it goes to flash (the song must be stopped). */
    suspend fun fm6Put(target: Int, index: Int, packed: IntArray): Int {
        val slow = target == 1
        val rc = Parse.fm6Put(request(Requests.fm6Put(target, index, packed), timeout = if (slow) 3000 else 600)).rc
        if (slow && rc == 0) fm6Refresh()
        return rc
    }

    suspend fun fm6Erase(index: Int): Int {
        val rc = Parse.fm6Erase(request(Requests.fm6Erase(index), timeout = 3000)).rc
        if (rc == 0) fm6Refresh()
        return rc
    }

    /**
     * Picks the patch of [slot] (F1..F8 = 0..7, B1.. = 8..) on [track] by writing its PTCH, as on the device.
     * The track must already play FM6; PTCH is that engine's last edit parameter.
     */
    suspend fun fm6Assign(track: Int, slot: Int) {
        val info = _state.value.info ?: return
        val id = info.pe0 + 7
        request(Requests.trackParamSet(track, id, slot))
        _state.update { s -> if (s.selectedTrack == track) s.withParam(0, id, slot) else s }
    }

    /** Makes [track] play the FM6 engine (selects it, sets ENG, reloads), like picking it on the device. */
    suspend fun fm6EnableTrack(track: Int) {
        val s = _state.value
        val eng = s.gdesc.indexOfFirst { it?.label == "ENG" }
        if (!s.fm6Supported || eng < 0) return
        if (s.selectedTrack != track) {
            val t = Parse.tracks(request(Requests.track(track)))
            _state.update { it.copy(tracks = t, selectedTrack = t.sel) }
        }
        request(Requests.set(1, eng, s.fm6Engine))
        markSelfReload()
        reloadAfterChange()
    }

    // ------------------------------------------------- step extras (proto v7/v8) ---

    /** Sets step [step]'s nudge, -32..31 in 1/64 of a step (0 = on the grid). Sent at once, not drafted. */
    fun setStepMicro(step: Int, nudge: Int) {
        val s = _state.value
        if (s.link != Link.READY || step !in s.micro.indices) return
        val tr = s.selectedTrack
        val v = nudge.coerceIn(-32, 31)
        _state.update { st -> if (st.selectedTrack != tr) st
            else st.copy(micro = st.micro.toMutableList().also { it[step] = v }) }
        scope.launch { runCatching { request(Requests.microSet(tr, step, v)) } }
    }

    /** Sets step [step]'s fill condition: 0 always, 1 fill only, 2 never during a fill. */
    fun setStepFill(step: Int, cond: Int) {
        val s = _state.value
        if (s.link != Link.READY || step !in s.fill.indices) return
        val tr = s.selectedTrack
        val v = cond % 3
        _state.update { st -> if (st.selectedTrack != tr) st
            else st.copy(fill = st.fill.toMutableList().also { it[step] = v }) }
        scope.launch { runCatching { request(Requests.fillSet(tr, step, v)) } }
    }

    /** Adds/updates the lock on (step, param); [value] = null deletes it. */
    fun setStepLock(step: Int, param: Int, value: Int?) {
        val s = _state.value
        if (s.link != Link.READY || s.info == null) return
        val tr = s.selectedTrack
        scope.launch {
            runCatching {
                val r = Parse.lockSet(request(Requests.lockSet(tr, step, param, value)))
                _state.update { st ->
                    if (st.selectedTrack != tr) st else st.copy(
                        locks = when {
                            r.rc != 0 -> st.locks
                            r.value != null -> st.locks
                                .filterNot { it.step == r.step && it.param == r.param } +
                                StepLock(r.step, r.param, r.value)
                            else -> st.locks.filterNot { it.step == r.step && it.param == r.param }
                        },
                        queueError = when (r.rc) {
                            0 -> st.queueError
                            3 -> "No free lock slots on this track"
                            else -> "Parameter cannot be locked"
                        })
                }
            }
        }
    }

    // ---------------------------------------------------------------- steps ---

    fun setStep(index: Int, step: Step) {
        val track = _state.value.selectedTrack
        applySynthBatch(listOf(UserEdit.Synth(track, index, step)))
    }

    fun setDrumStep(index: Int, ds: DrumStep) {
        val s = _state.value
        if (s.link != Link.READY || index !in s.drumSteps.indices) return
        val track = s.selectedTrack
        if (s.sequencerMode == SequencerMode.NOW && !enqueue(UserEdit.Drum(index, ds))) return
        val list = s.drumSteps.toMutableList().also { it[index] = ds }
        if (s.sequencerMode == SequencerMode.LATE) synchronized(draftLock) { draftDrum[track] = list }
        _state.update { current ->
            if (current.selectedTrack != track) current else current.copy(drumSteps = list,
                draftTracks = draftIds())
        }
    }

    private fun editNotes(transform: (DeviceState, List<PNote>) -> List<PNote>?) {
        val s = _state.value
        if (s.link != Link.READY || s.isDrum) return
        val len = s.patternLength
        val transformed = transform(s, decodeNotes(s.steps, len)) ?: return
        val changed = encodeNotes(s.steps, len, normalizeNotes(s, transformed, len))
        if (changed == null) {
            _state.update { it.copy(queueError = "Four notes maximum per step") }
            return
        }
        if (changed.isNotEmpty()) applySynthBatch(changed.map { UserEdit.Synth(s.selectedTrack, it.index, it) })
    }

    /** Adds a note of [len] steps at [index], or removes the note of that pitch sounding there. */
    fun toggleNote(index: Int, note: Int, len: Int = 1) {
        if (index !in 0 until _state.value.patternLength || note !in 0..127) return
        editNotes { s, notes ->
            val hit = notes.firstOrNull { it.pitch == note && index in it.start..it.end }
            if (hit != null) notes - hit else {
                val maxEnd = notes.filter { it.pitch == note && it.start > index }
                    .minOfOrNull { it.start - 1 } ?: (s.patternLength - 1)
                val n = PNote(index, len.coerceIn(1, maxEnd - index + 1), note)
                if (voiceMode(s) == V_POLY) notes + n else notes.carveCovered(n) + n
            }
        }
    }

    /** Moves the note that starts at [fromIndex] with pitch [fromNote]; its length is kept. */
    fun moveNote(fromIndex: Int, fromNote: Int, toIndex: Int, toNote: Int) {
        if (toIndex !in 0 until _state.value.patternLength || toNote !in 0..127) return
        if (fromIndex == toIndex && fromNote == toNote) return
        editNotes { s, notes ->
            val n = notes.firstOrNull { it.pitch == fromNote && it.start == fromIndex } ?: return@editNotes null
            val rest = notes - n
            val moved = PNote(toIndex, minOf(n.len, s.patternLength - toIndex), toNote)
            if (rest.any { it.pitch == toNote && it.start <= moved.end && it.end >= moved.start }) {
                _state.update { it.copy(queueError = "Note already there") }
                return@editNotes null
            }
            if (voiceMode(s) == V_POLY) rest + moved else rest.carveCovered(moved) + moved
        }
    }

    /** Shifts the left edge of the note that starts at [start] by [delta] steps, keeping its end. */
    fun adjustNoteStart(start: Int, pitch: Int, delta: Int) {
        editNotes { s, notes ->
            val n = notes.firstOrNull { it.pitch == pitch && it.start == start } ?: return@editNotes null
            if (delta < 0 && s.steps.getOrNull(n.start)?.rat.let { it != null && it != 0 }) {
                _state.update { it.copy(queueError = "Remove ratchets before extending a note") }
                return@editNotes null
            }
            val minStart = notes.filter { it.pitch == pitch && it.end < n.start }
                .maxOfOrNull { it.end + 1 } ?: 0
            var ns = (n.start + delta).coerceIn(minStart, n.end)
            notes.firstOrNull { it !== n && it.pitch == pitch && ns in it.start..it.end }
                ?.let { ns = it.end + 1 }
            if (ns == n.start || ns > n.end) null else {
                val m = n.copy(start = ns, len = n.end - ns + 1)
                if (voiceMode(s) == V_POLY) notes - n + m else (notes - n).carveCovered(m) + m
            }
        }
    }

    /** Resizes the note of [pitch] starting at [start]. */
    fun resizeNote(start: Int, pitch: Int, requestedLength: Int) {
        editNotes { s, notes ->
            val n = notes.firstOrNull { it.pitch == pitch && it.start == start } ?: return@editNotes null
            val resized = resized(s, notes, n, requestedLength) ?: return@editNotes null
            if (voiceMode(s) == V_POLY) notes - n + resized
            else (notes - n).carveCovered(resized) + resized
        }
    }

    /** Resizes every note that starts at [index] (a chord shares one duration in the step editor). */
    fun setNoteLength(index: Int, requestedLength: Int) {
        editNotes { s, notes ->
            val starting = notes.filter { it.start == index }
            if (starting.isEmpty()) return@editNotes null
            var out = notes
            starting.forEach { n -> resized(s, out, n, requestedLength)?.let { r ->
                out = if (voiceMode(s) == V_POLY) out - n + r else (out - n).carveCovered(r) + r
            } }
            out.takeIf { it != notes }
        }
    }

    private fun resized(s: DeviceState, notes: List<PNote>, n: PNote, requested: Int): PNote? {
        if (s.steps.getOrNull(n.start)?.rat.let { it != null && it != 0 } && requested > 1) {
            _state.update { it.copy(queueError = "Remove ratchets before extending a note") }
            return null
        }
        val maxEnd = notes.filter { it.pitch == n.pitch && it.start > n.start }.minOfOrNull { it.start - 1 }
            ?: (s.patternLength - 1)
        val len = requested.coerceIn(1, maxEnd - n.start + 1)
        return if (len == n.len) null else n.copy(len = len)
    }

    private fun applySynthBatch(batch: List<UserEdit.Synth>) {
        val s = _state.value
        if (s.link != Link.READY || batch.isEmpty() ||
            batch.any { it.track != s.selectedTrack || it.index !in s.steps.indices }) return
        if (s.sequencerMode == SequencerMode.NOW && !enqueueBatch(batch)) return
        val list = s.steps.toMutableList()
        batch.forEach { list[it.index] = it.step }
        if (s.sequencerMode == SequencerMode.LATE) synchronized(draftLock) { draftSynth[s.selectedTrack] = list }
        _state.update { current ->
            if (current.selectedTrack != s.selectedTrack) current else current.copy(steps = list,
                draftTracks = draftIds())
        }
    }

    private fun enqueueBatch(batch: List<UserEdit>): Boolean {
        if (_state.value.link != Link.READY) return false
        val size = synchronized(pendingLock) {
            if (edits.size + batch.size > maxEdits) -1
            else { batch.forEach { edits.addLast(it) }; edits.size }
        }
        if (size < 0) { _state.update { it.copy(queueError = "Queue full — wait for MIDI or retry") }; return false }
        _state.update { it.copy(queuedEdits = synchronized(pendingLock) { edits.size }) }
        editSignal.trySend(Unit)
        return true
    }

    fun clearPattern() {
        val s = _state.value
        if (s.link != Link.READY) return
        for (i in 0 until s.patternLength) {
            if (s.drumGrid) {
                val d = s.drumSteps.getOrNull(i) ?: continue
                if (d.on != 0) setDrumStep(i, DrumStep(i, 0, IntArray(16), IntArray(16)))
            } else {
                val step = s.steps.getOrNull(i) ?: continue
                if (step.n != 0 || step.time != 2) setStep(i,
                    Step(i, 0, intArrayOf(0, 0, 0, 0), 2, 0, 0))
            }
        }
    }

    fun setSequencerMode(mode: SequencerMode): Boolean {
        if (_state.value.link != Link.READY) return false
        if (mode == SequencerMode.NOW && draftIds().isNotEmpty()) return false
        _state.update { it.copy(sequencerMode = mode) }
        return true
    }

    fun sendCurrentPattern(): Boolean {
        val s = _state.value
        val info = s.info ?: return false
        if (s.link != Link.READY || s.sequencerMode != SequencerMode.LATE) return false
        val track = s.selectedTrack
        val batch: List<UserEdit> = if (s.drumGrid) {
            val steps = synchronized(draftLock) { draftDrum[track] } ?: s.drumSteps
            if (steps.size != info.nstep) return false
            steps.mapIndexed { index, step -> UserEdit.Drum(index, step) }
        } else {
            val steps = synchronized(draftLock) { draftSynth[track] } ?: s.steps
            if (steps.size != info.nstep) return false
            steps.mapIndexed { index, step -> UserEdit.Synth(track, index, step) }
        }
        if (!enqueueBatch(batch)) return false
        synchronized(draftLock) { draftSynth.remove(track); draftDrum.remove(track) }
        _state.update { it.copy(draftTracks = draftIds()) }
        return true
    }

    fun sendDraftsAndSwitch(): Boolean {
        val s = _state.value
        val info = s.info ?: return false
        if (s.link != Link.READY) return false
        val batch = synchronized(draftLock) {
            if (draftSynth.values.any { it.size != info.nstep } ||
                draftDrum.values.any { it.size != info.nstep }) return false
            val all = ArrayList<UserEdit>()
            draftSynth.forEach { (track, steps) ->
                steps.forEachIndexed { index, step -> all.add(UserEdit.Synth(track, index, step)) }
            }
            draftDrum.forEach { (_, steps) ->
                steps.forEachIndexed { index, step -> all.add(UserEdit.Drum(index, step)) }
            }
            all
        }
        if (batch.isNotEmpty() && !enqueueBatch(batch)) return false
        clearDrafts()
        _state.update { it.copy(sequencerMode = SequencerMode.NOW, draftTracks = emptySet()) }
        return true
    }

    fun discardDraftsAndSwitch() {
        clearDrafts()
        _state.update { it.copy(sequencerMode = SequencerMode.NOW, draftTracks = emptySet(),
            link = Link.LOADING, status = "Refreshing pattern…",
            steps = emptyList(), drumSteps = emptyList()) }
        scope.launch {
            val s = _state.value
            val info = s.info ?: return@launch
            val dump = s.dump ?: return@launch
            val result = runCatching { loadSteps(info, dump) }
            _state.update { it.copy(link = Link.READY, status = "Connected",
                queueError = if (result.isFailure) "Cannot reload pattern from device" else it.queueError) }
        }
    }

    private fun enqueue(edit: UserEdit, continuous: Boolean = false): Boolean {
        if (_state.value.link != Link.READY) return false
        val size = synchronized(pendingLock) {
            if (continuous) {
                for (i in edits.lastIndex downTo if (sending) 1 else 0) {
                    val previous = edits[i]
                    if ((previous is UserEdit.Parameter && edit is UserEdit.Parameter &&
                        previous.scope == edit.scope && previous.id == edit.id && previous.track == edit.track) ||
                        (previous is UserEdit.Mix && edit is UserEdit.Mix && previous.track == edit.track)) {
                        edits.removeAt(i)
                    }
                }
            }
            if (edits.size >= maxEdits) -1 else { edits.addLast(edit); edits.size }
        }
        if (size < 0) {
            _state.update { it.copy(queueError = "Queue full — wait for MIDI or retry") }
            return false
        }
        _state.update { it.copy(queuedEdits = synchronized(pendingLock) { edits.size }) }
        editSignal.trySend(Unit)
        return true
    }

    fun retryEdits() {
        synchronized(pendingLock) { queuePaused = false }
        _state.update { it.copy(queueError = null) }
        editSignal.trySend(Unit)
    }

    private suspend fun editWorker() {
        for (unit in editSignal) {
            while (true) {
                val next = synchronized(pendingLock) {
                    if (queuePaused || edits.isEmpty()) null
                    else (edits.first() to editGeneration).also { sending = true }
                } ?: break
                val result = runCatching { sendEdit(next.first) }
                val current = synchronized(pendingLock) {
                    if (next.second != editGeneration) false else {
                        sending = false
                        if (result.isSuccess && edits.firstOrNull() == next.first) edits.removeFirst()
                        else if (result.isFailure) queuePaused = true
                        true
                    }
                }
                if (!current) continue
                _state.update { it.copy(queuedEdits = synchronized(pendingLock) { edits.size },
                    queueError = if (result.isFailure) "MIDI reply missing — tap Retry in Device" else null) }
                if (result.isFailure) break
            }
        }
    }

    private suspend fun sendEdit(edit: UserEdit) {
        when (edit) {
            is UserEdit.Parameter -> {
                val info = _state.value.info
                val reply = if (edit.scope == 0 && (info?.proto ?: 0) >= 4)
                    request(Requests.trackParamSet(edit.track, edit.id, edit.value))
                else request(Requests.set(edit.scope, edit.id, edit.value))
                val value = if (edit.scope == 0 && (info?.proto ?: 0) >= 4)
                    Parse.trackParam(reply).value else Parse.param(reply).value // clamped value echoed back
                synchronized(pendingLock) { lastParamSent[paramKey(edit.scope, edit.id, edit.track)] = value }
                _state.update { s ->
                    if ((edit.scope == 1 || s.selectedTrack == edit.track) &&
                        (if (edit.scope == 0) s.paramValue(edit.id) else s.globalValue(edit.id)) == edit.value)
                        s.withParam(edit.scope, edit.id, value) else s
                }
            }
            is UserEdit.Mix -> {
                val reply = Parse.trackMix(request(Requests.trackMix(edit.track, edit.level, edit.mute)))
                _state.update { s ->
                    val tr = s.tracks?.tracks?.getOrNull(edit.track)
                    if (tr?.level != edit.level || (tr?.mute != 0) != edit.mute) s
                    else s.withTrackMix(edit.track, reply.level, reply.mute != 0)
                }
            }
            is UserEdit.Synth -> {
                val reply = if ((_state.value.info?.ntrk ?: 0) > 0)
                    Parse.trackStep(request(Requests.trackStepSet(edit.track, edit.index, edit.step))).step
                else Parse.step(request(Requests.stepSet(edit.index, edit.step)))
                _state.update { s ->
                    if (s.selectedTrack != edit.track || s.steps.getOrNull(edit.index) != edit.step) s
                    else s.copy(steps = s.steps.toMutableList().also { it[edit.index] = reply })
                }
            }
            is UserEdit.Drum -> {
                val reply = Parse.drumStep(request(Requests.drumStepSet(edit.index, edit.step)))
                _state.update { s ->
                    if (s.drumSteps.getOrNull(edit.index) != edit.step) s
                    else s.copy(drumSteps = s.drumSteps.toMutableList().also { it[edit.index] = reply })
                }
            }
        }
    }

    private fun DeviceState.withDraft(): DeviceState {
        if (sequencerMode != SequencerMode.LATE) return this
        val synth = synchronized(draftLock) { draftSynth[selectedTrack] }
        val drum = synchronized(draftLock) { draftDrum[selectedTrack] }
        return when {
            drumGrid && drum != null -> copy(drumSteps = drum)
            !drumGrid && synth != null -> copy(steps = synth)
            else -> this
        }
    }

    private fun DeviceState.withQueuedEdits(): DeviceState {
        val snapshot = synchronized(pendingLock) { edits.toList() }
        return snapshot.fold(this) { current, edit ->
            when (edit) {
                is UserEdit.Parameter -> if (edit.scope == 1 || edit.track == current.selectedTrack)
                    current.withParam(edit.scope, edit.id, edit.value) else current
                is UserEdit.Mix -> current.withTrackMix(edit.track, edit.level, edit.mute)
                is UserEdit.Synth -> if (edit.track == current.selectedTrack && edit.index in current.steps.indices)
                    current.copy(steps = current.steps.toMutableList().also { it[edit.index] = edit.step }) else current
                is UserEdit.Drum -> if (current.drumGrid && edit.index in current.drumSteps.indices)
                    current.copy(drumSteps = current.drumSteps.toMutableList().also { it[edit.index] = edit.step }) else current
            }
        }
    }

    // ---------------------------------------------------------------- link ---

    /** Sends one request and awaits its reply. Serialized; retries once on timeout. */
    private suspend fun request(req: Req, timeout: Long = 400, retries: Int = 1, quiet: Boolean = false): IntArray {
        val c = conn ?: throw IOException("not connected")
        return linkMutex.withLock {
            var attempt = 0
            var result: IntArray? = null
            while (result == null) {
                val d = CompletableDeferred<IntArray>()
                inflight = Inflight(req.cmd, req.args, d)
                val res = try {
                    c.send(frame(req.cmd, req.args))
                    lastSent = SystemClock.elapsedRealtime()
                    withTimeoutOrNull(timeout) { d.await() }
                } finally {
                    inflight = null
                }
                if (res != null) result = res
                else if (attempt++ >= retries) throw IOException("timeout (cmd ${req.cmd})")
            }
            result
        }
    }

    /** Incoming reassembled frame from the MIDI thread. */
    private fun onFrame(bytes: ByteArray) {
        val f = unframe(bytes) ?: return
        if (f.cmd in Cmd.PUSH) {
            handlePush(f.cmd, f.args)
            return
        }
        val cur = inflight
        if (cur != null && f.cmd == cur.cmd && replyMatches(cur.cmd, cur.args, f.args)) {
            cur.deferred.complete(f.args)
        }
    }

    // ---------------------------------------------------------------- push ---

    @Volatile private var selfReloadUntil = 0L

    private fun handlePush(cmd: Int, args: IntArray) {
        when (cmd) {
            Cmd.CHANGED -> {
                val pv = Parse.param(args)
                _state.update { s -> s.withParam(pv.scope, pv.id, pv.value) }
            }
            Cmd.RELOAD -> {
                if (System.currentTimeMillis() < selfReloadUntil) return
                scope.launch { runCatching { reloadAfterChange() } }
            }
            Cmd.STEP_CHANGED -> {
                val sc = Parse.stepChanged(args)
                scope.launch { runCatching { reloadOneStep(sc.index) } }
            }
            Cmd.TRACK_CHANGED -> {
                val tp = Parse.trackParam(args)
                _state.update { s ->
                    val t = s.tracks ?: return@update s
                    val list = t.tracks.toMutableList()
                    // Only level/pan/mute are pushed; reflect level + mute in the mixer.
                    if (tp.track < list.size) {
                        val tr = list[tp.track]
                        list[tp.track] = when (s.pdesc.getOrNull(tp.id)?.label) {
                            "LEVEL" -> tr.copy(level = tp.value)
                            "MUTE" -> tr.copy(mute = tp.value)
                            else -> tr
                        }
                    }
                    s.copy(tracks = t.copy(tracks = list))
                }
            }
        }
    }

    private suspend fun reloadAfterChange() {
        val info = _state.value.info ?: return
        // Engine params may have changed meaning: re-read their DESC.
        for (id in info.pe0 until info.pcount) {
            runCatching { Parse.desc(request(Requests.desc(0, id))) }.getOrNull()?.let { d ->
                _state.update { s -> val l = s.pdesc.toMutableList(); if (id < l.size) l[id] = d; s.copy(pdesc = l) }
            }
        }
        val dump = Parse.dump(request(Requests.dump()), info)
        ensurePresetNames(dump.engine, info)
        val tracks = runCatching { Parse.tracks(request(Requests.track())) }.getOrNull()
        _state.update { it.copy(dump = dump, tracks = tracks ?: it.tracks,
            selectedTrack = tracks?.sel ?: it.selectedTrack).withQueuedEdits() }
        loadSteps(info, dump)
        loadStepExtras(info)
    }

    private suspend fun reloadOneStep(index: Int) {
        val s = _state.value
        if (s.sequencerMode == SequencerMode.LATE && s.selectedTrack in s.draftTracks) return
        val info = s.info ?: return
        if (s.drumGrid) {
            val ds = Parse.drumStep(request(Requests.drumStepGet(index)))
            _state.update { st -> val l = st.drumSteps.toMutableList(); if (index < l.size) l[index] = ds
                st.copy(drumSteps = l).withQueuedEdits().withDraft() }
        } else {
            val step = Parse.step(request(Requests.stepGet(index)))
            _state.update { st -> val l = st.steps.toMutableList(); if (index < l.size) l[index] = step
                st.copy(steps = l).withQueuedEdits().withDraft() }
        }
        // v7+: a step's signature covers its nudge, locks and fill — the push may mean one of them changed
        if (info.proto >= 7) loadStepExtras(info)
    }

    // ---------------------------------------------------------------- keepalive ---

    private fun startKeepAlive() {
        keepAlive?.cancel()
        keepAlive = scope.launch {
            while (true) {
                delay(1000)
                if (conn == null) break
                val editsWaiting = synchronized(pendingLock) {
                    edits.isNotEmpty() && !queuePaused
                }
                if (!editsWaiting && !linkMutex.isLocked && SystemClock.elapsedRealtime() - lastSent >= 1800)
                    runCatching { request(Requests.ping(), quiet = true) }
            }
        }
    }

    fun markSelfReload() { selfReloadUntil = System.currentTimeMillis() + 1500 }

    fun release() {
        keepAlive?.cancel()
        scope.coroutineContext[Job]?.cancelChildren()
        synchronized(pendingLock) { edits.clear(); editGeneration++ }
        clearDrafts()
        conn?.close(); conn = null
        engine.release()
    }

    private suspend fun Mutex.withLockSafe(block: suspend () -> Unit) {
        if (tryLock()) try { block() } finally { unlock() }
    }

    companion object { private const val TAG = "SloopGo" }
}
