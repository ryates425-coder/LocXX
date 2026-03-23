package com.locxx.rules

enum class RowId {
    RED,
    YELLOW,
    GREEN,
    BLUE
}

enum class DieColor {
    WHITE1,
    WHITE2,
    RED,
    YELLOW,
    GREEN,
    BLUE
}

fun RowId.dieColor(): DieColor = when (this) {
    RowId.RED -> DieColor.RED
    RowId.YELLOW -> DieColor.YELLOW
    RowId.GREEN -> DieColor.GREEN
    RowId.BLUE -> DieColor.BLUE
}

/** Values left-to-right on the score sheet for each row (Qwixx layout). */
fun rowValues(row: RowId): IntArray = when (row) {
    RowId.RED, RowId.YELLOW -> IntArray(11) { i -> i + 2 }
    RowId.GREEN, RowId.BLUE -> IntArray(11) { i -> 12 - i }
}

data class PlayerRowState(
    /** Indices along [rowValues] that have been crossed (paper-accurate; gaps = skipped). */
    val crossedIndices: Set<Int> = emptySet(),
    val locked: Boolean = false
) {
    val crossCount: Int get() = crossedIndices.size
    val maxCrossedIndex: Int get() = crossedIndices.maxOrNull() ?: -1
}

data class PlayerSheet(
    val rows: Map<RowId, PlayerRowState> = enumValues<RowId>().associateWith { PlayerRowState() },
    val penalties: Int = 0
)

data class DiceRoll(
    val white1: Int,
    val white2: Int,
    val red: Int,
    val yellow: Int,
    val green: Int,
    val blue: Int
) {
    fun whiteSum(): Int = white1 + white2

    fun value(d: DieColor): Int = when (d) {
        DieColor.WHITE1 -> white1
        DieColor.WHITE2 -> white2
        DieColor.RED -> red
        DieColor.YELLOW -> yellow
        DieColor.GREEN -> green
        DieColor.BLUE -> blue
    }
}
