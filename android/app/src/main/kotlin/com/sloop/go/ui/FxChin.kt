// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState

/**
 * A strip along the right edge of the piano roll (shown with the FX switch in the sequencer's toolbar): the selected synth
 * track's DST CHO DLY REV, held at their maximum while pressed, and its MUTE / SOLO. It keeps the edge of the roll free for
 * notes: a touch right at the edge of the screen is Android's.
 */
@Composable
fun FxChin(vm: SloopViewModel, state: DeviceState, modifier: Modifier = Modifier) {
    val ctl = vm.controller
    val lv by ctl.transport.collectAsState()
    val t = state.selectedTrack
    val live = (state.info?.proto ?: 0) >= 12
    val muted = ((lv?.mutes ?: 0) shr t) and 1 == 1
    val solo = ((lv?.solos ?: 0) shr t) and 1 == 1
    Column(modifier.width(76.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for ((name, id) in listOf("DST" to 33, "CHO" to 34, "DLY" to 35, "REV" to 36))
            Pad(name, false, Modifier.fillMaxWidth().weight(1f),
                down = { ctl.holdParam(t, id, true) }, up = { ctl.holdParam(t, id, false) })
        if (live) {
            Pad("MUTE", muted, Modifier.fillMaxWidth().weight(1f), small = true, tap = { ctl.liveTap(6, t) })
            Pad("SOLO", solo, Modifier.fillMaxWidth().weight(1f), small = true, tap = { ctl.liveTap(6, 4 + t) })
        }
    }
}
