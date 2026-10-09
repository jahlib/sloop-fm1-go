// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

/** The tracks as vertical channel strips side by side: name, a tall fader, level, and big Mute / Edit buttons. */
@Composable
fun MixerScreen(vm: SloopViewModel, state: DeviceState, onDevice: () -> Unit) {
    if (state.link != Link.READY || state.tracks == null || state.info == null) {
        NotReady("Mixer", state, onDevice)
        return
    }
    val info = state.info
    val tracks = state.tracks
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Mixer", state, NAV_INSET)
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tracks.tracks.forEachIndexed { i, tr ->
                val selected = i == state.selectedTrack
                val isDrum = tr.engine >= info.nengines
                val name = if (isDrum) "DRUM" else info.engines.getOrNull(tr.engine) ?: "TRACK ${i + 1}"
                val muted = tr.mute != 0
                Card(Modifier.weight(1f).fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = if (selected) cs.surfaceVariant else cs.surface)) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("TRACK ${i + 1}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${tr.level}", fontFamily = SloopFontFamily, color = cs.primary,
                            style = MaterialTheme.typography.titleMedium)
                        VerticalSlider(tr.level, 0, 127, { vm.controller.setTrackMix(i, it, muted) }, Modifier.weight(1f))
                        if (muted) Button(onClick = { vm.controller.setTrackMix(i, tr.level, false) },
                            modifier = Modifier.fillMaxWidth().height(52.dp), contentPadding = ButtonDefaults.TextButtonContentPadding,
                            colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) { Text("MUTED") }
                        else OutlinedButton(onClick = { vm.controller.setTrackMix(i, tr.level, true) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Mute") }
                        if (selected) Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp),
                            contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("EDITING", maxLines = 1) }
                        else Button(onClick = { vm.controller.selectTrack(i) }, modifier = Modifier.fillMaxWidth().height(52.dp),
                            contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Edit") }
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}
