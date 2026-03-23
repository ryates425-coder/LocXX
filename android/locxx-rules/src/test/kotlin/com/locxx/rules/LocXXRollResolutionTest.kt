package com.locxx.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocXXRollResolutionTest {

    @Test
    fun usedWhiteSumLeavesNoFurtherMoves() {
        val sheet = PlayerSheet()
        val roll = DiceRoll(2, 3, 1, 1, 1, 1) // white sum 5
        val movesUnset = LocXXRollResolution.legalActiveMoves(
            roll,
            sheet,
            enumValues<DieColor>().toSet(),
            WhitePathChoice.Unset,
            emptySet()
        )
        assertTrue(movesUnset.any { it is LegalMove.WhiteSum && it.row == RowId.RED })
        val afterWhite = RollResolutionState(roll, WhitePathChoice.UsedWhiteSum)
        val movesAfter = LocXXRollResolution.legalActiveMoves(
            roll,
            sheet,
            enumValues<DieColor>().toSet(),
            afterWhite.pathChoice,
            afterWhite.whiteUsedForColor
        )
        assertTrue(movesAfter.isEmpty())
    }

    @Test
    fun colorComboConsumesWhiteDie() {
        val sheet = PlayerSheet()
        val allDice = enumValues<DieColor>().toSet()
        // white1=3, white2=2, red=4 -> white1+red=7, white2+red=6
        val roll = DiceRoll(3, 2, 4, 1, 1, 1)
        val moves0 = LocXXRollResolution.legalActiveMoves(
            roll, sheet, allDice, WhitePathChoice.Unset, emptySet()
        )
        val red7 = moves0.filterIsInstance<LegalMove.ColorCombo>()
            .firstOrNull { it.row == RowId.RED && it.value == 7 && it.whiteDie == DieColor.WHITE1 }
        assertTrue(red7 != null, "expected WHITE1+RED on red")
        val res1 = LocXXRollResolution.afterMove(RollResolutionState(roll), red7!!)
        assertEquals(WhitePathChoice.UsingColorCombos, res1.pathChoice)
        assertEquals(setOf(DieColor.WHITE1), res1.whiteUsedForColor)
        val moves1 = LocXXRollResolution.legalActiveMoves(
            roll, sheet, allDice, res1.pathChoice, res1.whiteUsedForColor
        )
        assertFalse(
            moves1.any {
                it is LegalMove.ColorCombo && it.whiteDie == DieColor.WHITE1
            },
            "WHITE1 should not be reusable in same roll"
        )
    }

    @Test
    fun afterMoveWhiteSumSetsPath() {
        val roll = DiceRoll(1, 1, 1, 1, 1, 1)
        val res = LocXXRollResolution.afterMove(
            RollResolutionState(roll),
            LegalMove.WhiteSum(RowId.RED, 2)
        )
        assertEquals(WhitePathChoice.UsedWhiteSum, res.pathChoice)
    }
}
