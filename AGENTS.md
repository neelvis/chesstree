---
description: ChessTree Kotlin Multiplatform project rules for Codex.
alwaysApply: true
---

# ChessTree development rules

## Product and supported targets

ChessTree is a production-oriented graphical board-game application. The current
product is a checkers game; authentication, online multiplayer, persistence, and
other capabilities may be added later.

The required product targets are Android, iOS, and Web. The repository currently
contains a desktop JVM target, but desktop/Windows is not a required acceptance
target unless the user explicitly includes it. Preserve existing desktop support;
do not expand or remove it incidentally. Web support must use the project's chosen
Kotlin target and must not be claimed until a browser target and its checks exist.

The user is new to KMP. Explain material KMP-specific decisions and trade-offs in
plain language. Do not make the user choose between implementation details when a
well-supported default can be selected from project evidence and official docs.

## General workflow

- Read this file, the invoked skill, and only task-related project documentation
  before modifying code.
- Keep changes tightly scoped. Preserve user-owned edits and generated files.
- Treat `.agents/skills` as the Codex project skill set. Do not create Claude or
  Cursor adapters unless the user later asks for them.
- Before dependency, Gradle, Kotlin, Compose Multiplatform, Android, iOS, or Web
  configuration changes, verify the current compatible APIs in official
  documentation. Prefer stable releases compatible with the repository; do not
  upgrade unrelated dependencies opportunistically.
- Run the narrowest relevant formatting, static, compile, and test checks. Never
  report an unbuilt target as verified.
- Never expose secrets, credentials, signing material, tokens, or private user data.

## Architecture

- Maximize useful sharing, not a nominal 100% shared-code target. Put domain rules,
  application state, use cases, data contracts, and reusable Compose UI in shared
  code when their behavior is genuinely common.
- Keep platform entry points thin. Put platform integrations behind small common
  interfaces and inject implementations. Prefer ordinary interfaces and factories
  over `expect`/`actual`; use `expect`/`actual` for small platform primitives when
  it is the clearest boundary.
- Keep domain code independent of Compose, Android SDK, UIKit/SwiftUI, browser DOM,
  storage engines, transports, and dependency-injection frameworks.
- Use unidirectional data flow: immutable UI state flows down and typed events flow
  up. Side effects belong at explicit boundaries with structured concurrency and
  lifecycle-aware collection.
- Prefer feature-oriented packages and clear `domain`, `data`, and `presentation`
  boundaries. Add modules only when ownership, build isolation, or reuse justifies
  them; do not create layers or abstractions speculatively.
- Dependencies point inward. UI may call application/domain APIs; domain must not
  depend on UI, transport, persistence, or a platform runtime.
- Model failures explicitly at boundaries. Do not catch and discard exceptions,
  use exceptions as normal domain control flow, or expose transport DTOs directly
  to UI state.

## Checkers domain

- Keep the rules engine deterministic, platform-independent, and separately
  testable in common Kotlin.
- Represent board coordinates, pieces, side-to-move, moves, captures, promotion,
  and terminal results with explicit domain types and immutable state.
- Do not infer a rule variant. Before behavior depends on capture priority,
  multi-jump rules, king movement, promotion timing, board size, draw rules, or
  notation, obtain or document the chosen ruleset.
- The domain validates legal moves; the UI only presents candidates and sends
  intent. Never make animation or pointer state authoritative game state.
- Make state transitions serializable and deterministic enough for future replay,
  save/restore, multiplayer synchronization, and server-authoritative validation.
- Test rule invariants, forced captures, capture chains, promotion, illegal moves,
  end states, and any chosen draw semantics before treating gameplay as complete.

## Compose Multiplatform UI

- Use Compose Multiplatform and Material 3 APIs supported by all required targets.
- Prefer stateless content composables with state/event parameters. Hoist screen
  state and keep navigation, effects, and platform services out of reusable UI.
- Make the board responsive rather than relying on device-specific dimensions.
  Preserve square geometry, hit targets, orientation semantics, and accessibility
  across touch, mouse, keyboard, screen sizes, and density.
- Provide useful previews or lightweight sample states where supported; do not make
  preview-only platform APIs part of production design.
- Avoid platform-specific UI forks unless interaction conventions or unavailable
  APIs materially require them. Document intentional differences.

## Future-facing boundaries

- Do not implement authentication, networking, storage, analytics, or multiplayer
  merely because they are planned. Keep today's interfaces evolvable without
  speculative infrastructure.
- When those features are requested, separate local identity/session state from
  game state, keep credentials in platform-appropriate secure storage, and treat
  backend authorization and game validation as authoritative.
- Multiplayer work must define protocol versioning, game identifiers, ordering,
  idempotency, reconnect/resync behavior, stale-event handling, and ownership of
  clocks/results before implementation.
- Persistence and wire schemas require explicit versioning and migration strategy.

## Source sets and target parity

- Respect KMP source-set boundaries (`commonMain/commonTest`, platform source sets,
  and any intermediate sets). Never import JVM/Android APIs into common code.
- A shared change is complete only after compiling every affected required target
  that the repository can build on the current host. If iOS or browser execution is
  unavailable, run the available compile/test task and name the remaining manual
  check precisely.
- Platform-specific behavior needs contract tests or focused platform tests as
  appropriate. Common tests alone do not prove entry-point, lifecycle, input,
  accessibility, or packaging correctness.

## Code quality

- Follow existing Kotlin style; use explicit imports and avoid wildcard imports.
- Prefer immutable values, exhaustive sealed hierarchies, small pure functions,
  and clear names over comments that restate code.
- Use coroutines with structured scopes. Do not use `GlobalScope`, unmanaged jobs,
  blocking waits on UI threads, or mutable shared state without an ownership model.
- Add only libraries that support all consuming targets or isolate them behind a
  platform boundary. Record the reason for a new dependency.
- Avoid deprecated and experimental APIs unless the benefit is explicit, support
  across required targets is verified, and opt-in/migration risk is documented.

## Multi-agent work

Use project roles only when the task is substantial enough to benefit from them.
Keep the root agent as coordinator, delegate bounded independent discovery or
review, assign one writer per file, and reconcile all findings before completion.
Routine bounded changes should stay in one context.

For large or cross-target work, define intended behavior, files, stages, acceptance
criteria, target-specific validation, and material risks before implementation.

