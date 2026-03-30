# LocXX gaming wire protocol (LocXX stack)

This document is the **contract** between the Android (`lan-gaming`) and iOS (`LanGaming`) libraries. Implementations must match byte-for-byte for framing and handshake payloads.

The **transport** can be BLE (historical) or **HTTP on the local network** (current app builds). The binary frame layout and handshake payloads are identical on both transports.

## HTTP layout (same Wi‑Fi / LAN)

Default TCP port: **28765** (see `LOCXX_LAN_PORT` / `locxxLanPort`).

| Method & path | Role | Body | Response |
|---------------|------|------|----------|
| `POST /locxx/v1/hello` | Client → Host | One full wire frame: `CLIENT_HELLO` | `200`, body = `HOST_WELCOME` frame, header `X-LocXX-Token: <uuid>` |
| `GET /locxx/v1/poll?token=<uuid>` | Client → Host | — | Long poll (up to ~55s). `200` + body = one wire frame, or `204` if timeout / idle |
| `POST /locxx/v1/send?token=<uuid>` | Client → Host | One full wire frame | `204` empty body on success |

- Host pushes outbound frames to each joined client by satisfying that client’s **poll** with the next queued message (same frames as BLE notifications).
- Clients **must** include the token from `X-LocXX-Token` on poll and send after hello.
- Host listens on all interfaces. Apps **discover** the host with **mDNS / Bonjour** (DNS-SD) service type **`_locxx._tcp`** on the same LAN—no URL handoff required. After discovery, the client uses `http://<resolved-host>:28765` for the HTTP endpoints below.
- Manual entry of the URL is optional (e.g. if mDNS is blocked on the network).

## BLE GATT layout (legacy reference)

| Item | UUID |
|------|------|
| Service | `A0B40001-9267-4521-8F90-ACCD260FF718` |
| Characteristic `clientToHost` (write, write-with-response) | `A0B40002-9267-4521-8F90-ACCD260FF718` |
| Characteristic `hostToClient` (notify) | `A0B40003-9267-4521-8F90-ACCD260FF718` |

- **Host (peripheral)** exposes the service and both characteristics. `clientToHost` accepts writes from centrals; `hostToClient` is used for notifications to each subscribed central.
- **Clients (centrals)** write frames to `clientToHost` and subscribe to `hostToClient` notifications.

Advertising: host sets local name prefix `LocXX-` when possible; service UUID must be in advertisement packet.

## Protocol version

- Current wire version: **1** (`0x01`).

## Frame format

All messages are length-prefixed **frames**:

| Offset | Size | Field |
|--------|------|--------|
| 0 | 1 | `wireVersion` (must be `0x01`) |
| 1 | 1 | `messageType` (see below) |
| 2 | 2 | `payloadLength` unsigned big-endian |
| 4 | N | `payload` (N = payloadLength) |

Maximum single-frame payload: **4096** bytes (implementations may enforce lower MTU; chunking is not in v1—keep payloads small).

## Message types (transport)

| Value | Name | Direction | Description |
|-------|------|-----------|-------------|
| `0x01` | `CLIENT_HELLO` | C→H | Handshake: display name + client nonce |
| `0x02` | `HOST_WELCOME` | H→C | Handshake: assigned player id + session nonce |
| `0x03` | `ACK` | both | Application-level ack for a prior frame |
| `0x04` | `PING` | both | Keepalive |
| `0x05` | `PONG` | both | Keepalive reply |
| `0x10` | `APP_PAYLOAD` | both | Opaque application payload (game-specific) |

## `CLIENT_HELLO` payload (binary)

| Field | Type |
|-------|------|
| `protocolVersion` | `uint8` (must be `1`) |
| `displayNameLength` | `uint8` (0–64) |
| `displayName` | UTF-8 bytes |
| `clientNonce` | 16 bytes random |

## `HOST_WELCOME` payload (binary)

| Field | Type |
|-------|------|
| `protocolVersion` | `uint8` |
| `playerId` | `uint8` (1–8; `0` reserved) |
| `sessionNonce` | 16 bytes (must match host session) |

## `ACK` payload (binary)

| Field | Type |
|-------|------|
| `acknowledgedMessageType` | `uint8` |
| `correlationId` | `uint32` BE (echo from sender or sequence) |

## `PING` / `PONG` payload

Empty (`payloadLength` = 0).

## `APP_PAYLOAD`

Opaque bytes for the LocXX app (or any game). Encoding is defined by the app layer (e.g. JSON with `kind`, `body`).

## Handshake sequence

1. Client connects transport (BLE subscribe + write, or HTTP hello).
2. Client sends `CLIENT_HELLO` with unique `clientNonce`.
3. Host assigns `playerId`, registers the connection, replies with `HOST_WELCOME` (HTTP: same response body; BLE: notification on that central’s channel). HTTP also returns `X-LocXX-Token`.
4. Either side may send `APP_PAYLOAD` for game messages. Use the binary `ACK` frame only when the app layer requires an explicit transport ack (optional per message kind).

## Session nonce

Host generates one **session nonce** (16 random bytes) when hosting starts. All `HOST_WELCOME` messages include it so clients can ignore stale sessions.

## Reliability

- BLE writes use **write with response** where the platform supports it for `clientToHost`.
- HTTP **send** returns after the host accepts the frame; **poll** delivers host-originated frames in order per client.
- For critical game steps, the app uses **`ACK`** after processing an `APP_PAYLOAD` (optional per message kind).

## LocXX app payloads (v1)

JSON UTF-8 inside `APP_PAYLOAD`. Envelope (all messages):

| Field | Type | Description |
|--------|------|-------------|
| `v` | number | Must be `1`. |
| `kind` | string | Message discriminator (see below). |
| `body` | object | Payload; may be `{}`. |

Host → client:

| `kind` | `body` (summary) |
|--------|-------------------|
| `game_started` | Empty. UI: open the score sheet. |
| `host_exited` | Empty. Client should disconnect and return to menu. |
| `game_state` | Serialized match (`playerCount`, `activePlayerIndex`, `sheets`, …) plus optional open-roll phase (see below). |
| `roll` | Dice values + `activePlayerIndex` + `cid` for the current open turn; clients animate dice and open local resolution. |

Client → host (intents): `kind` is `intent_<action>`. The `body` always includes **`playerIndex`** (0-based seat). Additional fields depend on the action.

### `game_state` body

Always includes the authoritative **match** (same structure as in app code: `playerCount`, `activePlayerIndex`, `sheets`, `diceInPlay`, `globallyLockedRows`).

When a roll is **open** (everyone is scoring or in the white phase before the next roll), the body also contains:

| Field | Description |
|--------|-------------|
| `openRoll` | Object: `white1`, `white2`, `red`, `yellow`, `green`, `blue` (integers). |
| `playerRollResolutions` | Array, one entry per player, aligned with `openRoll`: `whiteSumUsed` (bool), `whiteUsedForColor` (array of color names, e.g. `RED`). |
| `activeCrossesThisRoll` | Integer: host-side count of crosses the **roller** has placed this roll (others are `0` in broadcast). |
| `whitePhaseAcks` | Array of seat indices that have pressed **Done** for this roll (inactive = finished white phase; roller = finished scoring). Next roll when every seat appears here. |
| `playerNames` | Optional: array of display strings, length = `playerCount`. |

Broadcast **privacy**: until a seat has pressed Done, that seat’s sheet in `game_state` may be shown as at **roll open** (masks in-progress marks); after Done, the merged sheet is authoritative in the broadcast.

**Deferred global locks (open roll):** while a roll is open, `globallyLockedRows` and `diceInPlay` in `game_state` stay at the values from **roll start** (committed table state). Rows a player locks on their sheet during this phase do **not** close the row for everyone or remove that color die until **every** seat has finished this roll and the host advances the phase (then one full derivation runs for the next roll).

### `roll` body

| Field | Type |
|--------|------|
| `white1` … `blue` | int |
| `activePlayerIndex` | int (0-based roller for this roll) |
| `cid` | int (correlation / generation for animations) |

### Done phase: client → host (no per-move sync)

Clients apply marks, undos, and voluntary penalties **locally** until they press **Done**. The host only receives **one** submission per seat per roll via an intent whose `body` includes a **Done bundle** alongside `playerIndex`.

**Done bundle** (required keys inside `body`):

| Field | Description |
|--------|-------------|
| `playerSheet` | That seat’s final **player sheet** (see below) after all local edits for this roll (including any **pass** penalty applied on Done when the roller had no crosses). |
| `rollResolution` | **Resolution aux** for this seat (`whiteSumUsed`, `whiteUsedForColor`; see below). Dice are implied by the current `openRoll` / `roll` for that phase. |
| `crossesThisRoll` | Integer. **Inactive** seats send `0`. **Roller** sends their cross count for this roll (after any local penalty-only path, the count is still `0`). |

| `kind` | Who | When |
|--------|-----|------|
| `intent_ack_white` | Non-roller | Finished optional white phase; body must include the Done bundle (`crossesThisRoll` must be `0`). |
| `intent_end_turn` | Roller | Finished scoring (and applied pass penalty locally if required); body must include the Done bundle. |
| `intent_roll` | Roller | Request a new roll (`body` may be only `playerIndex`; host rolls authoritatively). |
| `intent_debug_roll` | Roller (dev / debug UI) | Same as a normal roll but **`body`** includes fixed dice: `white1`…`blue`, optional **`cid`** (animation key). Host broadcasts that roll; not for untrusted clients in production. |

The host merges `playerSheet` and `rollResolution` for that `playerIndex`, updates `crossesThisRoll` on the host for the roller when relevant, then runs the same **Done / advance** logic as for the local host player (no separate `intent_mark`, `intent_undo`, `intent_penalty`, or `intent_undo_penalty`).

### `playerSheet` JSON

Object with:

- `penalties`: int  
- `rows`: object keyed by row name (`RED`, `YELLOW`, `GREEN`, `BLUE`). Each row object has:  
  - `crossed`: sorted array of crossed cell indices (preferred), or  
  - `last`: int (legacy-style contiguous prefix),  
  - `count`, `locked` as in the app codec.

### `rollResolution` JSON

Object with:

- `whiteSumUsed`: boolean  
- `whiteUsedForColor`: array of strings (`RED`, `YELLOW`, `GREEN`, `BLUE`)

The open roll’s dice are **not** duplicated here; implementations pair this with the phase’s `openRoll` / `roll` message when reconstructing full state.
