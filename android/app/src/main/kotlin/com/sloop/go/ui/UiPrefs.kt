// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which colours the app wears: the phone's setting, or one of the two forced. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"), DARK("Dark"), LIGHT("Light");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Interface settings of the Device page; kept across restarts in the "ui" preferences. */
class UiPrefs(app: Application) {
    private val prefs = app.getSharedPreferences("ui", 0)

    var theme by mutableStateOf(ThemeMode.of(prefs.getString("theme", null))); private set

    fun pickTheme(mode: ThemeMode) {
        theme = mode
        prefs.edit().putString("theme", mode.name).apply()
    }
}
