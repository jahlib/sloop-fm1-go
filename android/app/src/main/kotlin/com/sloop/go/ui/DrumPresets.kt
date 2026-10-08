// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp

/** Drum lane indices, as the drum grid names them (SequencerScreen DRUM_LANES). */
private const val KICK = 0
private const val KICK2 = 1
private const val SNARE = 2
private const val CLAP = 3
private const val HAT = 4
private const val OPEN_HAT = 5
private const val PEDAL = 6
private const val RIM = 7
private const val SNARE2 = 8
private const val LOW_TOM = 9
private const val HI_TOM = 10
private const val CRASH = 11
private const val RIDE = 12
private const val SHAKER = 13
private const val CONGA = 14
private const val COWBELL = 15

/**
 * A ready-made drum pattern: one bar of 16 steps per lane ('x' = hit). Only the hits are set, sounds and the
 * track's patch are not touched; a pattern longer than 16 steps repeats the bar.
 */
class DrumPreset(val name: String, vararg lanes: Pair<Int, String>) {
    /** Lanes the preset uses, in lane order, each as 16 booleans. */
    val rows: List<Pair<Int, BooleanArray>> = lanes.sortedBy { it.first }
        .map { (lane, s) -> lane to BooleanArray(16) { s.getOrNull(it) == 'x' } }

    /** The step masks (bit l = lane l) for a pattern of [length] steps. */
    fun masks(length: Int): List<Int> = List(length) { step ->
        rows.fold(0) { m, (lane, hits) -> if (hits[step % 16]) m or (1 shl lane) else m }
    }
}

val DRUM_PRESETS = listOf(
    DrumPreset("Four on the floor",
        KICK to "x...x...x...x...", CLAP to "....x.......x...",
        HAT to "x.x.x.x.x.x.x.x.", OPEN_HAT to "..x...x...x...x."),
    DrumPreset("Rock",
        KICK to "x.......x.x.....", SNARE to "....x.......x...",
        HAT to "x.x.x.x.x.x.x.x.", CRASH to "x..............."),
    DrumPreset("Boom bap",
        KICK to "x.....x..x.x....", SNARE to "....x.......x...",
        HAT to "x.x.x.x.x.x.x.x.", OPEN_HAT to "..............x."),
    DrumPreset("Trap",
        KICK to "x......x..x.....", CLAP to "........x.......",
        HAT to "x.x.x.xxx.x.x.xx", OPEN_HAT to "......x........."),
    DrumPreset("House",
        KICK to "x...x...x...x...", CLAP to "....x.......x...",
        OPEN_HAT to "..x...x...x...x.", HAT to "x.xxx.xxx.xxx.xx", SHAKER to ".x.x.x.x.x.x.x.x"),
    DrumPreset("Techno",
        KICK to "x...x...x...x...", HAT to "x.xxx.xxx.xxx.xx",
        OPEN_HAT to "..x...x...x...x.", RIM to "...x.......x....", CLAP to "............x..."),
    DrumPreset("Breakbeat",
        KICK to "x.x.......xx....", SNARE to "....x..x.x..x...",
        HAT to "x.x.x.x.x.x.x.x.", OPEN_HAT to "......x.......x."),
    DrumPreset("Drum & bass",
        KICK to "x.........x.....", SNARE to "....x.......x...",
        HAT to "x.x.x.x.x.x.x.x.", RIDE to "..x...x...x...x."),
    DrumPreset("Disco",
        KICK to "x...x...x...x...", SNARE to "....x.......x...",
        HAT to "x...x...x...x...", OPEN_HAT to "..x...x...x...x.", CLAP to "....x.......x..."),
    DrumPreset("Funk",
        KICK to "x.....x..x.x....", SNARE to "....x.......x...",
        SNARE2 to ".x.....x...x....", HAT to "x.xxx.xxx.xxx.xx"),
    DrumPreset("Reggae one drop",
        KICK to "........x.......", RIM to "........x.......",
        HAT to "..x...x...x...x.", PEDAL to "x...x...x...x..."),
    DrumPreset("Bossa nova",
        KICK to "x..x....x..x....", RIM to "x..x..x...x..x..",
        PEDAL to "x.x.x.x.x.x.x.x.", CONGA to "....x.....x.x...", COWBELL to "x......x........"),
)

/**
 * "Beats ▾" for the drum grid header: a menu of the ready patterns, each with a little picture of its hits
 * (16 steps across, one row per lane it uses) next to the name.
 */
@Composable
fun DrumPresetMenu(onPick: (DrumPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Beats ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DRUM_PRESETS.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DrumThumb(p)
                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    onClick = { open = false; onPick(p) })
            }
        }
    }
}

/** The pattern as a tiny grid: 16 columns, a row per used lane, every fourth step shaded like the real grid. */
@Composable
private fun DrumThumb(p: DrumPreset) {
    val cell = 6.dp
    val on = MaterialTheme.colorScheme.primary
    val even = MaterialTheme.colorScheme.surfaceVariant
    val odd = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(width = cell * 16, height = cell * p.rows.size)) {
        val c = cell.toPx()
        val pad = c * 0.12f
        p.rows.forEachIndexed { r, (_, hits) ->
            for (s in 0 until 16) {
                val color = if (hits[s]) on else if ((s / 4) % 2 == 0) even else odd
                drawRoundRect(color, Offset(s * c + pad, r * c + pad), Size(c - 2 * pad, c - 2 * pad),
                    CornerRadius(c * 0.2f))
            }
        }
    }
}
