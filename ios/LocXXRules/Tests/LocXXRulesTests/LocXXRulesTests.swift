import LocXXRules
import XCTest

final class LocXXRulesTests: XCTestCase {
    func testScoringTable() {
        XCTAssertEqual(LocXXRules.pointsForCrosses(1), 1)
        XCTAssertEqual(LocXXRules.pointsForCrosses(12), 78)
    }

    func testRedRowOrder() throws {
        var st = PlayerRowState()
        XCTAssertTrue(LocXXRules.canCrossValue(row: .red, state: st, value: 5))
        st = try LocXXRules.applyCross(row: .red, state: st, value: 5).get()
        XCTAssertEqual(st.crossedIndices, Set([3]))
        XCTAssertTrue(LocXXRules.isValueSkipped(row: .red, state: st, value: 4))
        XCTAssertTrue(LocXXRules.isValueCrossed(row: .red, state: st, value: 5))
        XCTAssertFalse(LocXXRules.canCrossValue(row: .red, state: st, value: 4))
        XCTAssertTrue(LocXXRules.canCrossValue(row: .red, state: st, value: 7))
    }

    func testPaperSkippedLeftOfRightmostCross() throws {
        var st = PlayerRowState()
        st = try LocXXRules.applyCross(row: .red, state: st, value: 9).get()
        XCTAssertEqual(st.crossedIndices, Set([7]))
        XCTAssertTrue(LocXXRules.isValueSkipped(row: .red, state: st, value: 5))
        XCTAssertFalse(LocXXRules.isValueSkipped(row: .red, state: st, value: 9))
    }

    func testLockRequiresFive() throws {
        var st = PlayerRowState()
        let values = rowValues(.red)
        for i in 0 ..< 5 {
            st = try LocXXRules.applyCross(row: .red, state: st, value: values[i]).get()
        }
        XCTAssertTrue(LocXXRules.canCrossValue(row: .red, state: st, value: 12))
        st = try LocXXRules.applyCross(row: .red, state: st, value: 12).get()
        XCTAssertTrue(st.locked)
        XCTAssertEqual(st.crossedIndices, Set([0, 1, 2, 3, 4, 10]))
    }

    func testRowPoints() throws {
        XCTAssertEqual(LocXXRules.rowPoints(PlayerSheet(), .red), 0)
        var st = PlayerRowState()
        let values = rowValues(.red)
        for i in 0 ..< 4 {
            st = try LocXXRules.applyCross(row: .red, state: st, value: values[i]).get()
        }
        var rows = Dictionary(uniqueKeysWithValues: RowId.allCases.map { ($0, PlayerRowState()) })
        rows[.red] = st
        let sheet = PlayerSheet(rows: rows)
        XCTAssertEqual(LocXXRules.rowPoints(sheet, .red), LocXXRules.pointsForCrosses(4))
        let sumRows = RowId.allCases.reduce(0) { $0 + LocXXRules.rowPoints(sheet, $1) }
        XCTAssertEqual(LocXXRules.totalScore(sheet), sumRows - sheet.penalties * 5)
    }

    func testLockCellLocked() throws {
        var st = PlayerRowState()
        let values = rowValues(.red)
        for i in 0 ..< 5 {
            st = try LocXXRules.applyCross(row: .red, state: st, value: values[i]).get()
        }
        XCTAssertFalse(LocXXRules.isLockCellLocked(row: .red, state: st, value: 12))
        st = try LocXXRules.applyCross(row: .red, state: st, value: 12).get()
        XCTAssertTrue(LocXXRules.isLockCellLocked(row: .red, state: st, value: 12))
        XCTAssertFalse(LocXXRules.isLockCellLocked(row: .red, state: st, value: 11))
    }
}
