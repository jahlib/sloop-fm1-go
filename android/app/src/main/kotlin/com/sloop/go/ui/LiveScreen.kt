// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
@file:OptIn(ExperimentalLayoutApi::class)
package com.sloop.go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.sp
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

private val SENDS = listOf("DST" to 33, "CHO" to 34, "DLY" to 35, "REV" to 36)

/** SLOOP 2.7 LIVE, all on one screen: a thin toolbar (play, menu, BPM, master, CPU) over the mix and the 27 punch-in pads. */
@Composable
fun LiveScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit, nav: @Composable () -> Unit) {
    if (state.link != Link.READY || state.info == null) { NotReady("Live", state, onDevice); return }
    val ctl = vm.controller
    val lv by ctl.transport.collectAsState()
    val l = lv
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            nav()
            if (state.info.proto >= 12) {
                val cs = MaterialTheme.colorScheme
                val master = l?.master ?: 0
                Text("${l?.bpm ?: 0} BPM", Modifier.clickable { ctl.liveTap(LAYER_GLO, KEY_TAP) }.padding(horizontal = 4.dp, vertical = 8.dp),
                    fontFamily = SloopFontFamily, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text(if (master == 0) "MST KNOB" else "MST ${master - 1}", Modifier.clickable { ctl.liveMaster(127) }.padding(vertical = 8.dp),
                    fontFamily = SloopFontFamily, style = MaterialTheme.typography.labelSmall,
                    color = if (master == 0) cs.onSurfaceVariant else cs.primary)
                ThinSlider(if (master == 0) 100f / 126f else (master - 1) / 126f, master != 0,
                    { ctl.liveMaster((it * 126f).toInt()) }, Modifier.weight(1f))
                Text("CPU ${l?.cpu ?: 0}%", fontFamily = SloopFontFamily, style = MaterialTheme.typography.labelSmall,
                    color = if ((l?.cpu ?: 0) > 85) cs.error else cs.onSurfaceVariant)
            }
        }
        if (state.info.proto < 12) {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Text("The LIVE page needs SLOOP 2.7 on the FM-1.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            return
        }
        Row(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MixCard(state, l, ctl, Modifier.weight(1.15f).fillMaxHeight())
            FxCard(ctl, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** A slim 0..1 slider (the toolbar is thin); a touch or drag sets the value. */
@Composable
private fun ThinSlider(value: Float, active: Boolean, onChange: (Float) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val change by rememberUpdatedState(onChange)
    var local by remember { mutableStateOf<Float?>(null) }
    val v = local ?: value
    Box(modifier.height(36.dp).pointerInput(Unit) {
        detectTapGestures(onPress = { o ->
            val x = (o.x / size.width).coerceIn(0f, 1f); local = x; change(x)
            tryAwaitRelease(); local = null
        })
    }.pointerInput(Unit) {
        detectHorizontalDragGestures(onDragEnd = { local = null }, onDragCancel = { local = null }) { c, _ ->
            val x = (c.position.x / size.width).coerceIn(0f, 1f); local = x; change(x)
        }
    }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(cs.surfaceVariant))
        Box(Modifier.fillMaxWidth(v.coerceIn(0f, 1f)).height(3.dp).clip(RoundedCornerShape(2.dp))
            .background(if (active) cs.primary else cs.outline))
    }
}

@Composable
private fun MixCard(state: DeviceState, l: Live?, ctl: com.sloop.go.device.DeviceController, modifier: Modifier) {
    val info = state.info!!
    val n = (l?.meter?.size ?: 4).coerceAtLeast(1)
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in 0 until n) {
                    val e = state.tracks?.tracks?.getOrNull(t)
                    val eng = if (e == null) "" else if (e.engine >= info.nengines) "DRUM" else info.engines.getOrNull(e.engine) ?: ""
                    val muted = ((l?.mutes ?: 0) shr t) and 1 == 1
                    val solo = ((l?.solos ?: 0) shr t) and 1 == 1
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("T${t + 1}", fontFamily = SloopFontFamily, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(eng, maxLines = 1, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for ((name, id) in SENDS)
                            Pad(name, false, Modifier.fillMaxWidth().weight(1f), small = true,
                                down = { ctl.holdParam(t, id, true) }, up = { ctl.holdParam(t, id, false) })
                        Pad("M", muted, Modifier.fillMaxWidth().weight(1f), tap = { ctl.liveTap(LAYER_GLO, KEY_MUTE + t) })
                        Pad("S", solo, Modifier.fillMaxWidth().weight(1f), tap = { ctl.liveTap(LAYER_GLO, KEY_SOLO + t) })
                    }
                }
            }
            Row(Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Pad("FILL", l?.fillNow == true, Modifier.weight(1f).fillMaxHeight(), small = true,
                    down = { ctl.liveKey(LAYER_GLO, KEY_FILL, true) }, up = { ctl.liveKey(LAYER_GLO, KEY_FILL, false) })
                Pad("NEXT BAR", l?.fillNext == true, Modifier.weight(1f).fillMaxHeight(), small = true,
                    tap = { ctl.liveTap(LAYER_GLO, KEY_FILL_BAR) })
            }
            Row(Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Pad("REV THR", ((l?.throws ?: 0) and 1) != 0, Modifier.weight(1f).fillMaxHeight(), small = true,
                    down = { ctl.liveThrow(1) }, up = { ctl.liveThrow(0) })
                Pad("ECHO THR", ((l?.throws ?: 0) and 2) != 0, Modifier.weight(1f).fillMaxHeight(), small = true,
                    down = { ctl.liveThrow(2) }, up = { ctl.liveThrow(0) })
            }
        }
    }
}

@Composable
private fun FxCard(ctl: com.sloop.go.device.DeviceController, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FX_NAMES.chunked(3).forEachIndexed { r, row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { c, n ->
                        val fx = r * 3 + c
                        Pad(n, false, Modifier.weight(1f).fillMaxHeight(), small = true,
                            down = { ctl.liveFx(fx, true) }, up = { ctl.liveFx(fx, false) })
                    }
                }
            }
        }
    }
}

/** A pad: [tap] on a short press, or [down] / [up] for as long as it is held (the up also fires when the finger slides off or the page closes). */
@Composable
private fun Pad(
    label: String, active: Boolean, modifier: Modifier = Modifier,
    tap: (() -> Unit)? = null, down: (() -> Unit)? = null, up: (() -> Unit)? = null, small: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    val onTap by rememberUpdatedState(tap)
    val onDown by rememberUpdatedState(down)
    val onUp by rememberUpdatedState(up)
    Box(modifier.clip(RoundedCornerShape(8.dp))
        .background(if (pressed || active) cs.primary else cs.surfaceVariant)
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                pressed = true
                onDown?.invoke()
                try { if (tryAwaitRelease()) onTap?.invoke() } finally { pressed = false; onUp?.invoke() }
            })
        }, contentAlignment = Alignment.Center) {
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium, fontSize = if (small) 9.sp else 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (pressed || active) cs.onPrimary else cs.onSurface)
    }
}
