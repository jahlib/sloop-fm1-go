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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import com.sloop.go.proto.Arr

/** The song order being edited (the device's SONG screen); lives in the ViewModel. */
class SongEditor {
    var rows by mutableStateOf<List<Arr.Row>?>(null)
    var loop by mutableStateOf(false)
    var settings: ByteArray? = null
    var dirty by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)

    fun say(text: String, error: Boolean = false) { message = text; isError = error }
    fun edit(f: (MutableList<Arr.Row>) -> Unit) {
        rows = rows?.toMutableList()?.also(f)
        dirty = true
    }
}

private const val LETTERS = "ABCD"

/** SLOOP 2.5: the order of the sections A–D (what the FM-1's SONG screen edits), written to the device settings. */
@Composable
fun SongScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit) {
    if (state.link != Link.READY || state.info == null) {
        NotReady("Song", state, onDevice)
        return
    }
    val ed = vm.song
    val ctl = vm.controller
    val cs = MaterialTheme.colorScheme

    fun op(block: suspend () -> Unit) {
        if (ed.busy) return
        ed.busy = true
        vm.launch {
            try { block() } catch (e: Exception) { ed.say("Error: ${e.message ?: e.javaClass.simpleName}", true) }
            finally { ed.busy = false }
        }
    }

    suspend fun read() {
        val r = ctl.songRead()
        if (r == null) { ed.rows = null; ed.say("This firmware has no song order to edit (needs SLOOP 2.5)", true); return }
        ed.settings = r.first; ed.rows = Arr.group(r.second.entries); ed.loop = r.second.loop; ed.dirty = false
        ed.say("Read from the FM-1")
    }

    LaunchedEffect(state.link) { if (ed.rows == null) op { read() } }

    val rows = ed.rows
    val n = rows?.let { Arr.count(it) } ?: 0
    val bars = rows?.sumOf { it.bars * maxOf(1, it.times) } ?: 0

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp, 0.dp, 8.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader("Song", state) }

        if (ed.busy || ed.message != null) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                if (ed.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ed.message?.let { Text(it, color = if (ed.isError) cs.error else cs.primary, style = MaterialTheme.typography.bodySmall) }
            }
        }

        if (rows == null) {
            item { Button(enabled = !ed.busy, onClick = { op { read() } }, modifier = Modifier.padding(8.dp)) { Text("Read from the FM-1") } }
            return@LazyColumn
        }

        itemsIndexed(rows, key = { i, _ -> i }) { k, r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${k + 1}", Modifier.width(28.dp), color = cs.onSurfaceVariant, fontFamily = SloopFontFamily)
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (s in 0 until 4) FilterChip(selected = r.scene == s,
                                onClick = { ed.edit { it[k] = it[k].copy(scene = s) } }, label = { Text("${LETTERS[s]}") })
                        }
                        TextButton(enabled = k > 0, onClick = { ed.edit { it.add(k - 1, it.removeAt(k)) } }) { Text("▲") }
                        TextButton(enabled = k < rows.size - 1, onClick = { ed.edit { it.add(k + 1, it.removeAt(k)) } }) { Text("▼") }
                        TextButton(enabled = rows.size > 1, onClick = { ed.edit { it.removeAt(k) } }) { Text("✕") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Bars", Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { ed.edit { it[k] = r.copy(bars = (r.bars - 1).coerceAtLeast(1)) } }) { Text("−") }
                        Text("${r.bars}", Modifier.width(32.dp), color = cs.primary, fontFamily = SloopFontFamily)
                        TextButton(onClick = { ed.edit { it[k] = r.copy(bars = (r.bars + 1).coerceAtMost(Arr.MAX_BARS)) } }) { Text("+") }
                        Text("Times", Modifier.width(52.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { ed.edit { it[k] = r.copy(times = (r.times - 1).coerceAtLeast(1)) } }) { Text("−") }
                        Text("${r.times}", Modifier.width(32.dp), color = cs.primary, fontFamily = SloopFontFamily)
                        TextButton(enabled = n < Arr.STEPS, onClick = { ed.edit { it[k] = r.copy(times = r.times + 1) } }) { Text("+") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$n of ${Arr.STEPS} steps · $bars bars", color = if (n > Arr.STEPS) cs.error else cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = ed.loop, onCheckedChange = { ed.loop = it; ed.dirty = true })
                        Text("  Loop the song", style = MaterialTheme.typography.bodyMedium)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(enabled = n < Arr.STEPS, onClick = {
                            ed.edit { val last = it.lastOrNull(); it.add(Arr.Row(if (last != null) (last.scene + 1) % 4 else 0, last?.bars ?: 4, 1)) }
                        }) { Text("Add step") }
                        OutlinedButton(enabled = !ed.busy, onClick = { op { read() } }) { Text("Read") }
                        Button(enabled = !ed.busy && n in 1..Arr.STEPS && ed.settings != null, onClick = {
                            op {
                                val song = Arr.Song(ed.loop, Arr.expand(rows))
                                if (!Arr.valid(song)) { ed.say("Invalid song order", true); return@op }
                                when (ctl.songWrite(ed.settings!!, song)) {
                                    0 -> { read(); ed.say("Song sent") }
                                    else -> ed.say("Stop the song first: the FM-1 saves settings only when stopped", true)
                                }
                            }
                        }) { Text(if (ed.dirty) "Send *" else "Send") }
                    }
                    Text("Sections A–D are the FM-1's four projects; each step plays one section for its number of bars " +
                        "(the same step repeated is one row played Times). Saved with the device settings.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
