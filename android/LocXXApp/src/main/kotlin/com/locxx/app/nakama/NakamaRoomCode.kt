package com.locxx.app.nakama

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Prefix for Nakama `MatchCreate.name`; keeps v5 names globally namespaced for this app. */
const val LOCXX_NAKAMA_ROOM_KEY_PREFIX: String = "locxx:"

/** Length of the human-shareable segment (after prefix). */
const val LOCXX_NAKAMA_ROOM_CODE_LEN: Int = 6

private val namespaceDns: UUID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

private const val ROOM_CODE_ALPHABET: String = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

/**
 * RFC 4122 UUID v5 (SHA-1), matching [github.com/gofrs/uuid](https://github.com/gofrs/uuid)
 * as used by Nakama’s relay `matchCreate` when [name] is non-empty.
 */
internal fun uuidV5(namespace: UUID, name: String): UUID {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(uuidToBytes(namespace))
    md.update(name.toByteArray(StandardCharsets.UTF_8))
    val hash = md.digest()
    hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
    hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
    val bb = ByteBuffer.wrap(hash, 0, 16)
    return UUID(bb.long, bb.long)
}

private fun uuidToBytes(uuid: UUID): ByteArray =
    ByteBuffer.allocate(16).apply {
        putLong(uuid.mostSignificantBits)
        putLong(uuid.leastSignificantBits)
    }.array()

internal fun locxxNakamaRoomKeyFromCode(normalizedCode: String): String =
    LOCXX_NAKAMA_ROOM_KEY_PREFIX + normalizedCode

/** Uppercase A–Z / 0–9 only; strips separators. */
fun normalizeLocxxNakamaRoomInput(raw: String): String =
    raw.uppercase().filter { it.isLetterOrDigit() }

fun generateLocxxNakamaRoomCode(rnd: SecureRandom = SecureRandom()): String = buildString(LOCXX_NAKAMA_ROOM_CODE_LEN) {
    repeat(LOCXX_NAKAMA_ROOM_CODE_LEN) {
        append(ROOM_CODE_ALPHABET[rnd.nextInt(ROOM_CODE_ALPHABET.length)])
    }
}

/** Nakama relay match id: `"{uuid}."` (second segment empty for relay). */
fun nakamaRelayMatchIdFromLocxxRoomKey(roomKey: String): String =
    uuidV5(namespaceDns, roomKey).toString() + "."

/**
 * Joiners may paste either a short room code or a full relay match id (`xxxxxxxx-xxxx-...-xxxx.`).
 * Returns relay id suitable for [SocketClient.joinMatch], or `null` if input is invalid.
 */
fun resolveNakamaJoinMatchId(input: String): String? {
    val t = input.trim()
    if (t.isEmpty()) return null

    val hasTrailingDot = t.endsWith('.')
    val core = if (hasTrailingDot) t.dropLast(1).trim() else t

    val looksLikeUuid =
        core.length == 36 &&
            core[8] == '-' &&
            core[13] == '-' &&
            core[18] == '-' &&
            core[23] == '-' &&
            core.all { it == '-' || it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    if (looksLikeUuid) {
        val u = runCatching { UUID.fromString(core) }.getOrNull() ?: return null
        return u.toString() + "."
    }

    val code = normalizeLocxxNakamaRoomInput(t)
    if (code.length != LOCXX_NAKAMA_ROOM_CODE_LEN) return null
    val roomKey = locxxNakamaRoomKeyFromCode(code)
    return nakamaRelayMatchIdFromLocxxRoomKey(roomKey)
}
