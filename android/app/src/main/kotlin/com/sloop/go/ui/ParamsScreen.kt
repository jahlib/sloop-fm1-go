// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.LfoTarget
import com.sloop.go.device.Link
import com.sloop.go.proto.Desc
import com.sloop.go.proto.Fmt

/**
 * A page inside a group: a title, a scope (0 = P_*, 1 = G_*) and the parameter ids it shows.
 * [trackIds] are ids in the page that are track parameters (P_*) although the page's scope is global.
 */
private data class Page(
    val title: String, val scope: Int, val ids: List<Int>, val trackIds: Set<Int> = emptySet(),
    val onlyEngine: Int? = null,
)

private const val P_STRUM = 51  // SLOOP 2.4: chord strum (ms per note) and voice leading, after TFLT
private const val P_VLEAD = 52
private const val G_DRDLY = 32  // SLOOP 2.5: the drums' delay send (absent on 2.4: gdesc has no entry)
private const val P_TFLT = 50   // SLOOP 2.4: the track's filter; absent on older firmware (pe0 <= 50)
private const val PE0_26 = 63   // SLOOP 2.6: ten track parameters (INSERT 53..57, FTYPE 58, ESYNC 59, LFO 2 60..62) came before P_E0
private const val P_ESYNC = 59  // ENV SYNC: ATK (1), DEC (2) and REL (4) are note values of the tempo
private const val P_LSYNC = 60  // LFO 2 SYNC: its label is also a skipped global's
private val ESYNC_NAMES = arrayOf(
    "0", "1/64T", "1/64", "1/32T", "1/64D", "1/32", "1/16T", "1/32D", "1/16", "1/8T", "1/16D", "1/8", "1/4T", "1/8D",
    "1/4", "1/2T", "1/4D", "1/2", "1/1T", "1/2D", "1/1", "1/1D", "2BAR", "3BAR", "4BAR",
)
private data class Group(val title: String, val pages: List<Page>)

private val PINNED = setOf("Global", "Master")
private val RARE = setOf("MIDI", "Texture")

private fun applyOrder(groups: List<Group>, saved: String?): List<Group> {
    val order = saved?.split(",") ?: return groups
    val (pinned, rest) = groups.partition { it.title in PINNED }
    return pinned + rest.sortedBy { order.indexOf(it.title).let { i -> if (i < 0) Int.MAX_VALUE else i } }
}

private fun layout(pe0: Int): List<Group> = listOf(
    Group("Global", listOf(Page("GLOBAL", 1, listOf(0, 1, 2, 3)))),
    Group("Master", listOf(
        if (pe0 > P_TFLT) Page("MASTER", 1, listOf(27, 28, 29, P_TFLT), setOf(P_TFLT))
        else Page("MASTER", 1, listOf(27, 28, 29)),
    )),
    Group("Envelope", listOfNotNull(
        Page("ENV", 0, listOf(1, 2, 3, 4)), Page("ENV DEST", 0, listOf(5, 6, 7, 8)),
        if (pe0 >= PE0_26) Page("ENV SYNC", 0, listOf(P_ESYNC)) else null,
    )),
    Group("LFO", listOfNotNull(
        Page("LFO", 0, listOf(9, 10, 11, 12)),
        if (pe0 >= PE0_26) Page("LFO 2", 0, listOf(P_LSYNC, 61, 62)) else null,
        Page("LFO DEST", 0, listOf(13, 14, 15, 16)),
    )),
    Group("Engine edit", listOfNotNull(
        Page("EDIT 1", 0, listOf(pe0, pe0 + 1, pe0 + 2, pe0 + 3)),
        Page("EDIT 2", 0, listOf(pe0 + 4, pe0 + 5, pe0 + 6, pe0 + 7)),
        if (pe0 >= PE0_26) Page("FILTER", 0, listOf(58), onlyEngine = 0) else null,   // ANALOG's filter TYPE
    )),
    Group("Voice", listOf(
        Page("LEVEL", 0, listOf(0)),
        Page("VOICE", 0, listOf(37, 38, 41, 42)),
        Page("VOICE 2", 0, listOf(43, 44, 39, 40)),
    )),
    Group("FX", listOfNotNull(
        Page("FX", 0, listOf(33, 34, 35, 36)),
        Page("SLICER", 0, listOf(45, 46, 47, 48)),
        Page("DELAY", 1, listOf(4, 5, 6, 7)),
        Page("REVERB / CHORUS", 1, listOf(8, 9, 10, 11)),
        if (pe0 >= PE0_26) Page("INSERT", 0, listOf(53, 54, 55, 56)) else null,
        if (pe0 >= PE0_26) Page("INSERT 2", 0, listOf(57)) else null,
    )),
    Group("Scale", listOf(
        Page("SEL", 0, listOf(25, 26, 27, 49)),
        Page("SEL 2", 0, if (pe0 > P_VLEAD) listOf(28, P_STRUM, P_VLEAD) else listOf(28)),
    )),
    Group("Arp", listOf(Page("ARP", 0, listOf(17, 18, 19, 20)), Page("ARP 2", 0, listOf(21, 22, 23, 24)))),
    Group("Pattern", listOf(Page("PATTERN", 0, listOf(29, 30, 31, 32)))),
    Group("Drums", listOf(Page("DRUMS", 1, listOf(24, 25, 26, G_DRDLY)))),
) + (if (pe0 >= PE0_26) listOf(
        // GLO > MIDI: each track's channel (the drums' CH4 is G_DRCH, 24), OTHER and BEND; IN / OUT are the FM-1's own
        Group("MIDI", listOf(Page("MIDI", 1, listOf(33, 34, 35, 24)), Page("MIDI 2", 1, listOf(36, 44)),
            Page("MIDI 3", 1, listOf(45, 46, 47, 48)))),
        Group("Texture", listOf(Page("TEXTURE", 1, listOf(37, 38, 39, 40)), Page("TEXTURE 2", 1, listOf(41, 42, 43)))),
    ) else emptyList())

@Composable
fun ParamsScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit) {
    if (state.link != Link.READY || state.info == null || state.dump == null) {
        NotReady("Sound", state, onDevice)
        return
    }
    val info = state.info
    var picker by remember(state.selectedTrack) { mutableStateOf(false) }
    if (picker) PresetPicker(vm, state) { picker = false }

    val prefs = LocalContext.current.getSharedPreferences("sound_layout", 0)
    val addFav = rememberFavAdder()
    var groups by remember(info.pe0) { mutableStateOf(applyOrder(layout(info.pe0), prefs.getString("order", null))) }
    var collapsed by remember { mutableStateOf(prefs.getStringSet("collapsed", null) ?: RARE) }
    val gridState = rememberLazyStaggeredGridState()
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val lfoState by vm.lfo.state.collectAsState()
    var assign by remember { mutableIntStateOf(-1) }               // the LFO whose amounts the knobs are setting, -1 none
    var pendingRemove by remember { mutableStateOf<LfoTarget?>(null) }
    LaunchedEffect(pendingRemove) { if (pendingRemove != null) { kotlinx.coroutines.delay(4000); pendingRemove = null } }
    LaunchedEffect(assign) { pendingRemove = null }
    val frame = remember { mutableStateOf(0L) }                    // drives the LFO scopes and knob rings, read while drawing
    LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameNanos { frame.value = it } }

    fun drop() {
        val lead = if (assign >= 0) 3 else 2      // header, [LFO settings], sound card come before the groups
        if (dragIndex < 0) { dragOffset = Offset.Zero; return }
        val info2 = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragIndex + lead }
        if (info2 != null) {
            val cx = info2.offset.x + dragOffset.x + info2.size.width / 2
            val cy = info2.offset.y + dragOffset.y + info2.size.height / 2
            gridState.layoutInfo.visibleItemsInfo.firstOrNull {
                cx >= it.offset.x && cx < it.offset.x + it.size.width &&
                    cy >= it.offset.y && cy < it.offset.y + it.size.height
            }?.index?.let { target ->
                val pinnedCount = groups.count { it.title in PINNED }
                val t = (target - lead).coerceIn(pinnedCount, groups.size - 1)
                if (t != dragIndex) {
                    val g = groups.toMutableList()
                    g.add(t, g.removeAt(dragIndex))
                    groups = g
                    prefs.edit().putString("order", g.joinToString(",") { it.title }).apply()
                }
            }
        }
        dragIndex = -1; dragOffset = Offset.Zero
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine) {
            ScreenHeader("Sound · Track ${state.selectedTrack + 1}", state, NAV_INSET) {
                LfoChips(vm, assign, { assign = it }, frame)
            }
        }

        if (assign >= 0) item(key = "lfos", span = StaggeredGridItemSpan.FullLine) {
            LfoPanel(vm, assign, frame)
        }

        // Track + preset/kit selectors: two dropdown buttons on one row.
        item(key = "sound", span = StaggeredGridItemSpan.FullLine) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    var trkMenu by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { trkMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Track",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${state.selectedTrack + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = trkMenu, onDismissRequest = { trkMenu = false }) {
                            val ntrk = state.tracks?.ntrk ?: info.ntrk
                            for (i in 0 until ntrk) {
                                val label = state.tracks?.tracks?.getOrNull(i)?.let { e ->
                                    "TRACK ${i + 1} · " +
                                        (if (e.engine >= info.nengines) "DRUM" else info.engines.getOrNull(e.engine) ?: "?")
                                } ?: "TRACK ${i + 1}"
                                DropdownMenuItem(
                                    text = {
                                        Text(label, fontWeight = if (i == state.selectedTrack) FontWeight.Bold else FontWeight.Normal)
                                    },
                                    onClick = { trkMenu = false; vm.controller.selectTrack(i) },
                                )
                            }
                        }
                    }
                    run {
                        val fm6 = !state.isDrum && state.dump?.engine == state.fm6Engine
                        val name = if (state.isDrum) {
                            state.pdesc.getOrNull(info.pe0)?.names?.getOrNull(state.paramValue(info.pe0)) ?: "Kit"
                        } else if (fm6) {
                            state.pdesc.getOrNull(info.pe0 + 7)?.names
                                ?.getOrNull(state.paramValue(info.pe0 + 7))
                                ?: "Patch ${state.paramValue(info.pe0 + 7) + 1}"
                        } else state.dump?.let { d ->
                            state.presetNames[d.engine]?.getOrNull(d.preset) ?: "Preset ${d.preset + 1}"
                        } ?: "Preset"
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(
                                    if (state.isDrum) Icons.Filled.GridView else Icons.Filled.MusicNote,
                                    contentDescription = null, modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (state.isDrum) "Kit" else if (fm6) "Patch" else "Preset",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }

        items(count = groups.size, key = { groups[it].title }) { index ->
            val group = groups[index]
            val closed = group.title in collapsed
            Card(Modifier.graphicsLayer {
                if (index == dragIndex) {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = 1.03f; scaleY = 1.03f; alpha = 0.85f
                }
            }) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().pointerInput(group.title) {
                        detectTapGestures(onTap = {
                            collapsed = if (group.title in collapsed) collapsed - group.title
                                else collapsed + group.title
                            prefs.edit().putStringSet("collapsed", collapsed).apply()
                        })
                    }.pointerInput(group.title) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (group.title !in PINNED) { dragIndex = index; dragOffset = Offset.Zero }
                            },
                            onDrag = { c, amt -> dragOffset += amt; c.consume() },
                            onDragEnd = { drop() },
                            onDragCancel = { dragIndex = -1; dragOffset = Offset.Zero },
                        )
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Text(group.title, Modifier.weight(1f).padding(start = 14.dp),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (closed) "▸" else "▾", Modifier.padding(end = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(
                            if (group.title in PINNED) Icons.Filled.PushPin else Icons.Filled.DragHandle,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 10.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (closed) return@Card
                    val v26 = info.pe0 >= PE0_26
                    val pages = group.pages.mapNotNull { page ->
                        if (page.onlyEngine != null && state.dump?.engine != page.onlyEngine) return@mapNotNull null
                        val rows = page.ids.mapNotNull { id ->
                            val sc = if (id in page.trackIds) 0 else page.scope
                            val d0 = descOf(state, sc, id) ?: return@mapNotNull null
                            if (!shouldShow(d0, v26 && sc == 0 && id == P_LSYNC)) return@mapNotNull null
                            Triple(sc, id, if (sc == 0 && id == P_TFLT && page.title == "MASTER") d0.copy(label = "TFLT") else d0)
                        }
                        if (rows.isEmpty()) null else page.title to rows
                    }
                    pages.forEachIndexed { pi, (title, rows) ->
                        if (pi > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
                        if (pages.size > 1) Text(title, Modifier.padding(start = 14.dp, top = 2.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        rows.chunked(4).forEach { groupRows ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                groupRows.forEach { (scope, id, d) ->
                                    val value = if (scope == 0) state.paramValue(id) else state.globalValue(id)
                                    val target = LfoTarget(if (scope == 0) state.selectedTrack else -1, scope, id)
                                    val eligible = !isToggle(d) && !isEnum(d) && !(scope == 1 && id == 0)
                                    Box(Modifier.weight(1f).then(if (assign >= 0 && !eligible) Modifier.alpha(0.35f) else Modifier)) {
                                        if (assign >= 0 && eligible) {
                                            val pct = lfoState.amounts[assign][target]
                                            LfoAmountKnob(d, pct ?: 0, LFO_COLORS[assign], pct != null,
                                                onPct = { p -> vm.lfo.setAmount(assign, target, p, value) },
                                                onLongPress = { if (pct != null) pendingRemove = target })
                                            if (pct != null && pendingRemove == target) Box(
                                                Modifier.matchParentSize().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f))
                                                    .clickable { pendingRemove = null; vm.lfo.remove(assign, target) },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "Remove LFO from this knob",
                                                    modifier = Modifier.padding(4.dp).fillMaxSize(),
                                                    tint = MaterialTheme.colorScheme.onErrorContainer)
                                            }
                                        } else if (scope == 1 && id == 0) TextParamControl(d, value) { v ->
                                            vm.setParam(scope, id, v)
                                        } else {
                                            val shown = lfoState.bases[target] ?: value
                                            val swing = lfoState.amounts.sumOf { kotlin.math.abs(it[target] ?: 0) } / 200f
                                            val driver = lfoState.amounts.indexOfFirst { target in it }
                                            val knobLfo = if (driver < 0 || !eligible) null else KnobLfo(swing, LFO_COLORS[driver]) {
                                                frame.value
                                                if (swing <= 0f) 0f
                                                else (vm.lfo.modAt(target, System.nanoTime() / 1e9) * 0.5f / swing).coerceIn(-1f, 1f)
                                            }
                                            ParamControl(d, shown, text = esyncText(state, v26, scope, id, shown),
                                                onLongPress = { addFav(scope, id) }, lfo = knobLfo) { v ->
                                                vm.setParam(scope, id, v)
                                            }
                                        }
                                    }
                                }
                                repeat(4 - groupRows.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        if (state.isDrum && info.proto >= 12) item(key = "drum-sound", span = StaggeredGridItemSpan.FullLine) {
            DrumSoundCard(vm)
        }
    }
}

/** SLOOP 2.7: every drum sound's TUNE and DECAY (the DRUMS screen's SOUND page), through LIVE op 12. */
@Composable
private fun DrumSoundCard(vm: SloopViewModel) {
    val lv by vm.controller.transport.collectAsState()
    var lane by remember { mutableIntStateOf(0) }
    val tune = lv?.drumTune?.getOrNull(lane) ?: 0
    val decay = lv?.drumDecay?.getOrNull(lane) ?: 0
    val tuneDesc = remember { Desc(0, 900, Fmt.SEMI, -8, 7, 0, "TUNE", "", emptyList()) }
    val decDesc = remember { Desc(0, 901, Fmt.INT, -4, 3, 0, "DECAY", "", emptyList()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text("DRUM SOUND", Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DRUM_LANES.forEachIndexed { i, n ->
                    FilterChip(selected = i == lane, onClick = { lane = i }, label = { Text(n) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Box(Modifier.weight(1f)) {
                    ParamControl(tuneDesc, tune) { v -> vm.controller.setDrumSound(lane, v, decay) }
                }
                Box(Modifier.weight(1f)) {
                    ParamControl(decDesc, decay) { v -> vm.controller.setDrumSound(lane, tune, v) }
                }
                Box(Modifier.weight(2f))
            }
        }
    }
}

private fun descOf(state: DeviceState, scope: Int, id: Int): Desc? =
    if (scope == 0) state.pdesc.getOrNull(id) else state.gdesc.getOrNull(id)

/** ENV SYNC on (2.6): ATK / DEC / REL hold note values, not milliseconds (firmware params.c esync_idx). */
private fun esyncText(state: DeviceState, v26: Boolean, scope: Int, id: Int, value: Int): String? =
    if (v26 && scope == 0 && (id == 1 || id == 2 || id == 4) && state.paramValue(P_ESYNC) != 0)
        ESYNC_NAMES[(value and 127) * ESYNC_NAMES.size shr 7]
    else null

/** Hide fixed (min==max) non-interactive params and action placeholders; [keep]: shown whatever its label. */
private fun shouldShow(d: Desc, keep: Boolean = false): Boolean {
    if (d.label.isBlank()) return false
    if (d.label in SKIP && !keep) return false
    if (d.min == d.max && d.names.isEmpty()) return false
    return true
}

private val SKIP = setOf("SLOT", "NAME", "LOAD", "SAVE", "SET", "CLRSQ", "INIT", "MIDI", "SYNC", "ROUT", "CPU", "NEW", "ENG")

@Composable
fun NotReady(title: String, state: DeviceState, onDevice: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenHeader(title, state, NAV_INSET) }
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    if (state.link == Link.CONNECTING || state.link == Link.LOADING) {
                        Text("Connecting to FM-1…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("No device connected. Connect the FM-1 with USB-C and choose it in Device settings.",
                            style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onDevice, modifier = Modifier.padding(top = 12.dp)) {
                            Text("Find MIDI device →")
                        }
                    }
                }
            }
        }
    }
}
