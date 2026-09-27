# ChessTree

ChessTree is a Kotlin Multiplatform three-player chess application. Its shared
Compose UI and deterministic game domain serve Android, iOS, and Web. A Ktor
backend provides account registration, online lobbies, authoritative move
validation, history synchronization, and optional push notifications.

## Run

- Android: open the project in Android Studio and run `androidApp`.
- iOS: open `iosApp/iosApp.xcodeproj` in Xcode and run the `iosApp` scheme.
- Web (Wasm): run `./gradlew :composeApp:wasmJsBrowserDevelopmentRun`.
- Web JavaScript compatibility bundle: run `./gradlew :composeApp:jsBrowserDistribution`.

For manual testing, choose a starting position from the scenario menu above the
board. See [`docs/domain-model.md`](docs/domain-model.md#manual-test-scenarios)
for instructions on adding custom positions.

Requires JDK 17 or newer, Android SDK 37, and Xcode for iOS builds. The browser
targets are Kotlin/Wasm and Kotlin/JS. JVM is configured for the domain and server;
there is no desktop Compose application target.

## Multiplayer backend MVP

The `server` module contains the first Ktor multiplayer slice: username/password
registration, opaque bearer sessions, creation of a three-player lobby, a random
seven-character game code, and joining by code. Users, sessions, and lobbies are
stored in PostgreSQL. Passwords are hashed with Argon2id; raw session tokens are
never stored in the database.

Start a local PostgreSQL instance (the password below is intentionally local-only):

```shell
docker compose up -d postgres
```

Then run the backend:

```shell
CHESSTREE_DATABASE_URL=jdbc:postgresql://localhost:5432/chesstree \
CHESSTREE_DATABASE_USER=chesstree \
CHESSTREE_DATABASE_PASSWORD=local-development-only \
CHESSTREE_PUBLIC_BASE_URL=http://localhost:8080 \
CHESSTREE_CORS_HOSTS=localhost:8080,127.0.0.1:8080 \
./gradlew :server:run
```

The backend listens on port `8081` by default, leaving `8080` available for the
Compose Web development server. Android Emulator connects to `10.0.2.2:8081`,
iOS Simulator to `127.0.0.1:8081`, and a local browser to `localhost:8081`.

The service exposes:

- `POST /api/v1/auth/register` with `{"username":"alice","password":"..."}`;
- `POST /api/v1/auth/login` with the same JSON shape;
- `POST /api/v1/auth/logout` with `Authorization: Bearer <token>`;
- `POST /api/v1/games` to create a lobby;
- `POST /api/v1/games/{code}/join` to join it;
- `GET /api/v1/games/{code}` for a participating user;
- `GET /api/v1/games/{code}/state` to resynchronize the authoritative move history;
- `POST /api/v1/games/{code}/moves` to submit a versioned, idempotent move command;
- `WS /api/v1/games/{code}/events` to receive authenticated state updates;
- `GET /health` for a process health check.

The complete API contract, authorization rules, revision and retry semantics,
WebSocket synchronization, and deployment assumptions are described in
[`docs/api.md`](docs/api.md). The module and source-set boundaries are summarized
in [`docs/architecture.md`](docs/architecture.md).

For any non-local deployment, expose the service only through HTTPS, replace the
development database password, and keep the backend reachable only through the
trusted reverse proxy. The shipped Nginx config limits authentication requests by
the client IP; Ktor uses forwarded addresses only when the direct peer is trusted.
Serve the Web client from the same origin (or add an explicit allowlisted CORS
policy for a same-site Web origin so credentialed cookie requests are allowed.

When the third distinct user joins, the lobby becomes `ACTIVE` and the server
randomly assigns `WHITE`, `RED`, and `BLACK`. The shared Compose UI provides
registration, login, lobby creation, code entry, and joining. Lobby and game
changes arrive through WebSocket push; REST remains authoritative for commands and
full-state resynchronization. Android encrypts the access token with a key from
Android Keystore, iOS stores it in Keychain, and Web keeps its bearer credential
in an `HttpOnly; SameSite=Strict` cookie so page scripts cannot read it. The Web
session restores after reload and signs out other open tabs when the user logs out.

Once the lobby is active, all moves are validated by the same deterministic domain
engine on the server. Each command contains an expected revision and a unique
command ID; stale clients resynchronize from the ordered move history and retried
commands cannot apply twice. Multiple backend instances must connect to the same
PostgreSQL database. Transactional database triggers publish an internal game UUID
through PostgreSQL `LISTEN/NOTIFY`; each instance resolves it to local WebSocket
subscriptions, so load-balancer sticky sessions are not required. Public join codes
are not exposed in notification payloads. The notification is only an invalidation
signal: authoritative state remains in the database, and reconnecting listeners
resynchronize their active local games. `LISTEN` uses a dedicated long-lived JDBC
connection, so both servers must connect directly to PostgreSQL or through a proxy
configured for session pooling rather than transaction pooling.

Web and the native entry points recognize `/g/{code}` URLs. When a user opens an
invitation, the app preserves the room code through login or registration and then
joins that room automatically; a saved native session joins without showing the
authentication form. Android now declares the production App Link, and iOS has the
Associated Domains entitlement. The Apple association file is included in both web
browser distributions. Android App Link verification still needs the release
signing certificate SHA-256 substituted for the placeholder in
`/.well-known/assetlinks.json` on the public domain. The Android association file
template is included in both browser distributions. The web server must serve both
association files directly over HTTPS. Keep Firebase/APNs private keys in server
secret storage; never place them in the public web root.

System push notifications are not configured yet. They need an Apple Push
Notification service/Firebase project, Android and iOS client configuration, and
server credentials. The existing WebSocket updates only reach clients while the
game app is running; they do not produce operating-system notifications.

The planned single-backend/two-database-host production topology, firewall rules,
Nginx configuration, and PostgreSQL streaming-replication procedure are documented
in [`deploy/README.md`](deploy/README.md).
