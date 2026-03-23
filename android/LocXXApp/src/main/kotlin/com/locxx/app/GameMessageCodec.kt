package com.locxx.app

import com.locxx.rules.DiceRoll
import com.locxx.rules.MatchState
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-layer JSON inside [com.locxx.bluetoothgaming.WireMessageType.APP_PAYLOAD] frames.
 */
object GameMessageCodec {

    fun encodeAppPayload(json: JSONObject): ByteArray {
        val root = JSONObject()
        root.put("v", 1)
        root.put("kind", json.getString("kind"))
        root.put("body", json.optJSONObject("body") ?: JSONObject())
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeAppPayload(bytes: ByteArray): JSONObject {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("v") == 1)
        return root
    }

    fun encodeRoll(roll: DiceRoll, activePlayerIndex: Int, cid: Int): ByteArray {
        val body = JSONObject()
        body.put("white1", roll.white1)
        body.put("white2", roll.white2)
        body.put("red", roll.red)
        body.put("yellow", roll.yellow)
        body.put("green", roll.green)
        body.put("blue", roll.blue)
        body.put("activePlayerIndex", activePlayerIndex)
        body.put("cid", cid)
        val json = JSONObject()
        json.put("kind", "roll")
        json.put("body", body)
        return encodeAppPayload(json)
    }

    fun parseRoll(root: JSONObject): Pair<DiceRoll, Int> {
        val body = root.getJSONObject("body")
        val roll = DiceRoll(
            white1 = body.getInt("white1"),
            white2 = body.getInt("white2"),
            red = body.getInt("red"),
            yellow = body.getInt("yellow"),
            green = body.getInt("green"),
            blue = body.getInt("blue")
        )
        val active = body.getInt("activePlayerIndex")
        return roll to active
    }

    fun encodeGameState(state: MatchState): ByteArray {
        val sheets = JSONArray()
        for (sheet in state.playerSheets) {
            val rowObj = JSONObject()
            for (row in enumValues<com.locxx.rules.RowId>()) {
                val pr = sheet.rows[row]!!
                rowObj.put(
                    row.name,
                    JSONObject()
                        .put("last", pr.lastCrossedIndex)
                        .put("count", pr.crossCount)
                        .put("locked", pr.locked)
                )
            }
            sheets.put(
                JSONObject()
                    .put("rows", rowObj)
                    .put("penalties", sheet.penalties)
            )
        }
        val dice = JSONArray()
        state.diceInPlay.forEach { dice.put(it.name) }
        val locked = JSONArray()
        state.globallyLockedRows.forEach { locked.put(it.name) }
        val body = JSONObject()
        body.put("playerCount", state.playerCount)
        body.put("activePlayerIndex", state.activePlayerIndex)
        body.put("sheets", sheets)
        body.put("diceInPlay", dice)
        body.put("globallyLockedRows", locked)
        val json = JSONObject()
        json.put("kind", "game_state")
        json.put("body", body)
        return encodeAppPayload(json)
    }

    fun parseGameState(root: JSONObject): MatchState {
        val body = root.getJSONObject("body")
        val playerCount = body.getInt("playerCount")
        val sheetsJa = body.getJSONArray("sheets")
        val sheets = List(playerCount) { i ->
            val so = sheetsJa.getJSONObject(i)
            val rowsObj = so.getJSONObject("rows")
            val rows = enumValues<com.locxx.rules.RowId>().associateWith { r ->
                val o = rowsObj.getJSONObject(r.name)
                com.locxx.rules.PlayerRowState(
                    lastCrossedIndex = o.getInt("last"),
                    crossCount = o.getInt("count"),
                    locked = o.getBoolean("locked")
                )
            }
            com.locxx.rules.PlayerSheet(rows = rows, penalties = so.getInt("penalties"))
        }
        val diceJa = body.getJSONArray("diceInPlay")
        val diceInPlay = buildSet {
            for (i in 0 until diceJa.length()) {
                add(com.locxx.rules.DieColor.valueOf(diceJa.getString(i)))
            }
        }
        val lockedJa = body.getJSONArray("globallyLockedRows")
        val lockedRows = buildSet {
            for (i in 0 until lockedJa.length()) {
                add(com.locxx.rules.RowId.valueOf(lockedJa.getString(i)))
            }
        }
        return MatchState(
            playerCount = playerCount,
            playerSheets = sheets,
            activePlayerIndex = body.getInt("activePlayerIndex"),
            diceInPlay = diceInPlay,
            globallyLockedRows = lockedRows
        )
    }

    fun encodeIntent(playerId: Int, kind: String, body: JSONObject): ByteArray {
        val b = JSONObject(body.toString())
        b.put("playerId", playerId)
        val json = JSONObject()
        json.put("kind", "intent_$kind")
        json.put("body", b)
        return encodeAppPayload(json)
    }
}
