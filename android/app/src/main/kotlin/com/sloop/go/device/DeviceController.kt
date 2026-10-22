// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sloop.go.midi.MidiConnection
import com.sloop.go.midi.MidiEngine
import com.sloop.go.proto.Arr
import com.sloop.go.proto.Backup
import com.sloop.go.proto.Cmd
import com.sloop.go.proto.Desc
import com.sloop.go.proto.DrumStep
import com.sloop.go.proto.Dump
import com.sloop.go.proto.Fm6
import com.sloop.go.proto.Fm6Bank
import com.sloop.go.proto.Fmt
import com.sloop.go.proto.Info
import com.sloop.go.proto.Live
import com.sloop.go.proto.Parse
import com.sloop.go.proto.Req
import com.sloop.go.proto.Requests
import com.sloop.go.proto.Smp
import com.sloop.go.proto.Step
import com.sloop.go.proto.StepLock
import com.sloop.go.proto.frame
import com.sloop.go.proto.replyMatches
import com.sloop.go.proto.unframe
import com.sloop.go.store.Clip
import com.sloop.go.store.ClipKind
import com.sloop.go.store.ClipNote
import com.sloop.go.store.Smf
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    /** The notes sounding on the FM-1 now (keys pressed, and the sequencer's with MIDI OUT = SEQ), from its USB MIDI out. */
    private val _live = MutableStateFlow(LiveNotes())
    val live: StateFlow<LiveNotes> = _live.asStateFlow()

    /** SLOOP 2.7 (proto 12): what the FM-1 itself is doing (transport, playhead, song, drum TUNE / DECAY), polled through LIVE.
     *  Its own flow: it changes several times a second, only the screens that draw it observe it. */
    private val _transport = MutableStateFlow<Live?>(null)
    /** The playhead between answers (see [PlayClock]). */
    val clock = PlayClock()
    val transport: StateFlow<Live?> = _transport.asStateFlow()
    val recordingTrace = RecordingTrace()

    /** A parameter the FM-1 itself changed (CHANGED push of the selected track or a global): scope, id, value. */
    @Volatile var onParamPush: ((Int, Int, Int) -> Boolean)? = null

    /** Undo / redo, in RAM only: every edit is also noted here and is put back through the very same queue. */
    val history = EditHistory(onChange = { u, r -> _state.update { it.copy(canUndo = u, canRedo = r) } })
    private val historyMutex = Mutex()

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
        data class Transpose(val track: Int, val delta: Int, val before: List<Step>, val after: List<Step>) : UserEdit
        data class Drum(val index: Int, val step: DrumStep) : UserEdit
    }
    private val edits = ArrayDeque<UserEdit>()
    private val pendingLock = Any()
    private val editSignal = Channel<Unit>(Channel.CONFLATED)
    private var sending = false
    private var editGeneration = 0
    private var queuePaused = false
    @Volatile private var nativeTransposeTrack: Int? = null
    private val maxEdits = 16384
    private val draftLock = Any()
    private val draftSynth = mutableMapOf<Int, List<Step>>()
    private val draftDrum = mutableMapOf<Int, List<DrumStep>>()

    private var keepAlive: Job? = null

    // ---- LIVE page ops (SLOOP 2.7): one ordered queue, so a pad's down always reaches the device before its up ----
    private val liveOps = Channel<Req>(Channel.UNLIMITED)
    private val liveMasterQ = Channel<Int>(Channel.CONFLATED)
    private val holdOps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val held = HashMap<Triple<Int, Int, Int>, Int>()
    private class SlicerHold(val connection: MidiConnection, val values: Map<Int, Int>)
    private val heldSlicers = HashMap<Int, SlicerHold>()

    // params.c: SLCR=STUT (2), PAT=16, DEPTH=100% (127). RATE (47) is left alone:
    // STU keeps whatever rate the track already has instead of forcing 1/16.
    // Configure the pattern/depth before enabling STUT; restore mode last too.
    private val stutterParams = listOf(46 to 16, 48 to 127, 45 to 2)

    init {
        scope.launch { editWorker() }
        scope.launch { for (r in liveOps) runCatching { applyLive(Parse.live(request(r, quiet = true))) } }
        scope.launch { for (op in holdOps) runCatching { op() } }
        scope.launch { for (v in liveMasterQ) runCatching { applyLive(Parse.live(request(Requests.live(11, v), quiet = true))) } }
    }

    /** A key of a layer on the FM-1 (6 = GLO: white keys 0..3 mute, 4..7 solo, 8 fill held, 9 fill next bar, 15 tap tempo). */
    fun liveKey(layer: Int, key: Int, down: Boolean) { liveOps.trySend(Requests.live(2, layer, key, if (down) 1 else 0)) }
    fun liveTap(layer: Int, key: Int) { liveKey(layer, key, true); liveKey(layer, key, false) }
    /** A punch-in effect (0..26) while its pad is held. */
    fun liveFx(fx: Int, on: Boolean) { liveOps.trySend(Requests.live(3, fx, if (on) 1 else 0)) }
    /** THROW bits while held: 1 reverb, 2 echo (0: let go). */
    fun liveThrow(bits: Int) { liveOps.trySend(Requests.live(10, bits)) }
    /** The master level from the page, 0..126; 127 gives it back to the MASTER knob. */
    fun liveMaster(v: Int) { liveMasterQ.trySend(v.coerceIn(0, 127)) }

    // ---- hold-to-max sends (LIVE page): while held a track's send goes to its maximum, on release it returns to what it was ----

    /** A track parameter, or with [global] a global one (the drums' sends), held at its maximum. */
    fun holdParam(track: Int, id: Int, down: Boolean, global: Boolean = false) {
        holdOps.trySend {
            val scope = if (global) 1 else 0
            val key = Triple(scope, track, id)
            val info = _state.value.info
            if ((info?.proto ?: 0) < 4) return@trySend
            val value = if (down) {
                if (key in held) return@trySend
                held[key] = if (global) Parse.param(request(Requests.get(1, id), quiet = true)).value
                    else Parse.trackParam(request(Requests.trackParamGet(track, id), quiet = true)).value
                (if (global) _state.value.gdesc else _state.value.pdesc).getOrNull(id)?.max ?: 127
            } else held.remove(key) ?: return@trySend
            val v = if (global) Parse.param(request(Requests.set(1, id, value), quiet = true)).value
                else Parse.trackParam(request(Requests.trackParamSet(track, id, value), quiet = true)).value
            synchronized(pendingLock) { lastParamSent[paramKey(scope, id, track)] = v }
            _state.update { s -> if (global || s.selectedTrack == track) s.withParam(scope, id, v) else s }
        }
    }

    /** Momentary Slicer preset for any track, including drums. Down/up share the
     * hold queue so even a quick release restores the snapshot taken by its press. */
    fun holdSlicer(track: Int, down: Boolean) {
        val connection = conn ?: return
        if (down && (_state.value.link != Link.READY || (_state.value.info?.proto ?: 0) < 4 ||
            track !in 0 until (_state.value.info?.ntrk ?: 0))) return
        holdOps.trySend {
            if (conn !== connection) return@trySend
            heldSlicers[track]?.takeIf { it.connection !== connection }?.let { heldSlicers.remove(track) }
            if (down) {
                if (track in heldSlicers) return@trySend
                try {
                    val values = linkedMapOf<Int, Int>()
                    // Read every original before writing anything. Never snapshot a
                    // partly applied preset, or use the currently selected track's dump.
                    for ((id, _) in stutterParams) {
                        check(conn === connection) { "FM-1 disconnected" }
                        values[id] = Parse.trackParam(request(Requests.trackParamGet(track, id), quiet = true)).value
                    }
                    heldSlicers[track] = SlicerHold(connection, values)
                    for ((id, value) in stutterParams) writeSlicerParam(connection, track, id, value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A timeout may follow a successful device write. Restore all
                    // originals, including parameters whose acknowledgement was lost.
                    if (restoreSlicer(track))
                        _state.update { it.copy(queueError = "STU could not be applied: ${e.message}") }
                }
            } else restoreSlicer(track)
        }
    }

    private suspend fun writeSlicerParam(connection: MidiConnection, track: Int, id: Int, value: Int) {
        check(conn === connection) { "FM-1 disconnected" }
        val v = Parse.trackParam(request(Requests.trackParamSet(track, id, value), quiet = true)).value
        if (conn !== connection) return
        synchronized(pendingLock) { lastParamSent[paramKey(0, id, track)] = v }
        _state.update { s -> if (s.selectedTrack == track) s.withParam(0, id, v) else s }
    }

    private suspend fun restoreSlicer(track: Int): Boolean {
        val saved = heldSlicers[track] ?: return true
        if (conn !== saved.connection) {
            heldSlicers.remove(track)
            return true
        }
        var failure: Exception? = null
        for ((id, value) in saved.values) {
            try { writeSlicerParam(saved.connection, track, id, value) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e } // still attempt the remaining originals
        }
        if (failure == null) heldSlicers.remove(track)
        else _state.update { it.copy(queueError = "STU restore failed — release STU again to retry") }
        return failure == null
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
            _live.value = LiveNotes()
            val c = engine.open(deviceId, onReceive = ::onFrame, onLost = { onLost() },
                onNote = { ch, note, on ->
                    _live.update { it.with(ch, note, on) }
                    recordingTrace.note(ch, note, on)
                    // Recording: all keys up means the just-played phrase is written — read it soon.
                    if (!on && recordingActive() && !_live.value.anyHeld()) scheduleResync(300)
                })
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
        resyncJob?.cancel(); resyncJob = null
        recordingTrace.stop()
        keepAlive?.cancel(); keepAlive = null
        inflight?.deferred?.cancel()
        inflight = null
        linkMutex.withLockSafe { }
        try { if (conn != null) runCatching { request(Requests.watch(0), quiet = true) } } catch (_: Exception) {}
        conn?.close(); conn = null
        _live.value = LiveNotes()
        _transport.value = null
        clock.reset()
        val unsent = synchronized(pendingLock) {
            val count = edits.size
            edits.clear(); sending = false; queuePaused = false; editGeneration++
            count
        }
        val drafts = clearDrafts()
        history.clear()
        if (!silent) _state.value = DeviceState(status =
            if (unsent == 0 && drafts == 0) "Disconnected"
            else "Disconnected: $unsent queued edits and $drafts Late patterns discarded")
    }

    private fun onLost() {
        scope.launch {
            resyncJob?.cancel(); resyncJob = null
            recordingTrace.stop()
            conn?.close(); conn = null
            _live.value = LiveNotes()
            _transport.value = null
            clock.reset()
            keepAlive?.cancel(); keepAlive = null
            val unsent = synchronized(pendingLock) {
                val count = edits.size
                edits.clear(); sending = false; queuePaused = false; editGeneration++
                count
            }
            val drafts = clearDrafts()
            history.clear()
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
        smpRefresh()
        // Ask the device to push live changes (v4 WATCH 3; v2/v3 answer 1).
        runCatching { request(Requests.watch(3)) }
    }

    private suspend fun ensurePresetNames(engine: Int, info: Info) {
        if (engine >= info.nengines || engine in _state.value.presetNames) return
        runCatching { Parse.names(request(Requests.names(engine))) }.getOrNull()?.let { reply ->
            _state.update { it.copy(presetNames = it.presetNames + (reply.engine to reply.names)) }
        }
    }

    private suspend fun loadSteps(info: Info, dump: Dump, unchangedSince: DeviceState? = null) {
        val track = _state.value.selectedTrack
        val connection = conn
        fun current(st: DeviceState): Boolean =
            conn === connection && st.selectedTrack == track &&
                (unchangedSince == null ||
                    (st.link == Link.READY && st.steps === unchangedSince.steps &&
                        st.drumSteps === unchangedSince.drumSteps))
        val drum = dump.engine >= info.nengines && info.proto >= 5
        if (drum) {
            val ds = ArrayList<DrumStep>(info.nstep)
            for (i in 0 until info.nstep) {
                if (!current(_state.value)) return
                ds.add(Parse.drumStep(request(Requests.drumStepGet(i))))
            }
            _state.update { if (!current(it)) it
                else it.copy(drumSteps = ds, steps = emptyList(), intentNotes = null)
                    .withQueuedEdits().withDraft() }
        } else {
            val st = ArrayList<Step>(info.nstep)
            for (i in 0 until info.nstep) {
                if (!current(_state.value)) return
                st.add(if (info.ntrk > 0) Parse.trackStep(request(Requests.trackStepGet(track, i))).step
                    else Parse.step(request(Requests.stepGet(i))))
            }
            _state.update { if (!current(it)) it
                else it.copy(steps = st, drumSteps = emptyList(), intentNotes = null)
                    .withQueuedEdits().withDraft() }
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
    fun setParam(scope: Int, id: Int, value: Int, record: Boolean = true, trackOverride: Int? = null,
                 glide: Boolean = true, optimistic: Boolean = true) {
        val s = _state.value
        if (s.link != Link.READY) return
        val track = trackOverride ?: s.selectedTrack
        val desc = (if (scope == 0) s.pdesc else s.gdesc).getOrNull(id)
        val was = if (scope == 0) s.paramValue(id) else s.globalValue(id)
        // Discrete choices write once: stepping through them would flip intermediate patches/engines.
        val discrete = !glide || desc != null &&
            (desc.fmt == Fmt.ENUM || desc.fmt == Fmt.ONOFF || desc.names.isNotEmpty())
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
            val count = if (discrete) 0 else kotlin.math.abs(value - base) / chunk
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
        if (optimistic && (scope == 1 || track == s.selectedTrack))
            _state.update { it.withParam(scope, id, value) } // optimistic UI
        if (record) {
            // swapping the engine changes what every parameter of the track means: older edits cannot be put back
            if (scope == 1 && desc?.label == "ENG") history.dropTrack(track)
            else if (was != value) history.record(Change.Param(scope, id, if (scope == 0) track else -1, was, value))
        }
    }

    /** Loads a factory preset of [engine] into the selected synth track (the device pushes RELOAD afterwards). */
    fun selectPreset(engine: Int, preset: Int) {
        val s = _state.value
        if (s.link != Link.READY || s.isDrum || s.dump == null) return
        history.dropTrack(s.selectedTrack)
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
            if ((_state.value.info?.proto ?: 0) >= 12) {
                runCatching { applyLive(Parse.live(request(Requests.livePlay(play)))) }
                    .onSuccess { _state.update { it.copy(playing = play) } }
                    .onFailure { _state.update { it.copy(queueError = "Transport send failed") } }
                return@launch
            }
            runCatching {
                if (_state.value.globalValue(G_SYNC) != 1)
                    request(Requests.set(1, G_SYNC, 1))
                c.send(byteArrayOf(if (play) 0xFA.toByte() else 0xFC.toByte()))
            }.onSuccess { _state.update { it.copy(playing = play) } }
                .onFailure { _state.update { it.copy(queueError = "Transport send failed") } }
        }
    }

    /** Drum lane [lane]'s TUNE (-8..7) and DECAY (-4..3) on the device (SLOOP 2.7, LIVE op 12). */
    fun setDrumSound(lane: Int, tune: Int, decay: Int) {
        if ((_state.value.info?.proto ?: 0) < 12) return
        _transport.value?.let { lv ->
            _transport.value = lv.copy(drumTune = lv.drumTune.copyOf().also { it[lane and 15] = tune },
                drumDecay = lv.drumDecay.copyOf().also { it[lane and 15] = decay })
        }
        scope.launch { runCatching { applyLive(Parse.live(request(Requests.liveDrumSound(lane, tune, decay)))) } }
    }

    /** True while the device records [track] live (LIVE's per-track rec bits, plus the armed/waiting states). */
    private fun recordingTrack(track: Int): Boolean {
        val lv = _transport.value ?: return false
        return lv.freeTake || lv.recWait || (lv.recs and (1 shl track)) != 0
    }

    /** True while any track is being recorded (covers the track-less drum edits). */
    private fun recordingActive(): Boolean =
        _transport.value?.let { it.freeTake || it.recWait || it.recs != 0 } == true

    /** A LIVE reply: the transport button follows the device (while a PLAY / STOP is still pending, the last answer holds). */
    private fun applyLive(lv: Live) {
        clock.feed(lv, System.nanoTime() / 1e9)
        val wasRecording = _transport.value?.let { it.freeTake || it.recWait || it.recs != 0 } == true
        _transport.value = lv
        // Step writes held back while a track recorded go out now.
        if (wasRecording && !(lv.freeTake || lv.recWait || lv.recs != 0) &&
            synchronized(pendingLock) { edits.isNotEmpty() && !sending }) editSignal.trySend(Unit)
        _state.update { s ->
            val play = if (lv.playAsked) s.playing else lv.playing
            if (s.playing == play) s else s.copy(playing = play)
        }
    }

    // ---------------------------------------------------------------- tracks ---


    fun selectTrack(track: Int) {
        if (_state.value.link != Link.READY) return
        resyncJob?.cancel(); resyncJob = null
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
                applyFm6Names()
            }
            _state.update { it.copy(link = Link.READY,
                status = if (result.isSuccess) "Connected" else "Track load failed — select it again") }
        }
    }

    fun setTrackMix(track: Int, level: Int, mute: Boolean, record: Boolean = true) {
        val was = _state.value.tracks?.tracks?.getOrNull(track)?.let { it.level to (it.mute != 0) }
        if (!enqueue(UserEdit.Mix(track, level, mute), continuous = true)) return
        _state.update { s -> s.withTrackMix(track, level, mute) }
        if (record && was != null && was != (level to mute)) history.record(Change.Mix(track, was, level to mute))
    }

    // ------------------------------------------------- FM6 patches (proto v9) ---

    /** Re-reads the slot names (FM6_LIST) into the state. False when the device has no FM6 patches. */
    suspend fun fm6Refresh(): Boolean {
        if (!_state.value.fm6Supported) return false
        val list = runCatching { Parse.fm6List(request(Requests.fm6List())) }.getOrNull() ?: return false
        _state.update { it.copy(fm6 = list) }
        applyFm6Names()
        return true
    }

    /**
     * The names the device shows for FM6's ALG (PAT, 1..32) and PTCH (F1..F8, B1..B27):
     * DESC sends none for F_INT, so the web editor injects them (editor.html fm6Names()). Bank slots
     * also carry the stored patch name. Applies only while the selected track plays FM6.
     */
    private fun applyFm6Names() {
        _state.update { s ->
            val info = s.info ?: return@update s
            val fm6 = s.fm6 ?: return@update s
            if (s.dump?.engine != s.fm6Engine) return@update s
            val list = s.pdesc.toMutableList()
            list.getOrNull(info.pe0)?.takeIf { it.label == "ALG" && it.fmt == Fmt.INT }?.let {
                list[info.pe0] = it.copy(names = listOf("PAT") + (1..32).map { n -> "$n" })
            }
            list.getOrNull(info.pe0 + 7)?.takeIf { it.label == "PTCH" && it.fmt == Fmt.INT }?.let { d ->
                list[info.pe0 + 7] = d.copy(names = List(fm6.factory + fm6.bank) { i ->
                    val nm = if (i < fm6.factory) "F${i + 1}" else "B${i - fm6.factory + 1}"
                    val slot = fm6.slots.getOrNull(i)
                    if (slot != null && slot.used && slot.name.isNotBlank()) "$nm · ${slot.name}" else nm
                })
            }
            s.copy(pdesc = list)
        }
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
        history.dropTrack(track)
        request(Requests.trackParamSet(track, id, slot))
        _state.update { s -> if (s.selectedTrack == track) s.withParam(0, id, slot) else s }
    }

    /** Makes [track] play the FM6 engine (selects it, sets ENG, reloads), like picking it on the device. */
    suspend fun fm6EnableTrack(track: Int) {
        val s = _state.value
        val eng = s.gdesc.indexOfFirst { it?.label == "ENG" }
        if (!s.fm6Supported || eng < 0) return
        history.dropTrack(track)
        if (s.selectedTrack != track) {
            val t = Parse.tracks(request(Requests.track(track)))
            _state.update { it.copy(tracks = t, selectedTrack = t.sel) }
        }
        request(Requests.set(1, eng, s.fm6Engine))
        markSelfReload()
        reloadAfterChange()
    }

    // ------------------------------------------------- user samples (USR1..4) ---

    /** Re-reads SMP_INFO into the state. False when the device does not answer. */
    suspend fun smpRefresh(): Boolean {
        val s = _state.value
        if (s.link != Link.LOADING && s.link != Link.READY) return false
        val info = runCatching { Parse.smpInfo(request(Requests.smpInfo(), timeout = 600)) }
            .getOrNull() ?: return false
        _state.update { it.copy(smp = info) }
        return true
    }

    /** Erases a whole USR slot (~1 s on the device). rc: 0 ok. */
    suspend fun smpErase(slot: Int): Int {
        val rc = Parse.smpRc(request(Requests.smpErase(slot), timeout = 2500, retries = 0))
        if (rc == 0) smpRefresh()
        return rc
    }

    /**
     * Builds and writes a slot: BEGIN (clears it), WRITE in 256-byte chunks at DATA_OFF + n
     * (a write at a 4 KiB boundary erases that sector on the device), END with the header.
     * [onProgress] gets 0..1. Throws SlotError/IOException on failure.
     */
    suspend fun smpUpload(slot: Int, name: String, zones: List<Smp.ZoneIn>,
                          onProgress: (Float) -> Unit = {}) {
        val (hdr, data) = withContext(Dispatchers.Default) { Smp.buildSlot(name, zones) }
        var rc = Parse.smpRc(request(Requests.smpBegin(slot), timeout = 1000, retries = 0))
        if (rc != 0) throw Smp.SlotError("begin failed (rc $rc)")
        var off = 0
        while (off < data.size) {
            val a = Smp.DATA_OFF + off
            val n = minOf(256, data.size - off)
            rc = Parse.smpWrite(request(Requests.smpWrite(slot, a, data, off, n),
                timeout = 1000, retries = 1))
            if (rc != 0) throw Smp.SlotError("write at 0x${a.toString(16)} failed (rc $rc)")
            onProgress(minOf(1f, (off + n).toFloat() / data.size))
            off += n
        }
        if (data.isEmpty()) onProgress(1f)
        rc = Parse.smpRc(request(Requests.smpEnd(slot, hdr), timeout = 2000, retries = 0))
        if (rc != 0) throw Smp.SlotError("store failed (rc $rc: " + when (rc) {
            1 -> "size"; 2 -> "header"; 3 -> "data CRC"; 4 -> "flash"; 5 -> "zones"
            else -> "?"
        } + ")")
        smpRefresh()
    }

    /** Track [track] plays the SAMPLE engine with SET = USR{slot+1} (the web editor's smpUseOn). */
    suspend fun smpUseOn(track: Int, slot: Int) {
        val s = _state.value
        val info = s.info ?: return
        history.dropTrack(track)
        if (s.selectedTrack != track) {
            val t = Parse.tracks(request(Requests.track(track)))
            _state.update { it.copy(tracks = t, selectedTrack = t.sel) }
            reloadAfterChange()
        }
        val e = info.engines.indexOf("SAMPLE")
        if (e < 0) throw Smp.SlotError("no SAMPLE engine")
        if (_state.value.dump?.engine != e) {
            markSelfReload()
            request(Requests.preset(e, 0))
            reloadAfterChange()
        }
        val d = _state.value.pdesc.getOrNull(info.pe0) ?: throw Smp.SlotError("no SET parameter")
        val v = d.names.indexOf("USR${slot + 1}")
        if (v < 0) throw Smp.SlotError("USR${slot + 1} not in SAMPLE sets")
        request(Requests.set(0, info.pe0, d.min + v))
        _state.update { it.withParam(0, info.pe0, d.min + v) }
    }

    // ------------------------------------------------- SYN drum kits (proto v10, SLOOP 2.5) ---

    suspend fun dsynList() = Parse.dsynList(request(Requests.dsynList(), timeout = 600))

    suspend fun dsynGet(which: Int) = Parse.dsynGet(request(Requests.dsynGet(which), timeout = 800))

    /** Writes one sound (22 bytes) of SYN{k+1}; the drum voices play it from RAM at once. rc 0 ok. */
    suspend fun dsynSound(k: Int, lane: Int, bytes: ByteArray) =
        Parse.dsynRc(request(Requests.dsynSound(k, lane, bytes), timeout = 600))

    suspend fun dsynHead(k: Int, name: String, crush: Int, src: Int) =
        Parse.dsynRc(request(Requests.dsynHead(k, name, crush, src), timeout = 600))

    /** Resets SYN{k+1} to a copy of factory kit [src]. */
    suspend fun dsynCopy(k: Int, src: Int) = Parse.dsynRc(request(Requests.dsynCopy(k, src), timeout = 1500))

    /** Keeps the four kits in flash. rc 0 ok, 3 stop the song first, 4 flash. */
    suspend fun dsynStore() = Parse.dsynRc(request(Requests.dsynStore(), timeout = 5000, retries = 0))

    suspend fun dsynPlay(k: Int, lane: Int, vel: Int = 110) =
        Parse.dsynRc(request(Requests.dsynPlay(k, lane, vel), timeout = 400))

    /** The drum track plays kit SYN{k+1} (selects the drum track, sets its KIT through the common queue). */
    suspend fun dsynUse(k: Int): Boolean {
        val s = _state.value
        val info = s.info ?: return false
        val drum = info.ntrk - 1
        if (drum < 0) return false
        if (s.selectedTrack != drum) {
            val t = Parse.tracks(request(Requests.track(drum)))
            _state.update { it.copy(tracks = t, selectedTrack = t.sel) }
            reloadAfterChange()
        }
        val d = _state.value.pdesc.getOrNull(info.pe0) ?: return false
        val i = d.names.indexOf("SYN${k + 1}")
        if (i < 0) return false
        setParam(0, info.pe0, d.min + i)
        return true
    }

    // ------------------------------------------------- backup objects: song order, FM6 bank (proto v6+) ---

    /** Reads backup object [id] whole; null when this device has no such object. */
    suspend fun bkRead(id: Int): ByteArray? {
        val list = Parse.bkList(request(Requests.bkList(), timeout = 5000, retries = 0))
        if (list.rc != 0) throw IOException("backup list rc ${list.rc}")
        val it = list.items.firstOrNull { x -> x.id == id } ?: return null
        val out = ByteArray(it.len)
        var off = 0
        while (off < it.len) {
            val n = minOf(256, it.len - off)
            val g = Parse.bkGet(request(Requests.bkGet(id, off, n), timeout = 1500, retries = 2))
            if (g.rc != 0 || g.data.size != n) throw IOException("backup read rc ${g.rc}")
            g.data.copyInto(out, off)
            off += n
        }
        return out
    }

    /** Writes backup object [id] whole (begin, data, commit). 0 ok, 3 the song is playing (nothing changed). */
    suspend fun bkWrite(id: Int, data: ByteArray): Int {
        var r = Parse.bkPut(request(Requests.bkBegin(id, data.size, Backup.crc32(data)), timeout = 1500, retries = 1))
        if (r.rc != 0) throw IOException("backup begin rc ${r.rc}")
        var off = 0
        while (off < data.size) {
            val n = minOf(256, data.size - off)
            r = Parse.bkPut(request(Requests.bkData(id, off, data.copyOfRange(off, off + n)), timeout = 1500, retries = 1))
            if (r.rc != 0) throw IOException("backup data rc ${r.rc}")
            off += n
        }
        r = Parse.bkPut(request(Requests.bkCommit(id), timeout = 5000, retries = 0))
        if (r.rc == 3) return 3
        if (r.rc != 0) throw IOException("backup commit rc ${r.rc}")
        return 0
    }

    /** The song order of the device (null when the settings cannot be read as a song). */
    suspend fun songRead(): Pair<ByteArray, Arr.Song>? {
        val b = bkRead(Backup.SETTINGS) ?: return null
        return if (Arr.ok(b)) b to Arr.decode(b) else null
    }

    /** Writes the song order into the settings object read by [songRead]. 0 ok, 3 stop the song first. */
    suspend fun songWrite(settings: ByteArray, song: Arr.Song) = bkWrite(Backup.SETTINGS, Arr.encode(settings, song))

    /**
     * SLOOP 2.5 "Store cartridge": [packs] go to B1..Bn in one flash write (the bank is read whole, records 0..n-1
     * replaced, the others kept). 0 written, 3 the song is playing, -1 a device without the bank object.
     */
    suspend fun fm6StoreCartridge(packs: List<IntArray>): Int {
        require(packs.isNotEmpty() && packs.size <= Fm6Bank.SLOTS) { "bank: ${packs.size} voices" }
        val cur = bkRead(Backup.FM6_BANK) ?: return -1
        val slots = (Fm6Bank.unobj(cur) ?: throw IOException("bank: unknown format")).toMutableList()
        packs.forEachIndexed { i, pk -> slots[i] = Fm6.pack(Fm6.unpack(pk)) }
        val rc = bkWrite(Backup.FM6_BANK, Fm6Bank.obj(slots))
        if (rc == 0) fm6Refresh()
        return rc
    }

    // ------------------------------------------------- step extras (proto v7/v8) ---

    /** Sets step [step]'s nudge, -32..31 in 1/64 of a step (0 = on the grid). Sent at once, not drafted. */
    fun setStepMicro(step: Int, nudge: Int, record: Boolean = true) {
        val s = _state.value
        if (s.link != Link.READY || step !in s.micro.indices) return
        val tr = s.selectedTrack
        val v = nudge.coerceIn(-32, 31)
        if (record && s.micro[step] != v) history.record(Change.Micro(tr, step, s.micro[step], v))
        _state.update { st -> if (st.selectedTrack != tr) st
            else st.copy(micro = st.micro.toMutableList().also { it[step] = v }) }
        scope.launch { runCatching { request(Requests.microSet(tr, step, v)) } }
    }

    /** Sets step [step]'s fill condition: 0 always, 1 fill only, 2 never during a fill. */
    fun setStepFill(step: Int, cond: Int, record: Boolean = true) {
        val s = _state.value
        if (s.link != Link.READY || step !in s.fill.indices) return
        val tr = s.selectedTrack
        val v = cond % 3
        if (record && s.fill[step] != v) history.record(Change.Fill(tr, step, s.fill[step], v))
        _state.update { st -> if (st.selectedTrack != tr) st
            else st.copy(fill = st.fill.toMutableList().also { it[step] = v }) }
        scope.launch { runCatching { request(Requests.fillSet(tr, step, v)) } }
    }

    /** Adds/updates the lock on (step, param); [value] = null deletes it. */
    fun setStepLock(step: Int, param: Int, value: Int?, record: Boolean = true) {
        val s = _state.value
        if (s.link != Link.READY || s.info == null) return
        val tr = s.selectedTrack
        val was = s.locks.firstOrNull { it.step == step && it.param == param }?.value
        scope.launch {
            runCatching {
                val r = Parse.lockSet(request(Requests.lockSet(tr, step, param, value)))
                if (record && r.rc == 0 && was != r.value) history.record(Change.Lock(tr, step, param, was, r.value))
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

    fun setDrumStep(index: Int, ds: DrumStep, record: Boolean = true) {
        val s = _state.value
        if (s.link != Link.READY || index !in s.drumSteps.indices) return
        val track = s.selectedTrack
        if (s.sequencerMode == SequencerMode.NOW && !enqueue(UserEdit.Drum(index, ds))) return
        val was = s.drumSteps[index]
        if (record && was != ds) history.record(Change.Drum(track, index, was.snapshot(), ds.snapshot()))
        val list = s.drumSteps.toMutableList().also { it[index] = ds }
        if (s.sequencerMode == SequencerMode.LATE) synchronized(draftLock) { draftDrum[track] = list }
        _state.update { current ->
            if (current.selectedTrack != track) current else current.copy(drumSteps = list,
                draftTracks = draftIds())
        }
    }

    private fun editNotes(transform: (DeviceState, List<PNote>) -> List<PNote>?): Boolean {
        val s = _state.value
        if (s.link != Link.READY || s.isDrum) return false
        val len = s.patternLength
        val transformed = transform(s, s.drawnNotes) ?: return false
        val changed = encodeNotes(s.steps, len, transformed, s.voicePoly)
        if (changed == null) {
            showEditNotice("Four notes maximum per step")
            return false
        }
        if (changed.isEmpty()) {
            if (transformed != s.drawnNotes) _state.update { it.copy(intentNotes = transformed) }
            return false
        }
        return applySynthBatch(changed.map { UserEdit.Synth(s.selectedTrack, it.index, it) },
            intent = transformed)
    }

    /** Adds a note of [len] steps at [index], or removes the note of that pitch sounding there. */
    fun toggleNote(index: Int, note: Int, len: Int = 1) {
        if (index !in 0 until _state.value.patternLength || note !in 0..127) return
        editNotes { s, notes ->
            val hit = notes.firstOrNull { it.pitch == note && index in it.start..it.end }
            if (hit != null) notes - hit else {
                placePianoNotes(notes, listOf(PNote(index, len.coerceAtLeast(1), note)), s.patternLength, s.voicePoly)
            }
        }
    }

    /**
     * Moves (or, with [copy], clones) the notes named by [keys] ([noteKey]) as one group by [dStep] steps and [dPitch]
     * semitones; the group is clamped to stay inside the pattern and 0..127. On POLY different pitches may overlap
     * (the device writes the closest shape and the drawn intent keeps the overlap); on a single-voice track the
     * shift stops before overlapping another note. One batch through the normal edit queue.
     */
    fun placeNotes(keys: Set<Int>, dStep: Int, dPitch: Int, copy: Boolean): Boolean =
        editNotes { s, notes ->
            movePianoNotes(notes, keys, dStep, dPitch, copy, s.patternLength, s.voicePoly)
        }

    /** Quantizes note starts to [grid] steps, keeping lengths: the notes named by [keys], or all notes when
     * [keys] is empty. Returns the selection's new keys, or null if nothing was sent. */
    fun quantizeNotes(keys: Set<Int>, grid: Double): Set<Int>? {
        val s = _state.value
        val notes = s.drawnNotes
        val target = keys.takeIf { it.isNotEmpty() }
        val after = quantizePianoNotes(notes, target, grid, s.patternLength)
        if (after == notes || !editNotes { _, _ -> after }) return null
        val stored = _state.value.drawnNotes.map { noteKey(it.pitch, it.start) }.toSet()
        return if (target == null) emptySet()
        else notes.zip(after).filter { (n, _) -> noteKey(n.pitch, n.start) in keys }
            .map { (_, q) -> noteKey(q.pitch, q.start) }.filter { it in stored }.toSet()
    }

    /** Removes the notes named by [keys] ([noteKey]). */
    fun deleteNotes(keys: Set<Int>) {
        editNotes { _, notes -> notes.filter { noteKey(it.pitch, it.start) !in keys }.takeIf { it.size != notes.size } }
    }

    /** Shifts the whole pattern by [delta]. On v12 in Live mode, use the firmware's
     * transpose tool to preserve continuations; older/local edits use the note model. */
    fun transposeNotes(delta: Int) {
        val s = _state.value
        if (s.link != Link.READY || s.isDrum || delta == 0) return
        if ((s.info?.proto ?: 0) >= 12 && s.sequencerMode == SequencerMode.NOW) {
            transposeOnDevice(s, delta)
            return
        }
        if (s.steps.any { it.hasContinuations() }) {
            showEditNotice("Use Live mode to transpose this recorded pattern")
            return
        }
        editNotes { _, notes ->
            notes.map { it.copy(pitch = (it.pitch + delta).coerceIn(0, 127)) }
        }
    }

    /** Live selection transpose preserves the raw step structure. Store continues
     * to use placeNotes. Return new selection keys only after accepting the edit. */
    fun transposeSelectedNotes(keys: Set<Int>, delta: Int): Set<Int>? {
        val s = _state.value
        if (s.link != Link.READY || s.isDrum || s.sequencerMode != SequencerMode.NOW || delta == 0) return null
        val selected = s.drawnNotes.filter { noteKey(it.pitch, it.start) in keys }
        if (selected.isEmpty()) return null
        val allStored = s.drawnNotes
        val accepted = if ((s.info?.proto ?: 0) >= 12 &&
            allStored.all { noteKey(it.pitch, it.start) in keys }) {
            // Selecting every stored note can use the whole-track command, including CONT.
            transposeOnDevice(s, delta)
        } else {
            val after = transposeSelectedSteps(s.steps, s.patternLength, keys, delta, s.voicePoly)
            if (after == null) {
                showEditNotice("Transpose cancelled: note range or duplicate pitch in chord")
                return null
            }
            val changed = after.filterIndexed { i, step -> step != s.steps[i] }
            if (changed.any { it.hasContinuations() || s.steps[it.index].hasContinuations() }) {
                showEditNotice("FM-1 cannot transpose only these CONT notes; select the whole pattern")
                return null
            }
            changed.isNotEmpty() && applySynthBatch(changed.map { UserEdit.Synth(s.selectedTrack, it.index, it) })
        }
        return if (accepted) selected.map { noteKey(it.pitch + delta, it.start) }.toSet() else null
    }

    private fun transposeOnDevice(s: DeviceState, delta: Int): Boolean {
        val before = s.steps.map { it.snapshot() }
        if (before.size != s.nstep || delta !in -12..12) return false
        val after = transposeRawSteps(before, delta)
        if (after == null) {
            showEditNotice("Transpose cancelled: notes would exceed MIDI range 0–127")
            return false
        }
        if (before == after || !enqueueTranspose(UserEdit.Transpose(s.selectedTrack, delta, before, after))) return false
        history.record(Change.Transpose(s.selectedTrack, delta, before, after))
        return true
    }

    private fun enqueueTranspose(edit: UserEdit.Transpose): Boolean {
        val s = _state.value
        if (s.sequencerMode != SequencerMode.NOW || (s.info?.proto ?: 0) < 12) {
            showEditNotice("Switch to Live mode for device transpose")
            return false
        }
        val live = _transport.value
        if (live?.freeTake == true || live?.recWait == true ||
            ((live?.recs ?: 0) and (1 shl edit.track)) != 0) {
            showEditNotice("Finish recording before transposing")
            return false
        }
        if (!enqueueBatch(listOf(edit))) return false
        _state.update { it.withQueuedEdits() }
        return true
    }

    /** Moves one pitch with the same chord placement rules as a group drag. */
    fun moveNote(fromIndex: Int, fromNote: Int, toIndex: Int, toNote: Int) {
        if (toIndex !in 0 until _state.value.patternLength || toNote !in 0..127) return
        placeNotes(setOf(noteKey(fromNote, fromIndex)), toIndex - fromIndex, toNote - fromNote, copy = false)
    }

    /** Shifts the left edge of the note that starts at [start] by [delta] steps, keeping its end. */
    fun adjustNoteStart(start: Int, pitch: Int, delta: Int) {
        resizeNotes(setOf(noteKey(pitch, start)), -1, delta)
    }

    /** A pitch's right handle changes the shared duration of its NOTE + TIE chord. */
    fun resizeNote(start: Int, pitch: Int, requestedLength: Int) {
        editNotes { s, notes ->
            val n = notes.firstOrNull { it.pitch == pitch && it.start == start } ?: return@editNotes null
            resizePianoNotes(notes, setOf(noteKey(pitch, start)), 1, requestedLength - n.len, s.patternLength, s.voicePoly)
        }
    }

    /**
     * Stretches or shrinks one [edge] (1 = right, -1 = left) of the notes named by [keys] ([noteKey]) by the
     * same [delta] steps. Whole chords resize together and stop at the next NOTE, of any pitch.
     */
    fun resizeNotes(keys: Set<Int>, edge: Int, delta: Int): Boolean =
        editNotes { s, notes ->
            resizePianoNotes(notes, keys, edge, delta, s.patternLength, s.voicePoly)
        }

    /** Resizes every note that starts at [index] (a chord shares one duration in the step editor). */
    fun setNoteLength(index: Int, requestedLength: Int) {
        editNotes { s, notes ->
            val n = notes.firstOrNull { it.start == index } ?: return@editNotes null
            resizePianoNotes(notes, setOf(noteKey(n.pitch, index)), 1, requestedLength - n.len, s.patternLength, s.voicePoly)
        }
    }

    /** STEP_SET cannot carry SF_CONT (SLOOP 2.7). Match the stored reply flags locally;
     * length edits normally write only TIE/REST steps and leave the NOTE flags untouched. */
    /** Counts note edits written on a MONO / LEGATO / UNISON track: SLOOP 2.8's SF_LEG (legato) bit is not in STEP_SET. */
    val singleVoiceEdits = MutableStateFlow(0)

    private fun applySynthBatch(requested: List<UserEdit.Synth>, record: Boolean = true,
                                intent: List<PNote>? = null): Boolean {
        val batch = requested.map { e ->
            if (e.step.hasContinuations()) e.copy(step = e.step.copy(flags = e.step.flags and CONT_ALL.inv())) else e
        }
        val s = _state.value
        if (s.link != Link.READY || batch.isEmpty() ||
            batch.any { it.track != s.selectedTrack || it.index !in s.steps.indices }) return false
        if (s.sequencerMode == SequencerMode.NOW && !enqueueBatch(batch)) return false
        if (!s.voicePoly) singleVoiceEdits.update { it + 1 }
        if (record) history.transaction {
            batch.forEach { e ->
                val was = s.steps[e.index]
                if (was != e.step) history.record(Change.Synth(e.track, e.index, was.snapshot(), e.step.snapshot()))
            }
        }
        val list = s.steps.toMutableList()
        batch.forEach { list[it.index] = it.step }
        if (s.sequencerMode == SequencerMode.LATE) synchronized(draftLock) { draftSynth[s.selectedTrack] = list }
        _state.update { current ->
            if (current.selectedTrack != s.selectedTrack) current else current.copy(steps = list,
                draftTracks = draftIds(), intentNotes = intent)
        }
        return true
    }

    private fun showEditNotice(message: String) {
        val notice = EditNotice(message)
        _state.update { it.copy(editNotice = notice) }
        scope.launch {
            delay(6000)
            _state.update { if (it.editNotice === notice) it.copy(editNotice = null) else it }
        }
    }

    fun dismissEditNotice() {
        _state.update { it.copy(editNotice = null) }
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

    fun clearPattern() = history.transaction {
        val s = _state.value
        if (s.link != Link.READY) return@transaction
        if (!s.drumGrid) {
            // Validate the whole action before clearing any steps.
            val batch = s.steps.take(s.patternLength).filter { it.n != 0 || it.time != 2 }
                .map { UserEdit.Synth(s.selectedTrack, it.index, Step(it.index, 0, IntArray(4), 2, 0, 0)) }
            if (batch.isNotEmpty()) applySynthBatch(batch)
            return@transaction
        }
        for (i in 0 until s.patternLength) {
            val d = s.drumSteps.getOrNull(i) ?: continue
            if (d.on != 0) setDrumStep(i, DrumStep(i, 0, IntArray(16), IntArray(16)))
        }
    }

    /**
     * Moves (or, with [copy], clones) the drum hits named by [keys] (step * 16 + lane) as one group by [dStep] steps
     * and [dLane] lanes, with their level and ratchet; the group is kept inside the pattern and the 16 lanes. Hits
     * already lying where the group lands win: the dragged hit is dropped there. Only the changed steps go out, one
     * edit each through the normal queue (or Store draft).
     */
    fun placeDrumCells(keys: Set<Int>, dStep: Int, dLane: Int, copy: Boolean) = history.transaction {
        placeDrumCellsNow(keys, dStep, dLane, copy)
    }

    private fun placeDrumCellsNow(keys: Set<Int>, dStep: Int, dLane: Int, copy: Boolean) {
        val s = _state.value
        if (s.link != Link.READY || !s.drumGrid) return
        val len = s.patternLength
        val src = keys.map { it / 16 to it % 16 }
            .filter { (st, l) -> st < len && ((s.drumSteps.getOrNull(st)?.on ?: 0) shr l) and 1 == 1 }
        if (src.isEmpty()) return
        val ds = dStep.coerceIn(-src.minOf { it.first }, len - 1 - src.maxOf { it.first })
        val dl = dLane.coerceIn(-src.minOf { it.second }, 15 - src.maxOf { it.second })
        if (!copy && ds == 0 && dl == 0) return
        val work = Array(len) { i -> s.drumSteps.getOrNull(i)?.let { it.copy(lvl = it.lvl.copyOf(), rat = it.rat.copyOf()) }
            ?: DrumStep(i, 0, IntArray(16), IntArray(16)) }
        val moved = src.map { (st, l) -> Triple(st + ds, l + dl, work[st].lvl[l] to work[st].rat[l]) }
        val out = work.map { it.copy(lvl = it.lvl.copyOf(), rat = it.rat.copyOf()) }.toTypedArray()
        if (!copy) src.forEach { (st, l) ->
            out[st] = out[st].copy(on = out[st].on and (1 shl l).inv()).also { it.lvl[l] = 0; it.rat[l] = 0 }
        }
        for ((st, l, v) in moved) {
            if ((out[st].on shr l) and 1 == 1) continue
            out[st] = out[st].copy(on = out[st].on or (1 shl l)).also { it.lvl[l] = v.first; it.rat[l] = v.second }
        }
        for (i in 0 until len) if (out[i] != s.drumSteps.getOrNull(i)) setDrumStep(i, out[i])
    }

    /**
     * Moves every hit of lane [from] onto lane [to] (kick onto kick 2, open hat onto closed hat…) with their level
     * and ratchet. Hits already on [to] stay and win where both have one; the rest of [from] becomes empty.
     */
    fun moveDrumLane(from: Int, to: Int) {
        val s = _state.value
        if (s.link != Link.READY || !s.drumGrid || from == to || from !in 0..15 || to !in 0..15) return
        val keys = (0 until s.patternLength).filter { ((s.drumSteps.getOrNull(it)?.on ?: 0) shr from) and 1 == 1 }
            .map { it * 16 + from }.toSet()
        if (keys.isNotEmpty()) placeDrumCells(keys, 0, to - from, false)
    }

    /** Removes the drum hits named by [keys] (step * 16 + lane). */
    fun deleteDrumCells(keys: Set<Int>) = history.transaction { deleteDrumCellsNow(keys) }

    private fun deleteDrumCellsNow(keys: Set<Int>) {
        val s = _state.value
        if (s.link != Link.READY || !s.drumGrid) return
        for (i in 0 until s.patternLength) {
            val cur = s.drumSteps.getOrNull(i) ?: continue
            var on = cur.on
            val lvl = cur.lvl.copyOf()
            val rat = cur.rat.copyOf()
            for (l in 0 until 16) if ((on shr l) and 1 == 1 && (i * 16 + l) in keys) { on = on and (1 shl l).inv(); lvl[l] = 0; rat[l] = 0 }
            if (on != cur.on) setDrumStep(i, cur.copy(on = on, lvl = lvl, rat = rat))
        }
    }

    /** One undo entry for a drum step changed live with record = false (a level dragged on the grid). */
    fun recordDrumEdit(index: Int, was: DrumStep, now: DrumStep) {
        if (was != now) history.record(Change.Drum(_state.value.selectedTrack, index, was.snapshot(), now.snapshot()))
    }

    /** Fills the empty drum [lane] with a hit on every [every]th step (1 = all), from the first step. */
    fun fillDrumLane(lane: Int, every: Int) = history.transaction {
        val s = _state.value
        if (s.link != Link.READY || !s.drumGrid || lane !in 0 until 16 || every < 1) return@transaction
        for (i in 0 until s.patternLength step every) {
            val cur = s.drumSteps.getOrNull(i) ?: continue
            if ((cur.on shr lane) and 1 == 1) continue
            val lvl = cur.lvl.copyOf().also { it[lane] = 0 }
            val rat = cur.rat.copyOf().also { it[lane] = 0 }
            setDrumStep(i, cur.copy(on = cur.on or (1 shl lane), lvl = lvl, rat = rat))
        }
    }

    /** The active pattern of the selected track as a clip to save (drum grid or piano roll); null otherwise. */
    fun captureClip(): Clip? {
        val s = _state.value
        if (s.link != Link.READY) return null
        val len = s.patternLength
        return when {
            s.drumGrid -> Clip(ClipKind.DRUM, len, (0 until len).flatMap { i ->
                val d = s.drumSteps.getOrNull(i) ?: return@flatMap emptyList()
                (0 until 16).filter { (d.on shr it) and 1 == 1 }.map { ClipNote(i, 1, it, Smf.lvlVel(d.lvl[it])) }
            })
            !s.isDrum -> Clip(ClipKind.PIANO, len, s.drawnNotes.map { ClipNote(it.start, it.len, it.pitch, 100) })
            else -> null
        }
    }

    /**
     * Puts a saved pattern on the selected track as ordinary step edits (the same queue, or Store draft, as hand
     * edits): the whole active pattern is replaced and a shorter clip repeats to fill it. A clip longer than the
     * active pattern grows LEN to fit it — never shrinks it. A drum clip keeps the ratchet of a hit that did not
     * change.
     */
    fun applyClip(clip: Clip) = history.transaction { applyClipNow(clip) }

    private fun applyClipNow(clip: Clip) {
        var s = _state.value
        if (s.link != Link.READY) return
        if (clip.length > s.patternLength) {
            setParam(0, 29, clip.length.coerceIn(1, s.nstep))
            s = _state.value
        }
        if (clip.kind == ClipKind.DRUM) {
            if (!s.drumGrid) return
            val src = Array(clip.length) { IntArray(16) { -1 } }
            clip.notes.forEach { if (it.start in 0 until clip.length && it.pitch in 0..15) src[it.start][it.pitch] = Smf.velLvl(it.vel) }
            for (i in 0 until s.patternLength) {
                val row = src[i % clip.length]
                val cur = _state.value.drumSteps.getOrNull(i) ?: DrumStep(i, 0, IntArray(16), IntArray(16))
                var on = 0
                val lvl = IntArray(16)
                val rat = IntArray(16)
                for (l in 0 until 16) if (row[l] >= 0) {
                    on = on or (1 shl l); lvl[l] = row[l]
                    if ((cur.on shr l) and 1 == 1 && cur.lvl[l] == row[l]) rat[l] = cur.rat[l]
                }
                val next = DrumStep(i, on, lvl, rat)
                if (next != cur) setDrumStep(i, next)
            }
        } else {
            if (s.isDrum) return
            editNotes { st, _ ->
                val out = ArrayList<PNote>()
                var base = 0
                while (base < st.patternLength) {
                    for (n in clip.notes) {
                        val at = base + n.start
                        if (n.start < clip.length && at < st.patternLength)
                            out.add(PNote(at, minOf(n.len, clip.length - n.start, st.patternLength - at).coerceAtLeast(1), n.pitch.coerceIn(0, 127)))
                    }
                    base += clip.length
                }
                out
            }
        }
    }

    /**
     * Drops a saved piano-roll pattern on the roll as one group shifted by [dStep] steps and [dPitch] semitones (kept
     * inside the pattern and 0..127), using the same NOTE + TIE rules as manual placement.
     */
    fun dropClip(clip: Clip, dStep: Int, dPitch: Int) {
        editNotes { s, notes ->
            val src = clip.notes.filter { it.start < s.patternLength }
                .map { PNote(it.start, minOf(it.len, s.patternLength - it.start).coerceAtLeast(1), it.pitch.coerceIn(0, 127)) }
            if (src.isEmpty()) return@editNotes null
            val ds = dStep.coerceIn(-src.minOf { it.start }, s.patternLength - 1 - src.maxOf { it.end })
            val dp = dPitch.coerceIn(-src.minOf { it.pitch }, 127 - src.maxOf { it.pitch })
            placePianoNotes(notes, src.map { it.copy(start = it.start + ds, pitch = it.pitch + dp) }, s.patternLength, s.voicePoly)
        }
    }

    /** Selects [track] and waits until its pattern is loaded (so a clip can be applied to it). */
    suspend fun selectTrackAndWait(track: Int): Boolean {
        if (_state.value.selectedTrack == track && _state.value.link == Link.READY) return true
        selectTrack(track)
        return withTimeoutOrNull(8000) {
            _state.first { it.link == Link.READY && it.selectedTrack == track && (it.steps.isNotEmpty() || it.drumSteps.isNotEmpty()) }
        } != null
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
        history.drop { it is Change.Synth || it is Change.Drum || it is Change.Transpose }
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

    // ---------------------------------------------------------------- undo / redo ---

    fun undo() = replay(forward = false)
    fun redo() = replay(forward = true)

    /**
     * Puts the newest undo (or redo) entry back by sending the other value as a normal edit: the same setters,
     * the same queue, nothing recorded again. The entry's track is selected first when it is not the open one.
     */
    private fun replay(forward: Boolean) {
        if (_state.value.link != Link.READY) return
        scope.launch {
            historyMutex.withLock {
                val e = (if (forward) history.takeRedo() else history.takeUndo()) ?: return@withLock
                val ok = try { applyEntry(e, forward) } catch (_: Exception) { false }
                if (ok) history.done(e, forward) else {
                    history.putBack(e, forward)
                    _state.update { it.copy(queueError = "Cannot ${if (forward) "redo" else "undo"} right now") }
                }
            }
        }
    }

    private suspend fun applyEntry(e: HistoryEntry, forward: Boolean): Boolean {
        e.track?.let { if (!selectTrackAndWait(it)) return false }
        val synth = ArrayList<UserEdit.Synth>()
        for (c in if (forward) e.changes else e.changes.asReversed()) when (c) {
            is Change.Param -> setParam(c.scope, c.id, if (forward) c.after else c.before, record = false)
            is Change.Mix -> (if (forward) c.after else c.before).let { setTrackMix(c.tr, it.first, it.second, record = false) }
            is Change.Synth -> synth.add(UserEdit.Synth(c.tr, c.index, (if (forward) c.after else c.before).snapshot()))
            is Change.Transpose -> if (!enqueueTranspose(UserEdit.Transpose(c.tr,
                if (forward) c.delta else -c.delta,
                if (forward) c.before else c.after,
                if (forward) c.after else c.before))) return false
            is Change.Drum -> setDrumStep(c.index, (if (forward) c.after else c.before).snapshot(), record = false)
            is Change.Micro -> setStepMicro(c.step, if (forward) c.after else c.before, record = false)
            is Change.Fill -> setStepFill(c.step, if (forward) c.after else c.before, record = false)
            is Change.Lock -> setStepLock(c.step, c.param, if (forward) c.after else c.before, record = false)
            is Change.Custom<*> -> withContext(Dispatchers.Main) { c.replay(forward) }
        }
        if (synth.isNotEmpty() && !applySynthBatch(synth, record = false)) return false
        return true
    }

    private suspend fun editWorker() {
        for (unit in editSignal) {
            while (true) {
                val next = synchronized(pendingLock) {
                    val e = edits.firstOrNull()
                    // A step write into a track being recorded would fight the live recorder's chains.
                    val blocked = when (e) {
                        is UserEdit.Synth -> recordingTrack(e.track)
                        is UserEdit.Transpose -> recordingTrack(e.track)
                        is UserEdit.Drum -> recordingActive()
                        else -> false
                    }
                    if (queuePaused || e == null || blocked) null
                    else (e to editGeneration).also { sending = true }
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
                    queueError = if (result.isFailure) {
                        if (next.first is UserEdit.Transpose)
                            "Transpose paused: ${result.exceptionOrNull()?.message}. Retry in Device checks the pattern first."
                        else "MIDI reply missing — tap Retry in Device"
                    } else null) }
                if (result.isFailure) break
            }
        }
    }

    /** LIVE pattern tool preserves raw step flags. The firmware supports one
     * semitone per command, so an octave is twelve serialized operations.
     * Never retry a relative command blindly: first read and reconcile progress. */
    private suspend fun sendTranspose(edit: UserEdit.Transpose) {
        val connection = conn ?: throw IOException("not connected")
        val info = _state.value.info ?: throw IOException("device info unavailable")
        fun checkConnection() {
            check(conn === connection) { "FM-1 disconnected" }
        }
        fun checkRecording(live: Live) {
            check(!live.freeTake && !live.recWait && (live.recs and (1 shl edit.track)) == 0) {
                "finish recording first"
            }
        }
        suspend fun readSteps(): List<Step> {
            val steps = ArrayList<Step>(info.nstep)
            for (i in 0 until info.nstep) {
                checkConnection()
                val reply = Parse.trackStep(request(Requests.trackStepGet(edit.track, i)))
                check(reply.track == edit.track && reply.step.index == i) { "unexpected step reply" }
                steps.add(reply.step)
            }
            return steps
        }
        resyncJob?.cancel(); resyncJob = null
        nativeTransposeTrack = edit.track
        try {
            checkConnection()
            val live = Parse.live(request(Requests.live(0), quiet = true))
            checkRecording(live)
            applyLive(live)
            val actual = readSteps()
            val done = rawTransposeProgress(edit.before, actual, edit.delta)
                ?: throw IOException("pattern changed on FM-1; reconnect to discard this queued transpose")
            for (i in done until kotlin.math.abs(edit.delta)) {
                checkConnection()
                markSelfReload()
                val reply = Parse.live(request(Requests.liveTransposeStep(edit.track, edit.delta > 0),
                    retries = 0, quiet = true))
                applyLive(reply)
                checkRecording(reply)
            }
            val after = readSteps()
            check(after == edit.after) { "pattern changed during transpose" }
            _state.update { s ->
                if (conn !== connection || s.selectedTrack != edit.track) s
                else s.copy(steps = after, intentNotes = null).withQueuedEdits().withDraft()
            }
        } finally {
            // Ignore the delayed RELOAD caused by our own commands. The steps above
            // are already refreshed, and app undo must keep this as one action.
            if (conn === connection) markSelfReload()
            nativeTransposeTrack = null
        }
    }

    private suspend fun sendEdit(edit: UserEdit) {
        when (edit) {
            is UserEdit.Transpose -> sendTranspose(edit)
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
            !drumGrid && synth != null -> copy(steps = synth, intentNotes = null)
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
                is UserEdit.Transpose -> if (edit.track == current.selectedTrack)
                    current.copy(steps = edit.after, intentNotes = null) else current
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
                    recordingTrace.frame("TX", req.cmd, req.args)
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
        recordingTrace.frame("RX", f.cmd, f.args)
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
                // true: the echo of an LFO write, nothing to show
                if (onParamPush?.invoke(pv.scope, pv.id, pv.value) != true)
                    _state.update { s -> s.withParam(pv.scope, pv.id, pv.value) }
            }
            Cmd.RELOAD -> {
                if (nativeTransposeTrack != null) return
                if (System.currentTimeMillis() < selfReloadUntil) return
                resyncJob?.cancel(); resyncJob = null
                history.dropTrack(_state.value.selectedTrack)
                scope.launch { runCatching { reloadAfterChange() } }
            }
            Cmd.STEP_CHANGED -> {
                val sc = Parse.stepChanged(args)
                if (nativeTransposeTrack == sc.track) return
                if (sc.track != _state.value.selectedTrack) return
                // While the device records, a pushed step can be read mid-chain (a pure-CONT NOTE
                // before the attack carrying it): take one clean full read once pushes settle.
                if (!recordingTrack(sc.track))
                    scope.launch { runCatching { reloadOneStep(sc.index, sc.track) } }
                scheduleResync()
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
        applyFm6Names()
    }

    private var resyncJob: Job? = null

    /** Read a stopped synth track explicitly by number for comparison with the UI.
     * No STEP_SET, no state replacement, no reconstruction from note-offs. */
    suspend fun recordingReport(): String {
        val s = _state.value
        val info = s.info ?: error("Connect FM-1 first")
        check(s.link == Link.READY && !s.isDrum && info.proto >= 3) { "Select T1–T3 on the connected FM-1" }
        check(!s.playing) { "Stop playback before saving the trace" }
        check(s.queuedEdits == 0 && s.draftTracks.isEmpty()) { "Finish queued edits and Store drafts first" }
        val connection = conn
        val raw = ArrayList<Step>()
        fun checkContext() {
            check(conn === connection && _state.value.link == Link.READY &&
                _state.value.selectedTrack == s.selectedTrack && !_state.value.playing &&
                _state.value.queuedEdits == 0 && _state.value.draftTracks.isEmpty()) {
                "Track or playback changed during capture. Stop and save again."
            }
        }
        checkContext()
        val parameters = request(Requests.trackDump(s.selectedTrack))
        for (i in 0 until info.nstep) {
            checkContext()
            val reply = Parse.trackStep(request(Requests.trackStepGet(s.selectedTrack, i)))
            check(reply.track == s.selectedTrack && reply.step.index == i) { "Unexpected track step reply" }
            raw.add(reply.step)
        }
        checkContext()
        recordingTrace.stop()
        return buildString {
            appendLine("Sloop Go recording diagnostic")
            appendLine("Firmware=${info.version} protocol=${info.proto} track=${s.selectedTrack} length=${s.patternLength}")
            appendLine("TRACK_DUMP reply=${parameters.joinToString(",")}")
            appendLine("Cached micro=${s.micro} fill=${s.fill} locks=${s.locks}")
            appendLine(RecordingTrace.steps("APP before fresh read", s.steps, s.patternLength))
            appendLine(RecordingTrace.steps("DEVICE fresh TRACK_STEP reads", raw, s.patternLength))
            appendLine(recordingTrace.text())
        }
    }

    /**
     * Steps pushed one by one while the FM-1 records (its hold keeps moving a note's end, nudges change too) can leave a step
     * stale if a read lost a race. Once the pushes stop for a moment, the whole track is read again and the roll shows what the
     * device holds.
     */
    private fun scheduleResync(delayMs: Long = 1200) {
        resyncJob?.cancel()
        resyncJob = scope.launch {
            delay(delayMs)
            val s = _state.value
            val info = s.info ?: return@launch
            val dump = s.dump ?: return@launch
            if (s.link != Link.READY) return@launch
            if (synchronized(pendingLock) { edits.isNotEmpty() || sending }) { scheduleResync(); return@launch }
            runCatching {
                loadSteps(info, dump, unchangedSince = s)
                if (_state.value.selectedTrack == s.selectedTrack && _state.value.link == Link.READY)
                    loadStepExtras(info)
            }
        }
    }

    private suspend fun reloadOneStep(index: Int, track: Int) {
        val s = _state.value
        if (s.selectedTrack != track || s.link != Link.READY) return
        if (s.sequencerMode == SequencerMode.LATE && s.selectedTrack in s.draftTracks) return
        val info = s.info ?: return
        if (index !in 0 until info.nstep) return
        val connection = conn
        if (s.drumGrid) {
            val ds = Parse.drumStep(request(Requests.drumStepGet(index)))
            _state.update { st ->
                if (conn !== connection || st.selectedTrack != track || st.link != Link.READY ||
                    st.drumSteps.getOrNull(index) != s.drumSteps.getOrNull(index)) return@update st
                val l = st.drumSteps.toMutableList(); if (index < l.size) l[index] = ds
                st.copy(drumSteps = l).withQueuedEdits().withDraft() }
        } else {
            val step = if (info.ntrk > 0) Parse.trackStep(request(Requests.trackStepGet(track, index))).step
                else Parse.step(request(Requests.stepGet(index)))
            _state.update { st ->
                if (conn !== connection || st.selectedTrack != track || st.link != Link.READY ||
                    st.steps.getOrNull(index) != s.steps.getOrNull(index)) return@update st
                if (st.steps.getOrNull(index) == step) return@update st
                val l = st.steps.toMutableList(); if (index < l.size) l[index] = step
                st.copy(steps = l, intentNotes = null).withQueuedEdits().withDraft() }
        }
        // v7+: a step's signature covers its nudge, locks and fill — the push may mean one of them changed
        if (info.proto >= 7 && conn === connection && _state.value.selectedTrack == track &&
            _state.value.link == Link.READY) loadStepExtras(info)
    }

    // ---------------------------------------------------------------- keepalive ---

    private fun startKeepAlive() {
        keepAlive?.cancel()
        keepAlive = scope.launch {
            launch {
                while (true) {
                    val lv = _state.value
                    delay(if (lv.playing) 120 else 450)
                    if (conn == null) break
                    if ((lv.info?.proto ?: 0) < 12 || lv.link != Link.READY) continue
                    val editsWaiting = synchronized(pendingLock) { edits.isNotEmpty() && !queuePaused }
                    if (editsWaiting || linkMutex.isLocked) continue
                    runCatching { applyLive(Parse.live(request(Requests.live(0), timeout = 300, retries = 0, quiet = true))) }
                }
            }
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
        history.clear()
        conn?.close(); conn = null
        engine.release()
    }

    private suspend fun Mutex.withLockSafe(block: suspend () -> Unit) {
        if (tryLock()) try { block() } finally { unlock() }
    }

    companion object { private const val TAG = "SloopGo" }
}
