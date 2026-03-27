package com.locxx.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocXXRulesTest {

    @Test
    fun scoringTable() {
        assertEquals(1, LocXXRules.pointsForCrosses(1))
        assertEquals(3, LocXXRules.pointsForCrosses(2))
        assertEquals(78, LocXXRules.pointsForCrosses(12))
    }

    @Test
    fun rowPointsEmptyRowZero() {
        val sheet = PlayerSheet()
        assertEquals(0, LocXXRules.rowPoints(sheet, RowId.RED))
    }

    @Test
    fun rowPointsMatchesCrossCount() {
        var st = PlayerRowState()
        val r = rowValues(RowId.RED)
        for (i in 0 until 4) {
            st = LocXXRules.applyCross(RowId.RED, st, r[i]).getOrThrow()
        }
        val rows = enumValues<RowId>().associateWith { PlayerRowState() }.toMutableMap()
        rows[RowId.RED] = st
        val sheet = PlayerSheet(rows = rows)
        assertEquals(LocXXRules.pointsForCrosses(4), LocXXRules.rowPoints(sheet, RowId.RED))
        val sumRows = RowId.entries.sumOf { LocXXRules.rowPoints(sheet, it) }
        assertEquals(LocXXRules.totalScore(sheet), sumRows - sheet.penalties * 5)
    }

    @Test
    fun redRowLeftToRight() {
        val s0 = PlayerRowState()
        assertTrue(LocXXRules.canCrossValue(RowId.RED, s0, 5))
        val s1 = LocXXRules.applyCross(RowId.RED, s0, 5).getOrThrow()
        assertEquals(setOf(3), s1.crossedIndices)
        assertTrue(LocXXRules.isValueSkipped(RowId.RED, s1, 4))
        assertTrue(LocXXRules.isValueCrossed(RowId.RED, s1, 5))
        assertFalse(LocXXRules.canCrossValue(RowId.RED, s1, 4))
        assertTrue(LocXXRules.canCrossValue(RowId.RED, s1, 7))
    }

    @Test
    fun paperSkippedLeftOfRightmostCross() {
        val s0 = PlayerRowState()
        val s1 = LocXXRules.applyCross(RowId.RED, s0, 9).getOrThrow()
        assertEquals(setOf(7), s1.crossedIndices)
        assertTrue(LocXXRules.isValueSkipped(RowId.RED, s1, 5))
        assertFalse(LocXXRules.isValueSkipped(RowId.RED, s1, 9))
    }

    @Test
    fun lockRequiresFiveCrosses() {
        var st = PlayerRowState()
        val values = rowValues(RowId.RED)
        for (i in 0 until 5) {
            st = LocXXRules.applyCross(RowId.RED, st, values[i]).getOrThrow()
        }
        assertTrue(LocXXRules.canCrossValue(RowId.RED, st, 12))
        st = LocXXRules.applyCross(RowId.RED, st, 12).getOrThrow()
        assertTrue(st.locked)
        assertEquals(setOf(0, 1, 2, 3, 4, 10), st.crossedIndices)
    }

    @Test
    fun lockBonusCountsExtraCrossForScoring() {
        var st = PlayerRowState()
        val values = rowValues(RowId.RED)
        for (i in 0 until 5) {
            st = LocXXRules.applyCross(RowId.RED, st, values[i]).getOrThrow()
        }
        st = LocXXRules.applyCross(RowId.RED, st, 12).getOrThrow()
        assertEquals(6, st.crossCount)
        assertEquals(7, LocXXRules.crossCountForScoring(st))
        val rows = enumValues<RowId>().associateWith { PlayerRowState() }.toMutableMap()
        rows[RowId.RED] = st
        assertEquals(LocXXRules.pointsForCrosses(7), LocXXRules.rowPoints(PlayerSheet(rows = rows), RowId.RED))
    }

    @Test
    fun fullRowLockedScores78() {
        var st = PlayerRowState()
        val values = rowValues(RowId.RED)
        for (i in values.indices) {
            st = LocXXRules.applyCross(RowId.RED, st, values[i]).getOrThrow()
        }
        assertTrue(st.locked)
        assertEquals(11, st.crossCount)
        assertEquals(12, LocXXRules.crossCountForScoring(st))
        val rows = enumValues<RowId>().associateWith { PlayerRowState() }.toMutableMap()
        rows[RowId.RED] = st
        assertEquals(78, LocXXRules.rowPoints(PlayerSheet(rows = rows), RowId.RED))
    }

    @Test
    fun lockCellReadyToMarkGlowsAfterFiveCrosses() {
        var st = PlayerRowState()
        val values = rowValues(RowId.RED)
        for (i in 0 until 5) {
            st = LocXXRules.applyCross(RowId.RED, st, values[i]).getOrThrow()
        }
        assertTrue(LocXXRules.isLockCellReadyToMark(RowId.RED, st, 12))
        assertFalse(LocXXRules.isLockCellReadyToMark(RowId.RED, st, 11))
    }

    @Test
    fun lockCellLockedShowsLockIconTarget() {
        var st = PlayerRowState()
        val values = rowValues(RowId.RED)
        for (i in 0 until 5) {
            st = LocXXRules.applyCross(RowId.RED, st, values[i]).getOrThrow()
        }
        assertFalse(LocXXRules.isLockCellLocked(RowId.RED, st, 12))
        st = LocXXRules.applyCross(RowId.RED, st, 12).getOrThrow()
        assertTrue(st.locked)
        assertTrue(LocXXRules.isLockCellLocked(RowId.RED, st, 12))
        assertFalse(LocXXRules.isLockCellLocked(RowId.RED, st, 11))
    }

    @Test
    fun isLockingCell_lastSheetCellUntilFiveCrosses() {
        val fresh = PlayerRowState()
        assertTrue(LocXXRules.isLockingCell(RowId.RED, fresh, 12))
        assertFalse(LocXXRules.isLockingCell(RowId.RED, fresh, 11))
        assertTrue(LocXXRules.isLockingCell(RowId.GREEN, fresh, 2))
        var st = fresh
        val reds = rowValues(RowId.RED)
        for (i in 0 until 5) {
            st = LocXXRules.applyCross(RowId.RED, st, reds[i]).getOrThrow()
        }
        assertFalse(LocXXRules.isLockingCell(RowId.RED, st, 12))
        st = LocXXRules.applyCross(RowId.RED, st, 12).getOrThrow()
        assertTrue(st.locked)
        assertFalse(LocXXRules.isLockingCell(RowId.RED, st, 12))
    }

    @Test
    fun gameEndConditions() {
        assertTrue(LocXXRules.gameShouldEnd(listOf(4, 0), 0))
        assertTrue(LocXXRules.gameShouldEnd(listOf(0, 0), 2))
        assertFalse(LocXXRules.gameShouldEnd(listOf(0, 0), 1))
    }

    @Test
    fun applyCrossToMatchRemovesDieWhenRowLocks() {
        var state = initialMatchState(1)
        val vals = rowValues(RowId.RED)
        for (i in 0 until 5) {
            state = LocXXRules.applyCrossToMatch(state, 0, RowId.RED, vals[i]).getOrThrow()
        }
        assertTrue(DieColor.RED in state.diceInPlay)
        state = LocXXRules.applyCrossToMatch(state, 0, RowId.RED, 12).getOrThrow()
        assertFalse(DieColor.RED in state.diceInPlay)
        assertTrue(RowId.RED in state.globallyLockedRows)
    }
}
