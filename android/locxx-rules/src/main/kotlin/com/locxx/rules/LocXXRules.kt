package com.locxx.rules

/**
 * Qwixx-equivalent scoring: points by number of crosses in a row (1..12).
 * Source: official scoring chart (1→1, 2→3, …, 12→78).
 */
object LocXXRules {

    private val ROW_POINTS = intArrayOf(0, 1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 66, 78)

    fun pointsForCrosses(crosses: Int): Int {
        val c = crosses.coerceIn(0, ROW_POINTS.lastIndex)
        return ROW_POINTS[c]
    }

    fun totalScore(sheet: PlayerSheet): Int {
        var total = 0
        for (row in enumValues<RowId>()) {
            val st = sheet.rows[row] ?: continue
            if (st.crossCount == 0) continue
            total += pointsForCrosses(st.crossCount)
        }
        total -= sheet.penalties * 5
        return total
    }

    /** Points from one color row (0 if that row has no crosses). Matches [totalScore] row contributions. */
    fun rowPoints(sheet: PlayerSheet, row: RowId): Int {
        val st = sheet.rows[row] ?: return 0
        if (st.crossCount == 0) return 0
        return pointsForCrosses(st.crossCount)
    }

    fun isValueCrossed(row: RowId, state: PlayerRowState, value: Int): Boolean {
        val values = rowValues(row)
        val idx = values.indexOf(value)
        if (idx < 0) return false
        return idx in state.crossedIndices
    }

    /**
     * Paper Qwixx: numbers strictly left of the rightmost cross that were never crossed
     * (passed over when you marked farther right first).
     */
    fun isValueSkipped(row: RowId, state: PlayerRowState, value: Int): Boolean {
        val values = rowValues(row)
        val idx = values.indexOf(value)
        if (idx < 0) return false
        val max = state.maxCrossedIndex
        if (max < 0) return false
        return idx < max && idx !in state.crossedIndices
    }

    /** Whether [value] may be crossed in [row] given current progress (Qwixx left-to-right rule). */
    fun canCrossValue(row: RowId, state: PlayerRowState, value: Int): Boolean {
        if (state.locked) return false
        val values = rowValues(row)
        val idx = values.indexOf(value)
        if (idx < 0) return false
        if (idx in state.crossedIndices) return false
        val max = state.maxCrossedIndex
        // Strictly left of the rightmost cross without a mark = paper skipped (dead).
        if (max >= 0 && idx <= max) return false
        val lastIdx = values.lastIndex
        val isLast = idx == lastIdx
        if (isLast && state.crossCount < 5) return false
        return true
    }

    /**
     * The row's rightmost sheet cell (12 on red/yellow, 2 on green/blue), which may only be crossed
     * after five marks in that row — not strikethrough "skipped" territory while still waiting.
     */
    fun isLockingCell(row: RowId, state: PlayerRowState, value: Int): Boolean {
        if (state.locked) return false
        val values = rowValues(row)
        val idx = values.indexOf(value)
        if (idx < 0) return false
        return idx == values.lastIndex && state.crossCount < 5
    }

    /**
     * After five crosses in this row, the lock cell (12 on red/yellow, 2 on green/blue) may be marked
     * to lock the row — use for UI emphasis on that cell only.
     */
    fun isLockCellReadyToMark(row: RowId, state: PlayerRowState, value: Int): Boolean {
        val values = rowValues(row)
        val idx = values.indexOf(value)
        if (idx < 0 || idx != values.lastIndex) return false
        if (state.locked) return false
        if (state.crossCount < 5) return false
        return canCrossValue(row, state, value)
    }

    /** True on the row's lock cell (12 on red/yellow, 2 on green/blue) after the row has been locked. */
    fun isLockCellLocked(row: RowId, state: PlayerRowState, value: Int): Boolean {
        if (!state.locked) return false
        val values = rowValues(row)
        val idx = values.indexOf(value)
        return idx >= 0 && idx == values.lastIndex
    }

    fun applyCross(
        row: RowId,
        state: PlayerRowState,
        value: Int
    ): Result<PlayerRowState> {
        if (!canCrossValue(row, state, value)) return Result.failure(IllegalArgumentException("illegal cross"))
        val values = rowValues(row)
        val idx = values.indexOf(value)
        val lastIdx = values.lastIndex
        val locks = idx == lastIdx
        return Result.success(
            state.copy(
                crossedIndices = state.crossedIndices + idx,
                locked = state.locked || locks
            )
        )
    }

    fun whiteSumLegalTargets(sheet: PlayerSheet, sum: Int): List<RowId> {
        val out = ArrayList<RowId>()
        for (row in RowId.entries) {
            val st = sheet.rows[row] ?: continue
            if (rowValues(row).any { v -> v == sum && canCrossValue(row, st, v) }) {
                out.add(row)
            }
        }
        return out
    }

    /** Active player: white die + colored die sum for that color's row only. */
    fun colorComboLegal(
        sheet: PlayerSheet,
        row: RowId,
        sum: Int,
        dicePresent: Set<DieColor>
    ): Boolean {
        if (!dicePresent.contains(row.dieColor())) return false
        val st = sheet.rows[row] ?: return false
        return canCrossValue(row, st, sum)
    }

    fun gameShouldEnd(anyPlayerPenalties: List<Int>, lockedRowCount: Int): Boolean {
        if (lockedRowCount >= 2) return true
        if (anyPlayerPenalties.any { it >= 4 }) return true
        return false
    }

    fun lockedDieRemoved(row: RowId): DieColor = row.dieColor()

    /**
     * Apply a cross for [playerIndex]; if the row locks, add it to [MatchState.globallyLockedRows]
     * and remove that row's color die from [MatchState.diceInPlay].
     */
    fun applyCrossToMatch(state: MatchState, playerIndex: Int, row: RowId, value: Int): Result<MatchState> {
        if (playerIndex !in state.playerSheets.indices) {
            return Result.failure(IllegalArgumentException("bad playerIndex"))
        }
        val sheet = state.playerSheets[playerIndex]
        val rowState = sheet.rows[row] ?: return Result.failure(IllegalArgumentException("bad row"))
        val newRowState = applyCross(row, rowState, value).getOrElse { return Result.failure(it) }
        var newSheet = sheet.copy(rows = sheet.rows + (row to newRowState))
        var globallyLocked = state.globallyLockedRows
        var diceInPlay = state.diceInPlay
        if (newRowState.locked && row !in globallyLocked) {
            globallyLocked = globallyLocked + row
            diceInPlay = diceInPlay - lockedDieRemoved(row)
        }
        return Result.success(
            state.copySheet(playerIndex, newSheet).copy(
                diceInPlay = diceInPlay,
                globallyLockedRows = globallyLocked
            )
        )
    }
}
