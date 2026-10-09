// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
@file:OptIn(ExperimentalLayoutApi::class)

package com.sloop.go.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import com.sloop.go.proto.Desc
import com.sloop.go.proto.Fm6
import com.sloop.go.proto.Fmt
import kotlinx.coroutines.delay

private fun rcText(rc: Int): String = when (rc) {
    1 -> "Bad slot or arguments"
    2 -> "Empty slot or flash error"
    3 -> "Stop the device first (flash write)"
    else -> "Error $rc"
}

/** FM6 patch library + a full six-operator editor (DX7 voices), the app's version of the web editor's FM6 panel. */
@Composable
fun Fm6Screen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit) {
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
    val track = ed.track.takeIf { it in 0 until ntrk }
        ?: state.selectedTrack.takeIf { it in 0 until ntrk } ?: 0
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
        else if (!quiet) ed.say("Sent to track ${track + 1}: ${Fm6.name(ed.voice)}")
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
    val carriers = Fm6.carriers(v[Fm6.ALG])

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine) { ScreenHeader("FM6 patches", state, NAV_INSET) }

        if (ed.busy || ed.message != null) item(key = "msg", span = StaggeredGridItemSpan.FullLine) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                if (ed.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ed.message?.let {
                    Text(it, color = if (ed.isError) cs.error else cs.primary,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---- target and slots side by side, the track card a bit narrower
        item(key = "trackSlots", span = StaggeredGridItemSpan.FullLine) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top) {
            Card(Modifier.weight(0.45f).fillMaxHeight()) {
                Column(Modifier.fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Track", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (t in 0 until ntrk) {
                            val eng = state.trackEngine(t)
                            FilterChip(selected = t == track, onClick = { ed.track = t },
                                label = { Text("${t + 1}" + if (eng == state.fm6Engine) " · FM6"
                                    else info.engines.getOrNull(eng)?.let { " · $it" } ?: "") })
                        }
                    }
                    if (!trackIsFm6) {
                        Text("Track ${track + 1} does not play FM6, so a patch would not sound on it.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Button(enabled = !ed.busy, onClick = {
                            op {
                                ctl.fm6EnableTrack(track)
                                ed.say("Track ${track + 1} now plays FM6")
                                readTrack(true)
                            }
                        }) { Text("Switch track ${track + 1} to FM6") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(enabled = trackIsFm6 && !ed.busy, onClick = { op { readTrack(false) } }) {
                            Text("Read from track")
                        }
                        Button(enabled = trackIsFm6 && !ed.busy, onClick = { op { sendTrack(false) } }) {
                            Text("Send to track")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = ed.live, onCheckedChange = { ed.live = it })
                        Text("  Send while editing", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Card(Modifier.weight(0.55f).fillMaxHeight()) {
                Column(Modifier.fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Slots", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(slotLabel(slot) + "  ▾", maxLines = 1, modifier = Modifier.weight(1f))
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(enabled = !ed.busy, onClick = {
                            op {
                                val r = if (slot < nf) ctl.fm6Get(2, slot) else ctl.fm6Get(1, slot - nf)
                                if (r.rc == 2) ed.say("Empty slot ${slotName(slot)}")
                                else if (r.rc != 0) ed.say(rcText(r.rc), true)
                                else { ed.load(Fm6.unpack(r.packed!!)); ed.say("Loaded ${slotName(slot)}: ${Fm6.name(ed.voice)}") }
                            }
                        }) { Text("Load into editor") }
                        Button(enabled = slot >= nf && !ed.busy, onClick = {
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
                        }) { Text("Store in ${if (slot >= nf) slotName(slot) else "bank"}") }
                        OutlinedButton(enabled = trackIsFm6 && !ed.busy, onClick = {
                            op {
                                ctl.fm6Assign(track, slot)
                                delay(300)
                                readTrack(true)
                                ed.say("Track ${track + 1} plays ${slotName(slot)}")
                            }
                        }) { Text("Use on track ${track + 1}") }
                        OutlinedButton(enabled = slot >= nf && state.fm6?.slots?.getOrNull(slot)?.used == true && !ed.busy,
                            onClick = {
                                confirm = Triple("Erase ${slotName(slot)}?",
                                    "Slot ${slotName(slot)} (${state.fm6?.slots?.getOrNull(slot)?.name}) will be emptied.") {
                                    op {
                                        val rc = ctl.fm6Erase(slot - nf)
                                        if (rc == 0) ed.say("Erased ${slotName(slot)}") else ed.say(rcText(rc), true)
                                    }
                                }
                            }) { Text("Erase") }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("F1–F8 are the factory patches (read only). A bank write needs the device stopped. " +
                        "\"Use on track\" sets the track's PTCH to the slot, as choosing it on the FM-1.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            }
        }

        // ---- files
        item(key = "files", span = StaggeredGridItemSpan.FullLine) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DX7 SysEx files", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(enabled = !ed.busy, onClick = { opener.launch(arrayOf("*/*")) }) { Text("Import from phone") }
                        OutlinedButton(onClick = {
                            pendingExport = Fm6.singleSysex(v)
                            saver.launch(fileName(Fm6.name(v)) + ".syx")
                        }) { Text("Export voice") }
                        OutlinedButton(enabled = !ed.busy, onClick = {
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
                        }) { Text("Export bank") }
                        OutlinedButton(onClick = { ed.reset(); ed.rev++ }) { Text("Init voice") }
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
                        Button(enabled = !ed.busy && count > 0, onClick = {
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
                        }) { Text("Store cartridge in bank (B1–B$count)") }
                    }
                }
            }
        }

        // ---- the voice in one wide row: name on the left, its four knobs on the right
        item(key = "voice", span = StaggeredGridItemSpan.FullLine) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text("VOICE", Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = Fm6.name(v), onValueChange = { ed.rename(it) }, singleLine = true,
                            label = { Text("Name (10 characters)") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                        Text("Carriers: " + carriers.joinToString(" ") { "OP$it" } +
                            " · feedback: OP${Fm6.feedbackOp(v[Fm6.ALG]) ?: "-"}",
                            Modifier.padding(start = 14.dp, top = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Row(Modifier.weight(1f).padding(end = 8.dp)) {
                        listOf(
                            Fm6Ctl("ALG", Fm6.ALG, Fmt.ENUM, List(32) { "${it + 1}" }),
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
        item(key = "lfo") {
            Fm6Block("LFO") {
                Fm6Knobs(listOf(
                    Fm6Ctl("WAVE", Fm6.LFW, Fmt.ENUM, Fm6.LFO_WAVES),
                    Fm6Ctl("SPD", Fm6.LFS),
                    Fm6Ctl("DLY", Fm6.LFD),
                    Fm6Ctl("SYNC", Fm6.LKS, Fmt.ONOFF),
                    Fm6Ctl("PMD", Fm6.LPMD),
                    Fm6Ctl("AMD", Fm6.LAMD),
                    Fm6Ctl("PMS", Fm6.LPMS),
                ), v, defs, ed)
            }
        }
        item(key = "peg") {
            Fm6Block("PITCH EG") {
                Fm6Knobs(List(8) { Fm6Ctl(if (it < 4) "R${it + 1}" else "L${it - 3}", Fm6.PR1 + it) }, v, defs, ed)
            }
        }

        // ---- the six operators in order: OP1 OP2 / OP3 OP4 / OP5 OP6, two equal cards a row
        for (pair in 0 until Fm6.OPS / 2) item(key = "opRow${pair * 2 + 1}", span = StaggeredGridItemSpan.FullLine) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Fm6OpCard(pair * 2 + 1, v, defs, ed, carriers, Modifier.weight(1f))
                Fm6OpCard(pair * 2 + 2, v, defs, ed, carriers, Modifier.weight(1f))
            }
        }

        item(key = "legend", span = StaggeredGridItemSpan.FullLine) {
            Text("R1..R4 / L1..L4: EG rates and levels; BP LD RD LC RC: keyboard level scaling; RS: rate scaling; " +
                "AMS: LFO amplitude modulation; KVS: velocity; OL: output level; MODE: ratio / fixed; " +
                "FC FF: coarse / fine frequency; DET: detune (7 = centre). ● = carrier. " +
                "Double-tap a knob for the init value.",
                Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant)
        }
    }

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

/** One editable voice byte, rendered by ParamControl as a knob / switch / enum dropdown. */
private class Fm6Ctl(val label: String, val i: Int, val fmt: Int = Fmt.INT, val names: List<String> = emptyList()) {
    fun desc(def: Int) = Desc(2, i, fmt, 0, Fm6.max(i), def, label, "", names)
}

/** A named card in the 2-column grid, like a Sound group block. */
@Composable
private fun Fm6Block(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(title, Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** One operator's card; the six sit in three fixed two-column rows so they line up exactly. */
@Composable
private fun Fm6OpCard(n: Int, v: IntArray, defs: IntArray, ed: Fm6Editor, carriers: List<Int>, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    fun f(name: String) = Fm6.at(n, name)
    Card(modifier) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("OP$n" + if (n in carriers) " ●" else "", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = if (n in carriers) cs.primary else cs.onSurface,
                    modifier = Modifier.weight(1f))
                Text(Fm6.freqText(v, n), style = MaterialTheme.typography.labelMedium,
                    fontFamily = SloopFontFamily, color = cs.onSurfaceVariant)
            }
            EgPreview(IntArray(4) { v[f("R${it + 1}")] }, IntArray(4) { v[f("L${it + 1}")] },
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp))
            Fm6Knobs(listOf(
                Fm6Ctl("R1", f("R1")), Fm6Ctl("R2", f("R2")), Fm6Ctl("R3", f("R3")), Fm6Ctl("R4", f("R4")),
                Fm6Ctl("L1", f("L1")), Fm6Ctl("L2", f("L2")), Fm6Ctl("L3", f("L3")), Fm6Ctl("L4", f("L4")),
                Fm6Ctl("BP", f("BP")), Fm6Ctl("LD", f("LD")), Fm6Ctl("RD", f("RD")), Fm6Ctl("RS", f("RS")),
                Fm6Ctl("LC", f("LC"), Fmt.ENUM, Fm6.CURVES), Fm6Ctl("RC", f("RC"), Fmt.ENUM, Fm6.CURVES),
                Fm6Ctl("AMS", f("AMS")), Fm6Ctl("KVS", f("KVS")),
                Fm6Ctl("OL", f("OL")), Fm6Ctl("MODE", f("MODE"), Fmt.ENUM, listOf("RATIO", "FIXED")),
                Fm6Ctl("FC", f("FC")), Fm6Ctl("FF", f("FF")),
                Fm6Ctl("DET", f("DET")),
            ), v, defs, ed)
        }
    }
}

/** Controls in rows of four; a double tap on a knob restores the init voice's value. */
@Composable
private fun Fm6Knobs(items: List<Fm6Ctl>, v: IntArray, defs: IntArray, ed: Fm6Editor) {
    items.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            row.forEach { c ->
                Box(Modifier.weight(1f)) {
                    ParamControl(c.desc(defs[c.i]), v[c.i]) { ed.set(c.i, it) }
                }
            }
            repeat(4 - row.size) { Box(Modifier.weight(1f)) }
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
