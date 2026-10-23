// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sloop.go.device.LFO_COUNT
import com.sloop.go.device.LFO_CURVES
import com.sloop.go.device.LFO_PHASES
import com.sloop.go.device.LFO_RATES
import com.sloop.go.device.LFO_SHAPES
import com.sloop.go.device.lfoWave

val LFO_COLORS = listOf(Color(0xFFFFA726), Color(0xFF26C6DA), Color(0xFFEC407A), Color(0xFF9CCC65))

/**
 * The four LFO buttons of the Sound page's header. Each is a small scope with the LFO's number (the wave in the two
 * cycles it is in, a dot riding it); tap = assignment mode, and that LFO's settings (shape, rate, phase, curve) slide
 * out of the button to its right, pushing the buttons after it along. [frame] only drives the motion and is read
 * while drawing.
 */
@Composable
fun LfoChips(vm: SloopViewModel, assign: Int, onAssign: (Int) -> Unit, frame: State<Long>) {
    val st by vm.lfo.state.collectAsState()
    // The first tap on an LFO warns once (per install) that assigned knobs write to the device all the time.
    val warnPrefs = LocalContext.current.getSharedPreferences("lfo", 0)
    var warn by remember { mutableStateOf(false) }
    var pending by remember { mutableIntStateOf(-1) }
    fun closeWarn() {
        warn = false
        warnPrefs.edit().putBoolean("warned", true).apply()
        if (pending >= 0) { onAssign(pending); pending = -1 }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until LFO_COUNT) {
            val color = LFO_COLORS[i]
            val on = assign == i
            val cfg = st.cfg[i]
            val line = if (on) Color.Black else color
            Box(Modifier.padding(horizontal = 3.dp).size(width = 52.dp, height = 36.dp).clip(RoundedCornerShape(8.dp))
                .background(if (on) color else color.copy(alpha = 0.14f))
                .border(1.dp, color, RoundedCornerShape(8.dp))
                .clickable {
                    if (on) onAssign(-1)
                    else if (warnPrefs.getBoolean("warned", false)) onAssign(i)
                    else { pending = i; warn = true }
                }) {
                Canvas(Modifier.matchParentSize()) {
                    frame.value
                    val pad = 7.dp.toPx()
                    val h = size.height - 2 * pad
                    val path = Path()
                    val n = 24
                    val cyc = vm.lfo.cycleAt(i, System.nanoTime() / 1e9)
                    val origin = Math.floor(cyc / 2.0) * 2.0
                    for (k in 0..n) {
                        val x = size.width * k / n
                        val y = pad + h * (1f - (lfoWave(cfg.shape, origin + 2.0 * minOf(k.toDouble(), n - 0.001) / n, cfg.curve) + 1f) / 2f)
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, line.copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                    val u = cyc - origin
                    drawCircle(line, 3.dp.toPx(), Offset((size.width * u / 2.0).toFloat(),
                        pad + h * (1f - (lfoWave(cfg.shape, cyc, cfg.curve) + 1f) / 2f)))
                }
                Text("${i + 1}", Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 1.dp),
                    style = MaterialTheme.typography.labelSmall, color = line, fontFamily = SloopFontFamily)
            }
            AnimatedVisibility(on, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                Row(Modifier.padding(start = 3.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MiniMenu(LFO_SHAPES[cfg.shape], LFO_SHAPES, color, 62.dp) { vm.lfo.setCfg(i, cfg.copy(shape = it)) }
                    MiniMenu(LFO_RATES[cfg.rate].first, LFO_RATES.map { it.first }, color, 72.dp) {
                        vm.lfo.setCfg(i, cfg.copy(rate = it))
                    }
                    MiniMenu("${LFO_PHASES[cfg.phase]}°", LFO_PHASES.map { "$it°" }, color, 56.dp) {
                        vm.lfo.setCfg(i, cfg.copy(phase = it))
                    }
                    MiniMenu(LFO_CURVES[cfg.curve], LFO_CURVES, color, 80.dp) { vm.lfo.setCfg(i, cfg.copy(curve = it)) }
                    ClearButton("CLEAR ${st.amounts[i].size}", color, st.amounts[i].isNotEmpty()) { vm.lfo.removeAll(i) }
                }
            }
        }
        ClearButton("CLEAR ALL", MaterialTheme.colorScheme.error, st.amounts.any { it.isNotEmpty() } || st.bases.isNotEmpty(),
            Modifier.padding(start = 6.dp)) { vm.lfo.removeAll() }
    }
    if (warn) AlertDialog(onDismissRequest = ::closeWarn, title = { Text("LFO") },
        text = { Text("Careful: while the FM-1 plays, every knob an LFO is assigned to keeps receiving parameter " +
            "changes. Wide or fast LFOs on several knobs can flood the request queue and slow everything else down.") },
        confirmButton = { TextButton(onClick = ::closeWarn) { Text("Got it") } })
}

/** Takes assignments off their knobs (they go back to their base); dimmed and inert when there is nothing to remove. */
@Composable
private fun ClearButton(label: String, color: Color, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text("✕ $label", modifier.border(1.dp, color.copy(alpha = if (enabled) 0.8f else 0.3f), RoundedCornerShape(6.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
        style = MaterialTheme.typography.labelMedium, maxLines = 1, fontFamily = SloopFontFamily,
        color = color.copy(alpha = if (enabled) 1f else 0.4f))
}

@Composable
private fun MiniMenu(label: String, options: List<String>, color: Color, width: Dp, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text("$label ▾", Modifier.width(width).border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .clickable { open = true }.padding(horizontal = 6.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Clip,
            fontFamily = SloopFontFamily)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { k, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(k) }) }
        }
    }
}
