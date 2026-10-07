// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import com.sloop.go.proto.Desc
import com.sloop.go.proto.Fmt

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun noteName(n: Int): String = NOTE_NAMES[((n % 12) + 12) % 12] + (n / 12 - 1)

/** Human-readable value, close to the device (exact formatting is not required by the protocol). */
fun formatValue(desc: Desc?, value: Int): String {
    if (desc == null) return value.toString()
    // Some F_INT params carry injected names (FM6 ALG/PTCH) — they display like enums.
    if (desc.names.isNotEmpty()) return desc.names.getOrNull(value - desc.min) ?: value.toString()
    return when (desc.fmt) {
        Fmt.ENUM -> desc.names.getOrNull(value - desc.min) ?: value.toString()
        Fmt.ONOFF -> if (value != 0) "ON" else "OFF"
        Fmt.NOTE -> noteName(value)
        Fmt.PCT -> {
            val max = if (desc.max != 0) desc.max else 1
            "${Math.round(value * 100.0 / max)}%"
        }
        Fmt.BIPCT -> {
            val m = if (desc.max != 0) desc.max else 1
            "${Math.round(value * 100.0 / m)}%"
        }
        Fmt.SWING -> "${50 + value / 4}%"
        Fmt.FILT -> when {
            value == 0 -> "OFF"
            value < 0 -> "LP ${-value * 100 / 64}%"
            else -> "HP ${value * 100 / 63}%"
        }
        Fmt.DB -> "$value dB"
        Fmt.SEMI -> "$value st"
        Fmt.OCT -> "$value oct"
        Fmt.BPM -> "$value BPM"
        Fmt.STEPS -> "$value"
        else -> if (desc.unit.isNotBlank()) "$value ${desc.unit}" else value.toString()
    }
}

/** True when the parameter is best shown as a toggle. */
fun isToggle(desc: Desc?): Boolean = desc?.fmt == Fmt.ONOFF

/** True when the parameter is an enumeration (named choices, incl. injected F_INT names). */
fun isEnum(desc: Desc?): Boolean = desc != null && (desc.fmt == Fmt.ENUM || desc.names.isNotEmpty())
