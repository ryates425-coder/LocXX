package com.locxx.app

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private fun PaddingValues.startInset(layoutDirection: LayoutDirection) = when (layoutDirection) {
    LayoutDirection.Ltr -> calculateLeftPadding(layoutDirection)
    LayoutDirection.Rtl -> calculateRightPadding(layoutDirection)
}

private fun PaddingValues.endInset(layoutDirection: LayoutDirection) = when (layoutDirection) {
    LayoutDirection.Ltr -> calculateRightPadding(layoutDirection)
    LayoutDirection.Rtl -> calculateLeftPadding(layoutDirection)
}

/**
 * Pads for system bars, display cutout (punch-hole / notch), and [safeDrawing], using the
 * maximum of each edge. Some devices (e.g. Samsung landscape) under-report [safeDrawing] for the
 * cutout; merging [WindowInsets.displayCutout] avoids obscuring score rows by the camera.
 */
@Composable
fun Modifier.safeAreaPadding(): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Landscape punch-holes (e.g. Galaxy A55): OEM may report 0 cutout; keep a generous floor.
    val minHorizontal = if (landscape) 40.dp else 16.dp
    val minTop = if (landscape) 32.dp else 28.dp
    val minBottom = 24.dp

    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
    val barsAndCutout = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .asPaddingValues()

    val start = maxOf(
        minHorizontal,
        safeDrawing.startInset(layoutDirection),
        barsAndCutout.startInset(layoutDirection)
    )
    val end = maxOf(
        minHorizontal,
        safeDrawing.endInset(layoutDirection),
        barsAndCutout.endInset(layoutDirection)
    )
    val top = maxOf(
        minTop,
        safeDrawing.calculateTopPadding(),
        barsAndCutout.calculateTopPadding()
    )
    val bottom = maxOf(
        minBottom,
        safeDrawing.calculateBottomPadding(),
        barsAndCutout.calculateBottomPadding()
    )

    return this.padding(
        PaddingValues(start = start, top = top, end = end, bottom = bottom)
    )
}
