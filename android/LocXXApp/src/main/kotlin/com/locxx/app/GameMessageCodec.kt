package com.locxx.app

import com.locxx.rules.DiceRoll
import com.locxx.rules.DieColor
import com.locxx.rules.MatchState
import com.locxx.rules.PlayerSheet
import com.locxx.rules.RollResolutionState
import com.locxx.rules.RowId
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-layer JSON inside [com.locxx.langaming.WireMessageType.APP_PAYLOAD] frames.
 */
object GameMessageCodec {

    data class GameStateWire(
        val match: MatchState,
        val openRoll: DiceRoll?,
        /** Per-player resolution for [openRoll]; null when no roll is open. */
        val resolutionsByPlayer: List<RollResolutionState>?,
        val activeCrossesThisRoll: Int,
        /** Seat indices that pressed Done this roll (inactive = white phase finished; roller = scoring finished). Next roll after all seats are listed. */
        val whitePhaseAcks: Set<Int>,
        /** Optional UI names per seat, from host broadcasts; same order as [MatchState.playerSheets]. */
        val playerDisplayNames: List<String>? = null,
        /**
         * Per-seat rows where that player can mark the lock cell (5 crosses, not locked);
         * lets opponents pulse lock icons when sheets are masked during an open roll.
         */
        val lockReadyBySeat: List<Set<RowId>>? = null
    )

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

    /** Host tells clients to open the play / score screen (after “Start game”). */
    fun encodeGameStarted(): ByteArray {
        val json = JSONObject()
        json.put("kind", "game_started")
        json.put("body", JSONObject())
        return encodeAppPayload(json)
    }

    /**
     * Nakama: host orders seats (index 0 = session host). Clients set [LocXXViewModel] local index from this.
     */
    fun encodeNakamaMeta(hostUserId: String, seatUserIds: List<String>): ByteArray {
        val ja = JSONArray()
        seatUserIds.forEach { ja.put(it) }
        val body = JSONObject()
            .put("hostUserId", hostUserId)
            .put("seatUserIds", ja)
        val json = JSONObject()
        json.put("kind", "nakama_meta")
        json.put("body", body)
        return encodeAppPayload(json)
    }

    /** Host is stopping the session; clients should disconnect and return to the menu. */
    fun encodeHostExited(): ByteArray {
        val json = JSONObject()
        json.put("kind", "host_exited")
        json.put("body", JSONObject())
        return encodeAppPayload(json)
    }

    /** Dice fields for [encodeRoll] or [buildDebugRollBody] (no active index). */
    fun buildDebugRollBody(roll: DiceRoll, cid: Int): JSONObject =
        diceRollToJson(roll).put("cid", cid)

    fun parseDebugRollBody(body: JSONObject): DiceRoll = diceRollFromJson(body)

    fun encodeRoll(roll: DiceRoll, activePlayerIndex: Int, cid: Int): ByteArray {
        val body = buildDebugRollBody(roll, cid)
        body.put("activePlayerIndex", activePlayerIndex)
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

    fun encodeGameState(state: MatchState): ByteArray =
        encodeGameState(
            state,
            openRoll = null,
            resolutionsByPlayer = null,
            activeCrossesThisRoll = 0,
            whitePhaseAcks = emptySet(),
            playerDisplayNames = null
        )

    fun encodeGameState(
        state: MatchState,
        openRoll: DiceRoll?,
        resolutionsByPlayer: List<RollResolutionState>?,
        activeCrossesThisRoll: Int,
        whitePhaseAcks: Set<Int> = emptySet(),
        playerDisplayNames: List<String>? = null,
        lockReadyBySeat: List<Set<RowId>>? = null
    ): ByteArray {
        val body = matchStateToJson(state)
        if (playerDisplayNames != null && playerDisplayNames.size == state.playerCount) {
            val nameJa = JSONArray()
            playerDisplayNames.forEach { nameJa.put(it) }
            body.put("playerNames", nameJa)
        }
        if (openRoll != null && resolutionsByPlayer != null) {
            body.put("openRoll", diceRollToJson(openRoll))
            val arr = JSONArray()
            for (pr in resolutionsByPlayer) {
                val wc = JSONArray()
                pr.whiteUsedForColor.forEach { wc.put(it.name) }
                arr.put(
                    JSONObject()
                        .put("whiteSumUsed", pr.whiteSumUsed)
                        .put("whiteUsedForColor", wc)
                )
            }
            body.put("playerRollResolutions", arr)
            body.put("activeCrossesThisRoll", activeCrossesThisRoll)
            val ackJa = JSONArray()
            whitePhaseAcks.sorted().forEach { ackJa.put(it) }
            body.put("whitePhaseAcks", ackJa)
            if (lockReadyBySeat != null && lockReadyBySeat.size == state.playerCount) {
                val lrJa = JSONArray()
                for (rows in lockReadyBySeat) {
                    val rowJa = JSONArray()
                    rows.sortedBy { it.ordinal }.forEach { rowJa.put(it.name) }
                    lrJa.put(rowJa)
                }
                body.put("lockReadyBySeat", lrJa)
            }
        }
        val json = JSONObject()
        json.put("kind", "game_state")
        json.put("body", body)
        return encodeAppPayload(json)
    }

    fun parseGameState(root: JSONObject): MatchState = decodeGameStateWire(root).match

    fun decodeGameStateWire(root: JSONObject): GameStateWire {
        val body = root.getJSONObject("body")
        val match = matchStateFromJson(body)
        val playerDisplayNames = if (body.has("playerNames")) {
            val ja = body.getJSONArray("playerNames")
            val list = List(ja.length()) { i -> ja.getString(i) }
            if (list.size == match.playerCount) list else null
        } else {
            null
        }
        if (!body.has("openRoll") || !body.has("playerRollResolutions")) {
            return GameStateWire(match, null, null, 0, emptySet(), playerDisplayNames, null)
        }
        val roll = diceRollFromJson(body.getJSONObject("openRoll"))
        val arr = body.getJSONArray("playerRollResolutions")
        val list = List(arr.length()) { i ->
            val ro = arr.getJSONObject(i)
            val wcJa = ro.getJSONArray("whiteUsedForColor")
            val wc = buildSet {
                for (j in 0 until wcJa.length()) {
                    add(DieColor.valueOf(wcJa.getString(j)))
                }
            }
            RollResolutionState(
                roll = roll,
                whiteSumUsed = ro.getBoolean("whiteSumUsed"),
                whiteUsedForColor = wc
            )
        }
        val crosses = if (body.has("activeCrossesThisRoll")) body.getInt("activeCrossesThisRoll") else 0
        val acks = if (body.has("whitePhaseAcks")) {
            val ja = body.getJSONArray("whitePhaseAcks")
            buildSet {
                for (i in 0 until ja.length()) {
                    add(ja.getInt(i))
                }
            }
        } else {
            emptySet()
        }
        val lockReadyBySeat: List<Set<RowId>>? =
            if (body.has("lockReadyBySeat")) {
                val lr = body.getJSONArray("lockReadyBySeat")
                if (lr.length() == match.playerCount) {
                    List(lr.length()) { i ->
                        val rowJa = lr.getJSONArray(i)
                        buildSet<RowId> {
                            for (j in 0 until rowJa.length()) {
                                runCatching { add(RowId.valueOf(rowJa.getString(j))) }
                            }
                        }
                    }
                } else {
                    null
                }
            } else {
                null
            }
        return GameStateWire(match, roll, list, crosses, acks, playerDisplayNames, lockReadyBySeat)
    }

    fun encodeIntent(playerIndex: Int, kind: String, body: JSONObject): ByteArray {
        val b = JSONObject(body.toString())
        b.put("playerIndex", playerIndex)
        val json = JSONObject()
        json.put("kind", "intent_$kind")
        json.put("body", b)
        return encodeAppPayload(json)
    }

    /** One player's sheet for LAN Done (clients send; host merges). */
    fun playerDoneSheetToJson(sheet: PlayerSheet): JSONObject {
        val rowObj = JSONObject()
        for (row in enumValues<com.locxx.rules.RowId>()) {
            val pr = sheet.rows[row]!!
            val crossedJa = JSONArray()
            pr.crossedIndices.sorted().forEach { crossedJa.put(it) }
            rowObj.put(
                row.name,
                JSONObject()
                    .put("crossed", crossedJa)
                    .put("last", pr.maxCrossedIndex)
                    .put("count", pr.crossCount)
                    .put("locked", pr.locked)
            )
        }
        return JSONObject()
            .put("rows", rowObj)
            .put("penalties", sheet.penalties)
    }

    fun playerDoneSheetFromJson(o: JSONObject): PlayerSheet {
        val rowsObj = o.getJSONObject("rows")
        val rows = enumValues<com.locxx.rules.RowId>().associateWith { r ->
            val rowO = rowsObj.getJSONObject(r.name)
            val locked = rowO.getBoolean("locked")
            val crossed = when {
                rowO.has("crossed") -> {
                    val ja = rowO.getJSONArray("crossed")
                    buildSet {
                        for (j in 0 until ja.length()) {
                            add(ja.getInt(j))
                        }
                    }
                }
                rowO.has("last") -> {
                    val last = rowO.getInt("last")
                    if (last < 0) emptySet() else (0..last).toSet()
                }
                else -> emptySet()
            }
            com.locxx.rules.PlayerRowState(crossedIndices = crossed, locked = locked)
        }
        return PlayerSheet(rows = rows, penalties = o.getInt("penalties"))
    }

    fun rollResolutionAuxToJson(res: RollResolutionState): JSONObject {
        val wc = JSONArray()
        res.whiteUsedForColor.forEach { wc.put(it.name) }
        return JSONObject()
            .put("whiteSumUsed", res.whiteSumUsed)
            .put("whiteUsedForColor", wc)
    }

    fun rollResolutionAuxFromJson(o: JSONObject, roll: DiceRoll): RollResolutionState {
        val wcJa = o.getJSONArray("whiteUsedForColor")
        val wc = buildSet {
            for (j in 0 until wcJa.length()) {
                add(DieColor.valueOf(wcJa.getString(j)))
            }
        }
        return RollResolutionState(
            roll = roll,
            whiteSumUsed = o.getBoolean("whiteSumUsed"),
            whiteUsedForColor = wc
        )
    }

    /** Client → host when finishing white/done or roller end turn (no per-mark sync). */
    fun buildLanDonePhaseBody(
        sheet: PlayerSheet,
        resolution: RollResolutionState,
        crossesThisRoll: Int
    ): JSONObject = JSONObject()
        .put("playerSheet", playerDoneSheetToJson(sheet))
        .put("rollResolution", rollResolutionAuxToJson(resolution))
        .put("crossesThisRoll", crossesThisRoll)

    fun diceRollToJson(roll: DiceRoll) = JSONObject().apply {
        put("white1", roll.white1)
        put("white2", roll.white2)
        put("red", roll.red)
        put("yellow", roll.yellow)
        put("green", roll.green)
        put("blue", roll.blue)
    }

    fun diceRollFromJson(o: JSONObject) = DiceRoll(
        white1 = o.getInt("white1"),
        white2 = o.getInt("white2"),
        red = o.getInt("red"),
        yellow = o.getInt("yellow"),
        green = o.getInt("green"),
        blue = o.getInt("blue")
    )

    private fun matchStateToJson(state: MatchState): JSONObject {
        val sheets = JSONArray()
        for (sheet in state.playerSheets) {
            val rowObj = JSONObject()
            for (row in enumValues<com.locxx.rules.RowId>()) {
                val pr = sheet.rows[row]!!
                val crossedJa = JSONArray()
                pr.crossedIndices.sorted().forEach { crossedJa.put(it) }
                rowObj.put(
                    row.name,
                    JSONObject()
                        .put("crossed", crossedJa)
                        .put("last", pr.maxCrossedIndex)
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
        return JSONObject()
            .put("playerCount", state.playerCount)
            .put("activePlayerIndex", state.activePlayerIndex)
            .put("sheets", sheets)
            .put("diceInPlay", dice)
            .put("globallyLockedRows", locked)
    }

    private fun matchStateFromJson(body: JSONObject): MatchState {
        val playerCount = body.getInt("playerCount")
        val sheetsJa = body.getJSONArray("sheets")
        val sheets = List(playerCount) { i ->
            val so = sheetsJa.getJSONObject(i)
            val rowsObj = so.getJSONObject("rows")
            val rows = enumValues<com.locxx.rules.RowId>().associateWith { r ->
                val o = rowsObj.getJSONObject(r.name)
                val locked = o.getBoolean("locked")
                val crossed = when {
                    o.has("crossed") -> {
                        val ja = o.getJSONArray("crossed")
                        buildSet {
                            for (j in 0 until ja.length()) {
                                add(ja.getInt(j))
                            }
                        }
                    }
                    o.has("last") -> {
                        val last = o.getInt("last")
                        if (last < 0) emptySet() else (0..last).toSet()
                    }
                    else -> emptySet()
                }
                com.locxx.rules.PlayerRowState(
                    crossedIndices = crossed,
                    locked = locked
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
}
