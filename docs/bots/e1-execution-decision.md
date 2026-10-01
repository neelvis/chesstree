# E1 execution and search-state decision

Date: 2026-09-30. Status: design decision for the next bounded implementation
slice; no production runner or worker is implemented by this document. Baseline
search remains `MaxNBot` at depth 2 with 128 expanded nodes and 128 evaluations.
The E1 [cost profile](e1-cost-profile.md), [rules contract](rules-contract.md),
and [recording review](king-shuffle-video-review.md) are the evidence for this
decision. The product criteria remain in the [bot specification](../ChessTree_Bot_Product_Spec_v1.md).

## Decision and boundaries

1. Keep `GameState`, `LegalMoveGenerator`, and `GameReducer` authoritative. The
   first E1 implementation does not add a second move generator, mutable
   make/unmake position, a transposition table, or a new draw rule. A compact
   position becomes justified only if an equivalent optimization cannot meet
   the measured target and its full-state restoration can be verified.
2. Freeze the current MaxN control and its default policy for comparisons.
   The zero-mobility cost probe is diagnostic: it changed all ten sampled
   opening decisions. Any optimized evaluator must return the same scores and
   selected moves on the same states under the same work cap before it replaces
   the control. A new pawn/endgame evaluator is a separate quality candidate.
3. Put scheduling, deadlines, cancellation, and worker lifecycle outside
   `gameDomain`. The search core accepts an immutable state, fixed policy, work
   limits, and a small stop/deadline probe; it does not own UI state or storage.
   Preserve the existing `MaxNBot` API for server callers while adding a
   bounded-result API for the local runner.
4. Use one dedicated Kotlin/JS worker executable for browser search, callable
   from both the JS and Wasm UI builds through a versioned plain-data message.
   This avoids maintaining a second Wasm worker binary while still running the
   same shared Kotlin rules and search code away from the page thread. The
   exact bundling and asset path are subject to an executable Chrome spike on
   this repository's pinned Kotlin 2.4.20 toolchain. Native targets use an
   owned background coroutine/executor and the same search contract.
5. Keep the v1 rollout local: human versus two independent bots and existing
   local watch mode. Shared search API changes must compile for the server,
   but this decision does not replace online bots or add a backend dependency.

These are **strict-delivery** changes because stale results can corrupt a game,
Web execution crosses a serialization boundary, and shared source affects every
required target.

## Request, reply, and ownership contract

The local game controller is the only owner allowed to apply a move. It creates
one active request per game with a unique `requestId`, fresh `gameId`, and a
monotonically increasing `positionRevision`. The revision changes on every
applied move, undo, restore, reload, or restart; a new game identity is created
on restart or loading a different game. Reusing a previous ply number is never
enough to identify a position. A new request cancels the old one.

The immutable request contains the current actor, complete game state (or a
losslessly decoded wire representation), rules/schema version, engine/evaluation
version, fixed profile and difficulty, policy snapshot, seed, monotonic time
budget, and optional node/evaluation caps. The UI worker message uses a
versioned DTO of primitives, enums, and lists. It includes all piece identities,
coordinates, movement flags, castling and en-passant rights, army controllers,
participant statuses, turn, phase, and outcome. Neither `GameSnapshotCodec` v1
nor raw Kotlin object transfer is sufficient: that codec stores only scenario
and moves, while worker messages copy data without preserving Kotlin class
identity. Round-trip and cross-build compatibility must be checked before use.
No account identifiers or credentials enter the search message.

The controller accepts only a reply matching the currently active
`requestId`, `gameId`, and `positionRevision`, with compatible wire/rules/
engine/evaluation versions, while the current actor and position still match.
It then sends the returned move intent through `GameSession.apply` / the public
reducer. The worker's resulting position, if any, is never authoritative. A
failed public validation is an engine error, not a turn that silently
disappears. Clear the active request and thinking indicator for success,
terminal result, cancellation, timeout, and failure.

Use typed replies: `Move` (source = completed iteration or legal fallback),
`Terminal` (with the rules-authoritative outcome), `Cancelled`, and `Failed`.
Include stop reason, elapsed time, nodes/evaluations, and `completedDepth`.
An in-progress scenario with zero legal moves is a rules inconsistency or
unsupported setup; do not fabricate a terminal result. History is not a
terminal repetition input because ChessTree currently has no such rule. If a
future search preference uses visited positions, pass an explicit history
summary and version its position-equivalence rule; do not call that a draw.

## Time, fallback, and cancellation

Before exploring a root branch, obtain and retain one move from the
authoritative legal-move list. Generate this on the runner's background
execution context. The Web worker sends a `Ready` message containing the
validated fallback before starting expensive search. The controller starts
its hard-stop watchdog from request dispatch, includes startup, decoding,
fallback generation, and serialization in total latency, and retains the
fallback only for the matching live request. If the worker fails before
`Ready`, report a retryable failure; no guessed move is permitted.

Search checks the stop condition at least before each expanded node, leaf
evaluation, and root candidate, and during any long move-generation or
transition loop needed to satisfy N02/N03. Implement iterative deepening so
only an entirely finished root iteration updates the chosen move and
`completedDepth`. A partial iteration cannot be reported as completed. On a
normal budget expiry, return the last completed move, otherwise the validated
fallback. On explicit cancel, leave-game, or background invalidation, return
no move. Timeout and cancellation are distinct states.

For Android and iOS, cancel the owned job and have the core poll its stop
condition. A cancelled coroutine alone cannot interrupt synchronous search.
For Web, a `Cancel` message cannot be relied on while synchronous search is
occupying that worker's event loop. Terminate the worker on cancellation or
hard timeout, invalidate its request ID immediately, and create a replacement
before the next search. Do not require `SharedArrayBuffer` or cross-origin
isolation for v1. Keep at most one active worker/search for a local game and
set a small app-wide cap. A late message is ignored even if termination races
with delivery. Measure worker startup/replacement cost and main-thread frames.

## Performance and strategic acceptance

The first equivalent speed experiment should isolate mobility evaluation,
the largest demonstrated cost in the JVM opening probe. It may reuse legal
move counts already generated for the *same complete state and player* or add
an exact count path inside the authoritative generator. It must preserve
castling, en-passant eligibility, controller transfers, inactive participants,
king safety, and the current turn. Cache entries, if used, must be local to a
request until a complete key and invalidation policy are proven. Compare leaf
scores and selected root moves against the frozen MaxN control across opening,
tactical, transferred-army, and late-game fixtures; profile on the actual
browser and mobile builds before claiming the product time targets. Do not
equate the 734 ms JVM median with phone or release-build performance.

The supplied pre-improvement clip shows repeated nearby king moves while pawns
remain, but gives neither a move log nor the policy weights. Add a replayable
late-game fixture with all three players active, legal pawn advances, and king
shuffles. Record the control's root values and choices first. Test phase-aware
pawn advancement/promotion and a history-sensitive preference only as separate
candidate changes, with tactical exceptions where a king move is necessary.
Keep exact terminal placement reward above style preferences; use
`arena-reward-v1` for equal-resource comparisons. Do not introduce an
automatic repetition draw or claim a winning pawn line from the clip alone.

## Ordered implementation gates

| Gate | Output and key files | Evidence before moving on |
| --- | --- | --- |
| E1a.1 | Bounded result and stop contract in `gameDomain/.../bot`; narrow tests next to existing bot tests. | Frozen control still returns identical moves/scores under fixed work caps; fallback is legal; terminal, cancel, and partial-depth outcomes are distinct. |
| E1a.2 | Versioned search DTO and browser worker executable, plus JS/Wasm adapters in `composeApp`; update Gradle packaging only after a minimal build spike. | Both Web builds load the **same** JS worker asset in Chrome, exchange a full round-tripped position, return a reducer-valid move, terminate promptly, and keep page input responsive. Verify production distribution includes the asset. |
| E1a.3 | Native runners and local controller identity/lifecycle wiring in `composeApp`. | Android and iOS builds compile; on available emulator/simulator, replacement, undo, restore, leave, and background cases apply zero stale moves and clear the thinking state. Server callers still compile. |
| E1b | Equivalent mobility-cost optimization if E1a measurements justify it. | Exact evaluator and move parity over reviewed special-rule fixtures plus at least 10,000 reachable states when a separate counting path is introduced; then compare latency on each target. |
| E4 | Replayable king-shuffle fixture and one strategy candidate at a time. | Candidate improves the prespecified late-game behavior without losing mandatory tactics or material quality under equal resources; otherwise retain MaxN control. |

Run the narrowest relevant Kotlin checks during each gate and compile all
affected Android, iOS, JS, Wasm, and JVM/server targets available on this Mac
before accepting a shared production change. Browser and simulator behavior
needs real execution, not compile-only evidence. N01–N07 and the 60-position,
200-position, and tournament gates remain open until their specified samples
are actually collected. Reference mobile hardware is still unconfirmed; the
available Android emulator and iPhone simulator are development evidence.

## Official platform basis

- [Kotlin/JS project setup](https://kotlinlang.org/docs/js-project-setup.html):
  browser executables and Webpack/distribution tasks are supported. The
  repository pins Kotlin 2.4.20; its exact worker packaging still needs a build
  spike rather than an assumed Gradle recipe.
- [Kotlin Multiplatform target DSL](https://kotlinlang.org/docs/multiplatform/multiplatform-dsl-reference.html):
  JS and Wasm are distinct browser targets.
- [MDN Web Workers](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Using_web_workers):
  worker messages copy/transfer data and `terminate()` stops a worker from the
  page thread.
- [MDN structured clone](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Structured_clone_algorithm):
  object prototypes are not preserved across worker messages.
