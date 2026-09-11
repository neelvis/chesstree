# KMP architecture decision guide

Read this reference when a task changes module/source-set structure, adds a library,
introduces a platform service, or creates a new data/application boundary.

## Placement test

Put code in `commonMain` when its behavior and dependencies are valid for every
required consumer. Keep it platform-specific when it directly depends on Android,
Apple, browser, or host lifecycle/security/UI APIs.

Choose the boundary in this order:

1. A common interface with a platform implementation supplied at composition root.
2. A common factory whose platform dependency is passed in.
3. A small `expect`/`actual` function or property for a true platform primitive.
4. Platform-specific UI only when shared Compose cannot express the required native
   behavior or the product intentionally differs.

Avoid `expect`/`actual` classes when ordinary interfaces are sufficient. Keep
platform implementations out of domain models and make them replaceable by fakes.

## Default feature shape

Use the smallest subset that the feature needs:

```text
feature/<name>/
  domain/          immutable models, rules, use cases
  data/            repository implementations and DTO mapping
  presentation/    state holder, UI state/events, Compose UI
```

This is a responsibility map, not a requirement to create three packages or Gradle
modules for every feature. Introduce a module only for a concrete ownership, reuse,
dependency, or build-performance reason.

## State and effects

- Keep one authoritative immutable state per screen/game session.
- Serialize events through an explicit state holder or reducer.
- Make long-running work cancellable and owned by an explicit lifecycle.
- Inject dispatchers, clocks, random sources, identifiers, and platform services
  when determinism or testing requires it.
- Convert data/transport failures into typed application outcomes before UI.

## Data and future online play

- Keep domain models separate from persistence entities and wire DTOs.
- Map at boundaries so schema changes do not rewrite game/UI logic.
- For online games, assume the server will be authoritative; local prediction must
  be reconcilable and never silently override server state.
- Define protocol version, sequencing, deduplication, reconnect/resync, clock source,
  and conflict policy before implementing real-time transport.

## Dependency gate

Before adding a library, verify from official/current sources:

- support for Android, Kotlin/Native iOS, and the chosen Web target;
- compatibility with the repository's Kotlin, Compose, Gradle, and AGP versions;
- lifecycle/threading behavior on Kotlin/Native and browser runtimes;
- maintenance status, license, footprint, and whether a standard API suffices.

Use the version catalog. Avoid unrelated upgrades and dynamic versions.

