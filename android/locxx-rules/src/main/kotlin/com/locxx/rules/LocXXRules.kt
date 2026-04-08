package com.locxx.rules

/**
 * Qwixx-equivalent scoring: points by effective crosses on the score pad (1..12).
 * Physical marks on the row plus **one extra** when the row is **locked** (lock pad X), capped at 12 → 78 pts.
 * Source: official scoring chart (1→1, 2→3, …, 12→78); 11 sheet marks = 66, + lock bonus = 12th = 78.
 */
object LocXXRules {

    private val ROW_POINTS = intArrayOf(0, 1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 66, 78)

    fun pointsForCrosses(crosses: Int): Int {
        val c = crosses.coerceIn(0, ROW_POINTS.lastIndex)
        return ROW_POINTS[c]
    }

    /** Marks on the sheet plus one Qwixx lock bonus when [PlayerRowState.locked], for scoring only (max 12). */
    fun crossCountForScoring(state: PlayerRowState): Int {
        if (state.crossCount == 0) return 0
        val n = state.crossCount + if (state.locked) 1 else 0
        return n.coerceAtMost(ROW_POINTS.lastIndex)
    }

    fun totalScore(sheet: PlayerSheet): Int {
        var total = 0
        for (row in enumValues<RowId>()) {
            val st = sheet.rows[row] ?: continue
            if (st.crossCount == 0) continue
            total += pointsForCrosses(crossCountForScoring(st))
        }
        total -= sheet.penalties * 5
        return total
    }

    /** Points from one color row (0 if that row has no crosses). Matches [totalScore] row contributions. */
    fun rowPoints(sheet: PlayerSheet, row: RowId): Int {
        val st = sheet.rows[row] ?: return 0
        if (st.crossCount == 0) return 0
        return pointsForCrosses(crossCountForScoring(st))
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

    /**
     * The row’s rightmost scoring cell (same as [isLockCellLocked] / lock column).
     */
    fun isRowLastValueCell(row: RowId, value: Int): Boolean {
        val values = rowValues(row)
        val idx = values.indexOf(value)
        return idx >= 0 && idx == values.lastIndex
    }

    /**
     * This color row is closed on this sheet (player locked it) or closed for everyone ([globallyLockedRows]).
     */
    fun isRowClosedOnSheet(
        row: RowId,
        state: PlayerRowState,
        globallyLockedRows: Set<RowId>,
    ): Boolean = state.locked || row in globallyLockedRows

    /**
     * Lock icon on the last cell when this color is closed for everyone (someone locked it) but this
     * player did not take the lock bonus on their sheet — visual only; scoring has no +1 for them.
     */
    fun isGlobalLockBadgeOnly(row: RowId, state: PlayerRowState, value: Int, globallyLockedRows: Set<RowId>): Boolean {
        if (row !in globallyLockedRows) return false
        if (state.locked) return false
        val values = rowValues(row)
        val lastIdx = values.lastIndex
        if (values.indexOf(value) != lastIdx) return false
        if (lastIdx in state.crossedIndices) return false
        return true
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
     * Apply a cross for [playerIndex].
     *
     * **Global closure:** If [row] is already in [MatchState.globallyLockedRows] from a **completed** roll phase,
     * no one may mark that row (all four color rows: [RowId.RED], [RowId.YELLOW], [RowId.GREEN], [RowId.BLUE]).
     * When `removeLockedColorDieImmediately` is true (single-device), the first lock also updates
     * [MatchState.globallyLockedRows] and removes that row's color die immediately. When false (LAN / open roll),
     * locking only updates that player's sheet until the host commits the phase with [withDerivedGlobalLocksAndDice]
     * so everyone who could lock that row on this roll still can (Qwixx same-round behavior).
     */
    fun applyCrossToMatch(
        state: MatchState,
        playerIndex: Int,
        row: RowId,
        value: Int,
        removeLockedColorDieImmediately: Boolean = true
    ): Result<MatchState> {
        if (playerIndex !in state.playerSheets.indices) {
            return Result.failure(IllegalArgumentException("bad playerIndex"))
        }
        val sheet = state.playerSheets[playerIndex]
        val rowState = sheet.rows[row] ?: return Result.failure(IllegalArgumentException("bad row"))
        if (row in state.globallyLockedRows) {
            return Result.failure(IllegalArgumentException("row closed for all players"))
        }
        val newRowStateUnlocked = applyCross(row, rowState, value).getOrElse { return Result.failure(it) }
        var newSheet = sheet.copy(rows = sheet.rows + (row to newRowStateUnlocked))
        var globallyLocked = state.globallyLockedRows
        var diceInPlay = state.diceInPlay
        if (newRowStateUnlocked.locked && row !in globallyLocked && removeLockedColorDieImmediately) {
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
