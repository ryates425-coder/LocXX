package com.locxx.rules

/**
 * Authoritative match snapshot for host validation and client display.
 */
data class MatchState(
    val playerCount: Int,
    val playerSheets: List<PlayerSheet>,
    val activePlayerIndex: Int,
    /** Dice still rolled (when a row locks globally, its color die is removed for everyone). */
    val diceInPlay: Set<DieColor>,
    /** Row colors locked by any player (Qwixx: locking removes that color die globally). */
    val globallyLockedRows: Set<RowId>
) {
    init {
        require(playerSheets.size == playerCount)
    }

    fun lockedRowCount(): Int = globallyLockedRows.size

    fun copySheet(playerIndex: Int, sheet: PlayerSheet): MatchState {
        val next = playerSheets.toMutableList()
        next[playerIndex] = sheet
        return copy(playerSheets = next)
    }
}

fun initialMatchState(playerCount: Int): MatchState {
    val allDice = enumValues<DieColor>().toSet()
    return MatchState(
        playerCount = playerCount,
        playerSheets = List(playerCount) { PlayerSheet() },
        activePlayerIndex = 0,
        diceInPlay = allDice,
        globallyLockedRows = emptySet()
    )
}
