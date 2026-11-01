// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.sloop.go.proto.Fm6
import kotlin.math.abs

/** Where the boxes go in a canvas of w x h px: the centre of each operator, the box side, the cell and the output line. */
private class AlgGeom(val center: Map<Int, Offset>, val box: Float, val cell: Float, val busY: Float)

/** Cells never grow past [cap] px (a short algorithm would otherwise get huge boxes) and are as big as the room allows. */
private fun algGeom(l: Fm6.AlgLayout, w: Float, h: Float, cap: Float): AlgGeom {
    val rows = l.rows + 0.1f                                       // the output line under the carriers
    val cell = minOf(w / (l.cols + 0.05f), h / rows, cap)
    val ox = (w - cell * l.cols) / 2f
    val oy = (h - cell * rows) / 2f
    val center = l.x.mapValues { (op, x) ->
        Offset(ox + (x + 0.5f) * cell, oy + (l.rows - 1 - l.row.getValue(op) + 0.5f) * cell)
    }
    return AlgGeom(center, cell * 0.68f, cell, oy + l.rows * cell)
}

/**
 * Algorithm [alg] (0..31) drawn the way FM synths print it: operator boxes, each modulator above what it modulates,
 * the carriers on the bottom row joined by the output line, the feedback loop in orange. Carriers are filled; a thin bar
 * in each box shows its output level ([levels], OP1..OP6, 0..99) when given and [selected] gets a blue frame.
 * [onOp] makes the boxes tappable; [labels] turns the numbers off for thumbnails.
 */
@Composable
fun AlgDiagram(alg: Int, modifier: Modifier, levels: IntArray? = null, selected: Int = 0, labels: Boolean = true,
               onOp: ((Int) -> Unit)? = null) {
    val l = remember(alg) { Fm6.algLayout(alg) }
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val tap by rememberUpdatedState(onOp)
    val hit = if (onOp == null) Modifier else Modifier.pointerInput(alg) {
        val cap = 40.dp.toPx()
        detectTapGestures { p ->
            val g = algGeom(l, size.width.toFloat(), size.height.toFloat(), cap)
            g.center.entries.firstOrNull { (_, c) -> abs(p.x - c.x) <= g.cell / 2 && abs(p.y - c.y) <= g.cell / 2 }
                ?.let { tap?.invoke(it.key) }
        }
    }
    Canvas(modifier.then(hit)) {
        val g = algGeom(l, size.width, size.height, 40.dp.toPx())
        val half = g.box / 2
        val gap = g.cell - g.box
        val line = (g.box * 0.07f).coerceAtLeast(1f.dp.toPx())
        val wire = cs.onSurfaceVariant
        val round = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun top(op: Int) = g.center.getValue(op).let { Offset(it.x, it.y - half) }
        fun bottom(op: Int) = g.center.getValue(op).let { Offset(it.x, it.y + half) }

        // modulators into their targets: down, across, down (a shared bar when several meet)
        for ((t, ms) in l.alg.mods) for (m in ms) {
            val a = bottom(m)
            val b = top(t)
            val mid = b.y - gap / 2
            drawPath(Path().apply { moveTo(a.x, a.y); lineTo(a.x, mid); lineTo(b.x, mid); lineTo(b.x, b.y) }, wire, style = round)
        }
        // the carriers to the output line
        val outs = l.alg.carriers.map { bottom(it) }
        for (o in outs) drawLine(wire, o, Offset(o.x, g.busY), line, StrokeCap.Round)
        if (outs.isNotEmpty()) drawLine(wire, Offset(outs.minOf { it.x }, g.busY), Offset(outs.maxOf { it.x }, g.busY),
            line * 1.6f, StrokeCap.Round)
        // feedback: out of the source's right side, up past the column, over and down into the target
        val from = l.alg.fbFrom
        val to = l.alg.fbTo
        if (from != null && to != null) {
            val s = g.center.getValue(from)
            val d = top(to)
            val right = maxOf(s.x, d.x) + half + gap * 0.45f
            val over = d.y - gap * 0.38f
            drawPath(Path().apply {
                moveTo(s.x + half, s.y); lineTo(right, s.y); lineTo(right, over); lineTo(d.x, over); lineTo(d.x, d.y)
            }, cs.tertiary, style = round)
            val ah = line * 1.8f
            drawPath(Path().apply {
                moveTo(d.x, d.y); lineTo(d.x - ah, d.y - ah * 1.5f); lineTo(d.x + ah, d.y - ah * 1.5f); close()
            }, cs.tertiary)
        }
        // the boxes
        val style = TextStyle(fontFamily = SloopFontFamily, fontWeight = FontWeight.SemiBold, fontSize = (g.box * 0.5f).toSp())
        val radius = CornerRadius(g.box * 0.18f)
        for (op in 1..6) {
            val c = g.center.getValue(op)
            val tl = Offset(c.x - half, c.y - half)
            val carrier = op in l.alg.carriers
            val level = levels?.getOrNull(op - 1)
            val fill = if (carrier) cs.primary.copy(alpha = if (level == 0) 0.35f else 1f) else cs.surfaceVariant
            drawRoundRect(fill, tl, Size(g.box, g.box), radius)
            val edge = when { op == selected -> cs.secondary; carrier -> cs.primary; else -> cs.onSurfaceVariant.copy(alpha = 0.6f) }
            drawRoundRect(edge, tl, Size(g.box, g.box), radius, style = Stroke(if (op == selected) line * 1.8f else line))
            if (level != null && level > 0) {
                val bar = g.box * 0.1f
                val inset = g.box * 0.14f
                drawRect((if (carrier) cs.onPrimary else cs.primary).copy(alpha = 0.55f),
                    Offset(tl.x + inset, tl.y + g.box - inset - bar), Size((g.box - 2 * inset) * level / 99f, bar))
            }
            if (labels) {
                val t = measurer.measure("$op", style.copy(color = if (carrier) cs.onPrimary else cs.onSurface))
                drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height / 2f - g.box * 0.04f))
            }
        }
    }
}
