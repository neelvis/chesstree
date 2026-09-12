# ChessTree

An empty Kotlin Multiplatform project for a cross-platform chess game. The shared
Compose UI lives in `composeApp` and is used by Android, iOS, and web apps.

## Run

- Android: open the project in Android Studio and run `androidApp`.
- iOS: open `iosApp/iosApp.xcodeproj` in Xcode and run the `iosApp` scheme.
- Web (Wasm): run `./gradlew :composeApp:wasmJsBrowserDevelopmentRun`.
- Web compatibility bundle: run `./gradlew composeCompatibilityBrowserDistribution`.

Во время ручной проверки стартовую позицию можно выбрать в меню сценариев над
доской. Как добавлять собственные произвольные позиции, описано в
[`docs/domain-model.md`](docs/domain-model.md#сценарии-для-ручного-тестирования).

Requires JDK 17 or newer, Android SDK 37, and Xcode for iOS builds.

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
- `GET /health` for a process health check.

For any non-local deployment, expose the service only through HTTPS, replace the
development database password, and configure the reverse proxy to preserve the
real client address or enforce its own authentication rate limit. Serve the Web
client from the same origin (or add an explicit allowlisted CORS policy).

When the third distinct user joins, the lobby becomes `ACTIVE` and the server
randomly assigns `WHITE`, `RED`, and `BLACK`. The shared Compose UI now provides
registration, login, lobby creation, code entry, joining, and lobby refresh. On
Web, a `/g/{code}` URL opens the online screen with the code prefilled. Sessions
are persisted by the server, but the client access token currently remains only in
memory and requires a new login after restarting the app.
Native verified links and real-time move synchronization are separate later slices.
