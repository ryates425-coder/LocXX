import Foundation

public enum RowId: CaseIterable, Hashable, Sendable {
    case red, yellow, green, blue
}

public enum DieColor: CaseIterable, Hashable, Sendable {
    case white1, white2, red, yellow, green, blue
}

public extension RowId {
    func dieColor() -> DieColor {
        switch self {
        case .red: return .red
        case .yellow: return .yellow
        case .green: return .green
        case .blue: return .blue
        }
    }
}

/// Values left-to-right on the score sheet for each row (Qwixx layout).
public func rowValues(_ row: RowId) -> [Int] {
    switch row {
    case .red, .yellow:
        return Array(2 ... 12)
    case .green, .blue:
        return Array((2 ... 12).reversed())
    }
}

public struct PlayerRowState: Equatable, Sendable {
    public var lastCrossedIndex: Int
    public var crossCount: Int
    public var locked: Bool

    public init(lastCrossedIndex: Int = -1, crossCount: Int = 0, locked: Bool = false) {
        self.lastCrossedIndex = lastCrossedIndex
        self.crossCount = crossCount
        self.locked = locked
    }
}

public struct PlayerSheet: Equatable, Sendable {
    public var rows: [RowId: PlayerRowState]
    public var penalties: Int

    public init(rows: [RowId: PlayerRowState] = Dictionary(uniqueKeysWithValues: RowId.allCases.map { ($0, PlayerRowState()) }), penalties: Int = 0) {
        self.rows = rows
        self.penalties = penalties
    }
}

public struct DiceRoll: Equatable, Sendable {
    public let white1: Int
    public let white2: Int
    public let red: Int
    public let yellow: Int
    public let green: Int
    public let blue: Int

    public init(white1: Int, white2: Int, red: Int, yellow: Int, green: Int, blue: Int) {
        self.white1 = white1
        self.white2 = white2
        self.red = red
        self.yellow = yellow
        self.green = green
        self.blue = blue
    }

    public func whiteSum() -> Int { white1 + white2 }

    public func value(_ d: DieColor) -> Int {
        switch d {
        case .white1: return white1
        case .white2: return white2
        case .red: return red
        case .yellow: return yellow
        case .green: return green
        case .blue: return blue
        }
    }
}
