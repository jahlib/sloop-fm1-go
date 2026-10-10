// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.ui.Alignment
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
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import com.sloop.go.proto.Desc

/**
 * A page inside a group: a title, a scope (0 = P_*, 1 = G_*) and the parameter ids it shows.
 * [trackIds] are ids in the page that are track parameters (P_*) although the page's scope is global.
 */
private data class Page(val title: String, val scope: Int, val ids: List<Int>, val trackIds: Set<Int> = emptySet())

private const val P_STRUM = 51  // SLOOP 2.4: chord strum (ms per note) and voice leading, after TFLT
private const val P_VLEAD = 52
private const val G_DRDLY = 32  // SLOOP 2.5: the drums' delay send (absent on 2.4: gdesc has no entry)
private const val P_TFLT = 50   // SLOOP 2.4: the track's filter; absent on older firmware (pe0 <= 50)
private data class Group(val title: String, val pages: List<Page>)

private val PINNED = setOf("Global", "Master")

private fun layout(pe0: Int): List<Group> = listOf(
    Group("Global", listOf(Page("GLOBAL", 1, listOf(0, 1, 2, 3)))),
    Group("Master", listOf(
        if (pe0 > P_TFLT) Page("MASTER", 1, listOf(27, 28, 29, P_TFLT), setOf(P_TFLT))
        else Page("MASTER", 1, listOf(27, 28, 29)),
    )),
    Group("Envelope", listOf(Page("ENV", 0, listOf(1, 2, 3, 4)), Page("ENV DEST", 0, listOf(5, 6, 7, 8)))),
    Group("LFO", listOf(Page("LFO", 0, listOf(9, 10, 11, 12)), Page("LFO DEST", 0, listOf(13, 14, 15, 16)))),
    Group("Engine edit", listOf(
        Page("EDIT 1", 0, listOf(pe0, pe0 + 1, pe0 + 2, pe0 + 3)),
        Page("EDIT 2", 0, listOf(pe0 + 4, pe0 + 5, pe0 + 6, pe0 + 7)),
    )),
    Group("Voice", listOf(
        Page("LEVEL", 0, listOf(0)),
        Page("VOICE", 0, listOf(37, 38, 41, 42)),
        Page("VOICE 2", 0, listOf(43, 44, 39, 40)),
    )),
    Group("FX", listOf(
        Page("FX", 0, listOf(33, 34, 35, 36)),
        Page("SLICER", 0, listOf(45, 46, 47, 48)),
        Page("DELAY", 1, listOf(4, 5, 6, 7)),
        Page("REVERB / CHORUS", 1, listOf(8, 9, 10, 11)),
    )),
    Group("Scale", listOf(
        Page("SEL", 0, listOf(25, 26, 27, 49)),
        Page("SEL 2", 0, if (pe0 > P_VLEAD) listOf(28, P_STRUM, P_VLEAD) else listOf(28)),
    )),
    Group("Arp", listOf(Page("ARP", 0, listOf(17, 18, 19, 20)), Page("ARP 2", 0, listOf(21, 22, 23, 24)))),
    Group("Pattern", listOf(Page("PATTERN", 0, listOf(29, 30, 31, 32)))),
    Group("Drums", listOf(Page("DRUMS", 1, listOf(24, 25, 26, G_DRDLY)))),
)

@Composable
fun ParamsScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit) {
    if (state.link != Link.READY || state.info == null || state.dump == null) {
        NotReady("Sound", state, onDevice)
        return
    }
    val info = state.info
    var picker by remember(state.selectedTrack) { mutableStateOf(false) }
    if (picker) PresetPicker(vm, state) { picker = false }

    var groups by remember(state.selectedTrack) { mutableStateOf(layout(info.pe0)) }
    var collapsed by remember(state.selectedTrack) { mutableStateOf(setOf<String>()) }
    val gridState = rememberLazyStaggeredGridState()
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    fun drop() {
        if (dragIndex < 0) { dragOffset = Offset.Zero; return }
        val info2 = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragIndex + 2 }
        if (info2 != null) {
            val cx = info2.offset.x + dragOffset.x + info2.size.width / 2
            val cy = info2.offset.y + dragOffset.y + info2.size.height / 2
            gridState.layoutInfo.visibleItemsInfo.firstOrNull {
                cx >= it.offset.x && cx < it.offset.x + it.size.width &&
                    cy >= it.offset.y && cy < it.offset.y + it.size.height
            }?.index?.let { target ->
                val pinnedCount = groups.count { it.title in PINNED }
                val t = (target - 2).coerceIn(pinnedCount, groups.size - 1)
                if (t != dragIndex) {
                    val g = groups.toMutableList()
                    g.add(t, g.removeAt(dragIndex))
                    groups = g
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
            ScreenHeader("Sound · Track ${state.selectedTrack + 1}", state, NAV_INSET)
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
                    val pages = group.pages.mapNotNull { page ->
                        val rows = page.ids.mapNotNull { id ->
                            val sc = if (id in page.trackIds) 0 else page.scope
                            val d0 = descOf(state, sc, id) ?: return@mapNotNull null
                            if (!shouldShow(d0)) return@mapNotNull null
                            Triple(sc, id, if (sc == 0 && id == P_TFLT && page.title == "MASTER") d0.copy(label = "TFLT") else d0)
                        }
                        if (rows.isEmpty()) null else page.title to rows
                    }
                    pages.forEachIndexed { pi, (_, rows) ->
                        if (pi > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
                        rows.chunked(4).forEach { groupRows ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                groupRows.forEach { (scope, id, d) ->
                                    Box(Modifier.weight(1f)) {
                                        val value = if (scope == 0) state.paramValue(id) else state.globalValue(id)
                                        if (scope == 1 && id == 0) TextParamControl(d, value) { v ->
                                            vm.controller.setParam(scope, id, v)
                                        } else ParamControl(d, value) { v -> vm.controller.setParam(scope, id, v) }
                                    }
                                }
                                repeat(4 - groupRows.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun descOf(state: DeviceState, scope: Int, id: Int): Desc? =
    if (scope == 0) state.pdesc.getOrNull(id) else state.gdesc.getOrNull(id)

/** Hide fixed (min==max) non-interactive params and action placeholders. */
private fun shouldShow(d: Desc): Boolean {
    if (d.label.isBlank()) return false
    if (d.label in SKIP) return false
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
