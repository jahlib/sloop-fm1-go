// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.sloop.go.store.Clip
import com.sloop.go.store.ClipKind
import com.sloop.go.store.Saved

private val THUMB_W = 96.dp

/**
 * A little picture of a saved pattern. Drums: a row per used lane, a column per step, every fourth step shaded like
 * the grid. Piano roll: the notes as bars between the lowest and highest pitch.
 */
@Composable
fun ClipThumb(clip: Clip) {
    val on = MaterialTheme.colorScheme.primary
    val even = MaterialTheme.colorScheme.surfaceVariant
    val odd = MaterialTheme.colorScheme.surface
    val cols = clip.length.coerceIn(1, 64)
    if (clip.kind == ClipKind.DRUM) {
        val lanes = clip.notes.map { it.pitch }.distinct().sorted().ifEmpty { listOf(0) }
        val rowH = 5.dp
        Canvas(Modifier.size(THUMB_W, rowH * lanes.size)) {
            val cw = size.width / cols
            val rh = rowH.toPx()
            val hits = clip.notes.map { it.start to lanes.indexOf(it.pitch) }.toSet()
            lanes.indices.forEach { r ->
                for (s in 0 until cols) {
                    val color = if ((s to r) in hits) on else if ((s / 4) % 2 == 0) even else odd
                    drawRoundRect(color, Offset(s * cw + cw * 0.08f, r * rh + rh * 0.1f),
                        Size(cw * 0.84f, rh * 0.8f), CornerRadius(rh * 0.2f))
                }
            }
        }
    } else {
        val lo = clip.notes.minOfOrNull { it.pitch } ?: 48
        val hi = clip.notes.maxOfOrNull { it.pitch } ?: 60
        val rows = maxOf(hi - lo + 1, 8)
        val h = 40.dp
        Canvas(Modifier.size(THUMB_W, h)) {
            val cw = size.width / cols
            val rh = size.height / rows
            for (s in 0 until cols step 4) drawRect(if ((s / 4) % 2 == 0) even else odd,
                Offset(s * cw, 0f), Size(cw * 4, size.height))
            for (n in clip.notes) {
                val y = (rows - 1 - (n.pitch - lo) - (rows - (hi - lo + 1)) / 2) * rh
                drawRoundRect(on, Offset(n.start * cw + cw * 0.06f, y + rh * 0.1f),
                    Size(n.len * cw - cw * 0.12f, rh * 0.8f), CornerRadius(rh * 0.25f))
            }
        }
    }
}

fun clipInfo(c: Clip) = "${c.length} steps · ${c.notes.size} ${if (c.kind == ClipKind.DRUM) "hits" else "notes"}"

/** Touch hooks of a long-press-and-drag out of a list row; positions are in root coordinates. */
class ClipDrag(val start: (Clip, Offset) -> Unit, val move: (Offset) -> Unit, val end: () -> Unit, val cancel: () -> Unit)

/** A saved pattern being carried from the mini browser to the piano roll. */
class Carry(val clip: Clip) {
    var pos by mutableStateOf(Offset.Zero)
    var released by mutableStateOf(false)
}

/**
 * One saved pattern: picture, name and a line about it; [below] adds a row under it (the page's buttons). With [drag]
 * a long press picks the pattern up and the same touch carries it on.
 */
@Composable
fun ClipRow(item: Saved, onClick: (() -> Unit)? = null, drag: ClipDrag? = null, below: (@Composable () -> Unit)? = null) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dragNow by rememberUpdatedState(drag)
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() }
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .then(if (drag != null) Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); dragNow?.start?.invoke(item.clip, origin + it) },
                onDrag = { change, _ -> change.consume(); dragNow?.move?.invoke(origin + change.position) },
                onDragEnd = { dragNow?.end?.invoke() },
                onDragCancel = { dragNow?.cancel?.invoke() },
            )
        } else Modifier)
        .padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ClipThumb(item.clip)
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(clipInfo(item.clip), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        below?.invoke()
    }
}

enum class BrowserMode { SAVE, LOAD }

/**
 * The mini browser over the sequencer: SAVE asks for a name and saves the pattern on screen, LOAD lists the saved
 * patterns of this kind only (piano roll patterns from the piano roll, drum patterns from the drum grid). A tap loads
 * a pattern; in the piano roll a long press picks it up instead: the browser hides at once and the same touch drags
 * the pattern over the roll ([carry] callbacks), to be dropped on any notes.
 */
@Composable
fun ClipBrowserOverlay(vm: SloopViewModel, kind: ClipKind, mode: BrowserMode, carrying: Boolean, onDismiss: () -> Unit,
                       onLoad: (Clip) -> Unit, carry: ClipDrag? = null) {
    val store = vm.patterns
    val items = remember(store.rev, kind) { store.list(kind) }
    var name by remember {
        mutableStateOf(generateSequence(1) { it + 1 }.map { "${if (kind == ClipKind.DRUM) "Drums" else "Piano"} $it" }
            .first { n -> items.none { it.name == n } })
    }
    var error by remember { mutableStateOf<String?>(null) }
    val clean = store.clean(name)
    val exists = clean.isNotEmpty() && items.any { it.name == clean }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (carrying) 0f else 1f }
        .background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }) {
        Card(Modifier.align(Alignment.Center).padding(20.dp).fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { } }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (mode == BrowserMode.SAVE) "Save ${kind.title.lowercase()} pattern" else "Load ${kind.title.lowercase()} pattern",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                if (mode == BrowserMode.SAVE) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40); error = null }, singleLine = true,
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(error ?: if (exists) "A pattern with this name will be replaced" else "Saved inside the app")
                        }, isError = error != null)
                    Button(enabled = clean.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                        val c = vm.controller.captureClip()
                        if (c == null) error = "Nothing to save"
                        else if (store.save(kind, clean, c) == null) error = "Enter a name"
                        else onDismiss()
                    }) { Text(if (exists) "Replace" else "Save") }
                    if (items.isNotEmpty()) Text("Saved (tap to use the name)", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (carry != null && items.isNotEmpty()) Text("Tap to load · hold and drag onto the roll to place",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (items.isEmpty()) Text("No saved ${kind.title.lowercase()} patterns yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(items, key = { it.name }) { saved ->
                        ClipRow(saved, drag = if (mode == BrowserMode.LOAD) carry else null, onClick = {
                            if (mode == BrowserMode.LOAD) { onLoad(saved.clip); onDismiss() } else { name = saved.name; error = null }
                        })
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
