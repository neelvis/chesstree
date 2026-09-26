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

These are setup templates only. The current backend does not yet register device
tokens or send FCM messages, so installing credentials alone will not turn on
notifications. The notification sender and token-registration API must be added
before the service-account file is consumed.
