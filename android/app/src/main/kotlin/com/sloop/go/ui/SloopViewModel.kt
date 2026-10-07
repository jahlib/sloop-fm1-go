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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SloopViewModel(app: Application) : AndroidViewModel(app) {
    val controller = DeviceController(app)
    val state = controller.state
    val fm6 = Fm6Editor()

    /** Work that must outlive the page that started it (a bank upload, a flash write). */
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun launch(block: suspend () -> Unit) = jobs.launch { block() }

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
