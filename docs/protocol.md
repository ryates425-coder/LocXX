# Bluetooth gaming wire protocol (LocXX stack)

This document is the **contract** between the Android (`bluetooth-gaming`) and iOS (`BluetoothGaming`) libraries. Implementations must match byte-for-byte for framing and handshake payloads.

## BLE GATT layout

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

1. Client connects, enables notifications on `hostToClient`.
2. Client sends `CLIENT_HELLO` with unique `clientNonce`.
3. Host assigns `playerId`, registers the connection, replies with `HOST_WELCOME` on that central’s notification channel (same `sessionNonce` for all peers in the session).
4. Either side may send `APP_PAYLOAD` for game messages. Use `ACK` when ordering is required (e.g. after dice roll before marks).

## Session nonce

Host generates one **session nonce** (16 random bytes) when hosting starts. All `HOST_WELCOME` messages include it so clients can ignore stale advertisements.

## Reliability

- BLE writes use **write with response** where the platform supports it for `clientToHost`.
- For critical game steps, the app uses **`ACK`** after processing an `APP_PAYLOAD` (optional per message kind).

## LocXX app payloads (v1)

JSON UTF-8, object with:

- `v`: number (1)
- `kind`: string
- `cid`: number (optional correlation for ACK)

Kinds (examples):

| `kind` | Meaning |
|--------|---------|
| `lobby_state` | Host broadcast: player list, session nonce |
| `game_state` | Full serialized match state |
| `intent` | Player action: mark, pass, penalty, etc. |
| `intent_reject` | Host rejection with `reason` string |
| `roll` | Dice values + active player |
| `turn` | Turn / phase change |

Exact field schemas are implemented in app code; the Bluetooth library treats these as opaque bytes.
