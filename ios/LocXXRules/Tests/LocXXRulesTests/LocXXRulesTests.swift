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
        XCTAssertFalse(LocXXRules.canCrossValue(row: .red, state: st, value: 4))
        XCTAssertTrue(LocXXRules.canCrossValue(row: .red, state: st, value: 7))
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
    }
}
