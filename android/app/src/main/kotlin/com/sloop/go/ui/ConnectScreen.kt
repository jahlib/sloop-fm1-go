// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConnectScreen(vm: SloopViewModel, state: DeviceState) {
    LaunchedEffect(state.link) { vm.refreshDevices() }
    val context = LocalContext.current
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 104.dp)) {
        item { ScreenHeader("Device", state, NAV_INSET) }
        item { Text("Sloop Go $version", Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.queueError != null && state.link == Link.READY) {
            item {
                Text(state.queueError, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                if (state.queuedEdits > 0) {
                    Button(onClick = { vm.controller.retryEdits() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text("Retry ${state.queuedEdits} queued edits")
                    }
                }
            }
        }
        state.editNotice?.let { notice ->
            item {
                Text("${notice.message}  ×",
                    Modifier.padding(horizontal = 16.dp).clickable { vm.controller.dismissEditNotice() },
                    color = MaterialTheme.colorScheme.tertiary)
            }
        }

        if (!vm.midiAvailable) {
            item {
                InfoCard("This Android device has no MIDI support. A USB-host (OTG) capable phone is required.")
            }
            return@LazyColumn
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp, 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("MIDI devices", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { vm.refreshDevices() }) { Text("Refresh") }
            }
        }

        if (vm.devices.isEmpty()) {
            item {
                InfoCard(
                    "No MIDI device found.\n\nConnect the FM-1 / SLOOP with a USB-C to USB-C cable, then tap Refresh. " +
                        "Your phone must support USB host (OTG).",
                )
            }
        } else {
            items(vm.devices) { d ->
                Card(
                    Modifier.fillMaxWidth().padding(16.dp, 6.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        if (d.manufacturer.isNotBlank()) {
                            Text(d.manufacturer, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            val isThis = state.deviceName == d.name && state.link != Link.DISCONNECTED
                            if (isThis) {
                                OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                            } else {
                                Button(
                                    onClick = { vm.connect(d.id) },
                                    enabled = state.link == Link.DISCONNECTED,
                                ) { Text("Connect") }
                            }
                        }
                    }
                }
            }
        }

        if (state.link == Link.READY && state.info != null) {
            item {
                val i = state.info
                Card(Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Firmware", style = MaterialTheme.typography.titleMedium)
                        val lines = listOf(
                            "version   ${i.version}",
                            "protocol  v${i.proto}",
                            "engines   ${i.nengines}",
                            "tracks    ${i.ntrk}",
                            "steps     ${i.nstep}",
                            "params    ${i.pcount} + ${i.gcount} global",
                        )
                        lines.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = SloopFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item { RecordingDiagnostics(vm, state, version) }

        item {
            Card(Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Text("Sloop Go is free software (GPL-3.0). It is a companion app by jahlib for the SLOOP firmware " +
                        "by isod89 (github.com/isod89/sloop-fm1) and builds on Felucca by Leo Kuroshita / " +
                        "Hügelton Instruments (github.com/hugelton/Felucca). It is not affiliated with them. " +
                        "Have fun and make some bangers!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            val u = vm.update
            val busy = u is SloopViewModel.Update.Checking || u is SloopViewModel.Update.Downloading
            Card(Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Updates", style = MaterialTheme.typography.titleMedium)
                        Text(when (u) {
                            is SloopViewModel.Update.Checking -> "Checking GitHub…"
                            is SloopViewModel.Update.UpToDate -> "Sloop Go $version is the latest release."
                            is SloopViewModel.Update.Available -> "Version ${u.release.tag} is available (installed $version)."
                            is SloopViewModel.Update.Downloading -> "Downloading ${u.release.tag}: ${(u.progress * 100).toInt()}%"
                            is SloopViewModel.Update.Ready -> "${u.release.tag} is downloaded and ready to install."
                            is SloopViewModel.Update.Failed -> "Check failed: ${u.message}"
                            else -> "Installed: $version. Looks for a newer release on GitHub."
                        }, style = MaterialTheme.typography.bodySmall,
                            color = if (u is SloopViewModel.Update.Failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(enabled = !busy, onClick = {
                        if (u is SloopViewModel.Update.Available || u is SloopViewModel.Update.Ready) vm.showUpdate()
                        else vm.checkUpdate(manual = true)
                    }) {
                        Text(when (u) {
                            is SloopViewModel.Update.Available -> "Update"
                            is SloopViewModel.Update.Ready -> "Install"
                            else -> "Check for updates"
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingDiagnostics(vm: SloopViewModel, state: DeviceState, version: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by vm.controller.recordingTrace.active.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val report = pending
        pending = null
        if (uri != null && report != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open file")
                    output.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                }
                message = "Trace saved"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Save failed: ${e.message}"
            } finally { busy = false }
        }
    }
    val ready = state.link == Link.READY && !state.isDrum && (state.info?.proto ?: 0) >= 3
    Card(Modifier.fillMaxWidth().padding(16.dp, 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recording diagnostics", style = MaterialTheme.typography.titleMedium)
            Text("Start a trace, record a short example on T1–T3, then stop playback and save the trace. " +
                "It reads the FM-1 pattern without changing notes.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = ready && !busy && pending == null, onClick = {
                    vm.controller.recordingTrace.start(state)
                    message = "Capturing keys and device replies…"
                }) { Text(if (active) "Restart trace" else "Start trace") }
                OutlinedButton(enabled = ready && !state.playing && !busy && pending == null, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val report = withContext(Dispatchers.IO) { vm.controller.recordingReport() }
                            pending = "App version=$version\n$report"
                            saver.launch("sloop-recording-trace.txt")
                            message = "Choose where to save the trace"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = "Capture failed: ${e.message}"
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "Reading…" else "Save trace") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
