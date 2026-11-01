// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
@file:OptIn(ExperimentalLayoutApi::class)

package com.sloop.go.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import com.sloop.go.proto.Desc
import com.sloop.go.proto.Fm6
import com.sloop.go.proto.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun rcText(rc: Int): String = when (rc) {
    1 -> "Bad slot or arguments"
    2 -> "Empty slot or flash error"
    3 -> "Stop the device first (flash write)"
    else -> "Error $rc"
}

/** Items above the first operator row of the page list: the library bar and the voice card. */
private const val OP_ITEM0 = 2

/**
 * FM6 patch library + a full six-operator editor (DX7 voices), the app's version of the web editor's FM6 panel: the
 * algorithm drawn as boxes and lines, six compact operator cards (knobs sized to the room the screen has in dp, so a
 * dense or zoomed display still fits as much as possible), the LFO and pitch EG beside each other.
 */
@Composable
fun Fm6Screen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit, nav: @Composable () -> Unit) {
    if (state.link != Link.READY || state.info == null) {
        NotReady("FM6 patches", state, onDevice)
        return
    }
    if (!state.fm6Supported) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { ScreenHeader("FM6 patches", state, NAV_INSET) }
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("This FM-1 firmware has no FM6 engine. Update to SLOOP 2.4 or newer.",
                        Modifier.padding(16.dp))
                }
            }
        }
        return
    }

    val ed = vm.fm6
    val ctl = vm.controller
    val info = state.info
    val ctx = LocalContext.current
    val nf = state.fm6?.factory ?: 8
    val nb = state.fm6?.bank ?: Fm6.BANK_N
    val total = nf + nb
    val ntrk = minOf(3, info.ntrk.coerceAtLeast(1))
    val track = vm.fm6Track(state)
    val trackIsFm6 = state.trackEngine(track) == state.fm6Engine
    val slot = ed.slot.coerceIn(0, total - 1)
    fun slotName(i: Int) = if (i < nf) "F${i + 1}" else "B${i - nf + 1}"
    fun slotLabel(i: Int): String {
        val s = state.fm6?.slots?.getOrNull(i)
        return "${slotName(i)}  " + if (s != null && s.used) s.name else "— empty —"
    }
    val ptch = info.pe0 + 7
    val trackSlot = if (state.selectedTrack == track && trackIsFm6) state.paramValue(ptch) else -1

    fun op(block: suspend () -> Unit) {
        if (ed.busy) return
        ed.busy = true
        vm.launch {
            try { block() } catch (e: Exception) {
                ed.say("Error: ${e.message ?: e.javaClass.simpleName}", true)
            } finally { ed.busy = false }
        }
    }

    suspend fun readTrack(quiet: Boolean) {
        val r = ctl.fm6Get(0, track)
        if (r.rc != 0) { if (!quiet) ed.say(rcText(r.rc), true); return }
        ed.load(Fm6.unpack(r.packed!!))
        if (!quiet) ed.say("Read from track ${track + 1}: ${Fm6.name(ed.voice)}")
    }

    suspend fun sendTrack(quiet: Boolean) {
        val rc = ctl.fm6Put(0, track, Fm6.pack(ed.voice))
        if (rc != 0) ed.say(rcText(rc), true)
        else { ed.sentRev = ed.rev; if (!quiet) ed.say("Sent to track ${track + 1}: ${Fm6.name(ed.voice)}") }
    }

    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ed.say("Saved ${bytes.size} bytes")
            } catch (e: Exception) { ed.say("Save failed: ${e.message}", true) }
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            val r = Fm6.parseSysex(bytes)
            if (r.voices.isEmpty()) {
                ed.say(when {
                    "fm4" in r.kinds -> "These are 4-operator FM voices: FM6 reads 6-operator voices only"
                    "other43" in r.kinds -> "6-operator file format, but no voices (performance or supplement data only)"
                    r.kinds.any { it.startsWith("maker:") } -> "SysEx from another manufacturer"
                    "universal" in r.kinds -> "Universal SysEx, not voice data"
                    r.sysex -> "No 6-operator FM voices found (expects a 32-voice bank or single-voice SysEx)"
                    else -> "Not a SysEx file (expects a 32-voice bank or single-voice SysEx)"
                }, true)
            } else {
                ed.imported = r.voices
                if (r.voices.size == 1) ed.load(r.voices[0].v)
                val notes = listOfNotNull(
                    if (r.badSum > 0) "a checksum did not match" else null,
                    if (r.short > 0) "some data was cut short" else null,
                    if (r.skipped > 0) "${r.skipped} non-voice blocks skipped" else null)
                ed.say("Imported ${r.voices.size} voice(s)" + if (notes.isEmpty()) "" else " (${notes.joinToString(", ")})")
            }
        } catch (e: Exception) { ed.say("Cannot read file: ${e.message}", true) }
    }

    // live send: follows the user's edits only, a moment after the last one
    LaunchedEffect(ed.rev, ed.live, track, trackIsFm6) {
        if (!ed.live || !trackIsFm6 || ed.rev == ed.sentRev) return@LaunchedEffect
        delay(150)
        ed.sentRev = ed.rev
        try { ctl.fm6Put(0, track, Fm6.pack(ed.voice)) } catch (_: Exception) {}
    }

    val v = ed.voice
    val cs = MaterialTheme.colorScheme
    val defs = remember { Fm6.init() }
    val alg = v[Fm6.ALG]
    val carriers = Fm6.carriers(alg)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var sel by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        PinnedHeader(nav, headerStatus(state, if (ed.live) "live" else if (ed.dirty) "unsent edits" else null)) {
            Text("FM6 patches", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            FilterChip(selected = ed.live, onClick = { ed.live = true }, label = { Text("Live") })
            FilterChip(selected = !ed.live, onClick = { ed.live = false }, label = { Text("Store") })
        }

        if (ed.busy || ed.message != null) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            if (ed.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ed.message?.let {
                Text(it, color = if (ed.isError) cs.error else cs.primary, style = MaterialTheme.typography.bodySmall)
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val pad = 8.dp
            val gap = 6.dp
            // knobs: the screen's size in dp (see knobSizeFor) less a fifth, and in an operator card as wide as its
            // eight slots allow; a dense or zoomed display has fewer dp, so everything shrinks and still fits
            val base = LocalKnob.current.compact()
            fun fit(w: Dp, slots: Int) = minOf(base, (w - 16.dp) / slots - 3.dp).coerceAtLeast(26.dp)
            val cols = ((maxWidth - pad * 2 + gap) / ((base + 4.dp) * 8 + 16.dp + gap)).toInt().coerceIn(1, 3)
            val cardW = (maxWidth - pad * 2 - gap * (cols - 1)) / cols
            val rowW = maxWidth - pad * 2 - gap
            val diagH = (base * 3.4f).coerceIn(112.dp, 148.dp)
            val onOp: (Int) -> Unit = { n ->
                sel = if (sel == n) 0 else n
                if (sel != 0) scope.launch { listState.animateScrollToItem(OP_ITEM0 + (n - 1) / cols) }
            }

            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(pad, 4.dp, pad, 48.dp),
                verticalArrangement = Arrangement.spacedBy(gap)) {

                // ---- library bar: the track, the slot and what to do with them, two short rows
                item(key = "lib") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.height(ROW_H), contentAlignment = Alignment.CenterStart) {
                                    Text("Track", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                }
                                for (t in 0 until ntrk) {
                                    val eng = state.trackEngine(t)
                                    Box(Modifier.height(ROW_H), contentAlignment = Alignment.Center) {
                                        FilterChip(selected = t == track, onClick = { ed.track = t },
                                            label = { Text("${t + 1}" + if (eng == state.fm6Engine) " · FM6"
                                                else info.engines.getOrNull(eng)?.let { " · $it" } ?: "") })
                                    }
                                }
                                Cb("Read from track", trackIsFm6 && !ed.busy) { op { readTrack(false) } }
                                Cb("Send to track", trackIsFm6 && !ed.busy, filled = true) { op { sendTrack(false) } }
                            }
                            if (!trackIsFm6) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.height(ROW_H), contentAlignment = Alignment.CenterStart) {
                                    Text("Track ${track + 1} does not play FM6, so a patch would not sound on it.",
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                Cb("Switch track ${track + 1} to FM6", !ed.busy, filled = true) {
                                    op {
                                        ctl.fm6EnableTrack(track)
                                        ed.say("Track ${track + 1} now plays FM6")
                                        readTrack(true)
                                    }
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.height(ROW_H), contentAlignment = Alignment.CenterStart) {
                                    Text("Slot", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                }
                                var open by remember { mutableStateOf(false) }
                                Box(Modifier.height(ROW_H), contentAlignment = Alignment.Center) {
                                    OutlinedButton(onClick = { open = true }, modifier = Modifier.height(32.dp),
                                        contentPadding = TIGHT) {
                                        Text(slotLabel(slot) + "  ▾", maxLines = 1, fontFamily = SloopFontFamily)
                                    }
                                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                        for (i in 0 until total) DropdownMenuItem(
                                            text = {
                                                Text((if (i == trackSlot) "▶ " else "") + slotLabel(i),
                                                    fontWeight = if (i == slot) FontWeight.Bold else FontWeight.Normal,
                                                    fontFamily = SloopFontFamily)
                                            },
                                            onClick = { ed.slot = i; open = false })
                                    }
                                }
                                Cb("Load into editor", !ed.busy) {
                                    op {
                                        val r = if (slot < nf) ctl.fm6Get(2, slot) else ctl.fm6Get(1, slot - nf)
                                        if (r.rc == 2) ed.say("Empty slot ${slotName(slot)}")
                                        else if (r.rc != 0) ed.say(rcText(r.rc), true)
                                        else { ed.load(Fm6.unpack(r.packed!!)); ed.say("Loaded ${slotName(slot)}: ${Fm6.name(ed.voice)}") }
                                    }
                                }
                                Cb("Store in ${if (slot >= nf) slotName(slot) else "bank"}", slot >= nf && !ed.busy, filled = true) {
                                    val used = state.fm6?.slots?.getOrNull(slot)
                                    val doStore = {
                                        op {
                                            val rc = ctl.fm6Put(1, slot - nf, Fm6.pack(ed.voice))
                                            if (rc == 0) ed.say("Stored in ${slotName(slot)}: ${Fm6.name(ed.voice)}")
                                            else ed.say(rcText(rc), true)
                                        }
                                    }
                                    if (used != null && used.used) confirm = Triple("Overwrite ${slotName(slot)}?",
                                        "Slot ${slotName(slot)} (${used.name}) will be replaced by \"${Fm6.name(v)}\".", doStore)
                                    else doStore()
                                }
                                Cb("Use on track ${track + 1}", trackIsFm6 && !ed.busy) {
                                    op {
                                        ctl.fm6Assign(track, slot)
                                        delay(300)
                                        readTrack(true)
                                        ed.say("Track ${track + 1} plays ${slotName(slot)}")
                                    }
                                }
                                Cb("Erase", slot >= nf && state.fm6?.slots?.getOrNull(slot)?.used == true && !ed.busy) {
                                    confirm = Triple("Erase ${slotName(slot)}?",
                                        "Slot ${slotName(slot)} (${state.fm6?.slots?.getOrNull(slot)?.name}) will be emptied.") {
                                        op {
                                            val rc = ctl.fm6Erase(slot - nf)
                                            if (rc == 0) ed.say("Erased ${slotName(slot)}") else ed.say(rcText(rc), true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- the voice: name and quick actions, the algorithm drawn out, ALG / FB / transpose / key sync
                item(key = "voice") {
                    Fm6VoiceCard(v, defs, ed, base, diagH, sel, onOp) { picking = true }
                }

                // ---- the six operators in order, as many cards a row as fit with knobs of a usable size
                for (first in 0 until Fm6.OPS step cols) item(key = "ops$first") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (n in first + 1..first + cols)
                            Fm6OpCard(n, v, defs, ed, n in carriers, n == sel, fit(cardW, 8), Modifier.weight(1f))
                    }
                }

                // ---- LFO and pitch EG side by side, one row of knobs each
                item(key = "lfoPeg") {
                    val knob = minOf(fit(rowW * (7f / 15f), 7), fit(rowW * (8f / 15f), 8))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Fm6Block("LFO", Modifier.weight(7f)) {
                            Fm6Knobs(listOf(
                                Fm6Ctl("WAVE", Fm6.LFW, Fmt.ENUM, Fm6.LFO_WAVES),
                                Fm6Ctl("SPD", Fm6.LFS),
                                Fm6Ctl("DLY", Fm6.LFD),
                                Fm6Ctl("SYNC", Fm6.LKS, Fmt.ONOFF),
                                Fm6Ctl("PMD", Fm6.LPMD),
                                Fm6Ctl("AMD", Fm6.LAMD),
                                Fm6Ctl("PMS", Fm6.LPMS),
                            ), 7, knob, v, defs, ed)
                        }
                        Fm6Block("PITCH EG", Modifier.weight(8f)) {
                            Fm6Knobs(List(8) { Fm6Ctl(if (it < 4) "R${it + 1}" else "L${it - 3}", Fm6.PR1 + it) },
                                8, knob, v, defs, ed)
                        }
                    }
                }

                // ---- files
                item(key = "files") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.height(ROW_H), contentAlignment = Alignment.CenterStart) {
                                    Text("DX7 SysEx", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                }
                                Cb("Import from phone", !ed.busy, filled = true) { opener.launch(arrayOf("*/*")) }
                                Cb("Export voice") {
                                    pendingExport = Fm6.singleSysex(v)
                                    saver.launch(fileName(Fm6.name(v)) + ".syx")
                                }
                                Cb("Export bank", !ed.busy) {
                                    op {
                                        val out = ArrayList<IntArray?>()
                                        for (k in 0 until nb) {
                                            ed.say("Reading bank ${k + 1}/$nb…")
                                            val r = ctl.fm6Get(1, k)
                                            out.add(if (r.rc == 0) Fm6.unpack(r.packed!!) else null)
                                        }
                                        pendingExport = Fm6.bankSysex(out)
                                        ed.say("Bank read")
                                        saver.launch("sloop-fm6-bank.syx")
                                    }
                                }
                            }
                            if (ed.imported.isNotEmpty()) {
                                Text("Voices of the imported file (tap to edit):", style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    ed.imported.forEachIndexed { k, x ->
                                        FilterChip(selected = false, onClick = { ed.load(x.v); ed.say("Editing: ${x.name}") },
                                            label = { Text("${k + 1} ${x.name.ifBlank { "—" }}", maxLines = 1) })
                                    }
                                }
                                val count = minOf(ed.imported.size, nb)
                                Cb("Store cartridge in bank (B1–B$count)", !ed.busy && count > 0, filled = true) {
                                    confirm = Triple("Store cartridge in the bank?",
                                        "$count voice(s) will be written to B1–B$count in one flash write, replacing what is there " +
                                            "(the rest of the bank stays). Stop the device first.") {
                                        op {
                                            ed.say("Writing $count voice(s)…")
                                            val packs = List(count) { Fm6.pack(ed.imported[it].v) }
                                            when (val rc = ctl.fm6StoreCartridge(packs)) {
                                                0 -> ed.say("Stored $count voice(s) in B1–B$count")
                                                3 -> ed.say(rcText(3), true)
                                                -1 -> {
                                                    for (k in 0 until count) {
                                                        ed.say("Uploading ${k + 1}/$count: ${ed.imported[k].name}")
                                                        val r2 = ctl.fm6Put(1, k, packs[k])
                                                        if (r2 != 0) { ed.say("B${k + 1}: " + rcText(r2), true); return@op }
                                                    }
                                                    ed.say("Uploaded $count voice(s) to B1–B$count")
                                                }
                                                else -> ed.say(rcText(rc), true)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "legend") {
                    Text("Algorithm: tap a box to jump to its operator, ALG ▾ shows all 32. Green boxes are carriers (they " +
                        "sound), the orange line is the feedback loop, the bar in a box is its output level. " +
                        "R1..R4 / L1..L4: EG rates and levels; BP LD RD LC RC: keyboard level scaling; RS: rate scaling; " +
                        "AMS: LFO amplitude modulation; KVS: velocity; OL: output level; MODE: ratio / fixed; " +
                        "FC FF: coarse / fine frequency; DET: detune (7 = centre). Random keeps the carriers audible, " +
                        "Random+ changes every parameter. Double-tap a knob for the init value. " +
                        "Live: every knob change goes to the track at once. Store: edits stay here until SEND " +
                        "(top left, next to the menu). F1–F8 are the factory patches (read only); a bank write needs " +
                        "the device stopped; \"Use on track\" sets the track's PTCH to the slot, as choosing it on the FM-1.",
                        Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant)
                }
            }
        }
    }

    if (picking) AlgPicker(alg, { ed.set(Fm6.ALG, it); picking = false }) { picking = false }

    confirm?.let { (title, text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun fileName(name: String) = name.trim().ifBlank { "fm6-voice" }
    .replace(Regex("[^A-Za-z0-9._-]+"), "_")

private val TIGHT = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

/**
 * Every item of the page's wrapping rows (labels, chips, buttons, dropdowns) sits centred in a cell this high: chips
 * claim a 48 dp touch height and buttons 32 dp, which left them top-aligned and jumping up and down.
 */
private val ROW_H = 40.dp

/** A short button (32 dp) for the page's rows of actions. */
@Composable
private fun Cb(label: String, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val m = Modifier.height(32.dp)
    Box(Modifier.height(ROW_H), contentAlignment = Alignment.Center) {
        if (filled) Button(onClick, m, enabled, contentPadding = TIGHT) { Text(label, maxLines = 1) }
        else OutlinedButton(onClick, m, enabled, contentPadding = TIGHT) { Text(label, maxLines = 1) }
    }
}

/** One editable voice byte, rendered by ParamControl as a knob / switch / enum dropdown. */
private class Fm6Ctl(val label: String, val i: Int, val fmt: Int = Fmt.INT, val names: List<String> = emptyList()) {
    fun desc(def: Int) = Desc(2, i, fmt, 0, Fm6.max(i), def, label, "", names)
}

/** A named block of a knob row (the LFO, the pitch EG). */
@Composable
private fun Fm6Block(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(title, Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/**
 * The voice in one card: the name with Init / Random on the left, the algorithm drawn in the middle (tap a box to go to
 * that operator), the algorithm picker and FB / TRNSP / OKS on the right.
 */
@Composable
private fun Fm6VoiceCard(v: IntArray, defs: IntArray, ed: Fm6Editor, knob: Dp, diagH: Dp, selected: Int,
                         onOp: (Int) -> Unit, onPick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val alg = v[Fm6.ALG]
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant)
                    .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("NAME", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    BasicTextField(value = Fm6.name(v), onValueChange = { ed.rename(it) }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary), modifier = Modifier.weight(1f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Cb("Init") { ed.reset(); ed.rev++ }
                    Cb("Random", filled = true) { ed.randomize(false) }
                    Cb("Random+", filled = true) { ed.randomize(true) }
                }
            }
            AlgDiagram(alg, Modifier.weight(1.5f).height(diagH), levels = IntArray(Fm6.OPS) { v[Fm6.at(it + 1, "OL")] },
                selected = selected, onOp = onOp)
            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant),
                    verticalAlignment = Alignment.CenterVertically) {
                    StepButton("‹", alg > 0) { ed.set(Fm6.ALG, alg - 1) }
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onPick), contentAlignment = Alignment.Center) {
                        Text("ALG ${alg + 1} ▾", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            fontFamily = SloopFontFamily, maxLines = 1)
                    }
                    StepButton("›", alg < 31) { ed.set(Fm6.ALG, alg + 1) }
                }
                CompositionLocalProvider(LocalKnob provides knob, LocalDenseKnobs provides true) {
                    Row(Modifier.fillMaxWidth()) {
                        listOf(
                            Fm6Ctl("FB", Fm6.FB),
                            Fm6Ctl("TRNSP", Fm6.TRNSP),
                            Fm6Ctl("OKS", Fm6.OKS, Fmt.ONOFF),
                        ).forEach { c ->
                            Box(Modifier.weight(1f)) { ParamControl(c.desc(defs[c.i]), v[c.i]) { ed.set(c.i, it) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(36.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) c else c.copy(alpha = 0.3f))
    }
}

/** All 32 algorithms as small pictures to choose from. */
@Composable
private fun AlgPicker(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column {
                Text("Algorithm", Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LazyVerticalGrid(GridCells.Adaptive(124.dp), contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(32) { a ->
                        val on = a == current
                        val shape = RoundedCornerShape(10.dp)
                        Column(Modifier.clip(shape).background(if (on) cs.primary.copy(alpha = 0.18f) else cs.surfaceVariant)
                            .then(if (on) Modifier.border(2.dp, cs.primary, shape) else Modifier)
                            .clickable { onPick(a) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            AlgDiagram(a, Modifier.fillMaxWidth().height(84.dp))
                            Text("${a + 1}", style = MaterialTheme.typography.labelMedium, fontFamily = SloopFontFamily,
                                color = if (on) cs.primary else cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One operator's card, three rows of eight knobs: the EG rates and levels; level scaling, rate scaling and sensitivities;
 * output level, mode, frequency and detune with the EG sketched in the free slots. The six sit in rows of [Fm6Screen]'s
 * choosing so they line up exactly.
 */
@Composable
private fun Fm6OpCard(n: Int, v: IntArray, defs: IntArray, ed: Fm6Editor, carrier: Boolean, selected: Boolean,
                      knob: Dp, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    fun f(name: String) = Fm6.at(n, name)
    Card(modifier, border = if (selected) BorderStroke(1.5.dp, cs.secondary) else null) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("OP$n" + if (carrier) " ●" else "", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = if (carrier) cs.primary else cs.onSurface,
                    modifier = Modifier.weight(1f))
                Text(Fm6.freqText(v, n), style = MaterialTheme.typography.labelMedium,
                    fontFamily = SloopFontFamily, color = cs.onSurfaceVariant)
            }
            Fm6Knobs(listOf(
                Fm6Ctl("R1", f("R1")), Fm6Ctl("R2", f("R2")), Fm6Ctl("R3", f("R3")), Fm6Ctl("R4", f("R4")),
                Fm6Ctl("L1", f("L1")), Fm6Ctl("L2", f("L2")), Fm6Ctl("L3", f("L3")), Fm6Ctl("L4", f("L4")),
                Fm6Ctl("BP", f("BP")), Fm6Ctl("LD", f("LD")), Fm6Ctl("RD", f("RD")),
                Fm6Ctl("LC", f("LC"), Fmt.ENUM, Fm6.CURVES), Fm6Ctl("RC", f("RC"), Fmt.ENUM, Fm6.CURVES),
                Fm6Ctl("RS", f("RS")), Fm6Ctl("AMS", f("AMS")), Fm6Ctl("KVS", f("KVS")),
                Fm6Ctl("OL", f("OL")), Fm6Ctl("MODE", f("MODE"), Fmt.ENUM, listOf("RATIO", "FIXED")),
                Fm6Ctl("FC", f("FC")), Fm6Ctl("FF", f("FF")), Fm6Ctl("DET", f("DET")),
            ), 8, knob, v, defs, ed) {
                EgPreview(IntArray(4) { v[f("R${it + 1}")] }, IntArray(4) { v[f("L${it + 1}")] },
                    Modifier.fillMaxWidth().height(knob + 10.dp).padding(horizontal = 6.dp, vertical = 4.dp))
            }
        }
    }
}

/**
 * Controls in rows of [slots] equal slots (the last row's free slots hold [tail]) with knobs of size [knob], dense
 * (the value inside the knob); a double tap on a knob restores the init voice's value.
 */
@Composable
private fun Fm6Knobs(items: List<Fm6Ctl>, slots: Int, knob: Dp, v: IntArray, defs: IntArray, ed: Fm6Editor,
                     tail: (@Composable () -> Unit)? = null) {
    val rows = items.chunked(slots)
    CompositionLocalProvider(LocalKnob provides knob, LocalDenseKnobs provides true) {
        rows.forEachIndexed { k, row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { c ->
                    Box(Modifier.weight(1f)) {
                        ParamControl(c.desc(defs[c.i]), v[c.i]) { ed.set(c.i, it) }
                    }
                }
                val free = slots - row.size
                if (free > 0) Box(Modifier.weight(free.toFloat()), contentAlignment = Alignment.Center) {
                    if (tail != null && k == rows.lastIndex) tail()
                }
            }
        }
    }
}

/** The operator's envelope: level L4 -> L1 -> L2 -> L3 (held) -> L4, drawn from its four rates (a sketch, not to scale). */
@Composable
private fun EgPreview(rates: IntArray, levels: IntArray, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Canvas(modifier) {
        val t = FloatArray(4) { (100 - rates[it]) / 100f + 0.04f }
        val sustain = 0.5f
        val total = t[0] + t[1] + t[2] + sustain + t[3]
        fun px(x: Float) = x / total * size.width
        fun py(l: Int) = size.height * (1f - l / 99f)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        val p = Path()
        var x = 0f
        p.moveTo(0f, py(levels[3]))
        x += t[0]; p.lineTo(px(x), py(levels[0]))
        x += t[1]; p.lineTo(px(x), py(levels[1]))
        x += t[2]; p.lineTo(px(x), py(levels[2]))
        x += sustain; p.lineTo(px(x), py(levels[2]))
        x += t[3]; p.lineTo(px(x), py(levels[3]))
        drawPath(p, color, style = Stroke(2.dp.toPx()))
    }
}
