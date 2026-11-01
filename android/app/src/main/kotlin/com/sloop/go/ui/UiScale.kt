// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The knob size every ParamControl draws at (see [knobSizeFor]); a block can provide a smaller one for itself. */
val LocalKnob = staticCompositionLocalOf { KNOB_MAX }

/** Dense knobs: no value line under the knob (the number sits inside it, an enum shows in a chip), a smaller label. */
val LocalDenseKnobs = staticCompositionLocalOf { false }

val KNOB_MAX = 52.dp
val KNOB_MIN = 32.dp

/**
 * Knobs follow the room the screen has in dp, not its pixels: a phone with a high density or a large Display size
 * setting has fewer dp, so its knobs get smaller and as much still fits; a big screen never gets bigger knobs than
 * [KNOB_MAX]. The short side counts (the app runs in landscape, height is what runs out first).
 */
@Composable
fun knobSizeFor(): Dp {
    val cfg = LocalConfiguration.current
    val short = minOf(cfg.screenWidthDp, cfg.screenHeightDp)
    return (short / 8f).dp.coerceIn(36.dp, KNOB_MAX)
}

/** A smaller knob for dense blocks (the FM6 operators), never under [KNOB_MIN]. */
fun Dp.compact(f: Float = 0.82f): Dp = (this * f).coerceAtLeast(KNOB_MIN)
