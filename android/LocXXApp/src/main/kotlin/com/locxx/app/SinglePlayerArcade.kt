package com.locxx.app

import com.locxx.rules.LocXXRules
import com.locxx.rules.PlayerSheet
import kotlin.math.pow

/** Sum of X marks across all color rows (arcade “lines” target). */
fun PlayerSheet.totalMarksPlaced(): Int =
    rows.values.sumOf { it.crossCount }

/**
 * Single-player level goals: clear the level by hitting **both** thresholds on your current sheet.
 * When cleared, the sheet resets and the next level starts with faster dice roll animation
 * (analogous to quicker falling blocks in arcade puzzlers).
 */
data class SinglePlayerLevelSpec(val minLines: Int, val minScore: Int)

object SinglePlayerArcade {

    private val baseLevels: List<SinglePlayerLevelSpec> = listOf(
        SinglePlayerLevelSpec(minLines = 8, minScore = 18),
        SinglePlayerLevelSpec(minLines = 12, minScore = 28),
        SinglePlayerLevelSpec(minLines = 16, minScore = 38),
        SinglePlayerLevelSpec(minLines = 20, minScore = 50),
        SinglePlayerLevelSpec(minLines = 24, minScore = 64),
    )

    /** [displayLevel] is 1-based. Beyond the table, goals ramp with the last step as stride. */
    fun specForLevel(displayLevel: Int): SinglePlayerLevelSpec {
        require(displayLevel >= 1)
        val idx = displayLevel - 1
        if (idx < baseLevels.size) return baseLevels[idx]
        val last = baseLevels.last()
        val extra = idx - (baseLevels.size - 1)
        return SinglePlayerLevelSpec(
            minLines = last.minLines + extra * 4,
            minScore = last.minScore + extra * 14
        )
    }

    /**
     * Multiplier applied to dice roll motion (values ≥ 1). Each level adds ~12% speed
     * (shorter tumble/spin durations).
     */
    fun rollAnimationSpeedFactor(displayLevel: Int): Float {
        val L = displayLevel.coerceAtLeast(1)
        return 1f + 0.12f * (L - 1)
    }

    fun meetsGoal(displayLevel: Int, sheet: PlayerSheet): Boolean {
        val spec = specForLevel(displayLevel)
        val lines = sheet.totalMarksPlaced()
        val score = LocXXRules.totalScore(sheet)
        return lines >= spec.minLines && score >= spec.minScore
    }
}
