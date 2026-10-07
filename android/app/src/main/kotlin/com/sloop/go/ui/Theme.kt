// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.sloop.go.R

// JetBrains Mono (variable weight), bundled under the SIL OFL 1.1 — see fonts/OFL.txt.
// One variable file serves every weight on API 28+ (wght axis); older releases synthesize bold.
val SloopFontFamily = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

private val baseTypography = Typography()

private val SloopTypography = Typography(
    displayLarge = baseTypography.displayLarge.copy(fontFamily = SloopFontFamily),
    displayMedium = baseTypography.displayMedium.copy(fontFamily = SloopFontFamily),
    displaySmall = baseTypography.displaySmall.copy(fontFamily = SloopFontFamily),
    headlineLarge = baseTypography.headlineLarge.copy(fontFamily = SloopFontFamily),
    headlineMedium = baseTypography.headlineMedium.copy(fontFamily = SloopFontFamily),
    headlineSmall = baseTypography.headlineSmall.copy(fontFamily = SloopFontFamily),
    titleLarge = baseTypography.titleLarge.copy(fontFamily = SloopFontFamily),
    titleMedium = baseTypography.titleMedium.copy(fontFamily = SloopFontFamily),
    titleSmall = baseTypography.titleSmall.copy(fontFamily = SloopFontFamily),
    bodyLarge = baseTypography.bodyLarge.copy(fontFamily = SloopFontFamily),
    bodyMedium = baseTypography.bodyMedium.copy(fontFamily = SloopFontFamily),
    bodySmall = baseTypography.bodySmall.copy(fontFamily = SloopFontFamily),
    labelLarge = baseTypography.labelLarge.copy(fontFamily = SloopFontFamily),
    labelMedium = baseTypography.labelMedium.copy(fontFamily = SloopFontFamily),
    labelSmall = baseTypography.labelSmall.copy(fontFamily = SloopFontFamily),
)

// SLOOP palette (from assets/logo/sloop-icon.svg).
val SloopBlue = Color(0xFF287CFF)
val SloopGreen = Color(0xFF1ECC70)
val SloopYellow = Color(0xFFFFC618)
val SloopOrange = Color(0xFFFF621A)

private val Dark = darkColorScheme(
    primary = SloopGreen,
    onPrimary = Color.Black,
    secondary = SloopBlue,
    tertiary = SloopOrange,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0E0E0E),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF3A3A3A),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0E8A45),
    secondary = SloopBlue,
    tertiary = SloopOrange,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE6E6E6),
    outline = Color(0xFFBDBDBD),
)

@Composable
fun SloopTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = colors, typography = SloopTypography, content = content)
}
