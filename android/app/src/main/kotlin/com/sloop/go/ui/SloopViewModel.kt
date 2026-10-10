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
import com.sloop.go.midi.MidiDeviceDesc
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
                if (manual) Update.Failed(e.message ?: e.javaClass.simpleName) else Update.None
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
