// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.sloop.go.R
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import com.sloop.go.device.PNote
import com.sloop.go.device.decodeNotes
import com.sloop.go.device.noteKey
import com.sloop.go.device.resizeDeltaBounds
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.sloop.go.device.SequencerMode
import com.sloop.go.proto.DrumStep
import com.sloop.go.proto.Step
import com.sloop.go.proto.StepTime
import com.sloop.go.store.ClipKind
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val DRUM_LANES = listOf(
    "KICK", "KICK 2", "SNARE", "CLAP", "HAT", "OPEN HAT", "PEDAL", "RIM",
    "SNARE 2", "LOW TOM", "HI TOM", "CRASH", "RIDE", "SHAKER", "CONGA", "COWBELL",
)

private val BLACK_KEYS = setOf(1, 3, 6, 8, 10)
private const val TOP_NOTE = 127

private fun noteLength(steps: List<Step>, index: Int, length: Int): Int {
    var end = index
    while (end + 1 < length && steps.getOrNull(end + 1)?.time == StepTime.TIE) end++
    return end - index + 1
}

// ---------------------------------------------------------------- screen ---

@Composable
fun SequencerScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit, nav: @Composable () -> Unit) {
    if (state.link != Link.READY || state.info == null) {
        NotReady("Sequencer", state, onDevice)
        return
    }
    var askClear by remember { mutableStateOf(false) }
    var fitTick by remember { mutableIntStateOf(0) }
    var options by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var sel by remember(state.selectedTrack) { mutableIntStateOf(0) }
    val selected = sel.coerceIn(0, state.patternLength - 1)
    var selectMode by remember(state.selectedTrack) { mutableStateOf(false) }
    var noteSel by remember(state.selectedTrack) { mutableStateOf<Set<Int>>(emptySet()) }
    var browser by remember { mutableStateOf<BrowserMode?>(null) }
    var carry by remember { mutableStateOf<Carry?>(null) }
    val kind = if (state.drumGrid) ClipKind.DRUM else ClipKind.PIANO

    // Refit once the loaded steps of another track actually arrive: a track switch replaces the
    // steps list (user edits keep the same track), and a loaded clip refits right away.
    var fitTrack by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.steps, state.drumSteps) {
        if (state.selectedTrack != fitTrack) { fitTrack = state.selectedTrack; fitTick++ }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        ControlPanel(vm, state, nav, onPick = { picker = true }, onClear = { askClear = true }, onFit = { fitTick++ },
            onSave = { browser = BrowserMode.SAVE }, onLoad = { browser = BrowserMode.LOAD },
            onOptions = if (state.drumGrid) null else { { options = true } }, optionsLabel = "Step ${selected + 1}",
            selectMode = selectMode, onSelectMode = { selectMode = it }, selCount = noteSel.size,
            onDeleteSel = {
                if (state.drumGrid) vm.controller.deleteDrumCells(noteSel) else vm.controller.deleteNotes(noteSel)
                noteSel = emptySet()
            })
        if (state.drumGrid) DrumGrid(vm, state, fitTick, selectMode, noteSel, { noteSel = it }, Modifier.weight(1f).fillMaxWidth())
        else PianoRoll(vm, state, fitTick, selected, { sel = it }, selectMode, noteSel, { noteSel = it },
            carry, { carry = null; browser = null }, Modifier.weight(1f).fillMaxWidth())
    }
    browser?.let { mode ->
        ClipBrowserOverlay(vm, kind, mode, carrying = carry != null,
            onDismiss = { browser = null; carry = null },
            onLoad = { vm.controller.applyClip(it); fitTick++ },
            carry = if (kind == ClipKind.PIANO) ClipDrag(
                start = { clip, pos -> carry = Carry(clip).also { it.pos = pos } },
                move = { carry?.pos = it },
                end = { carry?.released = true },
                cancel = { carry = null; browser = null }) else null)
    }
    }

    if (picker) PresetPicker(vm, state) { picker = false }
    if (options && !state.drumGrid) AlertDialog(
        onDismissRequest = { options = false },
        confirmButton = { TextButton(onClick = { options = false }) { Text("Close") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StepEditor(vm, state, state.steps.getOrNull(selected), selected, state.steps, state.patternLength)
            }
        },
    )
    if (askClear) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { Text("Clear pattern?") },
        text = { Text("Remove all notes in the active ${state.patternLength} steps? " +
            if (state.sequencerMode == SequencerMode.LATE) "Changes stay on the phone until SEND."
            else "Changed steps will be queued for the device.") },
        confirmButton = { TextButton(onClick = { vm.controller.clearPattern(); askClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ControlPanel(
    vm: SloopViewModel, state: DeviceState, nav: @Composable () -> Unit, onPick: () -> Unit,
    onSave: () -> Unit, onLoad: () -> Unit,
    onClear: () -> Unit, onFit: () -> Unit, onOptions: (() -> Unit)?, optionsLabel: String,
    selectMode: Boolean, onSelectMode: (Boolean) -> Unit, selCount: Int, onDeleteSel: () -> Unit,
) {
    var confirmSwitch by remember { mutableStateOf(false) }
    val tight = PaddingValues(horizontal = 8.dp)
    val late = state.sequencerMode == SequencerMode.LATE
    val sep = @Composable { VerticalDivider(Modifier.padding(horizontal = 4.dp).height(24.dp)) }
    val btn = Modifier.height(32.dp)
    val mini = @Composable { icon: ImageVector, label: String, click: () -> Unit ->
        IconButton(onClick = click, modifier = Modifier.size(32.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
    }
    // One fixed row of function blocks: track | sound | mode | edit | key | pattern files | step. It never
    // scrolls: SpaceEvenly spreads the controls across the whole width, and conditional controls keep their
    // slots so appearing ones (SEND, the selection trash) don't move the neighbours.
    // The status is pinned at the right edge outside the row; its measured width is reserved in 40dp steps,
    // so the controls get all the space it doesn't need while small text changes don't move them.
    var statusPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bucket = with(density) { 40.dp.toPx() }
    val statusW = (((statusPx + bucket - 1) / bucket).toInt() * 40).dp
    BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
    Row(Modifier.fillMaxWidth().clipToBounds().padding(start = 4.dp, end = statusW + 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        nav()
        TrackSelect(vm, state)
        Text(state.soundLabel.substringAfter("· ") + " ▾",
            Modifier.widthIn(max = 120.dp).clickable(onClick = onPick).padding(horizontal = 6.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!state.drumGrid) VoiceSelect(vm, state)
        sep()
        FilterChip(selected = !late,
            onClick = { if (!vm.controller.setSequencerMode(SequencerMode.NOW)) confirmSwitch = true },
            label = { Text("Live") })
        Spacer(Modifier.width(4.dp))
        FilterChip(selected = late, onClick = { vm.controller.setSequencerMode(SequencerMode.LATE) },
            label = { Text("Store") })
        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
            if (late) Button(onClick = { vm.controller.sendCurrentPattern() }, modifier = Modifier.height(30.dp),
                contentPadding = tight) { Text("SEND") }
        }
        sep()
        mini(Icons.Filled.DeleteSweep, "Clear pattern", onClear)
        mini(Icons.Filled.FitScreen, "Fit to screen", onFit)
        sep()
        if (!state.drumGrid) {
            TextButton(onClick = { vm.controller.transposeNotes(-12); onFit() }, modifier = btn, contentPadding = tight) { Text("-12") }
            TextButton(onClick = { vm.controller.transposeNotes(12); onFit() }, modifier = btn, contentPadding = tight) { Text("+12") }
        }
        FilledTonalIconToggleButton(checked = selectMode, onCheckedChange = onSelectMode, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.HighlightAlt, contentDescription = "Select mode", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDeleteSel, enabled = selCount > 0, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete selected", modifier = Modifier.size(20.dp))
        }
        sep()
        mini(Icons.Filled.Save, "Save pattern", onSave)
        mini(Icons.Filled.FolderOpen, "Load pattern", onLoad)
        if (onOptions != null) {
            sep()
            mini(Icons.Filled.Tune, optionsLabel, onOptions)
        }
    }
    val parts = buildList {
        add("LEN ${state.patternLength}")
        if (late && state.draftTracks.isNotEmpty()) add("drafts ${state.draftTracks.sorted().joinToString(",") { "${it + 1}" }}")
        add("queue ${state.queuedEdits}")
    }
    Text(state.queueError ?: parts.joinToString(" · "),
        Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth)
            .background(MaterialTheme.colorScheme.surface).padding(start = 8.dp, end = 16.dp)
            .onSizeChanged { statusPx = it.width },
        color = if (state.queueError != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
    }
    if (confirmSwitch) AlertDialog(
        onDismissRequest = { confirmSwitch = false },
        title = { Text("Unsent Store drafts") },
        text = { Text("Send all local patterns before switching to Live, or discard them and reload from FM-1?") },
        confirmButton = {
            Column {
                TextButton(onClick = {
                    if (vm.controller.sendDraftsAndSwitch()) confirmSwitch = false
                }) { Text("Send all") }
                TextButton(onClick = {
                    vm.controller.discardDraftsAndSwitch()
                    confirmSwitch = false
                }) { Text("Discard") }
            }
        },
        dismissButton = { TextButton(onClick = { confirmSwitch = false }) { Text("Cancel") } },
    )
}

@Composable
fun PresetPicker(vm: SloopViewModel, state: DeviceState, onDismiss: () -> Unit) {
    val info = state.info ?: return
    val dump = state.dump ?: return
    val drum = state.isDrum
    val fm6 = !drum && dump.engine == state.fm6Engine    // FM6 has no presets: PTCH picks F1..F8 / B1..B27
    val engIndex = state.gdesc.indexOfFirst { it?.label == "ENG" }
    val engines = if (drum) emptyList() else state.gdesc.getOrNull(engIndex)?.names.orEmpty()
    val names = when {
        drum -> state.pdesc.getOrNull(info.pe0)?.names.orEmpty()
        fm6 -> state.pdesc.getOrNull(info.pe0 + 7)?.names.orEmpty()
        else -> state.presetNames[dump.engine].orEmpty()
    }
    val current = if (drum) state.paramValue(info.pe0)
        else if (fm6) state.paramValue(info.pe0 + 7) else dump.preset
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track ${state.selectedTrack + 1} · " +
            if (drum) "Kit" else if (fm6) "Patch" else "Preset") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column {
                if (engines.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    engines.forEachIndexed { i, name ->
                        FilterChip(selected = i == dump.engine, onClick = { vm.controller.setParam(1, engIndex, i) },
                            label = { Text(name) })
                    }
                }
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (names.isEmpty()) Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    names.forEachIndexed { i, name ->
                        Text(if (fm6) name else "${i + 1}  $name",
                            Modifier.fillMaxWidth().clickable {
                            if (drum) vm.controller.setParam(0, info.pe0, i)
                            else if (fm6) vm.launch { vm.controller.fm6Assign(state.selectedTrack, i) }
                            else vm.controller.selectPreset(dump.engine, i)
                            onDismiss()
                        }.padding(horizontal = 8.dp, vertical = 11.dp),
                            color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
    )
}

@Composable
private fun TrackSelect(vm: SloopViewModel, state: DeviceState) {
    val info = state.info ?: return
    var open by remember { mutableStateOf(false) }
    Box {
        Text("T${state.selectedTrack + 1} ▾",
            Modifier.clickable { open = true }.padding(horizontal = 8.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val ntrk = state.tracks?.ntrk ?: info.ntrk
            for (i in 0 until ntrk) {
                val label = state.tracks?.tracks?.getOrNull(i)?.let { e ->
                    "TRACK ${i + 1} · " +
                        (if (e.engine >= info.nengines) "DRUM" else info.engines.getOrNull(e.engine) ?: "?")
                } ?: "TRACK ${i + 1}"
                DropdownMenuItem(text = {
                    Text(label, fontWeight = if (i == state.selectedTrack) FontWeight.Bold else FontWeight.Normal)
                }, onClick = { open = false; vm.controller.selectTrack(i) })
            }
        }
    }
}

@Composable
private fun VoiceSelect(vm: SloopViewModel, state: DeviceState) {
    val idx = state.pdesc.indexOfFirst { it?.label == "VCE" }
    if (idx < 0) return
    val names = state.pdesc[idx]?.names.takeIf { !it.isNullOrEmpty() }
        ?: listOf("POLY", "MONO", "LEG", "UNI")
    val cur = state.paramValue(idx).coerceIn(0, names.size - 1)
    var open by remember { mutableStateOf(false) }
    Box {
        Text(names[cur] + " ▾", Modifier.clickable { open = true }.padding(horizontal = 8.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            names.forEachIndexed { i, name ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    vm.controller.setParam(0, idx, i)
                    open = false
                })
            }
        }
    }
}

// ---------------------------------------------------------------- viewport & gestures ---

/** Scroll/zoom state of one grid block. Content coordinates start at (lw, hdr) inside the canvas. */
private class Viewport {
    var sx by mutableFloatStateOf(0f)
    var sy by mutableFloatStateOf(0f)
    var cw by mutableFloatStateOf(40f)
    var rh by mutableFloatStateOf(40f)
    var size by mutableStateOf(IntSize.Zero)
    var d = 1f
    var lw = 0f
    var hdr = 0f
    var cols = 1
    var rows = 1
    /** Select mode: one finger draws a frame / drags notes, so two fingers scroll too and edges scroll vertically. */
    var selectMode = false

    val w get() = size.width.toFloat()
    val h get() = size.height.toFloat()

    fun clamp() {
        sx = sx.coerceIn(0f, max(0f, cols * cw - (w - lw)))
        sy = sy.coerceIn(0f, max(0f, rows * rh - (h - hdr)))
    }

    fun scrollBy(dx: Float, dy: Float) { sx += dx; sy += dy; clamp() }

    fun zoom(zx: Float, zy: Float, c: Offset) {
        val ox = (sx + c.x - lw) / cw
        val oy = (sy + c.y - hdr) / rh
        cw = (cw * zx).coerceIn(14f * d, 120f * d)
        rh = (rh * zy).coerceIn(22f * d, 44f * d)
        sx = ox * cw - (c.x - lw)
        sy = oy * rh - (c.y - hdr)
        // magnetic fit: within ~24dp of the grid area the pattern snaps to fill it exactly
        val fitCw = (w - lw) / cols
        val fitRh = (h - hdr) / rows
        if (kotlin.math.abs(cw - fitCw) * cols < 24f * d) { cw = fitCw; sx = 0f }
        if (kotlin.math.abs(rh - fitRh) * rows < 24f * d) { rh = fitRh; sy = 0f }
        clamp()
    }

    fun nudge(p: Offset) {
        val edge = 36f * d
        val step = 10f * d
        if (p.x > w - edge) scrollBy(step, 0f) else if (p.x < lw + edge) scrollBy(-step, 0f)
        if (selectMode) {
            if (p.y > h - edge) scrollBy(0f, step) else if (p.y < hdr + edge) scrollBy(0f, -step)
        }
    }

    fun stepAt(x: Float) = floor((x - lw + sx) / cw).toInt()
    fun rowAt(y: Float) = floor((y - hdr + sy) / rh).toInt()
}

private interface GridHandler {
    fun tap(p: Offset) {}
    fun grab(p: Offset): Boolean = false
    fun beginMove(p: Offset): Boolean = false
    fun longPress(p: Offset): Boolean = false
    fun drag(p: Offset) {}
    fun release(p: Offset) {}
    fun cancel() {}
}

private suspend fun PointerInputScope.gridGestures(vp: Viewport, handler: () -> GridHandler) {
    val slop = viewConfiguration.touchSlop
    val longMs = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val h = handler()
        val down = awaitFirstDown(requireUnconsumed = false)
        var claimed = h.grab(down.position)
        if (claimed) down.consume()
        var longDone = claimed
        var moved = false
        var multi = false
        var zoomAxis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical; locked until all fingers lift
        var spanX0 = -1f
        var spanY0 = -1f
        var last = down.position
        val t0 = System.currentTimeMillis()
        while (true) {
            val event = if (!longDone && !moved && !multi) {
                val left = (longMs - (System.currentTimeMillis() - t0)).coerceAtLeast(1L)
                withTimeoutOrNull(left) { awaitPointerEvent() }
            } else awaitPointerEvent()
            if (event == null) {
                longDone = true
                claimed = h.longPress(down.position)
                continue
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                if (claimed) { h.cancel(); claimed = false }
                multi = true
                val a = pressed[0]; val b = pressed[1]
                val spanX = kotlin.math.abs(a.position.x - b.position.x)
                val spanY = kotlin.math.abs(a.position.y - b.position.y)
                if (spanX0 < 0f) { spanX0 = spanX; spanY0 = spanY }
                val zx = run {
                    val old = kotlin.math.abs(a.previousPosition.x - b.previousPosition.x)
                    if (old > 8f) (spanX / old).coerceIn(0.5f, 2f) else 1f
                }
                val zy = run {
                    val old = kotlin.math.abs(a.previousPosition.y - b.previousPosition.y)
                    if (old > 8f) (spanY / old).coerceIn(0.5f, 2f) else 1f
                }
                if (zoomAxis == 0) {
                    // pick whichever axis moved more once the pinch is past the noise floor;
                    // it stays locked until every finger lifts
                    val dx = kotlin.math.abs(spanX - spanX0)
                    val dy = kotlin.math.abs(spanY - spanY0)
                    if (max(dx, dy) > 16f * vp.d) zoomAxis = if (dx > dy) 1 else 2
                }
                val centre = event.calculateCentroid(useCurrent = true)
                if (vp.selectMode) {
                    val before = event.calculateCentroid(useCurrent = false)
                    vp.scrollBy(before.x - centre.x, before.y - centre.y)
                }
                vp.zoom(if (zoomAxis == 2) 1f else zx,
                    if (zoomAxis == 1) 1f else 1f + (zy - 1f) * 0.45f,
                    centre)
                event.changes.forEach { it.consume() }
            } else if (pressed.size == 1) {
                val c = pressed[0]
                if (multi) c.consume()
                else if (claimed) { h.drag(c.position); vp.nudge(c.position); c.consume() }
                else if (!moved && (c.position - down.position).getDistance() > slop) {
                    moved = true
                    if (h.beginMove(down.position)) { claimed = true; h.drag(c.position); vp.nudge(c.position) }
                    else vp.scrollBy(down.position.x - c.position.x, down.position.y - c.position.y)
                    c.consume()
                } else if (moved) {
                    vp.scrollBy(last.x - c.position.x, last.y - c.position.y)
                    c.consume()
                }
                last = c.position
            } else {
                val p = event.changes.first().position
                if (claimed) h.release(p)
                else if (!moved && !multi && !longDone) h.tap(down.position)
                event.changes.forEach { it.consume() }
                break
            }
        }
    }
}

private class Painter(val paint: Paint) {
    fun DrawScope.text(s: String, x: Float, y: Float, color: Color, size: Float, align: Paint.Align) {
        paint.color = color.toArgb()
        paint.textSize = size
        paint.textAlign = align
        drawContext.canvas.nativeCanvas.drawText(s, x, y + size * 0.35f, paint)
    }
}

@Composable
private fun rememberPainter(): Painter {
    val context = LocalContext.current
    return remember {
        Painter(Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        })
    }
}

// ---------------------------------------------------------------- drum grid ---

@Composable
private fun DrumGrid(
    vm: SloopViewModel, state: DeviceState, fitTick: Int, selectMode: Boolean, cellSel: Set<Int>,
    onCellSel: (Set<Int>) -> Unit, modifier: Modifier,
) {
    val length = state.patternLength
    val steps = state.drumSteps
    val d = LocalDensity.current.density
    val vp = remember(state.selectedTrack) { Viewport() }
    vp.d = d; vp.lw = 64f * d; vp.hdr = 26f * d; vp.cols = length; vp.rows = DRUM_LANES.size; vp.selectMode = selectMode
    val painter = rememberPainter()
    val cs = MaterialTheme.colorScheme
    var gdrag by remember(state.selectedTrack, selectMode) { mutableStateOf<GroupDrag?>(null) }
    var marquee by remember(state.selectedTrack, selectMode) { mutableStateOf<Marquee?>(null) }
    val onSelNow by rememberUpdatedState(onCellSel)
    val live = remember { object { var v: Set<Int> = cellSel } }   // the selection as the gesture sees it
    live.v = cellSel
    fun setSel(s: Set<Int>) { live.v = s; onSelNow(s) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(state.selectedTrack, length, vp.size.width > 0, fitTick) {
        if (vp.size.width == 0) return@LaunchedEffect
        vp.cw = ((vp.w - vp.lw) / length).coerceIn(4f * d, 64f * d)
        vp.rh = ((vp.h - vp.hdr) / DRUM_LANES.size).coerceIn(8f * d, 56f * d)
        vp.sx = 0f; vp.sy = 0f; vp.clamp()
    }

    val handler = remember(state.selectedTrack, length, selectMode) {
        object : GridHandler {
            var paintOn: Boolean? = null
            fun picked() = selCells(vm.controller.state.value.drumSteps, live.v, length)
            fun gridPoint(p: Offset) = Offset((p.x - vp.lw + vp.sx) / vp.cw, (p.y - vp.hdr + vp.sy) / vp.rh)
            var lastCell = -1 to -1
            fun cell(p: Offset): Pair<Int, Int>? {
                if (p.x < vp.lw || p.y < vp.hdr) return null
                val step = vp.stepAt(p.x)
                val lane = vp.rowAt(p.y)
                return if (step in 0 until length && lane in DRUM_LANES.indices) step to lane else null
            }
            fun isOn(step: Int, lane: Int) =
                ((vm.controller.state.value.drumSteps.getOrNull(step)?.on ?: 0) shr lane) and 1 == 1
            override fun tap(p: Offset) {
                val c = cell(p)
                if (!selectMode) { c?.let { (s, l) -> toggleDrum(vm, s, l) }; return }
                if (c == null || !isOn(c.first, c.second)) { if (live.v.isNotEmpty()) setSel(emptySet()); return }
                val k = c.first * 16 + c.second           // tap a hit: toggle it in the selection; empty: deselect all
                setSel(if (k in live.v) live.v - k else live.v + k)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            override fun beginMove(p: Offset): Boolean {
                if (!selectMode) return false
                val c = cell(p)
                if (c != null && isOn(c.first, c.second)) {   // drag a hit: carry the selection (a loose hit becomes it)
                    val k = c.first * 16 + c.second
                    if (k !in live.v) setSel(setOf(k))
                    gdrag = GroupDrag(c.first, c.second, false)
                } else if (p.x >= vp.lw && p.y >= vp.hdr) {   // drag on empty grid: select frame
                    val g = gridPoint(p)
                    marquee = Marquee(g.x, g.y, g.x, g.y)
                } else return false
                return true
            }
            override fun longPress(p: Offset): Boolean {
                if (selectMode) {                              // hold a hit: take a copy of the selection and carry it
                    val c = cell(p)?.takeIf { isOn(it.first, it.second) } ?: return false
                    val k = c.first * 16 + c.second
                    if (k !in live.v) setSel(setOf(k))
                    gdrag = GroupDrag(c.first, c.second, true)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    return true
                }
                val (s, l) = cell(p) ?: return false
                paintOn = !isOn(s, l)
                lastCell = s to l
                toggleDrum(vm, s, l)
                return true
            }
            override fun drag(p: Offset) {
                if (selectMode) {
                    gdrag?.let { g -> gdrag = GroupDrag(g.grabStep, g.grabRow, g.copy,
                        vp.stepAt(p.x) - g.grabStep, vp.rowAt(p.y) - g.grabRow) }
                    marquee?.let { m -> val g = gridPoint(p); marquee = Marquee(m.x0, m.y0, g.x, g.y) }
                    return
                }
                val c = cell(p) ?: return
                if (c == lastCell) return
                lastCell = c
                if (isOn(c.first, c.second) != paintOn) toggleDrum(vm, c.first, c.second)
            }
            override fun release(p: Offset) {
                if (selectMode) {
                    drag(p)
                    gdrag?.let { g ->
                        val sel = picked()
                        val (ds, dl) = drumShift(sel, length, g.dStep, g.dPitch)
                        if (g.copy || ds != 0 || dl != 0) {
                            vm.controller.placeDrumCells(live.v, g.dStep, g.dPitch, g.copy)
                            if (ds != 0 || dl != 0) setSel(sel.map { (s, l) -> (s + ds) * 16 + l + dl }.toSet())
                        }
                    }
                    marquee?.let { m ->
                        val on = vm.controller.state.value.drumSteps
                        setSel((0 until length).flatMap { s -> (0 until 16).filter { l ->
                            ((on.getOrNull(s)?.on ?: 0) shr l) and 1 == 1 && m.hitsCell(s, l) }.map { s * 16 + it } }.toSet())
                    }
                    gdrag = null; marquee = null
                    return
                }
                paintOn = null
            }
            override fun cancel() { paintOn = null; gdrag = null; marquee = null }
        }
    }

    Canvas(modifier.clipToBounds().background(cs.background)
        .onSizeChanged { vp.size = it; vp.clamp() }
        .pointerInput(handler) { gridGestures(vp) { handler } }) {
        val cw = vp.cw; val rh = vp.rh; val sx = vp.sx; val sy = vp.sy; val lw = vp.lw; val hdr = vp.hdr
        val c0 = (sx / cw).toInt().coerceIn(0, length - 1)
        val c1 = ((sx + size.width - lw) / cw).toInt().coerceIn(0, length - 1)
        val r0 = (sy / rh).toInt().coerceIn(0, DRUM_LANES.size - 1)
        val r1 = ((sy + size.height - hdr) / rh).toInt().coerceIn(0, DRUM_LANES.size - 1)
        val pad = 2f * d
        clipRect(lw, hdr) {
            val picked = if (selectMode) selCells(steps, cellSel, length) else emptyList()
            val lifted = gdrag?.takeIf { !it.copy }
            for (r in r0..r1) for (c in c0..c1) {
                val on = ((steps.getOrNull(c)?.on ?: 0) shr r) and 1 == 1
                val isSel = on && selectMode && ((c to r) in picked || marquee?.hitsCell(c, r) == true)
                val color = when {
                    isSel -> if (lifted != null && (c to r) in picked) cs.tertiary.copy(alpha = 0.3f) else cs.tertiary
                    on -> cs.primary
                    (c / 4) % 2 == 0 -> cs.surfaceVariant
                    else -> cs.surface
                }
                drawRoundRect(color, Offset(lw + c * cw - sx + pad, hdr + r * rh - sy + pad),
                    Size(cw - 2 * pad, rh - 2 * pad), CornerRadius(5f * d))
                if (isSel) drawRoundRect(cs.onTertiary.copy(alpha = 0.9f), Offset(lw + c * cw - sx + pad, hdr + r * rh - sy + pad),
                    Size(cw - 2 * pad, rh - 2 * pad), CornerRadius(5f * d), style = Stroke(1.5f * d))
            }
            gdrag?.let { g ->                            // the carried group (or its clone) where it would land
                val (ds, dl) = drumShift(picked, length, g.dStep, g.dPitch)
                for ((s, l) in picked) {
                    val x = lw + (s + ds) * cw - sx + pad
                    val y = hdr + (l + dl) * rh - sy + pad
                    drawRoundRect(cs.tertiary.copy(alpha = 0.75f), Offset(x, y), Size(cw - 2 * pad, rh - 2 * pad), CornerRadius(5f * d))
                    drawRoundRect(cs.onTertiary, Offset(x, y), Size(cw - 2 * pad, rh - 2 * pad), CornerRadius(5f * d),
                        style = Stroke(1.5f * d))
                }
            }
            marquee?.let { m ->
                val left = lw + min(m.x0, m.x1) * cw - sx
                val top = hdr + min(m.y0, m.y1) * rh - sy
                val sz = Size(kotlin.math.abs(m.x1 - m.x0) * cw, kotlin.math.abs(m.y1 - m.y0) * rh)
                drawRect(cs.tertiary.copy(alpha = 0.15f), Offset(left, top), sz)
                drawRect(cs.tertiary, Offset(left, top), sz, style = Stroke(1.5f * d))
            }
        }
        clipRect(lw, 0f, size.width, hdr) {
            drawRect(cs.surface, Offset(lw, 0f), Size(size.width - lw, hdr))
            with(painter) {
                for (c in c0..c1) text("${c + 1}", lw + c * cw - sx + cw / 2, hdr / 2,
                    if (c % 4 == 0) cs.primary else cs.onSurfaceVariant, 11f * d, Paint.Align.CENTER)
            }
        }
        clipRect(0f, hdr, lw, size.height) {
            drawRect(cs.surface, Offset(0f, hdr), Size(lw, size.height - hdr))
            with(painter) {
                for (r in r0..r1) text(DRUM_LANES[r], 6f * d, hdr + r * rh - sy + rh / 2,
                    cs.onSurface, 10f * d, Paint.Align.LEFT)
            }
        }
        drawRect(cs.surface, Offset.Zero, Size(lw, hdr))
    }
}

private fun toggleDrum(vm: SloopViewModel, step: Int, lane: Int) {
    val cur = vm.controller.state.value.drumSteps.getOrNull(step) ?: DrumStep(step, 0, IntArray(16), IntArray(16))
    val newOn = cur.on xor (1 shl lane)
    val lvl = cur.lvl.copyOf()
    val rat = cur.rat.copyOf()
    lvl[lane] = 0
    rat[lane] = 0
    vm.controller.setDrumStep(step, cur.copy(on = newOn, lvl = lvl, rat = rat))
}

// ---------------------------------------------------------------- piano roll ---

private class NoteDrag(
    val move: Boolean, val start: Int, val note: Int, val len: Int,
    val grabStep: Int, val toStep: Int, val toNote: Int, val end: Int, val edge: Int,
)

private class NoteHit(val start: Int, val note: Int, val len: Int, val edge: Int)

/** The selected notes being carried (or, with [copy], a clone of them), as grid cells from the grab point. */
private class GroupDrag(val grabStep: Int, val grabRow: Int, val copy: Boolean, val dStep: Int = 0, val dPitch: Int = 0)

/** The selected notes being stretched together through one grabbed [edge] (1 = right, -1 = left). */
private class GroupResize(val edge: Int, val grabStep: Int, val delta: Int = 0)

/** The select frame, in grid units (steps across, rows down) so it stays put while the grid scrolls. */
private class Marquee(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    fun hits(n: PNote): Boolean {
        val r = TOP_NOTE - n.pitch
        return n.start < max(x0, x1) && n.end + 1 > min(x0, x1) && r < max(y0, y1) && r + 1 > min(y0, y1)
    }

    fun hitsCell(step: Int, lane: Int) = step < max(x0, x1) && step + 1 > min(x0, x1) && lane < max(y0, y1) && lane + 1 > min(y0, y1)
}

/** The selected drum hits (step to lane) among [keys] (step * 16 + lane). */
private fun selCells(steps: List<DrumStep>, keys: Set<Int>, length: Int): List<Pair<Int, Int>> =
    keys.map { it / 16 to it % 16 }.filter { (s, l) -> s < length && ((steps.getOrNull(s)?.on ?: 0) shr l) and 1 == 1 }

/** The drum group's shift kept inside the pattern and the 16 lanes (what the controller applies too). */
private fun drumShift(cells: List<Pair<Int, Int>>, length: Int, dStep: Int, dLane: Int): Pair<Int, Int> =
    if (cells.isEmpty()) 0 to 0 else
        dStep.coerceIn(-cells.minOf { it.first }, length - 1 - cells.maxOf { it.first }) to
            dLane.coerceIn(-cells.minOf { it.second }, 15 - cells.maxOf { it.second })

/** The group's shift kept inside the pattern and the note range (what the controller applies too). */
private fun clampShift(sel: List<PNote>, length: Int, dStep: Int, dPitch: Int): Pair<Int, Int> =
    if (sel.isEmpty()) 0 to 0 else
        dStep.coerceIn(-sel.minOf { it.start }, length - 1 - sel.maxOf { it.end }) to
            dPitch.coerceIn(-sel.minOf { it.pitch }, TOP_NOTE - sel.maxOf { it.pitch })

@Composable
private fun PianoRoll(
    vm: SloopViewModel, state: DeviceState, fitTick: Int, selected: Int,
    onSelect: (Int) -> Unit, selectMode: Boolean, noteSel: Set<Int>, onNoteSel: (Set<Int>) -> Unit,
    carry: Carry?, onCarryDone: () -> Unit, modifier: Modifier,
) {
    val length = state.patternLength
    val steps = state.steps
    val rows = TOP_NOTE + 1
    val d = LocalDensity.current.density
    val vp = remember(state.selectedTrack) { Viewport() }
    vp.d = d; vp.lw = 44f * d; vp.hdr = 26f * d; vp.cols = length; vp.rows = rows
    val painter = rememberPainter()
    val cs = MaterialTheme.colorScheme
    var ndrag by remember(state.selectedTrack) { mutableStateOf<NoteDrag?>(null) }
    var lastLen by remember(state.selectedTrack) { mutableIntStateOf(1) }
    var gdrag by remember(state.selectedTrack, selectMode) { mutableStateOf<GroupDrag?>(null) }
    var gresize by remember(state.selectedTrack, selectMode) { mutableStateOf<GroupResize?>(null) }
    var marquee by remember(state.selectedTrack, selectMode) { mutableStateOf<Marquee?>(null) }
    val onSelNow by rememberUpdatedState(onNoteSel)
    val live = remember { object { var v: Set<Int> = noteSel } }   // the selection as the gesture sees it, ahead of recomposition
    live.v = noteSel
    fun setSel(s: Set<Int>) { live.v = s; onSelNow(s) }
    val haptic = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    vp.selectMode = selectMode || noteSel.isNotEmpty() || marquee != null || gdrag != null || gresize != null

    /** The cell shift (steps, semitones) that puts the carried pattern's centre under the finger; null off the roll. */
    fun carryShift(c: Carry): Pair<Int, Int>? {
        val p = c.pos - origin
        if (p.x < vp.lw || p.y < vp.hdr || p.x > vp.size.width || p.y > vp.size.height) return null
        val src = c.clip.notes.filter { it.start < length }
        if (src.isEmpty()) return null
        val cs0 = (src.minOf { it.start } + src.maxOf { minOf(it.start + it.len, length) - 1 }) / 2
        val cp = (src.minOf { it.pitch } + src.maxOf { it.pitch }) / 2
        return (vp.stepAt(p.x) - cs0) to ((TOP_NOTE - vp.rowAt(p.y)) - cp)
    }
    LaunchedEffect(carry?.released) {
        val c = carry ?: return@LaunchedEffect
        if (!c.released) return@LaunchedEffect
        carryShift(c)?.let { (ds, dp) -> vm.controller.dropClip(c.clip, ds, dp) }
        onCarryDone()
    }

    LaunchedEffect(state.selectedTrack, length, vp.size.width > 0, fitTick) {
        if (vp.size.width == 0) return@LaunchedEffect
        vp.cw = ((vp.w - vp.lw) / length).coerceIn(4f * d, 64f * d)
        val cur = vm.controller.state.value.steps
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        var first = -1
        for (i in 0 until length) {
            val st = cur.getOrNull(i) ?: continue
            if (st.time != StepTime.NOTE || st.n <= 0) continue
            if (first < 0) first = i
            for (k in 0 until st.n) {
                val n = st.notes[k]
                if (n < lo) lo = n
                if (n > hi) hi = n
            }
        }
        if (lo > hi) { lo = 48 - 12; hi = 48 + 12 }      // no notes: C3 +- one octave
        else if (lo == hi) { lo -= 12; hi += 12 }        // single pitch: octave up/down around it
        lo -= 2; hi += 2                                 // two-row gap above and below
        val span = (hi - lo + 1).toFloat()
        vp.rh = ((vp.h - vp.hdr) / span).coerceIn(8f * d, 44f * d)
        val midRow = (2 * TOP_NOTE - hi - lo + 1) / 2f
        vp.sx = (if (first >= 0) first else 0) * vp.cw
        vp.sy = midRow * vp.rh - (vp.h - vp.hdr) / 2f
        vp.clamp()
    }

    val handler = remember(state.selectedTrack, length, selectMode) {
        object : GridHandler {
            fun noteAtRow(y: Float) = TOP_NOTE - vp.rowAt(y)
            fun allNotes() = decodeNotes(vm.controller.state.value.steps, length)
            fun hit(p: Offset): NoteHit? {
                if (p.x < vp.lw || p.y < vp.hdr) return null
                val note = noteAtRow(p.y)
                if (note !in 0..TOP_NOTE) return null
                val all = decodeNotes(vm.controller.state.value.steps, length)
                val slack = 8f * vp.d
                val zone = min(vp.cw * 0.4f, 24f * vp.d)
                for (x in floatArrayOf(p.x - slack, p.x, p.x + slack)) {
                    val i = vp.stepAt(x)
                    val n = all.firstOrNull { it.pitch == note && i in it.start..it.end } ?: continue
                    val left = vp.lw + n.start * vp.cw - vp.sx
                    val right = left + n.len * vp.cw
                    val edge = when {
                        n.len > 1 && p.x <= left + zone -> -1
                        p.x >= right - zone -> 1
                        else -> 0
                    }
                    return NoteHit(n.start, note, n.len, edge)
                }
                return null
            }
            /** Selection gestures apply in select mode and whenever a quick selection is alive. */
            fun selOn() = selectMode || live.v.isNotEmpty()
            fun frameAt(p: Offset) = Marquee((p.x - vp.lw + vp.sx) / vp.cw, (p.y - vp.hdr + vp.sy) / vp.rh,
                (p.x - vp.lw + vp.sx) / vp.cw, (p.y - vp.hdr + vp.sy) / vp.rh)
            fun ratcheted(n: PNote) =
                vm.controller.state.value.steps.getOrNull(n.start)?.rat?.let { it != 0 } == true
            override fun tap(p: Offset) {
                if (p.x < vp.lw || p.y < vp.hdr) {
                    if (p.y < vp.hdr && p.x >= vp.lw) vp.stepAt(p.x).takeIf { it in 0 until length }?.let(onSelect)
                    return
                }
                if (selOn()) {                          // tap a note: toggle it in the selection; empty: deselect all
                    val h = hit(p)
                    if (h == null) { if (live.v.isNotEmpty()) setSel(emptySet()) }
                    else {
                        val k = noteKey(h.note, h.start)
                        setSel(if (k in live.v) live.v - k else live.v + k)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    return
                }
                val note = noteAtRow(p.y)
                val step = vp.stepAt(p.x)
                if (note !in 0..TOP_NOTE || step !in 0 until length) return
                val h = hit(p)
                val at = h?.start ?: step
                onSelect(at)
                if (h != null) lastLen = h.len
                vm.controller.toggleNote(at, note, lastLen)
            }
            override fun longPress(p: Offset): Boolean {
                if (p.x < vp.lw || p.y < vp.hdr) return false
                val h = hit(p)
                if (h == null) {                        // hold on empty grid: quick rectangle select, no toggle needed
                    marquee = frameAt(p)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    return true
                }
                if (!selOn()) return false              // hold a note with no selection alive: nothing
                val k = noteKey(h.note, h.start)        // hold a note: take a copy of the selection and carry it
                if (k !in live.v) setSel(setOf(k))
                gdrag = GroupDrag(vp.stepAt(p.x), vp.rowAt(p.y), true)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                return true
            }
            override fun grab(p: Offset): Boolean {
                val h = hit(p)?.takeIf { it.edge != 0 } ?: return false
                if (selOn()) {                          // pull a selected note's edge: stretch them all together
                    val k = noteKey(h.note, h.start)
                    if (k !in live.v) setSel(setOf(k))
                    gresize = GroupResize(h.edge, vp.stepAt(p.x))
                    return true
                }
                onSelect(h.start)
                ndrag = NoteDrag(false, h.start, h.note, h.len, h.start, h.start, h.note,
                    h.start + h.len - 1, h.edge)
                return true
            }
            override fun beginMove(p: Offset): Boolean {
                if (selOn()) {
                    if (p.x < vp.lw || p.y < vp.hdr) return false
                    val h = hit(p)
                    if (h != null) {                    // drag a note: carry the selection (a loose note becomes it)
                        val k = noteKey(h.note, h.start)
                        if (k !in live.v) setSel(setOf(k))
                        gdrag = GroupDrag(vp.stepAt(p.x), vp.rowAt(p.y), false)
                    } else {                            // drag on empty grid: select frame
                        marquee = frameAt(p)
                    }
                    return true
                }
                val h = hit(p)?.takeIf { it.edge == 0 } ?: return false
                val g = vp.stepAt(p.x)
                onSelect(h.start)
                ndrag = NoteDrag(true, h.start, h.note, h.len, g, h.start, h.note,
                    h.start + h.len - 1, 0)
                return true
            }
            override fun drag(p: Offset) {
                val g = gdrag
                val m = marquee
                val r = gresize
                when {
                    g != null -> gdrag = GroupDrag(g.grabStep, g.grabRow, g.copy,
                        vp.stepAt(p.x) - g.grabStep, -(vp.rowAt(p.y) - g.grabRow))
                    m != null -> marquee = Marquee(m.x0, m.y0,
                        (p.x - vp.lw + vp.sx) / vp.cw, (p.y - vp.hdr + vp.sy) / vp.rh)
                    r != null -> gresize = GroupResize(r.edge, r.grabStep, vp.stepAt(p.x) - r.grabStep)
                    selOn() -> {}
                    else -> {
                        val dr = ndrag ?: return
                        ndrag = if (dr.move) dr.copy(
                            toStep = (dr.start + vp.stepAt(p.x) - dr.grabStep).coerceIn(0, length - dr.len),
                            toNote = noteAtRow(p.y).coerceIn(0, TOP_NOTE))
                        else if (dr.edge > 0) dr.copy(end = vp.stepAt(p.x).coerceIn(dr.start, length - 1))
                        else dr.copy(toStep = vp.stepAt(p.x).coerceIn(0, dr.start + dr.len - 1))
                    }
                }
            }
            override fun release(p: Offset) {
                if (gdrag != null || marquee != null || gresize != null) {
                    drag(p)
                    gdrag?.let { g ->
                        val sel = allNotes().filter { noteKey(it.pitch, it.start) in live.v }
                        val (ds, dp) = clampShift(sel, length, g.dStep, g.dPitch)
                        if (g.copy || ds != 0 || dp != 0) {
                            vm.controller.placeNotes(live.v, g.dStep, g.dPitch, g.copy)
                            if (ds != 0 || dp != 0) setSel(sel.map { noteKey(it.pitch + dp, it.start + ds) }.toSet())
                        }
                    }
                    gresize?.let { g ->
                        val sel = allNotes().filter { noteKey(it.pitch, it.start) in live.v }
                        val others = allNotes().filter { noteKey(it.pitch, it.start) !in live.v }
                        val (lo, hi) = resizeDeltaBounds(sel, others, length, g.edge, ::ratcheted)
                        val d = if (lo <= hi) g.delta.coerceIn(lo, hi) else 0
                        if (d != 0) {
                            vm.controller.resizeNotes(live.v, g.edge, g.delta)
                            if (g.edge < 0) setSel(sel.map { noteKey(it.pitch, it.start + d) }.toSet())
                        }
                    }
                    marquee?.let { m -> setSel(allNotes().filter { m.hits(it) }.map { noteKey(it.pitch, it.start) }.toSet()) }
                    gdrag = null; marquee = null; gresize = null
                    return
                }
                val dr = ndrag ?: return
                drag(p)
                val fin = ndrag ?: dr
                ndrag = null
                lastLen = if (fin.move) fin.len
                    else if (fin.edge > 0) fin.end - fin.start + 1
                    else fin.start + fin.len - fin.toStep
                if (fin.move) {
                    if (fin.toStep != fin.start || fin.toNote != fin.note)
                        vm.controller.moveNote(fin.start, fin.note, fin.toStep, fin.toNote)
                } else if (fin.edge > 0) {
                    if (fin.end - fin.start + 1 != fin.len)
                        vm.controller.resizeNote(fin.start, fin.note, fin.end - fin.start + 1)
                } else if (fin.toStep != fin.start)
                    vm.controller.adjustNoteStart(fin.start, fin.note, fin.toStep - fin.start)
            }
            override fun cancel() { ndrag = null; gdrag = null; gresize = null; marquee = null }
        }
    }

    Canvas(modifier.clipToBounds().background(cs.background)
        .onSizeChanged { vp.size = it; vp.clamp() }
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(handler) { gridGestures(vp) { handler } }) {
        val cw = vp.cw; val rh = vp.rh; val sx = vp.sx; val sy = vp.sy; val lw = vp.lw; val hdr = vp.hdr
        val c0 = (sx / cw).toInt().coerceIn(0, length - 1)
        val c1 = ((sx + size.width - lw) / cw).toInt().coerceIn(0, length - 1)
        val r0 = (sy / rh).toInt().coerceIn(0, rows - 1)
        val r1 = ((sy + size.height - hdr) / rh).toInt().coerceIn(0, rows - 1)
        val line = cs.outline.copy(alpha = 0.35f)
        val strongLine = cs.outline.copy(alpha = 0.8f)
        val radius = CornerRadius(4f * d)
        fun rowY(r: Int) = hdr + r * rh - sy
        fun colX(c: Int) = lw + c * cw - sx

        clipRect(lw, hdr) {
            val gridRight = colX(length)
            for (r in r0..r1) {
                val note = TOP_NOTE - r
                drawRect(if (note % 12 in BLACK_KEYS) cs.surfaceVariant else cs.surface,
                    Offset(lw, rowY(r)), Size((gridRight - lw).coerceAtLeast(0f), rh))
                drawLine(if (note % 12 == 0) strongLine else line, Offset(lw, rowY(r) + rh),
                    Offset(gridRight, rowY(r) + rh), 1f * d)
            }
            for (c in c0..min(c1 + 1, length)) {
                drawLine(if (c % 4 == 0) strongLine else line, Offset(colX(c), hdr), Offset(colX(c), size.height),
                    if (c % 4 == 0) 1.5f * d else 1f * d)
            }
            val all = decodeNotes(steps, length)
            val picked = if (noteSel.isNotEmpty()) all.filter { noteKey(it.pitch, it.start) in noteSel } else emptyList()
            val dimmed = (gdrag != null && gdrag?.copy == false) || gresize != null
            for (n in all) {
                val r = TOP_NOTE - n.pitch
                if (r !in r0..r1 || n.end < c0 || n.start > c1) continue
                val x = colX(n.start)
                val isSel = n in picked || marquee?.hits(n) == true
                val body = if (isSel) cs.tertiary else cs.primary
                drawRoundRect(if (dimmed && n in picked) body.copy(alpha = 0.3f) else body,
                    Offset(x + 1.5f * d, rowY(r) + 2f * d), Size(n.len * cw - 3f * d, rh - 4f * d), radius)
                if (isSel) drawRoundRect(cs.onTertiary.copy(alpha = 0.9f),
                    Offset(x + 1.5f * d, rowY(r) + 2f * d), Size(n.len * cw - 3f * d, rh - 4f * d), radius,
                    style = Stroke(1.5f * d))
                val handle = (if (isSel) cs.onTertiary else cs.onPrimary).copy(alpha = 0.7f)
                drawRoundRect(handle, Offset(x + n.len * cw - 8f * d, rowY(r) + rh * 0.28f),
                    Size(3f * d, rh * 0.44f), CornerRadius(1.5f * d))
                if (n.len > 1) drawRoundRect(handle,
                    Offset(x + 5f * d, rowY(r) + rh * 0.28f),
                    Size(3f * d, rh * 0.44f), CornerRadius(1.5f * d))
            }
            gdrag?.let { g ->                            // the carried group (or its clone) where it would land
                val (ds, dp) = clampShift(picked, length, g.dStep, g.dPitch)
                for (n in picked) {
                    val gr = TOP_NOTE - (n.pitch + dp)
                    drawRoundRect(cs.tertiary.copy(alpha = 0.75f),
                        Offset(colX(n.start + ds) + 1.5f * d, rowY(gr) + 2f * d),
                        Size(n.len * cw - 3f * d, rh - 4f * d), radius)
                    drawRoundRect(cs.onTertiary, Offset(colX(n.start + ds) + 1.5f * d, rowY(gr) + 2f * d),
                        Size(n.len * cw - 3f * d, rh - 4f * d), radius, style = Stroke(1.5f * d))
                }
            }
            gresize?.let { g ->                          // the stretched group where it would land
                val others = all.filter { noteKey(it.pitch, it.start) !in noteSel }
                val (lo, hi) = resizeDeltaBounds(picked, others, length, g.edge) { n ->
                    steps.getOrNull(n.start)?.rat?.let { it != 0 } == true }
                val dd = if (lo <= hi) g.delta.coerceIn(lo, hi) else 0
                if (dd != 0) for (n in picked) {
                    val ns = if (g.edge > 0) n.start else n.start + dd
                    val nl = if (g.edge > 0) n.len + dd else n.len - dd
                    drawRoundRect(cs.tertiary.copy(alpha = 0.75f),
                        Offset(colX(ns) + 1.5f * d, rowY(TOP_NOTE - n.pitch) + 2f * d),
                        Size(nl * cw - 3f * d, rh - 4f * d), radius)
                    drawRoundRect(cs.onTertiary,
                        Offset(colX(ns) + 1.5f * d, rowY(TOP_NOTE - n.pitch) + 2f * d),
                        Size(nl * cw - 3f * d, rh - 4f * d), radius, style = Stroke(1.5f * d))
                }
            }
            carry?.let { c ->                            // a saved pattern carried over the roll, where it would land
                val sh = carryShift(c) ?: return@let
                val src = c.clip.notes.filter { it.start < length }
                    .map { PNote(it.start, minOf(it.len, length - it.start).coerceAtLeast(1), it.pitch.coerceIn(0, TOP_NOTE)) }
                val (ds, dp) = clampShift(src, length, sh.first, sh.second)
                for (n in src) {
                    val gr = TOP_NOTE - (n.pitch + dp)
                    drawRoundRect(cs.tertiary.copy(alpha = 0.75f),
                        Offset(colX(n.start + ds) + 1.5f * d, rowY(gr) + 2f * d),
                        Size(n.len * cw - 3f * d, rh - 4f * d), radius)
                    drawRoundRect(cs.onTertiary, Offset(colX(n.start + ds) + 1.5f * d, rowY(gr) + 2f * d),
                        Size(n.len * cw - 3f * d, rh - 4f * d), radius, style = Stroke(1.5f * d))
                }
            }
            marquee?.let { m ->
                val left = lw + min(m.x0, m.x1) * cw - sx
                val top = hdr + min(m.y0, m.y1) * rh - sy
                val sz = Size(kotlin.math.abs(m.x1 - m.x0) * cw, kotlin.math.abs(m.y1 - m.y0) * rh)
                drawRect(cs.tertiary.copy(alpha = 0.15f), Offset(left, top), sz)
                drawRect(cs.tertiary, Offset(left, top), sz, style = Stroke(1.5f * d))
            }
            ndrag?.let { dr ->
                val (s, n, l) = if (dr.move) Triple(dr.toStep, dr.toNote, dr.len)
                    else if (dr.edge > 0) Triple(dr.start, dr.note, dr.end - dr.start + 1)
                    else Triple(dr.toStep, dr.note, dr.start + dr.len - dr.toStep)
                drawRoundRect(cs.tertiary.copy(alpha = 0.75f), Offset(colX(s) + 1.5f * d, rowY(TOP_NOTE - n) + 2f * d),
                    Size(l * cw - 3f * d, rh - 4f * d), radius)
            }
        }
        clipRect(lw, 0f, size.width, hdr) {
            drawRect(cs.surface, Offset(lw, 0f), Size(size.width - lw, hdr))
            with(painter) {
                for (c in c0..c1) text("${c + 1}", colX(c) + cw / 2, hdr / 2,
                    if (c == selected) cs.tertiary else if (c % 4 == 0) cs.primary else cs.onSurfaceVariant,
                    11f * d, Paint.Align.CENTER)
            }
        }
        clipRect(0f, hdr, lw, size.height) {
            drawRect(cs.surface, Offset(0f, hdr), Size(lw, size.height - hdr))
            with(painter) {
                for (r in r0..r1) {
                    val note = TOP_NOTE - r
                    text(noteName(note), 5f * d, rowY(r) + rh / 2,
                        if (note % 12 in BLACK_KEYS) cs.onSurfaceVariant else cs.onSurface, 10f * d, Paint.Align.LEFT)
                }
            }
        }
        drawRect(cs.surface, Offset.Zero, Size(lw, hdr))
    }
}

private fun NoteDrag.copy(
    move: Boolean = this.move, start: Int = this.start, note: Int = this.note, len: Int = this.len,
    grabStep: Int = this.grabStep, toStep: Int = this.toStep, toNote: Int = this.toNote,
    end: Int = this.end, edge: Int = this.edge,
) = NoteDrag(move, start, note, len, grabStep, toStep, toNote, end, edge)

// ---------------------------------------------------------------- step editor ---

/** p_lockable (firmware seq.c): the sound parameters only — not the sequencer's, arp's, key's or voice mode's. */
private fun isLockableParam(id: Int) = id <= 16 || id == 32 || id in 33..36 || id == 38 || id == 39 ||
    id == 44 || id in 45..48 || id == 50 || id in 53..60

@Composable
private fun StepEditor(
    vm: SloopViewModel, state: DeviceState,
    step: Step?, index: Int, steps: List<Step>, patternLength: Int,
) {
    Card(Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Step ${index + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (step != null && step.time == StepTime.NOTE && step.n > 0) {
                val duration = noteLength(steps, index, patternLength)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Length: $duration steps", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.controller.setNoteLength(index, duration - 1) },
                        enabled = duration > 1) { Text("−") }
                    TextButton(onClick = { vm.controller.setNoteLength(index, duration + 1) },
                        enabled = index + duration < patternLength) { Text("+") }
                }
                if (step.n > 1) Text("Length applies to the whole chord", style = MaterialTheme.typography.labelSmall)
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isNote = step?.time == StepTime.NOTE && (step?.n ?: 0) > 0
                FilterChip(selected = step?.time == StepTime.REST || step?.n == 0,
                    onClick = {
                        vm.controller.setNoteLength(index, 1)
                        vm.controller.setStep(index, rest(vm.controller.state.value.steps.getOrNull(index), index))
                    }, label = { Text("Rest") })
                FilterChip(selected = step?.time == StepTime.TIE,
                    onClick = { vm.controller.setStep(index, tie(vm.controller.state.value.steps.getOrNull(index), index)) }, label = { Text("Tie") })
                FilterChip(selected = (step?.flags ?: 0) and 1 != 0, enabled = isNote,
                    onClick = { vm.controller.state.value.steps.getOrNull(index)?.let {
                        vm.controller.setStep(index, it.copy(flags = it.flags xor 1)) } },
                    label = { Text("Accent") })
                FilterChip(selected = (step?.flags ?: 0) and 2 != 0, enabled = isNote,
                    onClick = { vm.controller.state.value.steps.getOrNull(index)?.let {
                        vm.controller.setStep(index, it.copy(flags = it.flags xor 2)) } },
                    label = { Text("Slide") })
            }

            Text("Note", Modifier.padding(top = 14.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Piano(selectedNote = if (step?.n != null && step.n > 0) step.notes[0] else -1) { note ->
                vm.controller.toggleNote(index, note)
            }

            if ((state.info?.proto ?: 0) >= 7) {
                Text("Timing", Modifier.padding(top = 14.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val nudge = state.micro.getOrElse(index) { 0 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nudge ${if (nudge > 0) "+" else ""}$nudge/64", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.controller.setStepMicro(index, nudge - 1) },
                        enabled = nudge > -32) { Text("−") }
                    TextButton(onClick = { vm.controller.setStepMicro(index, nudge + 1) },
                        enabled = nudge < 31) { Text("+") }
                }
                if ((state.info?.proto ?: 0) >= 8) {
                    val cond = state.fill.getOrElse(index) { 0 }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = cond == 0,
                            onClick = { vm.controller.setStepFill(index, 0) }, label = { Text("Always") })
                        FilterChip(selected = cond == 1,
                            onClick = { vm.controller.setStepFill(index, 1) }, label = { Text("Fill") })
                        FilterChip(selected = cond == 2,
                            onClick = { vm.controller.setStepFill(index, 2) }, label = { Text("No fill") })
                    }
                }

                Text("Locks", Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.locks.filter { it.step == index }.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.pdesc.getOrNull(l.param)?.label ?: "P${l.param}"} = ${l.value}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.controller.setStepLock(index, l.param, null) }) { Text("×") }
                    }
                }
                var lockParam by remember { mutableIntStateOf(-1) }
                var lockValue by remember { mutableIntStateOf(0) }
                var lockMenu by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { lockMenu = true }) {
                            Text(if (lockParam < 0) "Lock param…"
                                else state.pdesc.getOrNull(lockParam)?.label ?: "P$lockParam")
                        }
                        DropdownMenu(expanded = lockMenu, onDismissRequest = { lockMenu = false }) {
                            state.pdesc.forEachIndexed { id, d ->
                                if (d != null && isLockableParam(id)) DropdownMenuItem(
                                    text = { Text(d.label) },
                                    onClick = { lockParam = id; lockValue = d.def; lockMenu = false })
                            }
                        }
                    }
                    if (lockParam >= 0) TextButton(onClick = {
                        vm.controller.setStepLock(index, lockParam, lockValue); lockParam = -1
                    }) { Text("Add") }
                }
                state.pdesc.getOrNull(lockParam)?.let { d -> ParamControl(d, lockValue) { lockValue = it } }
            }
        }
    }
}

/** Two octaves from C3 (48). */
@Composable
private fun Piano(selectedNote: Int, onNote: (Int) -> Unit) {
    val base = 48
    Column {
        for (oct in 0 until 2) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (semi in 0 until 12) {
                    val note = base + oct * 12 + semi
                    val black = semi in listOf(1, 3, 6, 8, 10)
                    val sel = note == selectedNote
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    sel -> MaterialTheme.colorScheme.primary
                                    black -> Color(0xFF222222)
                                    else -> Color(0xFFDDDDDD)
                                }
                            )
                            .clickable { onNote(note) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(noteName(note), fontSize = 8.sp, fontFamily = SloopFontFamily,
                            color = if (sel) Color.Black else if (black) Color.White else Color.Black)
                    }
                }
            }
        }
    }
}

// step helpers
private fun emptyStep(index: Int) = Step(index, 0, intArrayOf(0, 0, 0, 0), StepTime.REST, 0, 100)
private fun rest(step: Step?, index: Int): Step {
    val s = step ?: emptyStep(index)
    return s.copy(n = 0, time = StepTime.REST)
}
private fun tie(step: Step?, index: Int): Step {
    val s = step ?: emptyStep(index)
    return s.copy(time = StepTime.TIE)
}
