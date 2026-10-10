// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
@file:OptIn(ExperimentalLayoutApi::class)

package com.sloop.go.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import com.sloop.go.proto.Desc
import com.sloop.go.proto.Fmt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import com.sloop.go.proto.Dsyn
import com.sloop.go.proto.DsynList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private const val HEAD = 100   // pending marker for the kit's name / crush

/** The SYN kit being edited; lives in the ViewModel so it survives tab switches. */
class DrumSynthEditor {
    var k by mutableIntStateOf(0)
    var list by mutableStateOf<DsynList?>(null)
    var kit by mutableStateOf<Dsyn.Kit?>(null)
    var src by mutableStateOf<Dsyn.Kit?>(null)
    var lane by mutableIntStateOf(0)
    var rev by mutableIntStateOf(0)
    var stored by mutableStateOf(true)
    var audition by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)
    var from by mutableIntStateOf(0)
    val pend = LinkedHashSet<Int>()
    var job: Job? = null

    fun say(text: String, error: Boolean = false) { message = text; isError = error }
    fun values(): IntArray = Dsyn.decode(kit!!.sounds[lane])
}

private fun kitJson(kit: Dsyn.Kit) = JSONObject().put("format", "sloop-drumsynth").put("version", 1)
    .put("name", kit.name).put("crush", kit.crush)
    .put("sounds", JSONArray(kit.sounds.map { s -> JSONArray(s.map { it.toInt() and 255 }) })).toString()

private fun kitFromJson(text: String): Dsyn.Kit {
    val j = JSONObject(text)
    require(j.optString("format") == "sloop-drumsynth") { "not a SLOOP drum synth kit" }
    val a = j.getJSONArray("sounds")
    require(a.length() == Dsyn.LANES) { "not a SLOOP drum synth kit" }
    val sounds = Array(Dsyn.LANES) { l ->
        val s = a.getJSONArray(l)
        require(s.length() == Dsyn.SIZE) { "not a SLOOP drum synth kit" }
        Dsyn.encode(Dsyn.decode(ByteArray(Dsyn.SIZE) { s.getInt(it).toByte() }))
    }
    return Dsyn.Kit(j.optString("name", "KIT").filter { it.code in 0x20..0x7E }.take(8), j.optInt("crush") and 255, 0, sounds)
}

/** SLOOP 2.5 drum synth: edit every value of the four synthesised kits SYN1..SYN4, heard at once. */
@Composable
fun DrumSynthScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit, nav: @Composable () -> Unit) {
    if (state.link != Link.READY || state.info == null) {
        NotReady("Drum synth", state, onDevice)
        return
    }
    if (state.info.proto < 10) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { ScreenHeader("Drum synth", state, NAV_INSET) }
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("The synthesised drum kits SYN1–SYN4 need SLOOP 2.5 or newer.", Modifier.padding(16.dp))
                }
            }
        }
        return
    }
    val ed = vm.dsyn
    val ctl = vm.controller
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val kit = ed.kit
    @Suppress("UNUSED_VARIABLE") val rev = ed.rev

    suspend fun flush() {
        val k = ed.k
        for (l in ed.pend.toList()) {
            val kt = ed.kit ?: return
            val rc = if (l == HEAD) ctl.dsynHead(k, kt.name, kt.crush, kt.src) else ctl.dsynSound(k, l, kt.sounds[l])
            if (rc != 0) { ed.say("The FM-1 refused the value (rc $rc)", true); return }
            ed.pend.remove(l)
        }
    }

    fun schedule(play: Boolean) {
        ed.job?.cancel()
        ed.job = vm.launch {
            try {
                delay(if (play) 0 else 80)
                flush()
                if (play && ed.audition) ctl.dsynPlay(ed.k, ed.lane)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { ed.say("Error: ${e.message ?: e.javaClass.simpleName}", true) }
        }
    }

    fun edit(key: Int, v: Int, done: Boolean) {
        if (ed.kit == null) return
        val o = ed.values()
        if (o[key] == v && !done) return
        o[key] = v
        ed.kit!!.sounds[ed.lane] = Dsyn.encode(o)
        ed.pend.add(ed.lane); ed.stored = false; ed.rev++
        schedule(done)
    }

    fun op(block: suspend () -> Unit) {
        if (ed.busy) return
        ed.busy = true
        vm.launch {
            try { block() } catch (e: Exception) { ed.say("Error: ${e.message ?: e.javaClass.simpleName}", true) }
            finally { ed.busy = false }
        }
    }

    suspend fun load(k: Int) {
        ed.job?.join()
        flush()
        val list = ctl.dsynList()
        val got = ctl.dsynGet(Dsyn.USER + k)
        val loaded = got.kit ?: throw IllegalStateException("DSYN_GET rc ${got.rc}")
        ed.k = k; ed.list = list; ed.kit = loaded; ed.stored = list.stored; ed.from = loaded.src
        ed.src = ctl.dsynGet(loaded.src).kit
        ed.rev++
    }

    LaunchedEffect(state.link) { if (ed.kit == null) op { load(ed.k) } }

    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = pendingExport; pendingExport = null
        if (uri != null && bytes != null) try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ed.say("Kit saved")
        } catch (e: Exception) { ed.say("Save failed: ${e.message}", true) }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: ""
            val file = kitFromJson(text)
            confirm = Triple("Open kit file?", "\"${file.name}\" will replace SYN${ed.k + 1} (not stored until you press Store).") {
                op {
                    val kt = ed.kit ?: return@op
                    kt.name = file.name; kt.crush = file.crush
                    for (l in 0 until Dsyn.LANES) { kt.sounds[l] = file.sounds[l]; ed.pend.add(l) }
                    ed.pend.add(HEAD); ed.stored = false; ed.rev++
                    flush()
                    ed.say("Opened ${file.name}")
                }
            }
        } catch (e: Exception) { ed.say("Not a SLOOP drum synth kit file", true) }
    }

    confirm?.let { (title, text, go) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(title) }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; go() }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }

    val o = if (kit != null) ed.values() else IntArray(Dsyn.NKEYS)

    class Item(val desc: Desc, val value: Int, val text: String, val onChange: (Int) -> Unit, val onFinished: (() -> Unit)?)

    fun knobItem(kn: Dsyn.Knob): Item {
        val v = o[kn.key]
        val (txt, unit) = kn.fmt(v, o)
        val names = kn.names
        val toggle = names != null && kn.min == 0 && kn.max == 1
        val discrete = names != null
        val origin = ed.src?.let { Dsyn.decode(it.sounds[ed.lane])[kn.key] } ?: v   // double tap puts it back
        val desc = Desc(2, kn.key, if (toggle) Fmt.ONOFF else if (discrete) Fmt.ENUM else Fmt.INT, kn.min, kn.max, origin,
            kn.label, "", names ?: emptyList())
        return Item(desc, v, if (unit.isEmpty()) txt else "$txt $unit", { nv -> edit(kn.key, nv, discrete) },
            if (discrete) null else ({ schedule(true) }))
    }

    fun crushItems(): List<Item> {
        val k = kit ?: return emptyList()
        val bits = k.crush and 15
        val sh = (k.crush shr 4) and 15
        fun set(b: Int, h: Int) {
            k.crush = (b and 15) or ((h and 15) shl 4)
            ed.pend.add(HEAD); ed.stored = false; ed.rev++
            schedule(false)
        }
        return listOf(
            Item(Desc(2, 100, Fmt.INT, 0, 15, 0, "CRUSH", "", emptyList()), bits, if (bits != 0) "-$bits bits" else "OFF",
                { set(it, sh) }, { schedule(true) }),
            Item(Desc(2, 101, Fmt.INT, 0, 15, 0, "S&H", "", emptyList()), sh, if (sh != 0) "1/${sh + 1}" else "OFF",
                { set(bits, it) }, { schedule(true) }),
        )
    }

    val tight = PaddingValues(horizontal = 10.dp)
    Column(Modifier.fillMaxSize()) {
        // ---- pinned header: navigation, kit pick, name and the audition controls
        Row(Modifier.fillMaxWidth().background(cs.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            nav()
            var kitMenu by remember { mutableStateOf(false) }
            Box {
                Text("SYN${ed.k + 1} ▾", Modifier.clickable(enabled = !ed.busy) { kitMenu = true }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                    color = cs.primary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                DropdownMenu(expanded = kitMenu, onDismissRequest = { kitMenu = false }) {
                    for (k in 0 until Dsyn.NUSER) DropdownMenuItem(
                        text = { Text("SYN${k + 1}", fontWeight = if (k == ed.k) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { kitMenu = false; if (k != ed.k) op { load(k) } })
                }
            }
            if (kit != null) {
                BasicTextField(value = kit.name, singleLine = true, cursorBrush = SolidColor(cs.primary),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                    onValueChange = {
                        kit.name = it.filter { c -> c.code in 0x20..0x7E }.take(8)
                        ed.pend.add(HEAD); ed.stored = false; ed.rev++
                        schedule(false)
                    },
                    modifier = Modifier.width(140.dp).border(1.dp, cs.outline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp))
                VerticalDivider(Modifier.padding(horizontal = 2.dp).height(24.dp))
                Button(onClick = { vm.launch { runCatching { ctl.dsynPlay(ed.k, ed.lane) } } },
                    modifier = Modifier.height(32.dp), contentPadding = tight) { Text("Play") }
                OutlinedButton(enabled = ed.src != null, modifier = Modifier.height(32.dp), contentPadding = tight, onClick = {
                    val s = ed.src ?: return@OutlinedButton
                    kit.sounds[ed.lane] = s.sounds[ed.lane].copyOf()
                    ed.pend.add(ed.lane); ed.stored = false; ed.rev++
                    schedule(true)
                }) { Text("Reset") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Switch(checked = ed.audition, onCheckedChange = { ed.audition = it })
                    Text("Hear changes", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
            }
        }

        if (ed.busy || ed.message != null) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
            if (ed.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ed.message?.let { Text(it, color = if (ed.isError) cs.error else cs.primary, style = MaterialTheme.typography.bodySmall) }
        }

        // ---- the scrolling middle: store / files first, then the knob blocks of the picked sound
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
        ) {
            if (kit == null && !ed.busy) item(key = "load", span = StaggeredGridItemSpan.FullLine) {
                Button(onClick = { op { load(ed.k) } }) { Text("Load kits") }
            }

            if (kit != null) {
                item(key = "actions", span = StaggeredGridItemSpan.FullLine) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (ed.stored) "Stored on the FM-1" else "Not stored yet: kept in RAM until you press Store",
                                style = MaterialTheme.typography.bodySmall, color = if (ed.stored) cs.onSurfaceVariant else cs.primary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(enabled = !ed.busy, onClick = {
                                    op {
                                        ed.job?.join(); flush()
                                        when (val rc = ctl.dsynStore()) {
                                            0 -> { ed.stored = true; ed.say("The four kits are stored") }
                                            3 -> ed.say("Stop the song first (flash write)", true)
                                            else -> ed.say("Flash error (rc $rc)", true)
                                        }
                                    }
                                }) { Text("Store") }
                                OutlinedButton(enabled = !ed.busy, onClick = {
                                    op {
                                        ed.job?.join(); flush()
                                        ed.say(if (ctl.dsynUse(ed.k)) "The drum track plays SYN${ed.k + 1}" else "SYN${ed.k + 1} is not in the KIT list", false)
                                    }
                                }) { Text("Use on drum track") }
                                OutlinedButton(onClick = {
                                    pendingExport = kitJson(kit).toByteArray()
                                    saver.launch("${kit.name.ifBlank { "kit" }.lowercase().replace(Regex("[^a-z0-9]+"), "-")}.sloopdrums.json")
                                }) { Text("Save file") }
                                OutlinedButton(enabled = !ed.busy, onClick = { opener.launch(arrayOf("*/*")) }) { Text("Open file") }
                            }
                            val names = ed.list?.names.orEmpty()
                            var open by remember { mutableStateOf(false) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text("From ${names.getOrNull(ed.from) ?: "?"}  ▾", maxLines = 1)
                                    }
                                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                        names.forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n) },
                                            onClick = { ed.from = i; open = false }) }
                                    }
                                }
                                Button(enabled = !ed.busy, onClick = {
                                    val i = ed.from
                                    confirm = Triple("Copy kit?", "SYN${ed.k + 1} becomes a copy of ${names.getOrNull(i)}; your edits to it are lost.") {
                                        op {
                                            val rc = ctl.dsynCopy(ed.k, i)
                                            if (rc != 0) { ed.say("Copy failed (rc $rc)", true); return@op }
                                            ed.pend.clear()
                                            load(ed.k)
                                            ed.stored = false
                                            ed.say("Copied ${names.getOrNull(i)}")
                                        }
                                    }
                                }) { Text("Copy") }
                            }
                            Text("Every change plays on the FM-1 at once. Store keeps SYN1–SYN4 in flash with the settings (stop the song first).",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }

                for (g in Dsyn.GROUPS) item(key = "g-${g.title}") {
                    val items = g.knobs.map { knobItem(it) } + if (g.title == "Output") crushItems() else emptyList()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(g.title.uppercase(), Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            items.chunked(4).forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                    row.forEach { it ->
                                        Box(Modifier.weight(1f)) {
                                            ParamControl(it.desc, it.value, it.text, it.onFinished, it.onChange)
                                        }
                                    }
                                    repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- pinned footer: the 16 sounds across the whole width, big squarish touch pads
        if (kit != null) Row(Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Dsyn.LANE_NAMES.forEachIndexed { l, n ->
                val on = l == ed.lane
                Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) cs.primary else cs.surfaceVariant)
                    .clickable {
                        ed.lane = l; ed.rev++
                        vm.launch { runCatching { ctl.dsynPlay(ed.k, l) } }
                    }, contentAlignment = Alignment.Center) {
                    Text(n, Modifier.padding(horizontal = 2.dp), textAlign = TextAlign.Center, maxLines = 2,
                        color = if (on) cs.onPrimary else cs.onSurfaceVariant,
                        fontSize = 10.sp, lineHeight = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}
