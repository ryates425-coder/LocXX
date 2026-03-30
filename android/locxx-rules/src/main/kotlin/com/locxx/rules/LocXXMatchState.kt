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

/**
 * After a multiplayer roll phase ends: [MatchState.globallyLockedRows] is every color row where at least one player
 * has locked ([RowId] red/yellow/green/blue), and those color dice are removed. While a roll is still open with
 * deferred die removal, call [LocXXRules.applyCrossToMatch] with `removeLockedColorDieImmediately = false` so
 * multiple players can lock the same row on that roll; then apply this so that row closes for everyone.
 */
fun MatchState.withDerivedGlobalLocksAndDice(): MatchState {
    val lockedRows =
        RowId.entries.filter { row ->
            playerSheets.any { sh -> sh.rows[row]?.locked == true }
        }.toSet()
    var dice = enumValues<DieColor>().toSet()
    for (r in lockedRows) {
        dice = dice - LocXXRules.lockedDieRemoved(r)
    }
    return copy(globallyLockedRows = lockedRows, diceInPlay = dice)
}
