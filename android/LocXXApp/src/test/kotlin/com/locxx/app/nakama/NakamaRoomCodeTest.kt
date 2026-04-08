package com.locxx.app.nakama

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class NakamaRoomCodeTest {

    @Test
    fun uuidV5_matchesModernNameBased() {
        val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        assertEquals(
            "2ed6657d-e927-568b-95e1-2665a8aea6a2",
            uuidV5(dns, "www.example.com").toString(),
        )
    }

    @Test
    fun resolve_join_accepts_short_code_and_full_relay_id() {
        val key = locxxNakamaRoomKeyFromCode("ABC123")
        val relay = nakamaRelayMatchIdFromLocxxRoomKey(key)
        assertEquals(relay, resolveNakamaJoinMatchId("abc123"))
        assertEquals(relay, resolveNakamaJoinMatchId(" ABC123 "))
        assertEquals(relay, resolveNakamaJoinMatchId(relay))
        assertEquals(relay, resolveNakamaJoinMatchId(relay.removeSuffix(".")))
    }
}
