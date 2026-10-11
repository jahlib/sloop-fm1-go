// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sloop.go.device.DeviceState
import kotlinx.coroutines.delay

/** Pinned knobs shown in a side panel on every page; each is `scope * 1000 + id` (scope 0 = track param, 1 = global). */
class Favorites(private val prefs: SharedPreferences) {
    val items = mutableStateListOf<Int>().apply {
        prefs.getString("items", "")?.split(",")?.mapNotNull { it.toIntOrNull() }?.let(::addAll)
    }
    var open by mutableStateOf(prefs.getBoolean("open", false))
        private set

    fun toggle() { open = !open; prefs.edit().putBoolean("open", open).apply() }

    fun add(scope: Int, id: Int): Boolean {
        val k = scope * 1000 + id
        if (k in items) return false
        items.add(k); save(); return true
    }

    fun remove(key: Int) { items.remove(key); save() }

    private fun save() = prefs.edit().putString("items", items.joinToString(",")).apply()
}

val LocalFavorites = compositionLocalOf<Favorites?> { null }

/** Long-press handler for a knob: pins it to the favorites panel. */
@Composable
fun rememberFavAdder(): (Int, Int) -> Unit {
    val favs = LocalFavorites.current
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    return { scope, id ->
        if (favs != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            Toast.makeText(ctx, if (favs.add(scope, id)) "Added to favorites" else "Already in favorites",
                Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun FavoritesPanel(vm: SloopViewModel, state: DeviceState, favs: Favorites) {
    var pending by remember { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(pending) { if (pending != null) { delay(4000); pending = null } }
    Surface(Modifier.width(88.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            items(favs.items.toList(), key = { it }) { key ->
                val scope = key / 1000
                val id = key % 1000
                val desc = if (scope == 0) state.pdesc.getOrNull(id) else state.gdesc.getOrNull(id)
                if (desc != null) Box(Modifier.fillMaxWidth()) {
                    ParamControl(desc, if (scope == 0) state.paramValue(id) else state.globalValue(id),
                        onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); pending = key }) { v ->
                        vm.controller.setParam(scope, id, v)
                    }
                    if (pending == key) Box(
                        Modifier.matchParentSize().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f))
                            .clickable { pending = null; favs.remove(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove from favorites",
                            modifier = Modifier.padding(4.dp).fillMaxSize(),
                            tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}
