package com.locxx.app



import androidx.compose.animation.AnimatedVisibility

import androidx.compose.animation.core.tween

import androidx.compose.animation.slideInHorizontally

import androidx.compose.animation.slideOutHorizontally

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.BoxScope

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.unit.IntOffset

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.Shadow

import androidx.compose.ui.text.font.FontWeight



/** Fly-in duration; must match the enter spec in [YourTurnToRollCue]. */

const val YourTurnToRollCueEnterMs = 380



/** Time the label stays centered after fly-in, before flying out. */

const val YourTurnToRollCueCenterHoldMs = 1_000



/** Use in `delay()` before setting [YourTurnToRollCue] `visible = false` (after fly-in + hold). */

const val YourTurnToRollCueMsBeforeDismiss =

    YourTurnToRollCueEnterMs + YourTurnToRollCueCenterHoldMs



private val cueSlideSpec = tween<IntOffset>(durationMillis = YourTurnToRollCueEnterMs)



/**

 * Brief centered message when it is this device's turn to roll (LAN). Flies in from the left,

 * pauses at center, then flies out the right when [visible] becomes false. No scrim — only the

 * label is drawn so touches pass through to the board and Roll button except on the glyphs.

 */

@Composable

fun BoxScope.YourTurnToRollCue(

    visible: Boolean,

    modifier: Modifier = Modifier

) {

    AnimatedVisibility(

        visible = visible,

        modifier = modifier.align(Alignment.Center),

        enter = slideInHorizontally(

            animationSpec = cueSlideSpec,

            initialOffsetX = { fullWidth -> -fullWidth }

        ),

        exit = slideOutHorizontally(

            animationSpec = cueSlideSpec,

            targetOffsetX = { fullWidth -> fullWidth }

        )

    ) {

        Box(

            modifier = Modifier.fillMaxWidth(),

            contentAlignment = Alignment.Center

        ) {

            Text(

                text = "Your turn — roll!",

                style = MaterialTheme.typography.displaySmall.copy(

                    fontWeight = FontWeight.Bold,

                    shadow = Shadow(

                        color = Color.Black.copy(alpha = 0.55f),

                        offset = Offset(1.5f, 1.5f),

                        blurRadius = 6f

                    )

                ),

                color = MaterialTheme.colorScheme.primary

            )

        }

    }

}

