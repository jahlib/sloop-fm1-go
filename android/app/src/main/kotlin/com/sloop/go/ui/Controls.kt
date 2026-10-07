// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sloop.go.proto.Desc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A single parameter, rendered as a slider / switch / chip row depending on its format. */
@Composable
fun ParamControl(desc: Desc?, value: Int, onChange: (Int) -> Unit) {
    if (desc == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(desc.label.ifBlank { "P${desc.id}" }, style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            isToggle(desc) -> Switch(checked = value != 0,
                onCheckedChange = { onChange(if (it) desc.max else desc.min) })
            isEnum(desc) -> EnumChips(desc, value, onChange)
            else -> Knob(desc, value, onChange)
        }
        Text(formatValue(desc, value), style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Knob(desc: Desc, value: Int, onChange: (Int) -> Unit) {
    val density = LocalDensity.current
    val latestValue by rememberUpdatedState(value)
    var dragged by remember(desc.scope, desc.id) { mutableFloatStateOf(value.toFloat()) }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val fraction = ((value - desc.min).toFloat() / (desc.max - desc.min).coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(Modifier.size(64.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), desc.min.toFloat()..desc.max.toFloat())
        setProgress { v -> onChange(v.roundToInt().coerceIn(desc.min, desc.max)); true }
    }.pointerInput(desc.scope, desc.id, desc.min, desc.max) {
        var startX = 0f
        val fineThreshold = with(density) { 32.dp.toPx() }
        val travel = with(density) { 180.dp.toPx() }
        detectDragGestures(
            onDragStart = { startX = it.x; dragged = latestValue.toFloat() },
            onDrag = { change, delta ->
                val precision = if (abs(change.position.x - startX) > fineThreshold) 0.15f else 1f
                dragged = (dragged - delta.y / travel * (desc.max - desc.min) * precision)
                    .coerceIn(desc.min.toFloat(), desc.max.toFloat())
                val next = dragged.roundToInt()
                if (next != latestValue) onChange(next)
                change.consume()
            },
        )
    }.pointerInput(desc.scope, desc.id, desc.def) {
        detectTapGestures(onDoubleTap = { onChange(desc.def) })
    }) {
        val radius = size.minDimension * 0.38f
        val origin = Offset(size.width / 2f, size.height / 2f)
        val corner = Offset(origin.x - radius, origin.y - radius)
        val diameter = Size(radius * 2, radius * 2)
        val width = 5.dp.toPx()
        drawCircle(surface, radius = radius * 0.82f, center = origin)
        drawArc(track, startAngle = 150f, sweepAngle = 240f, useCenter = false,
            topLeft = corner, size = diameter, style = Stroke(width, cap = StrokeCap.Round))
        drawArc(primary, startAngle = 150f, sweepAngle = 240f * fraction, useCenter = false,
            topLeft = corner, size = diameter, style = Stroke(width, cap = StrokeCap.Round))
        val angle = (150f + 240f * fraction) * PI / 180.0
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(primary, origin + dir * (radius * 0.38f), origin + dir * (radius * 0.75f),
            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    }
}

/**
 * A numeric parameter shown as a value display instead of a knob: a short hold + vertical drag nudges
 * it up/down, a long press opens a keyboard input dialog.
 */
@Composable
fun TextParamControl(desc: Desc, value: Int, onChange: (Int) -> Unit) {
    val density = LocalDensity.current
    val latestValue by rememberUpdatedState(value)
    var dragged by remember(desc.scope, desc.id) { mutableFloatStateOf(value.toFloat()) }
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(desc.label.ifBlank { "P${desc.id}" }, style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(64.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .pointerInput(desc.scope, desc.id, desc.min, desc.max) {
                val travel = with(density) { 640.dp.toPx() }
                detectDragGestures(
                    onDragStart = { dragged = latestValue.toFloat() },
                    onDrag = { change, delta ->
                        dragged = (dragged - delta.y / travel * (desc.max - desc.min))
                            .coerceIn(desc.min.toFloat(), desc.max.toFloat())
                        val next = dragged.roundToInt()
                        if (next != latestValue) onChange(next)
                        change.consume()
                    },
                )
            }
            .pointerInput(desc.scope, desc.id) {
                detectTapGestures(onLongPress = { text = "$latestValue"; editing = true })
            },
            contentAlignment = Alignment.Center) {
            Text("$value", style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        Text("${desc.min}–${desc.max}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(desc.label.ifBlank { "Value" }) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '-' }.take(5) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                suffix = { Text("${desc.min}–${desc.max}") },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                text.toIntOrNull()?.let { onChange(it.coerceIn(desc.min, desc.max)) }
                editing = false
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
    )
}

@Composable
private fun EnumChips(desc: Desc, value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text("▾", Modifier.clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            desc.names.forEachIndexed { i, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onChange(desc.min + i); open = false
                })
            }
        }
    }
}
