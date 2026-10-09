// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
@file:OptIn(ExperimentalLayoutApi::class)

package com.sloop.go.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import com.sloop.go.store.Saved
import com.sloop.go.store.ClipKind

/**
 * The patterns saved inside the app (piano roll and drum tabs): rename, delete, and load onto a track, which opens
 * that track in the sequencer. Saving is done from the sequencer itself.
 */
@Composable
fun MidiBrowserScreen(vm: SloopViewModel, state: DeviceState, openSequencer: () -> Unit) {
    val store = vm.patterns
    var kind by remember { mutableStateOf(ClipKind.PIANO) }
    val items = remember(store.rev, kind) { store.list(kind) }
    var message by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<Saved?>(null) }
    var delete by remember { mutableStateOf<Saved?>(null) }
    var load by remember { mutableStateOf<Saved?>(null) }
    val info = state.info
    val ready = state.link == Link.READY && info != null
    val drumTrack = (info?.ntrk ?: 0) - 1
    val drumOk = ready && info!!.proto >= 5 && drumTrack >= 0

    fun loadOnto(item: Saved, track: Int) {
        message = "Loading ${item.name}…"
        vm.launch {
            try {
                if (!vm.controller.selectTrackAndWait(track)) { message = "Could not open track ${track + 1}"; return@launch }
                vm.controller.applyClip(item.clip)
                message = null
                openSequencer()
            } catch (e: Exception) { message = "Error: ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    fun pick(item: Saved) {
        if (kind == ClipKind.DRUM) loadOnto(item, drumTrack) else load = item
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp, 0.dp, 8.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader("MIDI patterns", state) }
        item {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in ClipKind.entries) FilterChip(selected = kind == k, onClick = { kind = k; message = null },
                    label = { Text(k.title) })
            }
        }
        message?.let { item { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall) } }
        if (!ready) item { Text("Connect the FM-1 to load a pattern onto a track.", Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        if (items.isEmpty()) item {
            Text("No saved ${kind.title.lowercase()} patterns yet. Save one from the sequencer.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(items, key = { "${kind.dir}/${it.name}" }) { saved ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    ClipRow(saved, below = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(enabled = if (kind == ClipKind.DRUM) drumOk else ready, onClick = { pick(saved) }) { Text("Load") }
                            TextButton(onClick = { rename = saved }) { Text("Rename") }
                            TextButton(onClick = { delete = saved }) { Text("Delete") }
                        }
                    })
                }
            }
        }
    }

    load?.let { item ->
        val ntrk = info?.ntrk ?: 0
        AlertDialog(onDismissRequest = { load = null },
            title = { Text("Load \"${item.name}\" onto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in 0 until (ntrk - 1).coerceAtLeast(0)) {
                        val eng = state.trackEngine(t)
                        TextButton(onClick = { load = null; loadOnto(item, t) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Track ${t + 1}" + (info?.engines?.getOrNull(eng)?.let { " · $it" } ?: ""))
                        }
                    }
                    Text("The pattern replaces the notes on the track; its sound and settings stay.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { load = null }) { Text("Cancel") } })
    }

    rename?.let { item ->
        var name by remember(item) { mutableStateOf(item.name) }
        val clean = store.clean(name)
        val taken = clean != item.name && store.exists(kind, clean)
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, isError = taken,
                    supportingText = { if (taken) Text("This name is already used") })
            },
            confirmButton = {
                TextButton(enabled = clean.isNotEmpty() && !taken, onClick = {
                    store.rename(kind, item.name, clean); rename = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }

    delete?.let { item ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete pattern?") },
            text = { Text("\"${item.name}\" will be removed from the app.") },
            confirmButton = { TextButton(onClick = { store.delete(kind, item.name); delete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
}
