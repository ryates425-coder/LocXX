package com.locxx.app

import android.content.Context

private const val PREFS = "locxx_app"

private const val KEY_MARK_DING = "sound_mark_ding_variant"

private const val KEY_UNDO_MARK_DING = "sound_undo_mark_ding_variant"

private const val KEY_DICE_FLUTTER = "sound_dice_roll_flutter"

private const val KEY_DICE_ROLL_VARIANT = "sound_dice_roll_variant"

private const val KEY_ROW_LOCK_HORN = "sound_row_lock_horn"

private const val KEY_PENALTY_BUZZER = "sound_penalty_buzzer_variant"

private const val KEY_INCLUSIVITY_DICE = "inclusivity_dice_enabled"

data class LocxxSoundPrefs(
    val markDing: LocxxMarkDingVariant,
    val undoMarkDing: LocxxUndoMarkDingVariant,
    val penaltyBuzzer: LocxxPenaltyBuzzerVariant,
    val diceRollSound: LocxxDiceRollSoundVariant,
    val diceRollFlutterEnabled: Boolean,
    val rowLockHornEnabled: Boolean,
    val inclusivityDiceEnabled: Boolean
) {
    fun applyToRuntime() {
        LocxxMarkDingConfig.variant = markDing
        LocxxUndoMarkDingConfig.variant = undoMarkDing
        LocxxPenaltyBuzzerConfig.variant = penaltyBuzzer
        LocxxDiceRollFlutterConfig.variant = diceRollSound
        LocxxDiceRollFlutterConfig.enabled = diceRollFlutterEnabled
        LocxxRowLockFanfareConfig.playHorn = rowLockHornEnabled
        LocxxInclusivityDiceConfig.enabledState.value = inclusivityDiceEnabled
    }
}

/** Row lock overlay: bonus horn fanfare. */
object LocxxRowLockFanfareConfig {
    @JvmField
    var playHorn: Boolean = true
}

fun LocxxMarkDingVariant.displayLabel(): String = when (this) {
    LocxxMarkDingVariant.BRIGHT_PING -> "Bright ping"
    LocxxMarkDingVariant.SOFT_CHIME -> "Soft chime"
    LocxxMarkDingVariant.BELL_STACK -> "Bell stack"
}

fun LocxxPenaltyBuzzerVariant.displayLabel(): String = when (this) {
    LocxxPenaltyBuzzerVariant.CLASSIC -> "Classic buzz"
    LocxxPenaltyBuzzerVariant.BRIGHT -> "Bright buzz"
    LocxxPenaltyBuzzerVariant.STADIUM -> "Stadium low"
}

fun LocxxDiceRollSoundVariant.displayLabel(): String = when (this) {
    LocxxDiceRollSoundVariant.ORIGINAL_FLUTTER -> "Original flutter"
    LocxxDiceRollSoundVariant.RATTLE -> "Rattle"
    LocxxDiceRollSoundVariant.CUP_SHAKE -> "Cup shake"
    LocxxDiceRollSoundVariant.CRISP_CLICK -> "Crisp click"
    LocxxDiceRollSoundVariant.TABLE_TAP -> "Table tap"
}

fun Context.readLocxxSoundPrefs(): LocxxSoundPrefs {
    val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = sp.getString(KEY_MARK_DING, null)
    val ding = raw?.let { r ->
        runCatching { LocxxMarkDingVariant.valueOf(r) }.getOrNull()
    } ?: LocxxMarkDingVariant.BELL_STACK
    val undoRaw = sp.getString(KEY_UNDO_MARK_DING, null)
    val undoDing = undoRaw?.let { r ->
        runCatching { LocxxUndoMarkDingVariant.valueOf(r) }.getOrNull()
    } ?: LocxxUndoMarkDingVariant.DESCENDING_SWOOP
    val flutter = sp.getBoolean(KEY_DICE_FLUTTER, true)
    val horn = sp.getBoolean(KEY_ROW_LOCK_HORN, true)
    val buzzerRaw = sp.getString(KEY_PENALTY_BUZZER, null)
    val buzzer = buzzerRaw?.let { r ->
        runCatching { LocxxPenaltyBuzzerVariant.valueOf(r) }.getOrNull()
    } ?: LocxxPenaltyBuzzerVariant.CLASSIC
    val diceRaw = sp.getString(KEY_DICE_ROLL_VARIANT, null)
    val diceVariant = diceRaw?.let { r ->
        runCatching { LocxxDiceRollSoundVariant.valueOf(r) }.getOrNull()
    } ?: LocxxDiceRollSoundVariant.ORIGINAL_FLUTTER
    val inclusivityDice = sp.getBoolean(KEY_INCLUSIVITY_DICE, false)
    return LocxxSoundPrefs(ding, undoDing, buzzer, diceVariant, flutter, horn, inclusivityDice)
}

fun Context.writeLocxxSoundPrefs(prefs: LocxxSoundPrefs) {
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_MARK_DING, prefs.markDing.name)
        .putString(KEY_UNDO_MARK_DING, prefs.undoMarkDing.name)
        .putString(KEY_PENALTY_BUZZER, prefs.penaltyBuzzer.name)
        .putString(KEY_DICE_ROLL_VARIANT, prefs.diceRollSound.name)
        .putBoolean(KEY_DICE_FLUTTER, prefs.diceRollFlutterEnabled)
        .putBoolean(KEY_ROW_LOCK_HORN, prefs.rowLockHornEnabled)
        .putBoolean(KEY_INCLUSIVITY_DICE, prefs.inclusivityDiceEnabled)
        .apply()
    prefs.applyToRuntime()
}

fun Context.loadLocxxSoundSettingsIntoRuntime() {
    readLocxxSoundPrefs().applyToRuntime()
}
