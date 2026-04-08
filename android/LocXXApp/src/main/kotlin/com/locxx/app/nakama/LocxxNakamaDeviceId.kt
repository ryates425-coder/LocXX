package com.locxx.app.nakama

import android.content.Context

private const val PREFS = "locxx_nakama"
private const val KEY_DEVICE_ID = "device_id"

/** Stable device id for Nakama `authenticateDevice`. */
fun Context.locxxNakamaDeviceId(): String {
    val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val existing = sp.getString(KEY_DEVICE_ID, null)
    if (existing != null) return existing
    val id = java.util.UUID.randomUUID().toString()
    sp.edit().putString(KEY_DEVICE_ID, id).apply()
    return id
}
