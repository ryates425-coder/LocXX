import Foundation

public struct MatchState: Equatable, Sendable {
    public var playerCount: Int
    public var playerSheets: [PlayerSheet]
    public var activePlayerIndex: Int
    public var diceInPlay: Set<DieColor>
    public var globallyLockedRows: Set<RowId>

    public init(
        playerCount: Int,
        playerSheets: [PlayerSheet],
        activePlayerIndex: Int,
        diceInPlay: Set<DieColor>,
        globallyLockedRows: Set<RowId>
    ) {
        self.playerCount = playerCount
        self.playerSheets = playerSheets
        self.activePlayerIndex = activePlayerIndex
        self.diceInPlay = diceInPlay
        self.globallyLockedRows = globallyLockedRows
    }

    public func lockedRowCount() -> Int { globallyLockedRows.count }
}

public func initialMatchState(playerCount: Int) -> MatchState {
    let allDice = Set(DieColor.allCases)
    return MatchState(
        playerCount: playerCount,
        playerSheets: (0 ..< playerCount).map { _ in PlayerSheet() },
        activePlayerIndex: 0,
        diceInPlay: allDice,
        globallyLockedRows: []
    )
}
