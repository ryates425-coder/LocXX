# Local Nakama (optional)

Use when you do not have access to the Azure-hosted Nakama stack.

```bash
docker compose up -d
```

- API: `http://127.0.0.1:7350` — set in `android/local.properties` as `nakama.host=10.0.2.2` (emulator) or `127.0.0.1`, `nakama.port=7350`, `nakama.ssl=false`.

For **production**, set `nakama.host`, `nakama.port` (often `443`), and `nakama.ssl=true` in `android/local.properties` to match your Azure **`nakamaApiUrl`**. Use the **HTTP API / WebSocket** port for `nakama.port` (7350 locally, **443** on Azure when ingress maps to container 7350). The stock Java `DefaultClient` speaks **gRPC** on a different port (7349) by default; this app authenticates with **REST** on the same port as `/ws` so single-port Azure ingress works (`404` / `UNIMPLEMENTED` on gRPC otherwise).

`nakama.host` may be the **FQDN only** (recommended) or a full URL (`https://…`) — the app strips `http(s)://` and any path so Nakama’s client gets a valid hostname.
