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
    fun redRowLeftToRight() {
        val s0 = PlayerRowState()
        assertTrue(LocXXRules.canCrossValue(RowId.RED, s0, 5))
        val s1 = LocXXRules.applyCross(RowId.RED, s0, 5).getOrThrow()
        assertFalse(LocXXRules.canCrossValue(RowId.RED, s1, 4))
        assertTrue(LocXXRules.canCrossValue(RowId.RED, s1, 7))
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
