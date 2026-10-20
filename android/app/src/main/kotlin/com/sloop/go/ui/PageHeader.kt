// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState

/**
 * The page header every main page shares (it is the sequencer's control row): Play and Menu pinned at the left, a status
 * pinned at the right, and the page's controls between them in a row that scrolls sideways under both. The status's
 * measured width is reserved in 40dp steps so the controls do not move when its text changes.
 */
@Composable
fun PinnedHeader(nav: @Composable () -> Unit, status: String, statusColor: Color = Color.Unspecified,
                 content: @Composable RowScope.() -> Unit) {
    var statusPx by remember { mutableIntStateOf(0) }
    var navPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bucket = with(density) { 40.dp.toPx() }
    val statusW = (((statusPx + bucket - 1) / bucket).toInt() * 40).dp
    val navW = with(density) { navPx.toDp() }
    val surface = MaterialTheme.colorScheme.surface
    BoxWithConstraints(Modifier.fillMaxWidth().background(surface)) {
        Row(Modifier.align(Alignment.Center).fillMaxWidth().horizontalScroll(rememberScrollState()).clipToBounds()
                .padding(start = navW + 4.dp, end = statusW + 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            content = content)
        Box(Modifier.align(Alignment.CenterStart).background(surface).padding(start = 4.dp)
            .onSizeChanged { navPx = it.width }, contentAlignment = Alignment.Center) { nav() }
        Text(status, Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth).background(surface)
            .padding(start = 8.dp, end = 16.dp).onSizeChanged { statusPx = it.width },
            color = if (statusColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else statusColor,
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End)
    }
}

/** Divider between blocks of a header row. */
@Composable
fun HeaderSep() = VerticalDivider(Modifier.padding(horizontal = 4.dp).height(24.dp))

/** The queue / error text most pages show at the right of their header, with [extra] in front. */
fun headerStatus(state: DeviceState, extra: String? = null): String =
    state.queueError ?: listOfNotNull(extra, if (state.queuedEdits > 0) "queue ${state.queuedEdits}" else null).joinToString(" · ")
