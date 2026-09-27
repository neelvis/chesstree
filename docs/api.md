# Multiplayer API

The source of truth for request and response types is
[`ApiContract.kt`](../onlineContract/src/commonMain/kotlin/com/chesstree/multiplayer/contract/ApiContract.kt).
The server routes are in `server/src/main/kotlin/com/chesstree/server/Application.kt`.
The current protocol version is defined by `API_VERSION` and is sent in the
`X-ChessTree-Protocol-Version` header for REST state reads and in the first
WebSocket authentication message.

## Authentication

Register and login accept JSON `{ "username": "...", "password": "..." }`.
Successful responses contain a random bearer token and the public user ID/name.
Password hashes are Argon2id; only the hash is stored. Sessions expire after 30
days of inactivity and are renewed on authenticated use. Logout revokes the token.
Authenticated REST requests use `Authorization: Bearer <token>`.

The Web client uses the `/api/v1/auth/browser/*` routes instead. Register and
login set a `Secure` (on HTTPS), `HttpOnly`, `SameSite=Strict` cookie scoped to
`/api/v1`; their JSON response contains the user but never the bearer token.
`GET /api/v1/auth/browser/session` restores the signed-in user after a page
reload, and `POST /api/v1/auth/browser/logout` revokes the session and expires
the cookie. Browser login, registration, and cookie-authenticated state changes
must come from the `CHESSTREE_PUBLIC_BASE_URL` origin or an origin in
`CHESSTREE_CORS_HOSTS`. The Web client sends cookies with credentialed requests.
If it uses cross-origin API requests, the origins must also be same-site so the
Strict cookie is sent by the browser. Native clients continue using bearer
authentication.

All game routes require an authenticated session. A game is visible only to its
participants; non-participants receive the same not-found response as an unknown
game code. A game becomes active when its third distinct participant joins.

## Routes

| Method and path | Purpose |
| --- | --- |
| `POST /api/v1/auth/register` | Create an account. |
| `POST /api/v1/auth/login` | Start a session. |
| `POST /api/v1/auth/logout` | Revoke the current session. |
| `POST /api/v1/auth/browser/register` | Register for Web and set an HttpOnly session cookie. |
| `POST /api/v1/auth/browser/login` | Log in for Web and set an HttpOnly session cookie. |
| `GET /api/v1/auth/browser/session` | Restore the current Web session's public user. |
| `POST /api/v1/auth/browser/logout` | Revoke the Web session and expire its cookie. |
| `POST /api/v1/push/devices` | Register an Android/iOS push token. |
| `POST /api/v1/push/devices/unregister` | Remove the current user's push token. |
| `POST /api/v1/games` | Create a lobby. |
| `GET /api/v1/games` | List the user's games. |
| `POST /api/v1/games/{code}/join` | Join a lobby. |
| `GET /api/v1/games/{code}` | Read lobby metadata. |
| `GET /api/v1/games/{code}/state` | Read or resynchronize game state. |
| `POST /api/v1/games/{code}/moves` | Submit a move. |
| `POST /api/v1/games/{code}/undo-requests` | Request a group undo. |
| `POST /api/v1/games/{code}/undo-requests/{requestId}/votes` | Vote on an undo request. |
| `WS /api/v1/games/{code}/events` | Receive authenticated state updates. |
| `GET /health` | Process health check. |

## Move and sync semantics

A current move command includes `commandId`, `expectedRevision`, `expectedMoveCount`,
`from`, `to`, and an optional promotion. `expectedMoveCount` is the client's current
history length; it lets the move response return only newly added moves. Older
clients may omit it and receive a full history. The server serializes commands per game in a database
transaction. A command ID is idempotent only when retried with the same user,
revision, and move; reusing it for different input is a conflict. Stale revisions
must be synchronized before retrying.

State responses contain the game revision, a `moveOffset`, and move events. With no
`afterMoveCount` query parameter, REST returns the full move history with offset
zero. To request only moves after a known count, pass
`?afterMoveCount=<number>`. The server returns the suffix and its starting offset;
if the non-negative cursor is greater than the current history (for example after
an undo), it returns a full state so the client can rebuild safely. A malformed or
negative cursor is rejected with `400 Bad Request`. Revisions include undo voting
changes; move offsets count actual moves and therefore are not interchangeable
with game revisions.

The WebSocket first sends a full state. Later pushes include only appended moves.
Clients append a suffix only when its offset matches their current move count.
After an undo truncates history, the server sends a full state. On reconnect, the
client receives a full state and can request REST resynchronization at any time.

The WebSocket client sends `GameSocketAuthRequest` as its first frame because
browser WebSocket APIs do not permit setting an Authorization header. Native
clients include their bearer token in that frame. The Web client sends an empty
token and authenticates with the HttpOnly session cookie. The server checks the
request origin, session, and game membership before subscribing. WebSocket
protocol mismatches return the current version and close the connection so the
client can prompt for an update.

## Deployment security

Serve non-local traffic over HTTPS/WSS. The production Nginx proxy overwrites
`X-Forwarded-For` with the observed client address. Ktor uses forwarded client
addresses for rate limiting only when the direct peer matches
`CHESSTREE_TRUSTED_PROXY_ADDRESSES`; keep the backend unreachable from untrusted
networks. Nginx also applies the authentication rate limit before proxying, so
separate Ktor instances behind this proxy share the edge limit. CORS is an origin
policy, not authentication. Cookie sessions also validate the browser Origin on
state-changing requests. Database passwords and
Firebase service-account keys belong in server secret storage, never in the Web
bundle.
