# Push notification server files

## Public link association files

The web distributions include these files under `/.well-known/`:

- `apple-app-site-association` for iOS Universal Links;
- `assetlinks.json` for Android App Links.

Replace `<ANDROID_RELEASE_CERTIFICATE_SHA256>` in `assetlinks.json` with the
release certificate fingerprint in colon-separated SHA-256 format before serving
it. If Google Play signs the distributed app, use the Play App Signing certificate
fingerprint. Do not publish the placeholder value.

## Private Firebase credentials

`firebase-service-account.example.json` shows the shape of the Firebase Admin
service-account JSON. Download the real key from Firebase and store it on the
backend host outside the web root, for example at
`/etc/chesstree/secrets/firebase-service-account.json`, with access limited to the
backend service account. Never commit or serve the real key.

For iOS delivery through Firebase, upload the APNs authentication key (`.p8`), its
Key ID, and the Apple Team ID in the Firebase project settings. Keep the `.p8` file
private; it does not belong in the website's `/.well-known/` directory.

## Configure the backend

The production deploy script expects the real key at the repository root as
`firebase-service-account.json` (ignored by Git). It transfers the file over SSH
to the temporary release staging directory, then the remote activation step
installs it as
`/etc/chesstree/secrets/firebase-service-account.json` with restricted
permissions and removes the staging copy. Keep the local source file private and
never place it in a web-served directory.

The deploy activation step also adds or updates this setting in
`/etc/chesstree/server.env` while preserving its other variables:

```text
CHESSTREE_FCM_SERVICE_ACCOUNT_FILE=/etc/chesstree/secrets/firebase-service-account.json
```

The backend uses Firebase Admin SDK to send FCM messages. Without the service
account file, it starts with push delivery disabled. The service account should
belong to the same Firebase project as the mobile apps and have permission to
send Firebase Cloud Messaging messages. Do not put the actual JSON in the
repository or in a web-served directory.

Authenticated clients register a token with `POST /api/v1/push/devices`, sending
`{"token":"…","platform":"ANDROID"}` or `{"token":"…","platform":"IOS"}`.
They remove it with `POST /api/v1/push/devices/unregister` and
`{"token":"…"}`. The backend stores up to 20 device tokens per account and
removes tokens Firebase reports as unregistered. A game-start message goes to
players when the room becomes active; after a move, only the next player is
notified, and game-finish messages go to all players. Delivery is best-effort;
game actions do not depend on FCM being available.

The application clients still need to obtain their FCM registration tokens and
call these endpoints after sign-in. The push payload includes the game's web
link, `/g/{gameCode}`, so the existing app/universal links can route the user to
the room. On iOS, configure the APNs key in Firebase as described above.
