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

    public static func canCrossValue(row: RowId, state: PlayerRowState, value: Int) -> Bool {
        if state.locked { return false }
        let values = rowValues(row)
        guard let idx = values.firstIndex(of: value) else { return false }
        let lastIdx = values.count - 1
        let isLast = idx == lastIdx
        if state.lastCrossedIndex >= 0 && idx <= state.lastCrossedIndex { return false }
        if isLast && state.crossCount < 5 { return false }
        return true
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
                lastCrossedIndex: idx,
                crossCount: state.crossCount + 1,
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
