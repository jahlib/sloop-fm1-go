// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
    SAMPLES("Samples", Icons.Filled.LibraryMusic),
    DRUMSYNTH("Drum synth", Icons.Filled.Album),
    SONG("Song", Icons.Filled.Timeline),
    MIDI("MIDI patterns", Icons.Filled.FolderOpen),
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
            // the menu stays open, so several steps can be undone in a row
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { vm.controller.undo() }, enabled = state.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = { vm.controller.redo() }, enabled = state.canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
            }
            HorizontalDivider()
            Tab.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.title) },
                    leadingIcon = { Icon(t.icon, contentDescription = null) },
                    onClick = { tab = t; menuOpen = false },
                )
            }
        }
    }

    // play/stop + the page menu, pinned top-left on every screen (the sequencer keeps it in its own row);
    // both disabled until the FM-1 is connected
    val navButtons: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.controller.setPlaying(!state.playing) },
                enabled = state.link == Link.READY) {
                Icon(if (state.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Stop",
                    tint = if (state.playing) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = state.link == Link.READY) {
                    Icon(Icons.Filled.Menu, contentDescription = "Navigation")
                }
                navMenu()
            }
            // FM6 page in Store mode: SEND stays reachable without scrolling back up
            val fm6 = vm.fm6
            if (tab == Tab.FM6 && state.link == Link.READY && state.fm6Supported && !fm6.live) {
                val can = !fm6.busy && state.trackEngine(vm.fm6Track(state)) == state.fm6Engine
                val tight = PaddingValues(horizontal = 10.dp)
                val send = Modifier.padding(start = 2.dp, end = 8.dp).height(30.dp)
                if (fm6.dirty) Button(onClick = { vm.fm6SendNow() }, enabled = can, modifier = send,
                    contentPadding = tight) { Text("SEND") }
                else OutlinedButton(onClick = { vm.fm6SendNow() }, enabled = can, modifier = send,
                    contentPadding = tight) { Text("SEND") }
            }
        }
    }

    Scaffold { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (tab) {
                Tab.PARAMS -> ParamsScreen(vm, state, onDevice = { tab = Tab.SETTINGS })
                Tab.SEQ -> SequencerScreen(vm, state, onDevice = { tab = Tab.SETTINGS }, nav = navButtons)
                Tab.FM6 -> Fm6Screen(vm, state, onDevice = { tab = Tab.SETTINGS })
                Tab.SAMPLES -> SamplesScreen(vm, state, onDevice = { tab = Tab.SETTINGS })
                Tab.DRUMSYNTH -> DrumSynthScreen(vm, state, onDevice = { tab = Tab.SETTINGS }, nav = navButtons)
                Tab.SONG -> SongScreen(vm, state, onDevice = { tab = Tab.SETTINGS })
                Tab.MIDI -> MidiBrowserScreen(vm, state, openSequencer = { tab = Tab.SEQ })
                Tab.MIX -> MixerScreen(vm, state, onDevice = { tab = Tab.SETTINGS })
                Tab.SETTINGS -> ConnectScreen(vm, state)
            }
            // the sequencer embeds the cluster in its control row only once the device info is loaded
            val ready = state.link == Link.READY && state.info != null
            val seqHasNav = ready && (tab == Tab.SEQ || (tab == Tab.DRUMSYNTH && (state.info?.proto ?: 0) >= 10))
            if (!seqHasNav) {
                Surface(Modifier.align(Alignment.TopStart).padding(6.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp, shadowElevation = 4.dp) {
                    navButtons()
                }
            }
        }
    }
    UpdateDialog(vm)
}

@Composable
private fun UpdateDialog(vm: SloopViewModel) {
    if (!vm.updateDialog) return
    val u = vm.update
    val current = com.sloop.go.update.Updater.installedVersion(androidx.compose.ui.platform.LocalContext.current)
    val (title, body) = when (u) {
        is SloopViewModel.Update.Available ->
            "Update available" to "Sloop Go ${u.release.tag} is out (installed: $current). Download it now and install?"
        is SloopViewModel.Update.Downloading ->
            "Downloading ${u.release.tag}" to "${(u.progress * 100).toInt()}%"
        is SloopViewModel.Update.Ready ->
            "Ready to install" to "${u.release.apkName} is downloaded. If Android asks, allow installs from Sloop Go " +
                "and tap Install again."
        is SloopViewModel.Update.Failed -> "Update failed" to u.message
        else -> return
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.dismissUpdate() },
        title = { Text(title) },
        text = {
            Column {
                Text(body)
                if (u is SloopViewModel.Update.Downloading)
                    androidx.compose.material3.LinearProgressIndicator(progress = { u.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = {
            when (u) {
                is SloopViewModel.Update.Available ->
                    androidx.compose.material3.TextButton(onClick = { vm.downloadUpdate() }) { Text("Download & install") }
                is SloopViewModel.Update.Ready ->
                    androidx.compose.material3.TextButton(onClick = { vm.installUpdate() }) { Text("Install") }
                else -> {}
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { vm.dismissUpdate() }) {
                Text(if (u is SloopViewModel.Update.Available) "Later" else "Close")
            }
        },
    )
}

/** Extra space the fixed top-left nav cluster needs; screens other than Device pad their header by it. */
val NAV_INSET = 100.dp

/** Non-sticky header rendered as the first scrollable item, so it slides away on scroll. */
@Composable
fun ScreenHeader(title: String, state: DeviceState, navInset: Dp = 0.dp) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp + navInset, top = 14.dp, end = 16.dp, bottom = 6.dp)) {
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
                fontFamily = SloopFontFamily,
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
