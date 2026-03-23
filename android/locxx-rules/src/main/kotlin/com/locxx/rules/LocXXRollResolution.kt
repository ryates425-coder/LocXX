package com.locxx.rules

/**
 * Qwixx active-player turn: either use **both whites together** once on one row, **or** use
 * color combos (one white + that row's color die) with each white die used at most once.
 */
enum class WhitePathChoice {
    /** No cross yet this roll; white-sum and color moves are both allowed (if legal). */
    Unset,

    /** Chose the combined white dice sum; no further crosses this roll. */
    UsedWhiteSum,

    /** At least one color combo; white dice tracked in [RollResolutionState.whiteUsedForColor]. */
    UsingColorCombos
}

data class RollResolutionState(
    val roll: DiceRoll,
    val pathChoice: WhitePathChoice = WhitePathChoice.Unset,
    val whiteUsedForColor: Set<DieColor> = emptySet()
)

sealed class LegalMove {
    abstract val row: RowId
    abstract val value: Int

    data class WhiteSum(override val row: RowId, override val value: Int) : LegalMove()

    /** [whiteDie] is [DieColor.WHITE1] or [DieColor.WHITE2]. */
    data class ColorCombo(override val row: RowId, override val value: Int, val whiteDie: DieColor) : LegalMove()
}

object LocXXRollResolution {

    fun legalActiveMoves(
        roll: DiceRoll,
        sheet: PlayerSheet,
        diceInPlay: Set<DieColor>,
        pathChoice: WhitePathChoice,
        whiteUsedForColor: Set<DieColor>
    ): List<LegalMove> {
        when (pathChoice) {
            WhitePathChoice.UsedWhiteSum -> return emptyList()
            WhitePathChoice.Unset -> {
                val out = ArrayList<LegalMove>()
                val sum = roll.whiteSum()
                for (row in RowId.entries) {
                    val st = sheet.rows[row] ?: continue
                    if (LocXXRules.canCrossValue(row, st, sum)) {
                        out.add(LegalMove.WhiteSum(row, sum))
                    }
                }
                for (row in RowId.entries) {
                    val colorDie = row.dieColor()
                    if (!diceInPlay.contains(colorDie)) continue
                    for (white in listOf(DieColor.WHITE1, DieColor.WHITE2)) {
                        val comboSum = roll.value(white) + roll.value(colorDie)
                        if (LocXXRules.colorComboLegal(sheet, row, comboSum, diceInPlay)) {
                            out.add(LegalMove.ColorCombo(row, comboSum, white))
                        }
                    }
                }
                return out
            }
            WhitePathChoice.UsingColorCombos -> {
                val out = ArrayList<LegalMove>()
                for (row in RowId.entries) {
                    val colorDie = row.dieColor()
                    if (!diceInPlay.contains(colorDie)) continue
                    for (white in listOf(DieColor.WHITE1, DieColor.WHITE2)) {
                        if (white in whiteUsedForColor) continue
                        val comboSum = roll.value(white) + roll.value(colorDie)
                        if (LocXXRules.colorComboLegal(sheet, row, comboSum, diceInPlay)) {
                            out.add(LegalMove.ColorCombo(row, comboSum, white))
                        }
                    }
                }
                return out
            }
        }
    }

    fun afterMove(resolution: RollResolutionState, move: LegalMove): RollResolutionState {
        return when (move) {
            is LegalMove.WhiteSum -> resolution.copy(pathChoice = WhitePathChoice.UsedWhiteSum)
            is LegalMove.ColorCombo -> resolution.copy(
                pathChoice = WhitePathChoice.UsingColorCombos,
                whiteUsedForColor = resolution.whiteUsedForColor + move.whiteDie
            )
        }
    }
}
