package com.locxx.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.locxx.rules.LocXXRules

/**
 * Summary strip for the main menu. Full score sheet and dice live on [SinglePlayerGameScreen].
 */
@Composable
fun SinglePlayerSection(
    vm: LocXXViewModel,
    modifier: Modifier = Modifier,
    onOpenGameBoard: () -> Unit
) {
    val match by vm.match.collectAsState()
    val lastRoll by vm.lastRoll.collectAsState()

    val sheet = match?.playerSheets?.getOrNull(0) ?: return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenGameBoard)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Single player", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Score: ${LocXXRules.totalScore(sheet)}  Penalties: ${sheet.penalties}",
                    style = MaterialTheme.typography.bodyLarge
                )
                lastRoll?.let { r ->
                    Text(
                        "Last roll: W1=${r.white1} W2=${r.white2} R=${r.red} Y=${r.yellow} G=${r.green} B=${r.blue}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Tap to open game board (landscape)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
