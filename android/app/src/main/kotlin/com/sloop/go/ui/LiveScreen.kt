// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
@file:OptIn(ExperimentalLayoutApi::class)
package com.sloop.go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import com.sloop.go.proto.Live

private val FX_NAMES = listOf(
    "LOOP 4", "LOOP 8", "LOOP 16", "LOOP 32", "STUTTER", "REVERSE", "STOP", "HALF", "LOW", "HIGH", "PHONE", "CRUSH",
    "ALIAS", "GATE", "ECHO", "WOBBLE", "KILL LOW", "KILL MID", "KILL HIGH", "REWIND", "OCT UP", "FLANGER", "AUTO-PAN",
    "FREEZE", "LOOP 8T", "MONO", "FADE OUT",
)
private const val LAYER_GLO = 6
private const val KEY_MUTE = 0
private const val KEY_SOLO = 4
private const val KEY_FILL = 8
private const val KEY_FILL_BAR = 9
private const val KEY_TAP = 15

/** SLOOP 2.7 LIVE: the mix, the fills, THROWs and the 27 punch-in effects, played while a pad is held. */
@Composable
fun LiveScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit) {
    if (state.link != Link.READY || state.info == null) { NotReady("Live", state, onDevice); return }
    val ctl = vm.controller
    val lv by ctl.transport.collectAsState()
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
        item { ScreenHeader("Live", state, NAV_INSET) }
        if (state.info.proto < 12) item {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Text("The LIVE page needs SLOOP 2.7 on the FM-1.", Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else item {
            val l = lv
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusCard(l, onTap = { ctl.liveTap(LAYER_GLO, KEY_TAP) })
                MixCard(state, l, ctl)
                FxCard(ctl)
            }
        }
    }
}

@Composable
private fun StatusCard(l: Live?, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (l?.playing == true) "PLAYING" else "STOPPED", fontFamily = SloopFontFamily, fontWeight = FontWeight.SemiBold,
                color = if (l?.playing == true) cs.primary else cs.onSurfaceVariant)
            Text("${l?.bpm ?: 0} BPM", fontFamily = SloopFontFamily)
            TextButton(onClick = onTap) { Text("TAP") }
            Spacer(Modifier.weight(1f))
            Text("CPU ${l?.cpu ?: 0}%", fontFamily = SloopFontFamily, style = MaterialTheme.typography.bodySmall,
                color = if ((l?.cpu ?: 0) > 85) cs.error else cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun MixCard(state: DeviceState, l: Live?, ctl: com.sloop.go.device.DeviceController) {
    val cs = MaterialTheme.colorScheme
    val info = state.info!!
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MIX", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            for (t in 0 until (l?.meter?.size ?: 0)) {
                val e = state.tracks?.tracks?.getOrNull(t)
                val name = if (e == null) "T${t + 1}" else
                    "T${t + 1} " + (if (e.engine >= info.nengines) "DRUM" else info.engines.getOrNull(e.engine) ?: "")
                val muted = ((l?.mutes ?: 0) shr t) and 1 == 1
                val solo = ((l?.solos ?: 0) shr t) and 1 == 1
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, Modifier.width(92.dp), maxLines = 1, style = MaterialTheme.typography.bodySmall, fontFamily = SloopFontFamily)
                    val m = ((l?.meter?.getOrNull(t) ?: 0) - 48).coerceAtLeast(0) / 80f
                    Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(cs.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(m.coerceIn(0f, 1f)).height(10.dp).background(if (muted) cs.outline else cs.primary))
                    }
                    Pad("M", muted, Modifier.width(48.dp), tap = { ctl.liveTap(LAYER_GLO, KEY_MUTE + t) })
                    Pad("S", solo, Modifier.width(48.dp), tap = { ctl.liveTap(LAYER_GLO, KEY_SOLO + t) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pad("FILL", l?.fillNow == true, Modifier.weight(1f),
                    down = { ctl.liveKey(LAYER_GLO, KEY_FILL, true) }, up = { ctl.liveKey(LAYER_GLO, KEY_FILL, false) })
                Pad("FILL NEXT BAR", l?.fillNext == true, Modifier.weight(1f), tap = { ctl.liveTap(LAYER_GLO, KEY_FILL_BAR) })
                Pad("REV THROW", ((l?.throws ?: 0) and 1) != 0, Modifier.weight(1f),
                    down = { ctl.liveThrow(1) }, up = { ctl.liveThrow(0) })
                Pad("ECHO THROW", ((l?.throws ?: 0) and 2) != 0, Modifier.weight(1f),
                    down = { ctl.liveThrow(2) }, up = { ctl.liveThrow(0) })
            }
            val master = l?.master ?: 0
            var local by remember { mutableStateOf<Float?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MASTER", Modifier.width(92.dp), style = MaterialTheme.typography.bodySmall, fontFamily = SloopFontFamily)
                Slider(value = local ?: if (master == 0) 100f else (master - 1).toFloat(), valueRange = 0f..126f,
                    onValueChange = { local = it; ctl.liveMaster(it.toInt()) },
                    onValueChangeFinished = { local = null }, modifier = Modifier.weight(1f))
                Pad(if (master == 0) "KNOB" else "${master - 1}", master == 0, Modifier.width(64.dp), tap = { ctl.liveMaster(127) })
            }
        }
    }
}

@Composable
private fun FxCard(ctl: com.sloop.go.device.DeviceController) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PUNCH-IN FX · hold a pad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FX_NAMES.chunked(4).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, n ->
                        val fx = r * 4 + c
                        Pad(n, false, Modifier.weight(1f).height(52.dp),
                            down = { ctl.liveFx(fx, true) }, up = { ctl.liveFx(fx, false) })
                    }
                    repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A pad: [tap] on a short press, or [down] / [up] for as long as it is held (the up also fires when the finger slides off or the page closes). */
@Composable
private fun Pad(
    label: String, active: Boolean, modifier: Modifier = Modifier,
    tap: (() -> Unit)? = null, down: (() -> Unit)? = null, up: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    val onTap by rememberUpdatedState(tap)
    val onDown by rememberUpdatedState(down)
    val onUp by rememberUpdatedState(up)
    Box(modifier.height(40.dp).clip(RoundedCornerShape(8.dp))
        .background(if (pressed || active) cs.primary else cs.surfaceVariant)
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                pressed = true
                onDown?.invoke()
                try { if (tryAwaitRelease()) onTap?.invoke() } finally { pressed = false; onUp?.invoke() }
            })
        }, contentAlignment = Alignment.Center) {
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = if (pressed || active) cs.onPrimary else cs.onSurface)
    }
}
