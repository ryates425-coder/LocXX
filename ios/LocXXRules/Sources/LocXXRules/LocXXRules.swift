import Foundation

public enum LocXXRules {
    private static let rowPoints: [Int] = [0, 1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 66, 78]

    public static func pointsForCrosses(_ crosses: Int) -> Int {
        let c = min(max(crosses, 0), rowPoints.count - 1)
        return rowPoints[c]
    }

    public static func totalScore(_ sheet: PlayerSheet) -> Int {
        var total = 0
        for row in RowId.allCases {
            let st = sheet.rows[row]!
            if st.crossCount == 0 { continue }
            total += pointsForCrosses(st.crossCount)
        }
        total -= sheet.penalties * 5
        return total
    }

    /// Points from one color row (0 if that row has no crosses). Matches `totalScore` row contributions.
    public static func rowPoints(_ sheet: PlayerSheet, row: RowId) -> Int {
        guard let st = sheet.rows[row] else { return 0 }
        if st.crossCount == 0 { return 0 }
        return pointsForCrosses(st.crossCount)
    }

    public static func isValueCrossed(row: RowId, state: PlayerRowState, value: Int) -> Bool {
        let values = rowValues(row)
        guard let idx = values.firstIndex(of: value) else { return false }
        return state.crossedIndices.contains(idx)
    }

    /// Paper Qwixx: numbers strictly left of the rightmost cross that were never crossed.
    public static func isValueSkipped(row: RowId, state: PlayerRowState, value: Int) -> Bool {
        let values = rowValues(row)
        guard let idx = values.firstIndex(of: value) else { return false }
        let max = state.maxCrossedIndex
        if max < 0 { return false }
        return idx < max && !state.crossedIndices.contains(idx)
    }

    public static func canCrossValue(row: RowId, state: PlayerRowState, value: Int) -> Bool {
        if state.locked { return false }
        let values = rowValues(row)
        guard let idx = values.firstIndex(of: value) else { return false }
        if state.crossedIndices.contains(idx) { return false }
        let max = state.maxCrossedIndex
        if max >= 0 && idx <= max { return false }
        let lastIdx = values.count - 1
        let isLast = idx == lastIdx
        if isLast && state.crossCount < 5 { return false }
        return true
    }

    /// True on the row's lock cell (12 on red/yellow, 2 on green/blue) after the row has been locked.
    public static func isLockCellLocked(row: RowId, state: PlayerRowState, value: Int) -> Bool {
        guard state.locked else { return false }
        let values = rowValues(row)
        guard let idx = values.firstIndex(of: value) else { return false }
        return idx == values.count - 1
    }

    public static func applyCross(row: RowId, state: PlayerRowState, value: Int) -> Result<PlayerRowState, Error> {
        guard canCrossValue(row: row, state: state, value: value) else {
            return .failure(NSError(domain: "LocXXRules", code: 1))
        }
        let values = rowValues(row)
        guard let idx = values.firstIndex(of: value) else {
            return .failure(NSError(domain: "LocXXRules", code: 2))
        }
        let lastIdx = values.count - 1
        let locks = idx == lastIdx
        return .success(
            PlayerRowState(
                crossedIndices: state.crossedIndices.union([idx]),
                locked: state.locked || locks
            )
        )
    }

    public static func gameShouldEnd(anyPlayerPenalties: [Int], lockedRowCount: Int) -> Bool {
        if lockedRowCount >= 2 { return true }
        if anyPlayerPenalties.contains(where: { $0 >= 4 }) { return true }
        return false
    }
}
