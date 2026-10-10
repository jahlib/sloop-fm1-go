// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.sloop.go.device.DeviceController
import com.sloop.go.device.DeviceState
import com.sloop.go.midi.MidiDeviceDesc
import com.sloop.go.proto.Fm6
import com.sloop.go.store.PatternStore
import com.sloop.go.update.Updater
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SloopViewModel(app: Application) : AndroidViewModel(app) {
    val controller = DeviceController(app)
    val state = controller.state
    val fm6 = Fm6Editor()
    val samples = SamplesEditor()
    val dsyn = DrumSynthEditor()
    val patterns = PatternStore(app).also { it.seedDefaults() }
    val song = SongEditor()

    /** Work that must outlive the page that started it (a bank upload, a flash write). */
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun launch(block: suspend () -> Unit) = jobs.launch { block() }

    /** The track the FM6 page edits for: its pick, else the selected track, else the first. */
    fun fm6Track(s: DeviceState): Int {
        val ntrk = minOf(3, (s.info?.ntrk ?: 1).coerceAtLeast(1))
        return fm6.track.takeIf { it in 0 until ntrk } ?: s.selectedTrack.takeIf { it in 0 until ntrk } ?: 0
    }

    /** Store mode's SEND (also usable from anywhere): writes the edited FM6 patch to its track through the request queue. */
    fun fm6SendNow() {
        val s = state.value
        val t = fm6Track(s)
        if (fm6.busy || s.trackEngine(t) != s.fm6Engine) return
        fm6.busy = true
        launch {
            try {
                val rc = controller.fm6Put(0, t, Fm6.pack(fm6.voice))
                if (rc == 0) { fm6.sentRev = fm6.rev; fm6.say("Sent to track ${t + 1}: ${Fm6.name(fm6.voice)}") }
                else fm6.say(rcText(rc), true)
            } catch (e: Exception) {
                fm6.say("Error: ${e.message ?: e.javaClass.simpleName}", true)
            } finally { fm6.busy = false }
        }
    }

    /** Self-update from the latest GitHub release. */
    sealed interface Update {
        data object None : Update
        data object Checking : Update
        data object UpToDate : Update
        class Failed(val message: String) : Update
        class Available(val release: Updater.Release) : Update
        class Downloading(val release: Updater.Release, val progress: Float) : Update
        class Ready(val release: Updater.Release, val apk: File) : Update
    }

    var update by mutableStateOf<Update>(Update.None)
        private set
    /** The dialog is shown for Available / Downloading / Ready until the user dismisses it. */
    var updateDialog by mutableStateOf(false)
        private set
    private var updateJob: Job? = null

    /** [manual]: report "up to date" and failures; the automatic check at launch stays silent unless there is news. */
    fun checkUpdate(manual: Boolean = false) {
        if (update is Update.Checking || update is Update.Downloading) return
        val ctx = getApplication<Application>()
        update = Update.Checking
        updateJob = jobs.launch {
            update = try {
                val r = Updater.latest()
                if (r != null && Updater.isNewer(r.tag, Updater.installedVersion(ctx))) {
                    updateDialog = true
                    Update.Available(r)
                } else Update.UpToDate
            } catch (e: Exception) {
                if (manual) Update.Failed(
                    if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException)
                        "No internet connection (cannot reach github.com)"
                    else e.message ?: e.javaClass.simpleName) else Update.None
            }
        }
    }

    fun downloadUpdate() {
        val r = (update as? Update.Available)?.release ?: return
        val ctx = getApplication<Application>()
        update = Update.Downloading(r, 0f)
        updateJob = jobs.launch {
            update = try {
                val apk = Updater.download(ctx, r) { p -> update = Update.Downloading(r, p) }
                Updater.install(ctx, apk)
                Update.Ready(r, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Update.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun installUpdate() {
        val apk = (update as? Update.Ready)?.apk ?: return
        Updater.install(getApplication(), apk)
    }

    fun dismissUpdate() {
        updateDialog = false
        if (update is Update.Downloading) { updateJob?.cancel(); update = Update.None }
    }

    fun showUpdate() { updateDialog = true }

    init { checkUpdate() }

    var devices by mutableStateOf<List<MidiDeviceDesc>>(emptyList())
        private set

    val midiAvailable: Boolean get() = controller.midiAvailable

    fun refreshDevices() {
        devices = controller.devices()
    }

    fun connect(id: Int) = controller.connect(id)

    fun connectPreferred() {
        controller.preferredDevice()?.let { controller.connect(it.id) }
    }

    fun disconnect() = controller.disconnect()

    override fun onCleared() {
        jobs.cancel()
        controller.release()
    }
}
