// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link

@Composable
fun ConnectScreen(vm: SloopViewModel, state: DeviceState) {
    LaunchedEffect(state.link) { vm.refreshDevices() }
    val context = LocalContext.current
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 104.dp)) {
        item { ScreenHeader("Device", state) }
        item { Text("Sloop Go $version", Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.queueError != null && state.link == Link.READY) {
            item {
                Button(onClick = { vm.controller.retryEdits() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Retry ${state.queuedEdits} queued edits")
                }
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

        item {
            Card(Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Text("Sloop Go is free software (GPL-3.0). It is a companion app for the SLOOP firmware " +
                        "by isod89 (github.com/isod89/sloop-fm1) and builds on Felucca by Leo Kuroshita / " +
                        "Hügelton Instruments (github.com/hugelton/Felucca). It is not affiliated with them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
