// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
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
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun SamplesScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit, nav: @Composable () -> Unit) {
    if (state.link != Link.READY || state.info == null) {
        NotReady("Samples", state, onDevice)
        return
    }
    val ed = vm.samples
    val ctl = vm.controller
    val cs = MaterialTheme.colorScheme
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
    LaunchedEffect(ed.said) { if (ed.message != null) { delay(5000); ed.message = null } }

    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

    val tight = PaddingValues(horizontal = 10.dp)
    val focus = LocalFocusManager.current
    val cur = smp?.slots?.getOrNull(ed.slot)

    Column(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }) {
        // ---- pinned header: nav, slot pick with a short info, name, send, play on track ----
        Row(Modifier.fillMaxWidth().background(cs.surface).horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            nav()
            Text("Samples", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            var slotMenu by remember { mutableStateOf(false) }
            Box {
                Text("U${ed.slot + 1} ▾" + (if (cur != null && cur.zones > 0)
                        " ${cur.name.ifBlank { "—" }} · ${cur.zones}z" else ""),
                    Modifier.clickable { slotMenu = true }
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                    color = cs.primary, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                DropdownMenu(expanded = slotMenu, onDismissRequest = { slotMenu = false }) {
                    for (k in 0 until nslots) {
                        val sl = smp?.slots?.getOrNull(k)
                        DropdownMenuItem(text = {
                            Text("USR${k + 1}  " +
                                (if (sl != null && sl.zones > 0)
                                    "${sl.name.ifBlank { "—" }} · ${sl.zones}z · ${sl.kib} KiB"
                                else "empty"),
                                color = if (k == ed.slot) cs.primary else cs.onSurface)
                        }, onClick = { ed.slot = k; slotMenu = false })
                    }
                    if (cur != null && cur.zones > 0) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Erase USR${ed.slot + 1}", color = cs.error) },
                            onClick = {
                                slotMenu = false
                                confirm = Triple("Erase USR${ed.slot + 1}?",
                                    "\"${cur.name}\" (${cur.zones} zones) will be removed from the device.") {
                                    op { if (ctl.smpErase(ed.slot) == 0) ed.say("USR${ed.slot + 1} erased") }
                                }
                            })
                    }
                }
            }
            BasicTextField(value = ed.name, singleLine = true,
                onValueChange = { v -> ed.name = v.uppercase().filter { it.code in 32..126 }.take(8) },
                textStyle = MaterialTheme.typography.bodySmall.copy(color = cs.onSurface,
                    fontFamily = SloopFontFamily),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.width(80.dp).padding(top = 4.dp),
                decorationBox = { inner ->
                    Column {
                        Box {
                            if (ed.name.isEmpty()) Text("NAME",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = SloopFontFamily, color = cs.onSurfaceVariant)
                            inner()
                        }
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.height(1.dp).fillMaxWidth().background(cs.outline))
                    }
                })
            val ready = !ed.busy && ed.name.isNotBlank() && when (ed.mode) {
                SamplesEditor.Mode.FILES -> ed.files.isNotEmpty() &&
                    ed.files.sumOf { (it.s.size + 1) / 2 } <= Smp.MAX_DATA
                SamplesEditor.Mode.CHOP -> ed.usedChops.isNotEmpty() &&
                    ed.usedSamples <= Smp.MAX_DATA * 2
            }
            Button(enabled = ready, modifier = Modifier.height(32.dp), contentPadding = tight,
                onClick = {
                    val c2 = smp?.slots?.getOrNull(ed.slot)
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
                    if (c2 != null && c2.zones > 0)
                        confirm = Triple("Replace USR${ed.slot + 1}?",
                            "\"${c2.name}\" will be replaced by \"${ed.name}\".", doSend)
                    else doSend()
                }) { Text("Send") }
            Text("Play on", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            for (t in 0 until minOf(3, state.info.ntrk)) {
                HoldButton("${t + 1}", enabled = !ed.busy && cur != null && cur.zones > 0) {
                    op {
                        ctl.smpUseOn(t, ed.slot)
                        ed.say("Track ${t + 1}: SAMPLE, SET = USR${ed.slot + 1}")
                    }
                }
            }
        }

        if (ed.busy || ed.progress >= 0f || ed.message != null)
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                if (ed.progress >= 0f)
                    LinearProgressIndicator(progress = { ed.progress }, Modifier.fillMaxWidth())
                else if (ed.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ed.message?.let {
                    Text(it, color = if (ed.isError) cs.error else cs.primary,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

        // ---- the scrolling middle: one Sound card ----
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Sound", style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f))
                            FilterChip(selected = ed.mode == SamplesEditor.Mode.CHOP,
                                onClick = { ed.mode = SamplesEditor.Mode.CHOP },
                                label = { Text("Chop") })
                            FilterChip(selected = ed.mode == SamplesEditor.Mode.FILES,
                                onClick = { ed.mode = SamplesEditor.Mode.FILES },
                                label = { Text("Files") })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                    tint = if (ed.recording) cs.error else cs.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(if (ed.recording) "Stop" else "Record")
                            }
                            ed.mic?.let { cur ->
                                MiniPicker("", mics.map { it.label to it }, cur,
                                    enabled = !ed.recording) { ed.mic = it }
                            }
                            if (ed.recording) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(10.dp).background(cs.error, CircleShape))
                                    Text("%.1f s".format(ed.recSeconds), fontFamily = SloopFontFamily,
                                        style = MaterialTheme.typography.bodySmall)
                                    LinearProgressIndicator(progress = { ed.recLevel },
                                        modifier = Modifier.width(64.dp))
                                }
                            }
                        }
                        Text(if (ed.mode == SamplesEditor.Mode.FILES)
                                "One file per note — the root comes from each file name."
                            else "On the wave: tap adds a marker, tap a marker selects it, drag moves it, hold deletes",
                            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        if (ed.mode == SamplesEditor.Mode.FILES) FilesPane(ed) else ChopPane(ed)
                    }
                }
            }
        }


        // ---- pinned footer: the chops' keys, drum-synth style; only while a sample is loaded ----
        if (ed.mode == SamplesEditor.Mode.CHOP) ed.src?.let { x ->
            val chops = ed.chops
            if (chops.isNotEmpty()) {
                val keyOf = HashMap<Int, Int>()
                ed.usedChops.forEach { c -> if (ed.chopMode == 0) keyOf[c.i] = minOf(127, ed.key0 + ed.usedChops.indexOf(c)) }
                Surface(color = cs.surface, tonalElevation = 3.dp, shadowElevation = 12.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        chops.take(Smp.Chop.MAX).forEach { c ->
                            val on = c.i == ed.sel
                            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (on) cs.primary else cs.surfaceVariant)
                                .then(if (on) Modifier else Modifier.border(1.dp, cs.onSurfaceVariant, RoundedCornerShape(10.dp)))
                                .clickable { ed.sel = c.i; Audio.play(x, c.start, c.end) },
                                contentAlignment = Alignment.Center) {
                                Text("${c.i + 1}" + (keyOf[c.i]?.let { " ${Smp.noteName(it)}" } ?: ""),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = if (on) cs.onPrimary
                                        else if (c.off) cs.onSurfaceVariant else cs.onSurface,
                                    fontSize = 11.sp, lineHeight = 12.sp, maxLines = 2)
                            }
                        }
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
    val chops = ed.chops
    val used = ed.usedChops
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (x != null)
            Text("${ed.srcName} · ${secs(x.size)} · ${chops.size} chops" +
                (if (chops.count { !it.off } < chops.size) " (${chops.count { !it.off }} kept)" else ""),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)

        // ---- the wave: tap near a marker = select + play, tap elsewhere = add (snapped),
        //      drag a marker's handle = move it, long-press a marker = delete ----
        var dragMark by remember { mutableIntStateOf(-1) }
        val markPaint = remember { Paint().apply { textSize = 26f; this.typeface = typeface } }
        // the key each kept chop lands on: left to right on the wave, like the footer's pads
        val keyOf = HashMap<Int, Int>()
        used.forEachIndexed { j, c -> if (ed.chopMode == 0) keyOf[c.i] = minOf(127, ed.key0 + j) }
        // the viewer is pinned in the Sound block — an empty plate until a take is loaded
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            if (x == null) {
                Box(Modifier.fillMaxSize().background(cs.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    Text("Open a file or record — the wave appears here",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            } else
            Canvas(Modifier.fillMaxSize()
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
                for (m in ed.marks.withIndex()) {
                    val tx = m.value.start * w / x.size + 4f
                    markPaint.color = android.graphics.Color.WHITE
                    drawContext.canvas.nativeCanvas.drawText("${m.index + 1}", tx, 22f, markPaint)
                    keyOf[m.index]?.let { k ->
                        markPaint.color = (if (m.index == ed.sel) cs.primary else cs.onSurfaceVariant).toArgb()
                        drawContext.canvas.nativeCanvas.drawText(Smp.noteName(k), tx, 46f, markPaint)
                    }
                }
            }
        }
        if (x == null) return@Column

        // ---- the picked chop: one panel — who it is, its length, its marker spot ----
        val c = chops.getOrNull(ed.sel)
        if (c != null) {
            val step = { ms: Double -> (ms * Smp.RATE / 1000).toInt() }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { Audio.play(x, c.start, c.end) }, Modifier.size(36.dp)) {
                        Icon(Icons.Filled.PlayArrow, "play", Modifier.size(20.dp), tint = cs.primary)
                    }
                    Text("Chop ${c.i + 1}", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, color = cs.primary)
                    keyOf[c.i]?.let {
                        Text("· ${Smp.noteName(it)}", style = MaterialTheme.typography.bodySmall,
                            fontFamily = SloopFontFamily, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${secs(c.len)} / ${secs(c.full - c.start)}",
                        style = MaterialTheme.typography.labelSmall, fontFamily = SloopFontFamily,
                        color = cs.onSurfaceVariant)
                    Text("keep", style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant)
                    Switch(checked = !c.off, onCheckedChange = { ed.keep(c.i, it) })
                    IconButton(onClick = { ed.removeMark(c.i) }, Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Delete, "delete", Modifier.size(18.dp), tint = cs.error)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("len", style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant)
                    Slider(value = c.len.toFloat(),
                        onValueChange = { ed.setChopLen(c.i, it.toInt()) },
                        valueRange = 0f..maxOf(1f, (c.full - c.start).toFloat()),
                        modifier = Modifier.weight(1f))
                    StepButton("‹") { ed.setChopLen(c.i, c.len - step(10.0)) }
                    StepButton("›") { ed.setChopLen(c.i, c.len + step(10.0)) }
                    TextButton(onClick = { ed.setChopLen(c.i, 0) },
                        enabled = c.end < c.full) { Text("Full") }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("pos", style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant)
                    StepButton("«") { ed.moveMark(c.i, c.start - step(50.0)) }
                    StepButton("‹") { ed.moveMark(c.i, c.start - step(10.0)) }
                    Text("%.3f s".format(c.start.toDouble() / Smp.RATE),
                        style = MaterialTheme.typography.labelSmall, fontFamily = SloopFontFamily,
                        color = cs.onSurfaceVariant)
                    StepButton("›") { ed.moveMark(c.i, c.start + step(10.0)) }
                    StepButton("»") { ed.moveMark(c.i, c.start + step(50.0)) }
                }
            }
        }
        // ---- mark tools: compact buttons and pickers packed into one wrapping row ----
        var bpm by remember { mutableStateOf("90") }
        var div by remember { mutableStateOf(1.0) }
        var parts by remember { mutableIntStateOf(8) }
        val region = if (ed.marks.size >= 2) ed.marks.first().start to ed.marks.last().start
            else 0 to x.size
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = {
                ed.nov?.let { ed.setMarkers(Smp.chopHits(x, it, ed.sens).toList()) }
            }) { Text("Chop on hits") }
            MiniPicker("sens", (1..10).map { "$it" to it }, ed.sens) { ed.sens = it }
            OutlinedButton(onClick = {
                ed.setMarkers(Smp.chopGrid(region.first, region.second,
                    bpm.toDoubleOrNull()?.coerceAtLeast(40.0) ?: 90.0, div).toList())
            }) { Text("Grid") }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(40.dp).clip(RoundedCornerShape(8.dp))
                    .background(cs.surfaceVariant).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("BPM", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                BasicTextField(value = bpm, singleLine = true,
                    onValueChange = { bpm = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = cs.onSurface,
                        fontFamily = SloopFontFamily),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(40.dp))
            }
            MiniPicker("div", listOf("1 bar" to 4.0, "1/2" to 2.0, "1/4" to 1.0,
                "1/8" to 0.5, "1/16" to 0.25), div) { div = it }
            OutlinedButton(onClick = {
                ed.setMarkers(Smp.chopEqual(region.first, region.second, parts).toList())
            }) { Text("Equal") }
            MiniPicker("n", listOf(2, 3, 4, 6, 8, 12, 16).map { "$it" to it }, parts) { parts = it }
            MiniPicker("key", (36..84).map { Smp.noteName(it) to it }, ed.key0) { ed.key0 = it }
            MiniPicker("", listOf("one key per chop" to 0, "selected on all keys" to 1),
                ed.chopMode) { ed.chopMode = it }
            MiniPicker("max len", listOf("—" to 0f, "0.25 s" to 0.25f, "0.5 s" to 0.5f,
                "1 s" to 1f, "2 s" to 2f), ed.maxLen) { ed.maxLen = it }
        }

        if (chops.isNotEmpty()) {
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

/** A small square nudge button for the chop panel. */
@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.width(34.dp).height(32.dp).clip(RoundedCornerShape(8.dp))
        .background(cs.surfaceVariant).clickable { onClick() },
        contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurface)
    }
}

/** Press-and-hold pad: fills left to right and fires only once full (~0.5 s). */
@Composable
private fun HoldButton(label: String, enabled: Boolean, onHold: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var held by remember { mutableStateOf(false) }
    var prog by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(held) {
        if (!held) { prog = 0f; return@LaunchedEffect }
        val t0 = System.nanoTime()
        while (held) {
            prog = ((System.nanoTime() - t0) / 5e8).toFloat().coerceAtMost(1f)
            if (prog >= 1f) { held = false; prog = 0f; onHold(); break }
            delay(16)
        }
    }
    Box(Modifier.width(36.dp).height(32.dp).clip(RoundedCornerShape(8.dp))
        .background(cs.surfaceVariant.copy(alpha = if (enabled) 1f else 0.4f))
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(onPress = {
                held = true
                tryAwaitRelease()
                held = false
            })
        }, contentAlignment = Alignment.Center) {
        if (prog > 0f)
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight()
                .fillMaxWidth(prog).background(cs.primary.copy(alpha = 0.55f)))
        Text(label, style = MaterialTheme.typography.labelSmall,
            fontFamily = SloopFontFamily,
            color = if (enabled) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.5f))
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
