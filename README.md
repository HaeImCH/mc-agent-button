# Agent Button — Minecraft Fabric mod with an embedded MCP server

A client-side [Fabric](https://fabricmc.net) mod that embeds a
[Model Context Protocol](https://modelcontextprotocol.io) server **inside the running
Minecraft client**, so an external AI agent can drive the client over MCP:

- **read the current on-screen GUI** — every clickable button/widget, with index, label and state
- **click any of those buttons** — by index or visible label
- **manage multiplayer servers** — list saved servers, join one, disconnect

## How it works

```
 AI agent (MCP client)                Minecraft client process
 ─────────────────────                ─────────────────────────────────────
  tools/call  ──HTTP POST──▶  McpServer (127.0.0.1:8642 /mcp)
                              │  JSON-RPC 2.0 over Streamable HTTP (Gson)
                              ▼
                            McpTools ──ClientThread.get()──▶ render thread
                              │                                │
                              ├─ ServerActions  (connect / disconnect / list servers.dat)
                              └─ ScreenTools    (describe + click AbstractWidgets and list rows)
```

- Transport: MCP **Streamable HTTP**, single endpoint `POST /mcp`, one JSON response per
  request (no SSE needed). Bound to `127.0.0.1` with `Origin` validation.
- Every tool that touches game state is marshalled onto Minecraft's render thread via
  `ClientThread`, because client state is not thread-safe.

## MCP tools

| Tool | Arguments | Does |
|---|---|---|
| `get_screen` | – | List the open screen + its clickable widgets and list rows (index, label, state, position) |
| `click_button` | `index` *or* `label` | Click a widget, or scroll to + select a list row, on the current screen |
| `press_key` | `key` *or* `code` | Send a key press to the current screen |
| `list_servers` | – | List saved multiplayer servers (`servers.dat`) with indices |
| `join_server` | `address` *or* `index` | Connect to a server |
| `disconnect` | – | Disconnect from the current world/server |
| `get_status` | – | connected? current server, player name, open screen |

## Connecting an agent

The server listens at `http://127.0.0.1:8642/mcp` (override with
`-Dagentbutton.mcp.port=NNN` or env `AGENTBUTTON_MCP_PORT`).

**Claude Code** (HTTP transport, native):
```bash
claude mcp add --transport http minecraft http://127.0.0.1:8642/mcp
```

**Any stdio-only MCP client** (Claude Desktop, etc.) via the `mcp-remote` bridge:
```json
{
  "mcpServers": {
    "minecraft": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://127.0.0.1:8642/mcp"]
    }
  }
}
```

**Quick smoke test with curl** (while the game is running):
```bash
curl -s http://127.0.0.1:8642/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq
```

## Versions

| Component | Version |
|---|---|
| Minecraft | 26.3 |
| Mappings | none — 26.x is unobfuscated, code uses Mojang's names |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.163.0+26.3 |
| Fabric Loom | 1.18.4 |
| JDK | Temurin 25 |

This is the `26.3` branch. The 1.21.11 (Yarn) version lives on `main`.

## Build & run

JDK 25 is wired in via `org.gradle.java.home` in `gradle.properties`.

```bash
./gradlew build       # -> build/libs/agentbutton-1.0.0.jar
./gradlew runClient   # launch a dev client with the mod + MCP server
```

Drop `build/libs/agentbutton-1.0.0.jar` into the `mods/` folder of a Fabric Loader
26.3 install (with Fabric API) to use it in a normal client.

## Manual controls (handy for testing the screen tools)

Press **G** to open a demo screen of buttons, or use the client commands
`/agentbutton`, `/abconnect <host[:port]>`, `/abdisconnect`, `/abservers`.

## Layout

```
src/client/java/com/example/agentbutton/
  AgentButtonClient.java            # ClientModInitializer: keybind, commands, starts MCP server
  mcp/McpServer.java                # Streamable-HTTP JSON-RPC server (JDK HttpServer + Gson)
  mcp/McpTools.java                 # tool catalogue + dispatch
  mcp/ClientThread.java             # run work on the render thread, return a result
  action/ServerActions.java         # connect / disconnect / list saved servers
  action/ScreenTools.java           # describe + click on-screen widgets
  ui/AgentButtonScreen.java         # demo button screen
  command/AgentButtonCommands.java  # /agentbutton, /abconnect, /abdisconnect, /abservers
src/main/resources/fabric.mod.json
```
