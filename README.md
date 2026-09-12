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
