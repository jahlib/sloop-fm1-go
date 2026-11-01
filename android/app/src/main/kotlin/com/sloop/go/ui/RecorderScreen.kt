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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sloop.go.audio.RecFiles
import com.sloop.go.audio.Spectrum
import com.sloop.go.audio.Wav
import com.sloop.go.audio.WaveStore
import com.sloop.go.device.DeviceState
import com.sloop.go.proto.Desc
import com.sloop.go.proto.Fmt
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The spectrum's vertical range, dBFS. */
private const val DB_MIN = -90f
private const val DB_MAX = 0f

/**
 * Records the FM-1's USB audio out (the master, as a computer's DAW gets it) into WAV / FLAC files on the phone. One
 * screen without scrolling: the file name, destination and format on top, a slowly scrolling waveform of the take with
 * the level meter standing beside it, and the notes under them.
 */
@Composable
fun RecorderScreen(vm: SloopViewModel, state: DeviceState, nav: @Composable () -> Unit) {
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
    // a name already in the destination: asks before the take replaces it
    var overwrite by remember { mutableStateOf<String?>(null) }
    fun go(record: Boolean) {
        vm.launch {
            val taken = if (record) ed.nameTaken() else null
            if (taken != null) overwrite = taken else ed.start(record)
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) go(wantRecord)
        else ed.say("Android asks for the microphone permission to read any audio input, the USB one too", true)
    }
    fun run(record: Boolean) {
        wantRecord = record
        ed.autoTest = false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            go(record)
        else perm.launch(Manifest.permission.RECORD_AUDIO)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) ed.pickFolder(uri)
    }
    // the FM-1 plays: meter it and draw the waveform as an input test would (nothing written), and let go when it stops;
    // only with the microphone permission already given (a first Test input asks for it) and only for a test it began
    LaunchedEffect(state.playing) {
        if (state.playing) {
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted && !ed.active && !ed.busy && ed.input != null) { ed.autoTest = true; vm.launch { ed.start(false) } }
        } else if (ed.monitoring && ed.autoTest) vm.launch { ed.stop() }
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
            } else Button(onClick = { run(true) }, enabled = !ed.busy && ed.input != null && overwrite == null,
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

        // the blocks scroll when they do not fit; the waveform takes the height they leave (at least 150 dp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            var topPx by remember { mutableIntStateOf(0) }
            var bottomPx by remember { mutableIntStateOf(0) }
            val others = with(LocalDensity.current) { (topPx + bottomPx).toDp() }
            val waveH = (maxHeight - others - 20.dp).coerceAtLeast(150.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.onSizeChanged { topPx = it.height }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RecSettings(ed, ed.active || ed.busy) { folderPicker.launch(ed.folder) }
                    DynBlocks(ed)
                }
                // the analyzer, the compact waveform, the pan half-circle and the level meter in one row,
                // each with its own on/off in the header (off: dimmed, not analysed, not drawn)
                Row(Modifier.fillMaxWidth().height(waveH), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnalyzerCard("Spectrum", RecView.SPECTRUM, ed, Modifier.weight(1f).fillMaxHeight()) {
                        SpectrumView(ed, Modifier.fillMaxSize())
                    }
                    AnalyzerCard("Waveform", RecView.WAVE, ed, Modifier.weight(1.4f).fillMaxHeight()) {
                        Waveform(ed, Modifier.fillMaxSize())
                    }
                    AnalyzerCard("Pan", RecView.PAN, ed, Modifier.width(108.dp).fillMaxHeight()) { PanView(ed) }
                    AnalyzerCard("Level", RecView.LEVEL, ed, Modifier.width(88.dp).fillMaxHeight()) { Meters(ed) }
                }
                Box(Modifier.onSizeChanged { bottomPx = it.height }) { RecFooter(ed) }
            }
        }
    }

    overwrite?.let { name ->
        AlertDialog(
            onDismissRequest = { overwrite = null },
            title = { Text("Replace $name?") },
            text = { Text("A file with this name is already in ${ed.whereLabel}. Recording replaces it. " +
                "Change the name or switch on \"+ date\" to keep both.") },
            confirmButton = {
                TextButton(onClick = { overwrite = null; vm.launch { ed.start(true, overwrite = true) } }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { overwrite = null }) { Text("Cancel") } },
        )
    }
}

private fun rateLabel(r: Int) = if (r % 1000 == 0) "${r / 1000} kHz" else "%.1f kHz".format(r / 1000.0)
private fun bitsLabel(b: Int) = if (b == 32) "32-bit float" else "$b-bit"
private fun chLabel(c: Int) = if (c == 1) "Mono" else "Stereo"

/** The top block: the file name and where it goes, then the format, all as short dropdowns. */
@Composable
private fun RecSettings(ed: RecorderEditor, locked: Boolean, onPickFolder: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant)
                    .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("NAME", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    BasicTextField(value = ed.prefix, onValueChange = ed::editPrefix, enabled = !locked, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary), modifier = Modifier.weight(1f))
                    Text(ed.nameTail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
                FilterChip(selected = ed.stamp, enabled = !locked, onClick = { ed.pickStamp(!ed.stamp) },
                    label = { Text("+ date") })
                var open by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { open = true }, enabled = !locked,
                        modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ed.whereLabel, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                        Text("▾")
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("Choose folder…") }, onClick = { open = false; onPickFolder() })
                        if (ed.folder != null) DropdownMenuItem(text = { Text("Default (${RecFiles.defaultLabel(ctx)})") },
                            onClick = { open = false; ed.defaultFolder() })
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pick("Input", ed.input?.label ?: "none", ed.inputs.map { it.label to it.id },
                        !locked && ed.inputs.size > 1) { ed.inputId = it }
                    IconButton(onClick = { ed.refreshInputs() }, enabled = !locked, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh inputs", Modifier.size(18.dp))
                    }
                }
                Pick("File", if (ed.flac) "FLAC" else "WAV", listOf("WAV" to false, "FLAC" to true), !locked) { ed.pickFlac(it) }
                Pick("Rate", rateLabel(ed.rate), listOf(44100, 48000).map { rateLabel(it) to it }, !locked) { ed.pickRate(it) }
                Pick("Bits", bitsLabel(ed.format.bits), (if (ed.flac) listOf(16, 24) else listOf(16, 24, 32))
                    .map { bitsLabel(it) to it }, !locked) { ed.pickBits(it) }
                Pick("Ch", chLabel(ed.channels), listOf(2, 1).map { chLabel(it) to it }, !locked) { ed.pickChannels(it) }
                // the signal path: both are applied before the meters and the file, and can be switched during a take
                Box(Modifier.height(34.dp), contentAlignment = Alignment.CenterStart) {
                    FilterChip(selected = ed.dyn.compOn, onClick = { ed.editDyn { it.copy(compOn = !it.compOn) } },
                        label = { Text("Compressor") })
                }
                Box(Modifier.height(34.dp), contentAlignment = Alignment.CenterStart) {
                    FilterChip(selected = ed.dyn.limOn, onClick = { ed.editDyn { it.copy(limOn = !it.limOn) } },
                        label = { Text("Limiter") })
                }
                Box(Modifier.height(34.dp), contentAlignment = Alignment.CenterStart) {
                    val perMin = ed.format.bytesPerSecond * 60L
                    Text(if (ed.flac) "up to ${sizeText(perMin)} / min"
                        else "${sizeText(perMin)} / min · 4 GB ≈ ${(Wav.MAX_DATA / ed.format.bytesPerSecond / 60).toInt()} min",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The knob blocks under the settings, always there: the compressor and the limiter (dimmed and deaf while switched
 * off) and the waveform's zoom and speed.
 */
@Composable
private fun DynBlocks(ed: RecorderEditor) {
    val c = ed.dyn
    fun d(id: Int, label: String, min: Int, max: Int, def: Int) = Desc(9, 9000 + id, Fmt.INT, min, max, def, label, "", emptyList())
    val knob = LocalKnob.current.compact()
    // ten knob slots in all: the compressor's six, the limiter's two and the waveform's two
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CompositionLocalProvider(LocalKnob provides knob, LocalDenseKnobs provides true) {
            DynCard("Compressor", c.compOn, Modifier.weight(6f)) {
                Knobs6(listOf(
                    DynKnob(d(1, "THR dB", -60, 0, -18), c.thr, "${c.thr}") { ed.editDyn { cfg -> cfg.copy(thr = it) } },
                    DynKnob(d(2, "RATIO", 11, 200, 30), c.ratio10, "%.1f".format(c.ratio10 / 10f)) { ed.editDyn { cfg -> cfg.copy(ratio10 = it) } },
                    DynKnob(d(3, "ATK ms", 1, 100, 10), c.atk, "${c.atk}") { ed.editDyn { cfg -> cfg.copy(atk = it) } },
                    DynKnob(d(4, "REL ms", 1, 100, 15), c.rel10, "${c.rel10 * 10}") { ed.editDyn { cfg -> cfg.copy(rel10 = it) } },
                    DynKnob(d(5, "KNEE dB", 0, 12, 6), c.knee, "${c.knee}") { ed.editDyn { cfg -> cfg.copy(knee = it) } },
                    DynKnob(d(6, "GAIN dB", 0, 24, 0), c.makeup, "${c.makeup}") { ed.editDyn { cfg -> cfg.copy(makeup = it) } },
                ))
            }
            DynCard("Limiter", c.limOn, Modifier.weight(2f)) {
                Knobs6(listOf(
                    DynKnob(d(7, "CEIL dB", -120, 0, -10), c.ceil10, "%.1f".format(c.ceil10 / 10f)) { ed.editDyn { cfg -> cfg.copy(ceil10 = it) } },
                    DynKnob(d(8, "REL ms", 1, 50, 10), c.limRel10, "${c.limRel10 * 10}") { ed.editDyn { cfg -> cfg.copy(limRel10 = it) } },
                ))
            }
            // the waveform's own knobs: always on. ZOOM is the time across it (10 ms shows the wave itself, a minute the
            // whole take); SPEED how fast it runs: 100% is live, less is slow motion behind the live edge
            DynCard("Waveform", true, Modifier.weight(2f)) {
                Knobs6(listOf(
                    DynKnob(d(9, "ZOOM", 0, WAVE_ZOOMS.lastIndex, WAVE_ZOOMS.lastIndex), ed.zoom, spanText(WAVE_ZOOMS[ed.zoom])) { ed.pickZoom(it) },
                    DynKnob(d(10, "SPEED", 5, 100, 100), ed.speed, if (ed.speed >= 100) "LIVE" else "${ed.speed}%") { ed.pickSpeed(it) },
                ))
            }
        }
    }
}

/** A block of knobs; off, it is dimmed and a cover over it swallows every touch. */
@Composable
private fun DynCard(title: String, on: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier.alpha(if (on) 1f else 0.38f)) {
        Box {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                Text(title, Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                content()
            }
            if (!on) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
        }
    }
}

private data class DynKnob(val desc: Desc, val value: Int, val text: String, val onChange: (Int) -> Unit)

/** A row of dense knobs: what each sets, its value, the text in the knob and what a change does; double tap resets. */
@Composable
private fun Knobs6(items: List<DynKnob>) {
    Row(Modifier.fillMaxWidth()) {
        items.forEach { (desc, value, text, onChange) ->
            Box(Modifier.weight(1f)) { ParamControl(desc, value, text) { onChange(it) } }
        }
    }
}

/** A short dropdown field: "Label  current ▾". */
@Composable
private fun <T> Pick(label: String, current: String, options: List<Pair<String, T>>, enabled: Boolean, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("$current ▾", maxLines = 1, style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, v) -> DropdownMenuItem(text = { Text(text) }, onClick = { onPick(v); open = false }) }
        }
    }
}

/** The seconds between the faint time lines of the waveform, by how much time it shows. */
private val WAVE_GRID = doubleArrayOf(0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0)

/** A span of the waveform, for the ZOOM knob: "10ms", "500ms", "2s". */
private fun spanText(seconds: Double) = if (seconds < 1.0) "${(seconds * 1000).roundToInt()}ms" else "${seconds.roundToInt()}s"

/**
 * One of the display blocks: its name in a small header with a power button that switches it on and off. Off, the card
 * is dimmed, shows "off" and does not compose its content at all, so it costs nothing to analyse or to draw.
 */
@Composable
private fun AnalyzerCard(title: String, view: RecView, ed: RecorderEditor, modifier: Modifier,
                         content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val on = ed.isOn(view)
    Card(modifier.alpha(if (on) 1f else 0.55f)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(20.dp).padding(start = 8.dp, end = 1.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = if (on) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.45f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(22.dp).clip(CircleShape)
                    .background(if (on) cs.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { ed.toggleView(view) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = "$title on/off",
                        Modifier.size(13.dp), tint = if (on) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }
            if (on) Box(Modifier.weight(1f)) { content() }
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("off", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.3f))
            }
        }
    }
}

/**
 * The take as a waveform in the way of a DJ app: the newest audio at the right edge, running off to the left. ZOOM sets
 * the time across the width (from a few cycles of a note, to see the wave itself, to a minute of overall audio), SPEED
 * how fast it runs (less than 100% is slow motion behind the live edge). A column is the lowest and highest sample of
 * its slice of time, so it is the real wave up close and its envelope far out. After a run it goes on running, off the
 * screen.
 */
@Composable
private fun Waveform(ed: RecorderEditor, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 9.sp, fontFamily = SloopFontFamily, color = cs.onSurfaceVariant)
    // one picture per display frame while a run is on and, after it, until the take has run off the screen
    var frame by remember { mutableLongStateOf(0L) }
    var away by remember { mutableStateOf(true) }
    LaunchedEffect(ed.active) {
        ed.resetViewClock()
        while (ed.active || !ed.waveAway()) { away = false; withFrameNanos { frame = it; ed.advanceView(it) } }
        away = true
    }
    val lo = remember { FloatArray(WAVE_MAX_COLS) }
    val hi = remember { FloatArray(WAVE_MAX_COLS) }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            frame                                                   // redraw with every display frame
            val w = size.width
            val h = size.height
            val mid = h / 2f
            val cols = (w / 2.dp.toPx()).toInt().coerceIn(1, WAVE_MAX_COLS)
            val window = ed.windowFrames
            val end = ed.viewEnd
            // column slices snap to whole buckets, so a column keeps the same span of the run while it scrolls across
            // the screen: its height is stable and only its position moves (far out; close up the slices are samples)
            var per = window / cols
            if (per >= WaveStore.BUCKET * 2.0) per = kotlin.math.ceil(per / WaveStore.BUCKET) * WaveStore.BUCKET
            val base = kotlin.math.floor((end - window) / per) * per
            val nCols = kotlin.math.ceil((end - base) / per).toInt().coerceIn(0, WAVE_MAX_COLS)
            val rate = ed.runRate.toDouble()
            val seconds = window / rate
            // faint time lines at round times of the run, with the time; they move with the audio
            val step = WAVE_GRID.firstOrNull { it >= seconds / 6 } ?: 60.0
            var k = kotlin.math.ceil(maxOf(0.0, (end - window) / rate) / step).toLong()
            while (k * step * rate <= end) {
                val x = ((k * step * rate - (end - window)) / window * w).toFloat()
                drawRect(cs.outline.copy(alpha = 0.6f), Offset(x, 0f), Size(1f, h))
                val t = k * step
                val text = if (step >= 1.0) "%d:%02d".format(t.toInt() / 60, t.toInt() % 60)
                    else if (step >= 0.01) "%.2f".format(t) else "%.3f".format(t)
                drawText(measurer.measure(text, label), topLeft = Offset(x + 3.dp.toPx(), 1.dp.toPx()))
                k++
            }
            drawRect(cs.outline, Offset(0f, mid), Size(w, 1f))
            ed.waveColumns(base, per, nCols, lo, hi)
            // linear close up (the shape matters), lifted far out so that quiet passages still show
            val g = if (seconds <= 0.5) 1f else 0.6f
            fun y(v: Float) = mid - (if (v < 0f) -((-v).pow(g)) else v.pow(g)).coerceIn(-1f, 1f) * (mid - 2f)
            for (c in 0 until nCols) {
                if (lo[c].isNaN()) continue
                val x = ((base + c * per - (end - window)) / window * w).toFloat()
                val x2 = ((base + (c + 1) * per - (end - window)) / window * w).toFloat()
                val top = y(hi[c])
                drawRect(cs.primary, Offset(x, top), Size(max(x2 - x, 1f), max(y(lo[c]) - top, 1.5f)))
            }
            // where the live edge is while a run is on (at the right edge unless the view runs behind it)
            if (ed.active) {
                val x = ((ed.liveFrames - (end - window)) / window * w).toFloat()
                if (x in 0f..w) drawRect(if (ed.recording) cs.error else cs.tertiary, Offset(minOf(x, w - 2f), 0f), Size(2f, h))
            }
        }
        if (away && !ed.active) Text("The waveform of a take or an input test appears here",
            Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

private const val WAVE_MAX_COLS = 1024

/**
 * The spectrum of what is being recorded, drawn like an EQ's analyzer: log frequency across, dBFS up, a smooth filled
 * curve with a falling peak line over a quiet grid. (A tilt of 3 dB per octave keeps ordinary music from sloping away.)
 */
@Composable
private fun SpectrumView(ed: RecorderEditor, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 8.sp, fontFamily = SloopFontFamily, color = cs.onSurfaceVariant)
    Canvas(modifier) {
        ed.specRev                                                  // redraw with every new analysis
        val x0 = 6.dp.toPx()
        val x1 = size.width - 22.dp.toPx()
        val y0 = 6.dp.toPx()
        val y1 = size.height - 14.dp.toPx()
        val w = x1 - x0
        val h = y1 - y0
        val span = ln(Spectrum.F_MAX / Spectrum.F_MIN)
        fun fx(f: Float) = x0 + w * ln(f / Spectrum.F_MIN) / span
        fun dy(db: Float) = y0 + h * (1f - ((db - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0f, 1f))
        // grid: a line every 10 dB, a faint one at the usual frequencies, the labels at the edges
        var d = DB_MIN.toInt() + 10
        while (d <= DB_MAX.toInt() - 10) {
            drawRect(cs.outline.copy(alpha = if (d % 20 == 0) 0.55f else 0.3f), Offset(x0, dy(d.toFloat())), Size(w, 1f))
            if (d % 20 == 0) {
                val t = measurer.measure("$d", label)
                drawText(t, topLeft = Offset(x1 + 3.dp.toPx(), dy(d.toFloat()) - t.size.height / 2f))
            }
            d += 10
        }
        for (f in intArrayOf(20, 30, 40, 50, 60, 80, 100, 200, 300, 400, 500, 600, 800, 1000, 2000, 3000, 4000, 5000, 6000,
            8000, 10000, 20000)) {
            val major = f == 100 || f == 1000 || f == 10000
            drawRect(cs.outline.copy(alpha = if (major) 0.6f else 0.28f), Offset(fx(f.toFloat()), y0), Size(1f, h))
        }
        for ((f, name) in listOf(50 to "50", 100 to "100", 200 to "200", 500 to "500", 1000 to "1k", 2000 to "2k",
            5000 to "5k", 10000 to "10k", 20000 to "20k")) {
            val t = measurer.measure(name, label)
            val x = (fx(f.toFloat()) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
            drawText(t, topLeft = Offset(x, y1 + 2.dp.toPx()))
        }
        // the curve: smooth through the points, filled under with a fade
        val n = Spectrum.POINTS
        fun curve(v: FloatArray, p: Path) {
            p.moveTo(fx(Spectrum.freq(0)), dy(v[0]))
            for (i in 1 until n) {
                val xb = fx(Spectrum.freq(i))
                val yb = dy(v[i])
                // a quadratic spline: each point is a control point, the curve passes the middles between them
                if (i < n - 1) p.quadraticBezierTo(xb, yb, (xb + fx(Spectrum.freq(i + 1))) / 2f, (yb + dy(v[i + 1])) / 2f)
                else p.lineTo(xb, yb)
            }
        }
        val line = Path().also { curve(ed.spec, it) }
        val fill = Path().also {
            curve(ed.spec, it)
            it.lineTo(fx(Spectrum.freq(n - 1)), y1); it.lineTo(fx(Spectrum.freq(0)), y1); it.close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.6f), cs.primary.copy(alpha = 0.04f)),
            startY = y0, endY = y1))
        drawPath(Path().also { curve(ed.specHold, it) }, cs.onSurface.copy(alpha = 0.3f), style = Stroke(1.dp.toPx()))
        drawPath(line, cs.primary, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Where the sound sits between the ears: a half-circle from L through the centre to R, a needle at the pan of the
 * louder channel's lead (their loudness ratio), a faint wedge from the centre to it and a trail of the last second.
 */
@Composable
private fun PanView(ed: RecorderEditor) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 9.sp, fontFamily = SloopFontFamily, color = cs.onSurfaceVariant)
    Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val p = ed.pan                                          // redraw as it moves
            val lvl = ed.panLevel
            val room = size.height - 14.dp.toPx()                   // below the baseline: L / C / R
            val r = minOf(size.width / 2f - 8.dp.toPx(), room * 0.95f).coerceAtLeast(10f)
            val cx = size.width / 2f
            val cy = (room + r) / 2f
            val tl = Offset(cx - r, cy - r)
            val box = Size(2 * r, 2 * r)
            fun at(pos: Float, radius: Float): Offset {
                val a = Math.toRadians(270.0 + 90.0 * pos)
                return Offset(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat())
            }
            // the wedge from the centre to the needle, then the arc and its ticks
            val a0 = 270f + 90f * p
            drawArc(cs.primary.copy(alpha = 0.12f + 0.2f * lvl), startAngle = minOf(270f, a0), sweepAngle = abs(a0 - 270f),
                useCenter = true, topLeft = tl, size = box)
            drawArc(cs.outline, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = tl, size = box,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            for (k in -4..4) {
                val pos = k / 4f
                val len = if (k % 2 == 0) 6.dp.toPx() else 3.dp.toPx()
                drawLine(cs.onSurfaceVariant.copy(alpha = 0.7f), at(pos, r - len), at(pos, r), 1.dp.toPx())
            }
            // the last second, fading
            for (age in ed.trailCount - 1 downTo 0)
                drawCircle(cs.primary.copy(alpha = 0.5f * (1f - age / 20f) * lvl), 2.dp.toPx(), at(ed.trailAt(age), r * 0.9f))
            // the needle
            val tip = at(p, r * 0.95f)
            val color = cs.primary.copy(alpha = 0.35f + 0.65f * lvl)
            drawLine(color, Offset(cx, cy), tip, 2.5.dp.toPx(), StrokeCap.Round)
            drawCircle(color, 4.dp.toPx(), tip)
            drawCircle(cs.onSurfaceVariant, 3.dp.toPx(), Offset(cx, cy))
            for ((pos, name) in listOf(-1f to "L", 0f to "C", 1f to "R")) {
                val t = measurer.measure(name, label)
                val o = at(pos, r)
                drawText(t, topLeft = Offset(if (pos == 0f) cx - t.size.width / 2f else o.x - t.size.width / 2f,
                    if (pos == 0f) cy - r - t.size.height else cy + 2.dp.toPx()))
            }
        }
        val p = ed.pan
        Text(when {
            !ed.active -> "—"
            abs(p) < 0.03f -> "C"
            p < 0 -> "L ${(-p * 100).roundToInt()}"
            else -> "R ${(p * 100).roundToInt()}"
        }, style = MaterialTheme.typography.labelMedium, fontFamily = SloopFontFamily)
    }
}

/** The level meter standing up: two bars, -60..0 dBFS, green, yellow from -12, red from -3, with a clip lamp on top. */
@Composable
private fun Meters(ed: RecorderEditor) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 8.sp, fontFamily = SloopFontFamily, color = cs.onSurfaceVariant)
    fun db(p: Float) = if (p <= 1e-6f) -120f else 20f * log10(p)
    val loud = db(max(ed.peakL, ed.peakR))
    Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (ed.clipped) "CLIP" else "clip",
            Modifier.clip(RoundedCornerShape(6.dp)).background(if (ed.clipped) cs.error else Color.Transparent)
                .clickable(enabled = ed.clipped) { ed.resetClip() }.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = if (ed.clipped) cs.onError else cs.onSurfaceVariant,
            fontWeight = if (ed.clipped) FontWeight.Bold else FontWeight.Normal)
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val h = size.height
            val labelW = 22.dp.toPx()
            val gap = 4.dp.toPx()
            val barW = (size.width - labelW - gap) / 2f
            fun y(d: Float) = h * (1f - ((d + 60f) / 60f).coerceIn(0f, 1f))
            for ((k, peak) in listOf(ed.peakL, ed.peakR).withIndex()) {
                val x0 = labelW + k * (barW + gap)
                val top = y(db(peak))
                drawRect(cs.surfaceVariant, Offset(x0, 0f), Size(barW, h))
                for ((lo, hi, color) in listOf(Triple(-60f, -12f, SloopGreen), Triple(-12f, -3f, SloopYellow),
                    Triple(-3f, 0f, cs.error))) {
                    val from = max(y(hi), top)
                    val to = y(lo)
                    if (to > from) drawRect(color, Offset(x0, from), Size(barW, to - from))
                }
            }
            for (d in listOf(0, -12, -24, -36, -48, -60)) {
                val yy = y(d.toFloat())
                drawRect(cs.outline, Offset(labelW, yy.coerceIn(0f, h - 1f)), Size(size.width - labelW, 1f))
                val t = measurer.measure("$d", label)
                drawText(t, topLeft = Offset(0f, (yy - t.size.height / 2f).coerceIn(0f, h - t.size.height)))
            }
        }
        Text(if (loud <= -60f) "-∞" else "%.1f".format(loud), style = MaterialTheme.typography.labelSmall,
            fontFamily = SloopFontFamily)
        if (ed.dyn.any) Text(if (ed.reduction > -0.1f) "GR 0" else "GR %.1f".format(ed.reduction),
            style = MaterialTheme.typography.labelSmall, fontFamily = SloopFontFamily, color = cs.tertiary)
        Row(Modifier.fillMaxWidth().padding(start = 22.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("L", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            Text("R", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Under the waveform, only what there is to say: the run, warnings, the last take with Share, and a help text. */
@Composable
private fun RecFooter(ed: RecorderEditor) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var help by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                ed.recording -> "Writing ${ed.format.let { "${it.ext.uppercase()} · ${it.rate} Hz · ${bitsLabel(it.bits)} · ${chLabel(it.channels)}" }}" +
                    (if (ed.dyn.compOn) " · compressor" else "") + (if (ed.dyn.limOn) " · limiter" else "") +
                    " to ${ed.whereLabel}"
                ed.monitoring -> "Input test: levels only, nothing is written"
                else -> ""
            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("?", Modifier.clip(CircleShape).clickable { help = !help }.padding(horizontal = 10.dp, vertical = 2.dp),
                color = cs.primary, fontWeight = FontWeight.Bold)
        }
        val cur = ed.input
        if (cur == null) Foot("The phone sees no USB audio input. Connect the FM-1 with a USB-C cable; " +
            "the phone must support USB audio (most do with USB host / OTG).", cs.tertiary)
        else if (cur.rates.isNotEmpty() && ed.rate !in cur.rates)
            Foot("The input does not report ${rateLabel(ed.rate)}: Android will resample. Pick a rate it reports " +
                "(${cur.rates.joinToString(" / ") { rateLabel(it) }}) for a bit-exact take.", cs.tertiary)
        ed.warning?.let { Foot(it, cs.tertiary) }
        ed.last?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last take: ${r.name} · ${hms(r.seconds)} · ${sizeText(r.bytes)} · ${r.where}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType(r.mime)
                        .putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { ctx.startActivity(Intent.createChooser(send, r.name)) }
                        .onFailure { ed.say("No app to share with", true) }
                }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Share, "Share the last take", Modifier.size(18.dp)) }
            }
            if (!r.headerOk) Foot("This folder could not rewrite the file's header: the length is marked unknown. " +
                "Most players and DAWs open it anyway.", cs.tertiary)
        }
        if (help) Foot("The FM-1 sends its master out over the USB cable as a sound card input, the same stream a computer " +
            "records in a DAW: 16 bit stereo at 44.1 kHz, or 48 kHz when its AUDIO > USB RATE offers it (it resamples " +
            "itself). 24 bit and 32 bit float hold the same samples with headroom for editing. FLAC is lossless and " +
            "has no 4 GB limit. A take goes on while you use the other pages; closing the app stops it and keeps the " +
            "file. Keep Sloop Go in front: Android silences audio inputs for apps in the background.", cs.onSurfaceVariant)
    }
}

@Composable
private fun Foot(text: String, color: Color) = Text(text, style = MaterialTheme.typography.bodySmall, color = color)
