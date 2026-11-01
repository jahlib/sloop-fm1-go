// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
@file:OptIn(ExperimentalLayoutApi::class)

package com.sloop.go.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sloop.go.audio.RecFiles
import com.sloop.go.audio.Wav
import com.sloop.go.device.DeviceState
import kotlinx.coroutines.delay
import kotlin.math.log10

/** Records the FM-1's USB audio out (the master, as a computer's DAW gets it) into WAV files on the phone. */
@Composable
fun RecorderScreen(vm: SloopViewModel, @Suppress("UNUSED_PARAMETER") state: DeviceState, nav: @Composable () -> Unit) {
    val ed = vm.usbRec
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme

    // the input list follows the cable: the FM-1 appears / disappears as a USB audio device
    DisposableEffect(Unit) {
        ed.refreshInputs()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = ed.refreshInputs()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = ed.refreshInputs()
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        onDispose {
            am.unregisterAudioDeviceCallback(cb)
            if (ed.monitoring) vm.launch { ed.stop() }        // metering ends with the page; a take goes on
        }
    }
    LaunchedEffect(ed.said) { if (ed.message != null) { delay(if (ed.isError) 9000 else 6000); ed.message = null } }

    var wantRecord by remember { mutableStateOf(true) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.launch { ed.start(wantRecord) }
        else ed.say("Android asks for the microphone permission to read any audio input, the USB one too", true)
    }
    fun run(record: Boolean) {
        wantRecord = record
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            vm.launch { ed.start(record) }
        else perm.launch(Manifest.permission.RECORD_AUDIO)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) ed.pickFolder(uri)
    }

    Column(Modifier.fillMaxSize()) {
        PinnedHeader(nav, when {
            ed.recording -> "● REC ${sizeText(ed.bytes)}"
            ed.monitoring -> "input test"
            else -> ""
        }, if (ed.recording) cs.error else Color.Unspecified) {
            Text("Recorder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val tight = PaddingValues(horizontal = 12.dp)
            if (ed.recording) Button(onClick = { vm.launch { ed.stop() } }, enabled = !ed.busy,
                modifier = Modifier.height(34.dp), contentPadding = tight,
                colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) {
                Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop")
            } else Button(onClick = { run(true) }, enabled = !ed.busy && ed.input != null,
                modifier = Modifier.height(34.dp), contentPadding = tight) {
                Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp), tint = cs.error)
                Spacer(Modifier.width(6.dp)); Text("Rec")
            }
            OutlinedButton(onClick = { if (ed.monitoring) vm.launch { ed.stop() } else run(false) },
                enabled = !ed.busy && !ed.recording && ed.input != null,
                modifier = Modifier.height(34.dp), contentPadding = tight) {
                Icon(Icons.Filled.GraphicEq, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(if (ed.monitoring) "Stop test" else "Test input")
            }
            Text(hms(if (ed.active) ed.seconds else 0.0), style = MaterialTheme.typography.titleMedium,
                fontFamily = SloopFontFamily, color = if (ed.recording) cs.error else cs.onSurfaceVariant)
        }
        ed.message?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                .clickable { ed.message = null },
                color = if (ed.isError) cs.error else cs.primary, style = MaterialTheme.typography.bodySmall)
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ---- left: levels, status, the last take ----
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Block("Level") {
                    Meter("L", ed.peakL)
                    Meter("R", ed.peakR)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("-60", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(if (ed.clipped) "CLIP · tap to reset" else "no clip",
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = ed.clipped) { ed.resetClip() }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ed.clipped) cs.error else cs.onSurfaceVariant,
                            fontWeight = if (ed.clipped) FontWeight.Bold else FontWeight.Normal)
                        Spacer(Modifier.weight(1f))
                        Text("0 dBFS", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    if (ed.active && ed.silent >= 3f) Note(
                        "No signal for ${ed.silent.toInt()} s. Play something on the FM-1. If the phone still hears " +
                            "silence or distortion, set AUDIO > USB RATE on the FM-1 to 44.1 (or the rate chosen here) " +
                            "and plug the cable in again.", cs.tertiary)
                    ed.warning?.let { Note(it, cs.tertiary) }
                    if (ed.active) Text(
                        if (ed.recording) "Writing ${ed.format.let { "${it.ext.uppercase()} · ${it.rate} Hz · ${bitsLabel(it.bits)} · ${chLabel(it.channels)}" }} " +
                            "to ${ed.whereLabel}" else "Input test: levels only, nothing is written",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                ed.last?.let { r ->
                    Block("Last take") {
                        Text(r.name ?: "", style = MaterialTheme.typography.bodyMedium, fontFamily = SloopFontFamily)
                        Text("${hms(r.seconds)} · ${sizeText(r.bytes)} · ${r.where}",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        if (!r.headerOk) Note("This folder could not rewrite the file's header: the length is " +
                            "marked unknown. Most players and DAWs open it anyway.", cs.tertiary)
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType(r.mime)
                                .putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { ctx.startActivity(Intent.createChooser(send, r.name)) }
                                .onFailure { ed.say("No app to share with", true) }
                        }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
                    }
                }
                Block("How it works") {
                    Text("The FM-1 sends its master out over the USB cable as a sound card input, the same stream a " +
                        "computer records in a DAW. A take goes on while you use the other pages; closing the app " +
                        "stops it and keeps the file. Keep Sloop Go in front: Android silences audio inputs for apps " +
                        "in the background.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }

            // ---- right: input, format, destination ----
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                val locked = ed.active || ed.busy
                Block("Input") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var open by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            val cur = ed.input
                            OutlinedButton(onClick = { open = true }, enabled = !locked && ed.inputs.size > 1,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(cur?.label ?: "No USB audio input", maxLines = 1)
                            }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                ed.inputs.forEach { i ->
                                    DropdownMenuItem(text = { Text(i.label) }, onClick = { ed.inputId = i.id; open = false })
                                }
                            }
                        }
                        IconButton(onClick = { ed.refreshInputs() }, enabled = !locked) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh inputs")
                        }
                    }
                    val cur = ed.input
                    if (cur == null) Note("The phone sees no USB audio input. Connect the FM-1 with a USB-C cable; " +
                        "the phone must support USB audio (most do with USB host / OTG).", cs.tertiary)
                    else {
                        Text("Reports: " + listOf(
                            if (cur.rates.isEmpty()) "any rate" else cur.rates.joinToString(" / ") { rateLabel(it) },
                            if (cur.channels.isEmpty()) "" else cur.channels.joinToString("/") + " ch",
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        if (cur.rates.isNotEmpty() && ed.rate !in cur.rates)
                            Note("The input does not report ${rateLabel(ed.rate)}: Android will resample. Pick a rate " +
                                "it reports for a bit-exact take.", cs.tertiary)
                    }
                }
                Block("Format") {
                    Label("File")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !ed.flac, enabled = !locked, onClick = { ed.pickFlac(false) },
                            label = { Text("WAV") })
                        FilterChip(selected = ed.flac, enabled = !locked, onClick = { ed.pickFlac(true) },
                            label = { Text("FLAC") })
                    }
                    Hint(if (ed.flac) "FLAC is lossless: the same samples as WAV, usually in half to two thirds of " +
                        "the size, with no 4 GB limit." else "WAV: uncompressed, opens everywhere.")
                    Label("Sample rate")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (r in listOf(44100, 48000))
                            FilterChip(selected = ed.rate == r, enabled = !locked, onClick = { ed.pickRate(r) },
                                label = { Text(rateLabel(r)) })
                    }
                    Hint("The FM-1 plays at 44.1 kHz; for 48 kHz it resamples itself. Its AUDIO > USB RATE must offer " +
                        "the rate (AUTO offers both).")
                    Label("Bit depth")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (b in if (ed.flac) listOf(16, 24) else listOf(16, 24, 32))
                            FilterChip(selected = ed.format.bits == b, enabled = !locked, onClick = { ed.pickBits(b) },
                                label = { Text(bitsLabel(b)) })
                    }
                    Hint("The FM-1 sends 16 bit. " + (if (ed.flac) "24 bit holds" else "24 bit and 32 bit float hold") +
                        " the same samples exactly, with headroom for editing later, in a bigger file.")
                    Label("Channels")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in listOf(2, 1))
                            FilterChip(selected = ed.channels == c, enabled = !locked, onClick = { ed.pickChannels(c) },
                                label = { Text(chLabel(c)) })
                    }
                    val perMin = ed.format.bytesPerSecond * 60L
                    Hint(if (ed.flac) "Up to ${sizeText(perMin)} per minute (the WAV size), usually much less"
                        else "${sizeText(perMin)} per minute · ${sizeText(perMin * 60)} per hour · a WAV stops at 4 GB " +
                            "(about ${(Wav.MAX_DATA / ed.format.bytesPerSecond / 60).toInt()} min)")
                }
                Block("Save to") {
                    Text(ed.whereLabel, style = MaterialTheme.typography.bodyMedium, fontFamily = SloopFontFamily)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderPicker.launch(ed.folder) }, enabled = !locked) {
                            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Choose folder")
                        }
                        if (ed.folder != null) OutlinedButton(onClick = { ed.defaultFolder() }, enabled = !locked) {
                            Text("Default (${RecFiles.defaultLabel(ctx)})", maxLines = 1)
                        }
                    }
                    OutlinedTextField(value = ed.prefix, onValueChange = ed::editPrefix, enabled = !locked,
                        singleLine = true, label = { Text("File name") }, modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = SloopFontFamily))
                    Hint("Next: ${ed.nextName()}")
                }
            }
        }
    }
}

private fun rateLabel(r: Int) = if (r % 1000 == 0) "${r / 1000} kHz" else "%.1f kHz".format(r / 1000.0)
private fun bitsLabel(b: Int) = if (b == 32) "32-bit float" else "$b-bit"
private fun chLabel(c: Int) = if (c == 1) "Mono" else "Stereo"

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Note(text: String, color: Color) = Text(text, style = MaterialTheme.typography.bodySmall, color = color)

/** A peak meter, -60..0 dBFS: green, yellow from -12, red from -3. */
@Composable
private fun Meter(label: String, peak: Float) {
    val cs = MaterialTheme.colorScheme
    val db = if (peak <= 1e-6f) -120f else 20f * log10(peak)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontFamily = SloopFontFamily)
        Canvas(Modifier.weight(1f).height(16.dp)) {
            val w = size.width
            val h = size.height
            fun x(d: Float) = w * ((d + 60f) / 60f).coerceIn(0f, 1f)
            drawRect(cs.surfaceVariant)
            val fill = x(db)
            val bands = listOf(-60f to -12f to SloopGreen, -12f to -3f to SloopYellow, -3f to 0f to cs.error)
            for ((range, color) in bands) {
                val a = x(range.first)
                val b = minOf(fill, x(range.second))
                if (b > a) drawRect(color, Offset(a, 0f), Size(b - a, h))
            }
            for (d in listOf(-48f, -36f, -24f, -12f, -6f, -3f))
                drawRect(cs.outline, Offset(x(d), 0f), Size(1f, h))
        }
        Text(if (db <= -60f) "  -∞" else "%5.1f".format(db), Modifier.width(44.dp),
            style = MaterialTheme.typography.labelSmall, fontFamily = SloopFontFamily)
    }
}
