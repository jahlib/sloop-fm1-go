// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
@file:OptIn(ExperimentalLayoutApi::class)

package com.sloop.go.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.sloop.go.R
import com.sloop.go.audio.Audio
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import com.sloop.go.proto.Smp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun displayName(ctx: Context, uri: Uri): String {
    var name: String? = null
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) name = c.getString(i)
    }
    return name ?: uri.lastPathSegment ?: "file"
}

private fun secs(n: Int) = "%.2f s".format(n.toDouble() / Smp.RATE)

/** USR1..4 sample slots: files (one zone per file) or one recording chopped into keys. */
@Composable
fun SamplesScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit) {
    if (state.link != Link.READY || state.info == null) {
        NotReady("Samples", state, onDevice)
        return
    }
    val ed = vm.samples
    val ctl = vm.controller
    val ctx = LocalContext.current
    val smp = state.smp
    val nslots = smp?.nslots ?: 4

    fun op(block: suspend () -> Unit) {
        if (ed.busy) return
        ed.busy = true
        vm.launch {
            try { block() } catch (e: Exception) {
                ed.say("Error: ${e.message ?: e.javaClass.simpleName}", true)
            } finally { ed.busy = false }
        }
    }

    // ---- file open (both modes) ----
    var pendingMode by remember { mutableStateOf(SamplesEditor.Mode.FILES) }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (pendingMode == SamplesEditor.Mode.CHOP) {
            val uri = uris.first()
            val fname = displayName(ctx, uri)
            vm.launch {
                try {
                    val r = withContext(Dispatchers.Default) {
                        val (sr, x) = Audio.decode(ctx, uri)
                        Smp.resample(x, sr, Smp.RATE)
                    }
                    if (r.isEmpty()) throw Smp.SlotError("no audio")
                    withContext(Dispatchers.Default) {
                        ed.loadChopSource(r, fname.substringBeforeLast('.')
                            .uppercase().filter { it.code in 32..126 }.take(8).ifBlank { "CHOP" })
                    }
                    if (ed.name.isBlank()) ed.name = ed.srcName
                    ed.say("Loaded $fname (${secs(r.size)})")
                } catch (e: Exception) { ed.say("Cannot read: $fname (${e.message})", true) }
            }
        } else {
            vm.launch {
                var full = false
                for (uri in uris) {
                    val fname = displayName(ctx, uri)
                    if (ed.files.size >= Smp.MAX_ZONES) { full = true; break }
                    try {
                        val s = withContext(Dispatchers.Default) {
                            val (sr, x) = Audio.decode(ctx, uri)
                            Smp.normalize(Smp.resample(x, sr, Smp.RATE))
                        }
                        if (s.isEmpty()) throw Smp.SlotError("no audio")
                        val stem = fname.substringBeforeLast('.')
                        ed.files = ed.files + Smp.ZoneIn(fname, s, Smp.rootFromName(stem))
                        if (ed.name.isBlank()) ed.name =
                            stem.uppercase().filter { it.code in 32..126 }.take(8)
                    } catch (e: Exception) { ed.say("Cannot read: $fname (${e.message})", true) }
                }
                if (full) ed.say("A slot holds $Smp.MAX_ZONES files max", true)
            }
        }
    }

    // ---- mic recording ----
    val mics = remember { Audio.mics(ctx) }
    if (ed.mic == null) ed.mic = mics.first()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ed.startRecording() else ed.say("Microphone permission denied", true)
    }
    fun recordToggle() {
        if (ed.recording) {
            ed.recording = false
            vm.launch {
                try {
                    val rec = withContext(Dispatchers.Default) { ed.stopRecording() }
                    val r = withContext(Dispatchers.Default) { Smp.resample(rec, Audio.REC_RATE, Smp.RATE) }
                    if (r.isEmpty()) { ed.say("Nothing recorded", true); return@launch }
                    val pk = r.fold(0.0) { a, v -> if (v > a) v else if (-v > a) -v else a }
                    val pkInfo = "peak ${(pk * 100).toInt()}%" +
                        (if (pk < 0.001) " — silence, try another mic" else "")
                    if (ed.mode == SamplesEditor.Mode.CHOP) {
                        withContext(Dispatchers.Default) { ed.loadChopSource(r, "REC") }
                        if (ed.name.isBlank()) ed.name = "REC"
                        ed.say("Recorded ${secs(r.size)} · $pkInfo", pk < 0.001)
                    } else {
                        if (ed.files.size >= Smp.MAX_ZONES) {
                            ed.say("A slot holds ${Smp.MAX_ZONES} files max", true); return@launch
                        }
                        val fname = "REC ${ed.files.size + 1}"
                        val s = withContext(Dispatchers.Default) { Smp.normalize(r) }
                        ed.files = ed.files + Smp.ZoneIn("$fname.wav", s, 60)
                        if (ed.name.isBlank()) ed.name = "REC"
                        ed.say("Recorded ${secs(r.size)} → $fname · $pkInfo", pk < 0.001)
                    }
                } catch (e: Exception) { ed.say("Recording failed: ${e.message}", true) }
            }
        } else {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) ed.startRecording()
            else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(ed.recording) {
        while (ed.recording) {
            ed.recSeconds = ed.recorder.seconds
            ed.recLevel = ed.recorder.level
            delay(80)
        }
    }
    DisposableEffect(Unit) { onDispose { Audio.stop(); if (ed.recording) ed.stopRecording() } }

    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenHeader("Samples", state) }

        // ---- 1: the slot ----
        item {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("USR slot", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (k in 0 until nslots) {
                            val s = smp?.slots?.getOrNull(k)
                            FilterChip(selected = ed.slot == k, onClick = { ed.slot = k },
                                label = {
                                    Text("USR${k + 1}  " +
                                        (if (s != null && s.zones > 0)
                                            "${s.name.ifBlank { "—" }} · ${s.zones}z · ${s.kib} KiB"
                                        else "empty"))
                                })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val cur = smp?.slots?.getOrNull(ed.slot)
                        Text(
                            if (cur != null && cur.zones > 0)
                                "USR${ed.slot + 1} holds \"${cur.name}\" — sending replaces it"
                            else "USR${ed.slot + 1} is free",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = !ed.busy && cur != null && cur.zones > 0, onClick = {
                            confirm = Triple("Erase USR${ed.slot + 1}?",
                                "\"${cur?.name ?: ""}\" (${cur?.zones ?: 0} zones) will be removed from the device.") {
                                op { if (ctl.smpErase(ed.slot) == 0) ed.say("USR${ed.slot + 1} erased") }
                            }
                        }) { Text("Erase") }
                    }
                }
            }
        }

        // ---- 2: the sound ----
        item {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sound", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f))
                        FilterChip(selected = ed.mode == SamplesEditor.Mode.FILES,
                            onClick = { ed.mode = SamplesEditor.Mode.FILES }, label = { Text("Files") })
                        FilterChip(selected = ed.mode == SamplesEditor.Mode.CHOP,
                            onClick = { ed.mode = SamplesEditor.Mode.CHOP }, label = { Text("Chop") })
                    }
                    Text(
                        if (ed.mode == SamplesEditor.Mode.FILES)
                            "One file per note: roots come from file names (\"kick_C3.wav\"), the rest of the keys split themselves. Recording goes in as a file too."
                        else "One recording cut into pieces, one piece per key. Open a file or record, mark the chops, send. Longer than a slot is fine: keep the chops you want. On the wave: tap drops a marker, tap on one selects it, drag its dot to move it, hold to delete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !ed.busy && !ed.recording, onClick = {
                            pendingMode = ed.mode
                            opener.launch(arrayOf("audio/*", "application/octet-stream"))
                        }) {
                            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (ed.mode == SamplesEditor.Mode.FILES) "Add files" else "Open")
                        }
                        OutlinedButton(enabled = !ed.busy, onClick = { recordToggle() }) {
                            Icon(if (ed.recording) Icons.Filled.Stop else Icons.Filled.Mic, null,
                                Modifier.size(18.dp),
                                tint = if (ed.recording) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(if (ed.recording) "Stop" else "Record")
                        }
                        ed.mic?.let { cur ->
                            MiniPicker("", mics.map { it.label to it }, cur,
                                enabled = !ed.recording) { ed.mic = it }
                        }
                        if (ed.recording) {
                            Box(Modifier.size(10.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape))
                            Text("%.1f s".format(ed.recSeconds),
                                fontFamily = SloopFontFamily,
                                style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { ed.recLevel },
                                modifier = Modifier.width(80.dp))
                        }
                    }

                    if (ed.mode == SamplesEditor.Mode.FILES) FilesPane(ed)
                    else ChopPane(ed)
                }
            }
        }

        // ---- 3: send and play ----
        item {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Send", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ed.name,
                            onValueChange = { v ->
                                ed.name = v.uppercase().filter { it.code in 32..126 }.take(8)
                            },
                            label = { Text("Name (8 chars)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f))
                        val ready = !ed.busy && ed.name.isNotBlank() && when (ed.mode) {
                            SamplesEditor.Mode.FILES -> ed.files.isNotEmpty() &&
                                ed.files.sumOf { (it.s.size + 1) / 2 } <= Smp.MAX_DATA
                            SamplesEditor.Mode.CHOP -> ed.usedChops.isNotEmpty() &&
                                ed.usedSamples <= Smp.MAX_DATA * 2
                        }
                        Button(enabled = ready, onClick = {
                            val cur = smp?.slots?.getOrNull(ed.slot)
                            val doSend: () -> Unit = {
                                op {
                                    ed.progress = 0f
                                    val zones = if (ed.mode == SamplesEditor.Mode.FILES) ed.files
                                        else Smp.chopZones(ed.src!!, ed.chops, ed.key0,
                                            ed.chopMode, ed.sel.coerceAtLeast(0))
                                    try {
                                        ctl.smpUpload(ed.slot, ed.name, zones) { ed.progress = it }
                                        ed.say("USR${ed.slot + 1} written")
                                    } finally { ed.progress = -1f }
                                }
                            }
                            if (cur != null && cur.zones > 0)
                                confirm = Triple("Replace USR${ed.slot + 1}?",
                                    "\"${cur.name}\" will be replaced by \"${ed.name}\".", doSend)
                            else doSend()
                        }) { Text("Send to USR${ed.slot + 1}") }
                    }
                    if (ed.progress >= 0f)
                        LinearProgressIndicator(progress = { ed.progress }, Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Play on track", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val cur = smp?.slots?.getOrNull(ed.slot)
                        for (t in 0 until minOf(3, state.info.ntrk)) {
                            OutlinedButton(enabled = !ed.busy && cur != null && cur.zones > 0,
                                onClick = {
                                    op {
                                        ctl.smpUseOn(t, ed.slot)
                                        ed.say("Track ${t + 1}: SAMPLE, SET = USR${ed.slot + 1}")
                                    }
                                }) { Text("${t + 1}") }
                        }
                    }
                    ed.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (ed.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    confirm?.let { (title, text, act) ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(title) }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; act() }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

// ---------------------------------------------------------------- files mode ---

@Composable
private fun FilesPane(ed: SamplesEditor) {
    val keys = Smp.keySplit(ed.files.map { it.root })
    val usedBytes = ed.files.sumOf { (it.s.size + 1) / 2 }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ed.files.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { (usedBytes.toFloat() / Smp.MAX_DATA).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f))
                Text(
                    (if (usedBytes > Smp.MAX_DATA) "too big · " else "") +
                        "%.1f / %.1f s".format(ed.files.sumOf { it.s.size }.toDouble() / Smp.RATE,
                            Smp.MAX_DATA * 2.0 / Smp.RATE),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = SloopFontFamily,
                    color = if (usedBytes > Smp.MAX_DATA) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            ed.files.forEachIndexed { i, f ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { Audio.play(f.s) }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.PlayArrow, "play", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(f.name, Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    NotePicker(f.root) { f.root = it }
                    val k = keys.getOrNull(i)
                    if (k != null) Text("${Smp.noteName(k[0])}–${Smp.noteName(k[1])}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = SloopFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(secs(f.s.size), style = MaterialTheme.typography.labelSmall,
                        fontFamily = SloopFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = {
                        ed.files = ed.files.filterIndexed { j, _ -> j != i }
                    }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, "remove", Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            TextButton(onClick = { ed.files = emptyList() }) { Text("Clear list") }
        }
    }
}

/** Root-note selector: C1..C7 in a dropdown. */
@Composable
private fun NotePicker(value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(Smp.noteName(value), Modifier.clickable { open = true }
            .padding(horizontal = 6.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, fontFamily = SloopFontFamily,
            color = MaterialTheme.colorScheme.primary)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (n in 24..95) DropdownMenuItem(
                text = { Text(Smp.noteName(n)) },
                onClick = { onChange(n); open = false })
        }
    }
}

// ---------------------------------------------------------------- chop mode ---

@Composable
private fun ChopPane(ed: SamplesEditor) {
    val cs = MaterialTheme.colorScheme
    val typeface = ResourcesCompat.getFont(LocalContext.current, R.font.jetbrains_mono)
        ?: Typeface.MONOSPACE
    val x = ed.src
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (x == null) {
            Text("Nothing loaded — open a file or record. The wave appears here.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant)
            return@Column
        }
        val chops = ed.chops
        val used = ed.usedChops
        Text("${ed.srcName} · ${secs(x.size)} · ${chops.size} chops" +
            (if (chops.count { !it.off } < chops.size) " (${chops.count { !it.off }} kept)" else ""),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

        // ---- the wave: tap near a marker = select + play, tap elsewhere = add (snapped),
        //      drag a marker's handle = move it, long-press a marker = delete ----
        var dragMark by remember { mutableIntStateOf(-1) }
        val markPaint = remember { Paint().apply { textSize = 26f; this.typeface = typeface } }
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height(140.dp)
                .pointerInput(x) {
                    fun nearMark(px: Float, radiusPx: Float): Int? {
                        val per = x.size / size.width.toFloat()
                        val pos = px * per
                        return ed.marks.indices.minByOrNull { kotlin.math.abs(ed.marks[it].start - pos) }
                            ?.takeIf { kotlin.math.abs(ed.marks[it].start - pos) < radiusPx * per }
                    }
                    detectTapGestures(
                        onTap = { off ->
                            val mi = nearMark(off.x, 24f)
                            if (mi != null) {
                                ed.sel = mi
                                chops.getOrNull(mi)?.let { Audio.play(x, it.start, it.end) }
                            } else {
                                val n = ed.nov
                                ed.addMark(if (n != null)
                                    Smp.chopSnap(x, n, (off.x * x.size / size.width).toDouble(), 0.02)
                                    else (off.x * x.size / size.width).toInt())
                            }
                        },
                        onLongPress = { off -> nearMark(off.x, 24f)?.let { ed.removeMark(it) } },
                    )
                }
                .pointerInput(x) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val per = x.size / size.width.toFloat()
                            val pos = o.x * per
                            dragMark = ed.marks.indices
                                .minByOrNull { kotlin.math.abs(ed.marks[it].start - pos) }
                                ?.takeIf { kotlin.math.abs(ed.marks[it].start - pos) < 28 * per } ?: -1
                            if (dragMark >= 0) ed.sel = dragMark
                        },
                        onDrag = { c, _ ->
                            if (dragMark >= 0) {
                                ed.moveMark(dragMark,
                                    (c.position.x * (x.size / size.width.toFloat())).toInt())
                                c.consume()
                            }
                        },
                        onDragEnd = {
                            dragMark = -1
                            chops.getOrNull(ed.sel)?.let { Audio.play(x, it.start, it.end) }
                        },
                    )
                }) {
                val w = size.width
                val h = size.height
                val mid = h / 2f
                drawRect(cs.surfaceVariant)
                // chop regions
                for (c in chops) {
                    val x0 = c.start * w / x.size
                    val x1 = c.end * w / x.size
                    drawRect(
                        if (c.i == ed.sel) cs.primary.copy(alpha = 0.18f)
                        else cs.secondary.copy(alpha = if (c.off) 0.03f else 0.09f),
                        topLeft = Offset(x0, 0f), size = androidx.compose.ui.geometry.Size(x1 - x0, h))
                }
                // wave
                val per = x.size / w
                var px = 0
                while (px < w.toInt()) {
                    val a = (px * per).toInt()
                    val b = minOf(x.size, ((px + 1) * per).toInt().coerceAtLeast(a + 1))
                    var mn = 0.0; var mx = 0.0
                    for (i in a until b) { val v = x[i]; if (v < mn) mn = v; if (v > mx) mx = v }
                    drawLine(cs.onSurfaceVariant, Offset(px.toFloat(), mid - mx.toFloat() * mid * 0.92f),
                        Offset(px.toFloat(), mid - mn.toFloat() * mid * 0.92f), 1f)
                    px++
                }
                drawLine(cs.outline, Offset(0f, mid), Offset(w, mid), 1f)
                // markers: a grab handle on top of each line (touch zone is +-24 px)
                for (m in ed.marks.withIndex()) {
                    val mk = m.value
                    val mx = mk.start * w / x.size
                    val col = if (m.index == ed.sel) cs.primary else cs.tertiary
                    drawLine(col, Offset(mx, 0f), Offset(mx, h), if (m.index == ed.sel) 5f else 3f)
                    drawCircle(col, radius = if (m.index == ed.sel) 11f else 8f,
                        center = Offset(mx, 16f))
                }
                markPaint.color = android.graphics.Color.WHITE
                for (m in ed.marks.withIndex()) {
                    drawContext.canvas.nativeCanvas.drawText("${m.index + 1}",
                        m.value.start * w / x.size + 4f, 22f, markPaint)
                }
            }
        }

        // ---- mark tools ----
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                ed.nov?.let { ed.setMarkers(Smp.chopHits(x, it, ed.sens).toList()) }
            }) { Text("Chop on hits") }
            Text("sens", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Slider(value = ed.sens.toFloat(), onValueChange = { ed.sens = it.toInt() },
                valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f))
            Text("${ed.sens}", fontFamily = SloopFontFamily,
                style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var bpm by remember { mutableStateOf("90") }
            var div by remember { mutableStateOf(1.0) }
            var parts by remember { mutableIntStateOf(8) }
            val region = if (ed.marks.size >= 2) ed.marks.first().start to ed.marks.last().start
                else 0 to x.size
            OutlinedButton(onClick = {
                ed.setMarkers(Smp.chopGrid(region.first, region.second,
                    bpm.toDoubleOrNull()?.coerceAtLeast(40.0) ?: 90.0, div).toList())
            }) { Text("Grid") }
            OutlinedTextField(value = bpm, onValueChange = { bpm = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                modifier = Modifier.width(72.dp), singleLine = true,
                label = { Text("BPM") })
            MiniPicker("div", listOf("1 bar" to 4.0, "1/2" to 2.0, "1/4" to 1.0,
                "1/8" to 0.5, "1/16" to 0.25), div) { div = it }
            OutlinedButton(onClick = {
                ed.setMarkers(Smp.chopEqual(region.first, region.second, parts).toList())
            }) { Text("Equal") }
            MiniPicker("n", listOf(2, 3, 4, 6, 8, 12, 16).map { "$it" to it }, parts) { parts = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("First key", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            MiniPicker("", (36..84).map { Smp.noteName(it) to it }, ed.key0) { ed.key0 = it }
            MiniPicker("", listOf("one key per chop" to 0, "selected on all keys" to 1),
                ed.chopMode) { ed.chopMode = it }
            MiniPicker("max len", listOf("—" to 0f, "0.25 s" to 0.25f, "0.5 s" to 0.5f,
                "1 s" to 1f, "2 s" to 2f), ed.maxLen) { ed.maxLen = it }
        }

        // ---- the chops ----
        if (chops.isNotEmpty()) {
            val keyOf = HashMap<Int, Int>()
            used.forEach { c -> if (ed.chopMode == 0) keyOf[c.i] = minOf(127, ed.key0 + used.indexOf(c)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                chops.forEach { c ->
                    FilterChip(selected = c.i == ed.sel,
                        onClick = { ed.sel = c.i; Audio.play(x, c.start, c.end) },
                        label = {
                            Text("${c.i + 1}${keyOf[c.i]?.let { " ${Smp.noteName(it)}" } ?: if (c.off) " —" else ""}",
                                color = if (c.off) cs.onSurfaceVariant else cs.onSurface)
                        })
                }
            }
            val c = chops.getOrNull(ed.sel)
            if (c != null) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chop ${c.i + 1}", style = MaterialTheme.typography.bodySmall,
                        color = cs.primary)
                    Text("keep", style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant)
                    Switch(checked = !c.off, onCheckedChange = { ed.keep(c.i, it) })
                    IconButton(onClick = { Audio.play(x, c.start, c.end) }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.PlayArrow, "play", Modifier.size(18.dp), tint = cs.primary)
                    }
                    TextButton(onClick = { ed.removeMark(c.i) }) { Text("Delete") }
                    Spacer(Modifier.weight(1f))
                    Text("${secs(c.len)} / ${secs(c.full - c.start)}",
                        style = MaterialTheme.typography.labelSmall, fontFamily = SloopFontFamily,
                        color = cs.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("len", style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant)
                    Slider(value = c.len.toFloat(),
                        onValueChange = { ed.setChopLen(c.i, it.toInt()) },
                        valueRange = 0f..maxOf(1f, (c.full - c.start).toFloat()),
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { ed.setChopLen(c.i, 0) },
                        enabled = c.end < c.full) { Text("Full") }
                }
                // marker position: fine nudging (1 / 10 / 50 ms) for thumbs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("mark", style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant)
                    val step = { ms: Double -> (ms * Smp.RATE / 1000).toInt() }
                    TextButton(onClick = { ed.moveMark(c.i, c.start - step(50.0)) }) { Text("«") }
                    TextButton(onClick = { ed.moveMark(c.i, c.start - step(10.0)) }) { Text("‹") }
                    TextButton(onClick = { ed.moveMark(c.i, c.start - step(1.0)) }) { Text("·") }
                    Text("%.3f s".format(c.start.toDouble() / Smp.RATE),
                        style = MaterialTheme.typography.labelSmall, fontFamily = SloopFontFamily,
                        color = cs.onSurfaceVariant)
                    TextButton(onClick = { ed.moveMark(c.i, c.start + step(1.0)) }) { Text("·") }
                    TextButton(onClick = { ed.moveMark(c.i, c.start + step(10.0)) }) { Text("›") }
                    TextButton(onClick = { ed.moveMark(c.i, c.start + step(50.0)) }) { Text("»") }
                }
            }
            // meter + fit
            val over = used.fold(0) { a, q -> a + (q.len + 1) / 2 } > Smp.MAX_DATA
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { (ed.usedSamples.toFloat() / (Smp.MAX_DATA * 2)).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f))
                Text("${secs(ed.usedSamples)} / ${secs(Smp.MAX_DATA * 2)}" +
                    (if (over) " too long" else "") +
                    (if (ed.chopMode == 0 && chops.count { !it.off } > Smp.Chop.MAX) " · 16 max" else ""),
                    style = MaterialTheme.typography.bodySmall, fontFamily = SloopFontFamily,
                    color = if (over) cs.error else cs.onSurfaceVariant)
                TextButton(enabled = over, onClick = { ed.fitAll() }) { Text("Fit") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { ed.marks.indices.forEach { ed.keep(it, true) } }) { Text("All") }
                TextButton(onClick = { ed.marks.indices.forEach { ed.keep(it, false) } }) { Text("None") }
                TextButton(onClick = { ed.setMarkers(emptyList()) }) { Text("Clear") }
            }
        }
    }
}

/** Compact dropdown for numeric/note options. */
@Composable
private fun <T> MiniPicker(label: String, options: List<Pair<String, T>>, value: T,
                           enabled: Boolean = true, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text((if (label.isBlank()) "" else "$label ") +
                (options.firstOrNull { it.second == value }?.first ?: "$value"),
                style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (t, v) ->
                DropdownMenuItem(text = { Text(t) }, onClick = { onChange(v); open = false })
            }
        }
    }
}
