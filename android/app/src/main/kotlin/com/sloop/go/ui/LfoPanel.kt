// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sloop.go.device.LFO_COUNT
import com.sloop.go.device.LFO_PHASES
import com.sloop.go.device.LFO_RATES
import com.sloop.go.device.LFO_CURVES
import com.sloop.go.device.LFO_SHAPES
import com.sloop.go.device.LfoCfg
import com.sloop.go.device.lfoWave

val LFO_COLORS = listOf(Color(0xFFFFA726), Color(0xFF26C6DA), Color(0xFFEC407A), Color(0xFF9CCC65))

/** The four LFO buttons for the Sound page's header: each is a small scope with the LFO's number; tap = assign mode. */
@Composable
fun LfoChips(vm: SloopViewModel, assign: Int, onAssign: (Int) -> Unit, frame: State<Long>) {
    val st by vm.lfo.state.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until LFO_COUNT) {
            val color = LFO_COLORS[i]
            val on = assign == i
            val cfg = st.cfg[i]
            val line = if (on) Color.Black else color
            Box(Modifier.size(width = 52.dp, height = 36.dp).clip(RoundedCornerShape(8.dp))
                .background(if (on) color else color.copy(alpha = 0.14f))
                .border(1.dp, color, RoundedCornerShape(8.dp))
                .clickable { onAssign(if (on) -1 else i) }) {
                Canvas(Modifier.matchParentSize()) {
                    frame.value
                    val pad = 7.dp.toPx()
                    val h = size.height - 2 * pad
                    val path = Path()
                    val n = 24
                    for (k in 0..n) {
                        val x = size.width * k / n
                        val y = pad + h * (1f - (lfoWave(cfg.shape, 2.0 * k / n + LFO_PHASES[cfg.phase] / 360.0, cfg.curve) + 1f) / 2f)
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, line.copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                    val cyc = vm.lfo.cycleAt(i, System.nanoTime() / 1e9)
                    val u = cyc - Math.floor(cyc / 2.0) * 2.0
                    drawCircle(line, 3.dp.toPx(), Offset((size.width * u / 2.0).toFloat(),
                        pad + h * (1f - (lfoWave(cfg.shape, cyc, cfg.curve) + 1f) / 2f)))
                }
                Text("${i + 1}", Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 1.dp),
                    style = MaterialTheme.typography.labelSmall, color = line, fontFamily = SloopFontFamily)
            }
        }
    }
}

/** The settings of the LFO in assign mode: shape, rate (note value or bars), phase, curve, with a large scope. */
@Composable
fun LfoPanel(vm: SloopViewModel, i: Int, frame: State<Long>) {
    val st by vm.lfo.state.collectAsState()
    val color = LFO_COLORS[i]
    val cfg = st.cfg[i]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val n = st.amounts[i].size
                Text("LFO ${i + 1}", style = MaterialTheme.typography.titleSmall, color = color)
                Text("drag a knob to set its amount" + if (n > 0) " · $n assigned" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniMenu(LFO_SHAPES[cfg.shape], LFO_SHAPES, color, Modifier.weight(1f)) { vm.lfo.setCfg(i, cfg.copy(shape = it)) }
                MiniMenu(LFO_RATES[cfg.rate].first, LFO_RATES.map { it.first }, color, Modifier.weight(1f)) {
                    vm.lfo.setCfg(i, cfg.copy(rate = it))
                }
                MiniMenu("PH ${LFO_PHASES[cfg.phase]}°", LFO_PHASES.map { "$it°" }, color, Modifier.weight(1f)) {
                    vm.lfo.setCfg(i, cfg.copy(phase = it))
                }
                MiniMenu(LFO_CURVES[cfg.curve], LFO_CURVES, color, Modifier.weight(1f)) {
                    vm.lfo.setCfg(i, cfg.copy(curve = it))
                }
            }
            Scope(vm, i, cfg, color, frame)
        }
    }
}

@Composable
private fun MiniMenu(label: String, options: List<String>, color: Color, modifier: Modifier, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Text("$label ▾", Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .clickable { open = true }.padding(horizontal = 6.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Clip,
            fontFamily = SloopFontFamily)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { k, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(k) }) }
        }
    }
}

/** Two cycles of the wave; the dot rides it at the LFO's position now (read from the engine every frame). */
@Composable
private fun Scope(vm: SloopViewModel, i: Int, cfg: LfoCfg, color: Color, frame: State<Long>) {
    val line = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(48.dp).border(1.dp, line.copy(alpha = 0.5f), RoundedCornerShape(8.dp))) {
        frame.value
        drawRect(surface.copy(alpha = 0.5f))
        val pad = 6.dp.toPx()
        val h = size.height - 2 * pad
        val w = size.width
        fun y(v: Float) = pad + h * (1f - (v + 1f) / 2f)
        drawLine(line.copy(alpha = 0.4f), Offset(0f, y(0f)), Offset(w, y(0f)), strokeWidth = 1.dp.toPx())
        val phase = LFO_PHASES[cfg.phase] / 360.0
        val path = Path()
        val n = (w / 2.dp.toPx()).toInt().coerceAtLeast(2)
        var prev = 0f
        for (k in 0..n) {
            val x = w * k / n
            val yy = y(lfoWave(cfg.shape, 2.0 * k / n + phase, cfg.curve))
            if (k == 0) path.moveTo(x, yy) else {
                if (cfg.shape >= 4) path.lineTo(x, prev)   // a step wave holds its old value up to the jump
                path.lineTo(x, yy)
            }
            prev = yy
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        val cyc = vm.lfo.cycleAt(i, System.nanoTime() / 1e9)
        val u = cyc - Math.floor(cyc / 2.0) * 2.0
        val cx = (w * u / 2.0).toFloat()
        drawLine(color.copy(alpha = 0.35f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.dp.toPx())
        drawCircle(color, 4.dp.toPx(), Offset(cx, y(lfoWave(cfg.shape, cyc, cfg.curve))))
    }
}
