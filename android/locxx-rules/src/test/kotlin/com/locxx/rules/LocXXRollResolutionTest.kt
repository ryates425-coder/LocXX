package com.locxx.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocXXRollResolutionTest {

    private val allDice = enumValues<DieColor>().toSet()

    @Test
    fun passivePlayerNeverGetsColorCombos() {
        val sheet = PlayerSheet()
        val roll = DiceRoll(3, 2, 4, 1, 1, 1)
        val res = RollResolutionState(roll)
        val moves = LocXXRollResolution.legalMoves(
            isActivePlayer = false,
            roll = roll,
            sheet = sheet,
            diceInPlay = allDice,
            resolution = res
        )
        assertTrue(moves.all { it is LegalMove.WhiteSum })
        assertFalse(moves.any { it is LegalMove.ColorCombo })
    }

    @Test
    fun passivePlayerNoMovesAfterWhiteSum() {
        val sheet = PlayerSheet()
        val roll = DiceRoll(2, 3, 1, 1, 1, 1)
        val afterWhite = RollResolutionState(roll, whiteSumUsed = true)
        val moves = LocXXRollResolution.legalMoves(
            isActivePlayer = false,
            roll = roll,
            sheet = sheet,
            diceInPlay = allDice,
            resolution = afterWhite
        )
        assertTrue(moves.isEmpty())
    }

    @Test
    fun activePlayerStillHasColorMovesAfterWhiteSum() {
        val sheet = PlayerSheet()
        val roll = DiceRoll(3, 2, 4, 1, 1, 1) // white1+red=7 on RED
        val afterWhite = RollResolutionState(roll, whiteSumUsed = true)
        val moves = LocXXRollResolution.legalMoves(
            isActivePlayer = true,
            roll = roll,
            sheet = sheet,
            diceInPlay = allDice,
            resolution = afterWhite
        )
        assertTrue(
            moves.any { it is LegalMove.ColorCombo && it.row == RowId.RED && (it as LegalMove.ColorCombo).value == 7 },
            "active player should still see color combos after using white sum"
        )
    }

    @Test
    fun colorComboConsumesWhiteDie() {
        val sheet = PlayerSheet()
        val roll = DiceRoll(3, 2, 4, 1, 1, 1)
        val moves0 = LocXXRollResolution.legalMoves(
            isActivePlayer = true,
            roll = roll,
            sheet = sheet,
            diceInPlay = allDice,
            resolution = RollResolutionState(roll)
        )
        val red7 = moves0.filterIsInstance<LegalMove.ColorCombo>()
            .firstOrNull { it.row == RowId.RED && it.value == 7 && it.whiteDie == DieColor.WHITE1 }
        assertTrue(red7 != null, "expected WHITE1+RED on red")
        val res1 = LocXXRollResolution.afterMove(RollResolutionState(roll), red7!!)
        assertEquals(setOf(DieColor.WHITE1), res1.whiteUsedForColor)
        val moves1 = LocXXRollResolution.legalMoves(
            isActivePlayer = true,
            roll = roll,
            sheet = sheet,
            diceInPlay = allDice,
            resolution = res1
        )
        assertFalse(
            moves1.any { it is LegalMove.ColorCombo },
            "only one color combo per roll — no further white+color crosses"
        )
    }

    @Test
    fun secondColorComboNotAllowedAfterFirst() {
        val sheet = PlayerSheet()
        val roll = DiceRoll(1, 1, 1, 1, 1, 1)
        val moves0 = LocXXRollResolution.legalMoves(
            isActivePlayer = true,
            roll = roll,
            sheet = sheet,
            diceInPlay = allDice,
            resolution = RollResolutionState(roll)
        )
        val yellow2 = moves0.filterIsInstance<LegalMove.ColorCombo>()
            .firstOrNull { it.row == RowId.YELLOW && it.value == 2 }
        assertTrue(yellow2 != null, "YELLOW row should have white+yellow=2 (equal whites → one die choice)")
        val res1 = LocXXRollResolution.afterMove(RollResolutionState(roll), yellow2!!)
        val moves1 = LocXXRollResolution.legalMoves(
            isActivePlayer = true,
            roll = roll,
            sheet = sheet,
            diceInPlay = allDice,
            resolution = res1
        )
        assertFalse(
            moves1.any { it is LegalMove.ColorCombo },
            "cannot use a second colored die after first color cross"
        )
    }

    @Test
    fun equalWhiteDiceNoDuplicateColorComboForSameRowAndSum() {
        val sheet = PlayerSheet()
        val roll = DiceRoll(4, 4, 3, 1, 1, 1) // white1==white2; WHITE+RED = 4+3=7
        val moves = LocXXRollResolution.legalMoves(
            isActivePlayer = true,
            roll = roll,
            sheet = sheet,
            diceInPlay = allDice,
            resolution = RollResolutionState(roll)
        )
        val red7 = moves.filterIsInstance<LegalMove.ColorCombo>()
            .filter { it.row == RowId.RED && it.value == 7 }
        assertEquals(1, red7.size, "should not list WHITE1 and WHITE2 separately when whites match")
        assertEquals(DieColor.WHITE1, red7.first().whiteDie)
    }

    @Test
    fun afterMoveWhiteSumSetsFlag() {
        val roll = DiceRoll(1, 1, 1, 1, 1, 1)
        val res = LocXXRollResolution.afterMove(
            RollResolutionState(roll),
            LegalMove.WhiteSum(RowId.RED, 2)
        )
        assertTrue(res.whiteSumUsed)
    }
}
