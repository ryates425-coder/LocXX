package com.locxx.app

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Applies [WindowInsets.safeDrawing] as padding, merged with minimum margins.
 * Some OEMs (including Samsung) report 0 insets unless decor fits system windows is disabled,
 * or return incomplete cutout values — minimums keep content clear of punch-holes and bars.
 */
@Composable
fun Modifier.safeAreaPadding(): Modifier {
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val minHorizontal = if (landscape) 24.dp else 16.dp
    val minTop = 28.dp
    val minBottom = 24.dp
    val startInset = when (layoutDirection) {
        LayoutDirection.Ltr -> safe.calculateLeftPadding(layoutDirection)
        LayoutDirection.Rtl -> safe.calculateRightPadding(layoutDirection)
    }
    val endInset = when (layoutDirection) {
        LayoutDirection.Ltr -> safe.calculateRightPadding(layoutDirection)
        LayoutDirection.Rtl -> safe.calculateLeftPadding(layoutDirection)
    }
    return this.padding(
        PaddingValues(
            start = maxOf(startInset, minHorizontal),
            top = maxOf(safe.calculateTopPadding(), minTop),
            end = maxOf(endInset, minHorizontal),
            bottom = maxOf(safe.calculateBottomPadding(), minBottom)
        )
    )
}
