// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloop.go.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.sloop.go.device.DeviceState
import com.sloop.go.device.Link

private enum class Tab(val title: String, val icon: ImageVector) {
    PARAMS("Sound", Icons.Filled.Tune),
    SEQ("Sequencer", Icons.Filled.GridView),
    FM6("FM6 patches", Icons.Filled.GraphicEq),
    MIX("Mixer", Icons.Filled.Equalizer),
    SETTINGS("Device", Icons.Filled.Settings),
}

@Composable
fun App(vm: SloopViewModel, autoConnect: Boolean) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.PARAMS) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshDevices()
        if (autoConnect && state.link == Link.DISCONNECTED) vm.connectPreferred()
    }

    val navMenu: @Composable () -> Unit = {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (state.link == Link.READY) {
                DropdownMenuItem(
                    text = { Text(if (state.playing) "Stop" else "Play") },
                    leadingIcon = {
                        Icon(
                            if (state.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                        )
                    },
                    onClick = { vm.controller.setPlaying(!state.playing) },
                )
                HorizontalDivider()
            }
            Tab.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.title) },
                    leadingIcon = { Icon(t.icon, contentDescription = null) },
                    onClick = { tab = t; menuOpen = false },
                )
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            if (tab != Tab.SEQ) Box {
                ExtendedFloatingActionButton(
                    onClick = { menuOpen = true },
                    icon = { Icon(Icons.Filled.Menu, contentDescription = "Navigation") },
                    text = { Text(tab.title) },
                )
                navMenu()
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (tab) {
                Tab.PARAMS -> ParamsScreen(vm, state, onDevice = { tab = Tab.SETTINGS })
                Tab.SEQ -> SequencerScreen(vm, state, onDevice = { tab = Tab.SETTINGS }, nav = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.link == Link.READY) IconButton(
                            onClick = { vm.controller.setPlaying(!state.playing) }) {
                            Icon(if (state.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = "Play/Stop",
                                tint = if (state.playing) MaterialTheme.colorScheme.primary
                                    else LocalContentColor.current)
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Menu, contentDescription = "Navigation") }
                            navMenu()
                        }
                    }
                })
                Tab.FM6 -> Fm6Screen(vm, state, onDevice = { tab = Tab.SETTINGS })
                Tab.MIX -> MixerScreen(vm, state, onDevice = { tab = Tab.SETTINGS })
                Tab.SETTINGS -> ConnectScreen(vm, state)
            }
        }
    }
}

/** Non-sticky header rendered as the first scrollable item, so it slides away on scroll. */
@Composable
fun ScreenHeader(title: String, state: DeviceState) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 14.dp, 16.dp, 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when (state.link) {
                Link.READY -> SloopGreen
                Link.CONNECTING, Link.LOADING -> SloopYellow
                Link.DISCONNECTED -> MaterialTheme.colorScheme.outline
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Text(
                "  " + (if (state.deviceName.isNotBlank() && state.link == Link.READY)
                    state.deviceName else state.status) +
                    if (state.queuedEdits > 0) " · queued ${state.queuedEdits}" else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.queueError != null) {
            Text(state.queueError, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SplashScreen() {
    var filling by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { filling = true }
    val progress by animateFloatAsState(
        targetValue = if (filling) 1f else 0f,
        animationSpec = tween(2000, easing = FastOutSlowInEasing), label = "logo fill",
    )
    val transition = rememberInfiniteTransition(label = "particles")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(4800, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit",
    )
    val logo = painterResource(R.drawable.ic_launcher)
    val grayscale = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val colors = arrayOf(SloopBlue, SloopGreen, SloopYellow, SloopOrange)
                val center = Offset(size.width / 2f, size.height / 2f)
                repeat(24) { i ->
                    val angle = phase + i * (2 * PI / 24).toFloat()
                    val radius = 100.dp.toPx() + 6.dp.toPx() * sin(phase * 2 + i).toFloat()
                    drawCircle(colors[i % 4].copy(alpha = 0.3f + 0.5f * progress),
                        radius = (if (i % 3 == 0) 2.5.dp else 1.5.dp).toPx(),
                        center = center + Offset(cos(angle) * radius, sin(angle) * radius))
                }
            }
            Box(Modifier.size(174.dp)) {
                Image(logo, contentDescription = "SLOOP logo", modifier = Modifier.fillMaxSize(),
                    colorFilter = grayscale)
                Image(logo, contentDescription = null, modifier = Modifier.fillMaxSize().drawWithContent {
                    val content = this
                    clipRect(right = size.width * progress) { content.drawContent() }
                })
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("SLOOP GO", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground, letterSpacing = 4.sp)
        Spacer(Modifier.height(8.dp))
        Text("FM-1 companion", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
