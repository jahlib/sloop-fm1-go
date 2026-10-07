// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link
import kotlin.math.roundToInt

@Composable
fun MixerScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit) {
    if (state.link != Link.READY || state.tracks == null || state.info == null) {
        NotReady("Mixer", state, onDevice)
        return
    }
    val info = state.info
    val tracks = state.tracks

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 104.dp)) {
        item { ScreenHeader("Mixer", state) }

        itemsIndexed(tracks.tracks) { i, tr ->
            val selected = i == state.selectedTrack
            val isDrum = tr.engine >= info.nengines
            val name = if (isDrum) "DRUM" else info.engines.getOrNull(tr.engine) ?: "TRACK ${i + 1}"

            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Track ${i + 1}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = tr.mute != 0,
                                onClick = { vm.controller.setTrackMix(i, tr.level, tr.mute == 0) },
                                label = { Text("Mute") },
                            )
                            if (selected) {
                                Text("EDITING", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary, fontFamily = SloopFontFamily)
                            } else {
                                Button(
                                    onClick = { vm.controller.selectTrack(i) },
                                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                                ) { Text("Edit") }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = tr.level.toFloat().coerceIn(0f, 127f),
                            onValueChange = { vm.controller.setTrackMix(i, it.roundToInt(), tr.mute != 0) },
                            valueRange = 0f..127f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${tr.level}", Modifier.padding(start = 12.dp), fontFamily = SloopFontFamily,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
