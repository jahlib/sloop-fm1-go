// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link

/**
 * The tracks as vertical channel strips side by side: name, level, a fader and Mute / Edit. The strips take the whole
 * height under the pinned header and the fader gets what the fixed parts leave, so the page fits whatever the display
 * density or size setting; the buttons and the fader's thumb follow the room in dp the same way knobs do.
 */
@Composable
fun MixerScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit, nav: @Composable () -> Unit) {
    if (state.link != Link.READY || state.tracks == null || state.info == null) {
        NotReady("Mixer", state, onDevice)
        return
    }
    val info = state.info
    val tracks = state.tracks
    val cs = MaterialTheme.colorScheme
    val unit = (LocalKnob.current / KNOB_MAX).coerceIn(0.7f, 1f)

    Column(Modifier.fillMaxSize()) {
        PinnedHeader(nav, headerStatus(state)) {
            Text("Mixer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            val buttonH = (maxHeight * 0.14f).coerceIn(34.dp, 52.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tracks.tracks.forEachIndexed { i, tr ->
                    val selected = i == state.selectedTrack
                    val isDrum = tr.engine >= info.nengines
                    val name = if (isDrum) "DRUM" else info.engines.getOrNull(tr.engine) ?: "TRACK ${i + 1}"
                    val muted = tr.mute != 0
                    Card(Modifier.weight(1f).fillMaxHeight(),
                        colors = CardDefaults.cardColors(containerColor = if (selected) cs.surfaceVariant else cs.surface)) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${i + 1}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                    color = if (selected) cs.primary else cs.onSurfaceVariant)
                                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${tr.level}", fontFamily = SloopFontFamily, color = cs.primary,
                                style = MaterialTheme.typography.titleMedium)
                            VerticalSlider(tr.level, 0, 127, { vm.controller.setTrackMix(i, it, muted) },
                                Modifier.weight(1f), unit)
                            Row(Modifier.fillMaxWidth().height(buttonH), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val tight = PaddingValues(horizontal = 2.dp)
                                val small = MaterialTheme.typography.labelMedium
                                val m = Modifier.weight(1f).fillMaxHeight()
                                if (muted) Button(onClick = { vm.controller.setTrackMix(i, tr.level, false) },
                                    modifier = m, contentPadding = tight,
                                    colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) {
                                    Text("MUTED", style = small, maxLines = 1)
                                }
                                else OutlinedButton(onClick = { vm.controller.setTrackMix(i, tr.level, true) },
                                    modifier = m, contentPadding = tight) { Text("Mute", style = small, maxLines = 1) }
                                if (selected) Button(onClick = {}, enabled = false, modifier = m, contentPadding = tight) {
                                    Text("EDITING", style = small, maxLines = 1)
                                }
                                else Button(onClick = { vm.controller.selectTrack(i) }, modifier = m, contentPadding = tight) {
                                    Text("Edit", style = small, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
