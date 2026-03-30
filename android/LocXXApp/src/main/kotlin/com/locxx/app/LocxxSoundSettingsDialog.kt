package com.locxx.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun LocxxSoundSettingsDialog(
    appContext: Context,
    onDismiss: () -> Unit
) {
    val initial = remember { appContext.readLocxxSoundPrefs() }
    var markDing by remember { mutableStateOf(initial.markDing) }
    var undoMarkDing by remember { mutableStateOf(initial.undoMarkDing) }
    var penaltyBuzzer by remember { mutableStateOf(initial.penaltyBuzzer) }
    var diceRollSound by remember { mutableStateOf(initial.diceRollSound) }
    var diceFlutter by remember { mutableStateOf(initial.diceRollFlutterEnabled) }
    var lockHorn by remember { mutableStateOf(initial.rowLockHornEnabled) }
    var inclusivityDice by remember { mutableStateOf(initial.inclusivityDiceEnabled) }

    fun persist() {
        appContext.writeLocxxSoundPrefs(
            LocxxSoundPrefs(
                markDing = markDing,
                undoMarkDing = undoMarkDing,
                penaltyBuzzer = penaltyBuzzer,
                diceRollSound = diceRollSound,
                diceRollFlutterEnabled = diceFlutter,
                rowLockHornEnabled = lockHorn,
                inclusivityDiceEnabled = inclusivityDice
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Mark cell",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LocxxMarkDingVariant.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = markDing == option,
                            onClick = {
                                markDing = option
                                persist()
                            }
                        )
                        Text(
                            text = option.displayLabel(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp)
                        )
                        TextButton(
                            onClick = { playLocxxMarkDingPreview(option) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Preview")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Undo mark",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LocxxUndoMarkDingVariant.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = undoMarkDing == option,
                            onClick = {
                                undoMarkDing = option
                                persist()
                            }
                        )
                        Text(
                            text = option.displayLabel(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp)
                        )
                        TextButton(
                            onClick = { playLocxxUndoMarkDingPreview(option) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Preview")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Penalty",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LocxxPenaltyBuzzerVariant.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = penaltyBuzzer == option,
                            onClick = {
                                penaltyBuzzer = option
                                persist()
                            }
                        )
                        Text(
                            text = option.displayLabel(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp)
                        )
                        TextButton(
                            onClick = { playLocxxPenaltyBuzzerPreview(option) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Preview")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Other",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Dice roll sound", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = diceFlutter,
                        onCheckedChange = {
                            diceFlutter = it
                            persist()
                        }
                    )
                }
                Text(
                    text = "Dice style",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LocxxDiceRollSoundVariant.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = diceRollSound == option,
                            onClick = {
                                diceRollSound = option
                                persist()
                            }
                        )
                        Text(
                            text = option.displayLabel(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp)
                        )
                        TextButton(
                            onClick = { playLocxxDiceRollSoundPreview(option) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Preview")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Row lock fanfare", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = lockHorn,
                        onCheckedChange = {
                            lockHorn = it
                            persist()
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Inclusivity Dice", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "White dice 1: WLW tones. White dice 2: Progress Pride stripes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = inclusivityDice,
                        onCheckedChange = {
                            inclusivityDice = it
                            persist()
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            if (lockHorn) playSnotzeeBonusHorn()
                        },
                        enabled = lockHorn
                    ) {
                        Text("Preview fanfare")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f)
    )
}
