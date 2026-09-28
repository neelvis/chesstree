# ChessTree Architecture

ChessTree is a three-player chess application for Android, iOS, and Web. JVM is
configured for the domain and backend, but the project has no desktop Compose
application target.

## Modules

| Module | Responsibility |
| --- | --- |
| `gameDomain` | Shared, pure game rules, board topology, immutable state, and replay. It has no dependency on UI, databases, networking, or platform APIs. |
| `onlineContract` | Versioned Kotlin Serialization DTOs for the REST and WebSocket APIs. |
| `composeApp` | Shared Compose UI, local screen state, API client, and local-save boundary. Platform source sets provide transport, session storage, navigation, and export adapters. |
| `androidApp` | Android entry point, Firebase Messaging setup, and Android-specific adapters. |
| `server` | Ktor API, authentication, game-command authorization, PostgreSQL JDBC adapter, and update/push delivery. The server uses the same `gameDomain` module. |

Dependencies point inward: the server and UI use the game engine, while the
domain knows nothing about HTTP, SQL, or Compose. `ChessTreeStore` separates
server use cases from JDBC; `onlineContract` separates the wire format from
server models and domain types.

## Online move flow

1. The client sends a move intent with a command ID and expected revision.
2. The server checks membership, turn, revision, duplicate commands, and move
   legality using the shared engine in `gameDomain`.
3. PostgreSQL records the move and increments the revision in one transaction.
   The unique `(game_id, command_id)` constraint prevents duplicate application.
4. PostgreSQL `LISTEN/NOTIFY` wakes subscribed server instances. The notification
   contains the internal game UUID and only signals that state should be read
   again.
5. The WebSocket sends a complete state first, then only appended moves. After
   moves are undone, the server sends the full history again. Clients can request
   REST resynchronization; the server remains authoritative.

Each game row stores a versioned snapshot of the last validated state and the
move count it represents. The move and snapshot are written in the same
transaction. A bounded in-memory cache is used only when its revision exactly
matches the database. If the snapshot is missing, corrupt, has an unsupported
version, or does not match the move count after an undo, the server rebuilds the
position from the journal. The journal remains authoritative; the snapshot only
speeds up reads. Schema versions are tracked in `schema_metadata`.

## Storage and platform boundaries

- Server credentials and game data are stored in PostgreSQL. Password hashes are
  available only to authentication paths; API responses contain public user
  records.
- REST and WebSocket DTOs are defined in `onlineContract`. The API version is
  included in the WebSocket handshake; see [`api.md`](api.md) for details and
  compatibility behavior.
- Local game saves use a separate format in `composeApp`. Session tokens use
  platform storage: Android Keystore, iOS Keychain, and memory only on the Web.
- The JDBC pool limits application connections. PostgreSQL `LISTEN` uses a
  separate dedicated connection because it must remain open between
  notifications.

## Bot strategy and training

`gameDomain` contains a bounded MaxN player that scores material, king safety,
check pressure, and mobility using typed policy rules. It selects only from
legal moves produced by the authoritative rules engine. Local three-bot games
save the learned policy with the local game data. Online bot games run on the
server, persist each bot move's feature sample, and update the shared policy
once when a game finishes. The policy is loaded again before every bot turn, so
new weights affect the next decision without restarting a game or server. Search
defaults cap work at 128 expanded nodes and 128 position evaluations, shared
across the legal root moves.
Online policy and training records are stored in versioned schema tables.

## Evolution

When game rules change, update the domain specification and engine tests. When
REST or WebSocket DTOs change, increment `API_VERSION`, add contract tests, and
plan client compatibility. When database tables change, add a migration tracked
by `schema_metadata`; do not rewrite the move journal to improve read speed. Add
new platform integrations behind a small interface and inject them at the
application composition root.
