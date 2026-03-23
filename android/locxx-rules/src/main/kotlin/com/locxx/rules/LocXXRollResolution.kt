package com.locxx.rules

/**
 * Qwixx scoring for one player on one roll:
 *
 * 1. **All players** may (optionally) use the sum of the two white dice to cross **one** number on **one** row.
 * 2. **Only the active player** may (optionally) add **one** white die + **one** colored die and cross in that
 *    color’s row. **At most one** such color combo per roll (only one colored die may be used for scoring this roll).
 *
 * Using the white-sum option does **not** prevent the active player from also using color combos in the same roll.
 */
data class RollResolutionState(
    val roll: DiceRoll,
    /** This player has already crossed a number using the white+white sum this roll. */
    val whiteSumUsed: Boolean = false,
    /** After the one allowed white+color cross, records which white was used (set has 0 or 1 element). */
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

    /**
     * @param isActivePlayer If false (non-active players in multiplayer), only white-sum moves are returned.
     */
    fun legalMoves(
        isActivePlayer: Boolean,
        roll: DiceRoll,
        sheet: PlayerSheet,
        diceInPlay: Set<DieColor>,
        whiteSumUsed: Boolean,
        whiteUsedForColor: Set<DieColor>
    ): List<LegalMove> {
        val out = ArrayList<LegalMove>()
        if (!whiteSumUsed) {
            val sum = roll.whiteSum()
            for (row in RowId.entries) {
                val st = sheet.rows[row] ?: continue
                if (LocXXRules.canCrossValue(row, st, sum)) {
                    out.add(LegalMove.WhiteSum(row, sum))
                }
            }
        }
        // At most one white+color cross per roll (one colored die).
        if (isActivePlayer && whiteUsedForColor.isEmpty()) {
            // If both whites show the same value, white+color sums are identical for WHITE1 vs WHITE2 — only one move.
            val whitesForColor =
                if (roll.white1 == roll.white2) listOf(DieColor.WHITE1)
                else listOf(DieColor.WHITE1, DieColor.WHITE2)
            for (row in RowId.entries) {
                val colorDie = row.dieColor()
                if (!diceInPlay.contains(colorDie)) continue
                for (white in whitesForColor) {
                    val comboSum = roll.value(white) + roll.value(colorDie)
                    if (LocXXRules.colorComboLegal(sheet, row, comboSum, diceInPlay)) {
                        out.add(LegalMove.ColorCombo(row, comboSum, white))
                    }
                }
            }
        }
        return out
    }

    fun legalMoves(
        isActivePlayer: Boolean,
        roll: DiceRoll,
        sheet: PlayerSheet,
        diceInPlay: Set<DieColor>,
        resolution: RollResolutionState
    ): List<LegalMove> = legalMoves(
        isActivePlayer = isActivePlayer,
        roll = roll,
        sheet = sheet,
        diceInPlay = diceInPlay,
        whiteSumUsed = resolution.whiteSumUsed,
        whiteUsedForColor = resolution.whiteUsedForColor
    )

    fun afterMove(resolution: RollResolutionState, move: LegalMove): RollResolutionState {
        return when (move) {
            is LegalMove.WhiteSum -> resolution.copy(whiteSumUsed = true)
            is LegalMove.ColorCombo -> resolution.copy(
                whiteUsedForColor = resolution.whiteUsedForColor + move.whiteDie
            )
        }
    }
}
