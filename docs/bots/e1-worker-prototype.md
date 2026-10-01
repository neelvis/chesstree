# E1 bounded-search and browser-worker prototype

Date: 2026-09-30. Scope: E1a.1 and the executable portion of E1a.2 in
[the execution decision](e1-execution-decision.md). This is a development
prototype, not a user-facing bot mode or an accepted speed result.

Historical milestone: the statements below describe the initial prototype.
The subsequent [watch integration and candidate report](e1-watch-integration.md)
records app runners, wire v2 completed-depth publication, current asset packaging,
geometry optimization, and the bounded pawn-progress experiment. The development
watch UI now uses the bounded runner; this is not a released bot acceptance.

## Delivered in this slice

- `BoundedMaxNBot` provides iterative MaxN with a checked legal fallback,
  completed-depth accounting, node/evaluation caps, and an injected stop probe.
  Cancellation returns no move; timeout returns the last completed move or the
  fallback. `MaxNBot` and its existing callers retain their previous behavior.
- `botWire` encodes the complete `GameState` as versioned JSON without adding
  transport dependencies to `gameDomain`. The codec round-trips castling rights,
  multiple en-passant eligibilities, army controllers, participant statuses,
  turn, and outcome. Worker requests/replies carry game/request/revision identity
  and pinned schema/rules/engine/evaluation versions.
- `botWorker` is a dedicated Kotlin/JS executable using the same shared rules
  and bounded search. It sends a `READY` message with the authoritative legal
  fallback before search, then a final response. The page can terminate this
  separate worker when the request is cancelled.
- Both JS and Wasm production distributions include the same
  `botWorker.js` asset. The worker is not yet invoked by the ChessTree UI.

## Evidence and limits

The focused `BoundedMaxNBotTest` passed after a Kotlin incremental-compiler
cache failure; retrying with `-Pkotlin.incremental=false` passed. The full
`gameDomain:jvmTest` suite passed after the initial bounded-search
implementation. `botWire:jvmTest` passed, including full-state round trips,
request validation, and a deterministic browser request fixture. The
`botWorker:jsBrowserDevelopmentWebpack` build and both Web production
distribution tasks passed. The final distribution configuration uses Gradle's
`Sync.from` input; an earlier `doLast` copy attempt failed configuration-cache
serialization and was replaced.

The same worker file was found in both distribution directories with an
identical SHA-256 at the checked build. A headless Google Chrome smoke page
received `CHESSTREE_BOT_WORKER_PONG_V1` from the JS distribution. In the Codex
in-app browser, a complete opening request sent to the worker from each of the
JS and Wasm distribution directories received `READY` followed by `MOVE` for
WHITE, `completedDepth=1`, `reason=TIMEOUT`, at a 1,200 ms worker budget. After
including worker-side decoding in that budget, the single repeated probes
reported 1,207 ms from the JS directory and 1,213 ms from the Wasm directory.
The Wasm probe loaded its worker asset directly; it did not run the Compose/Wasm UI
or a Wasm-to-worker adapter. The generated smoke HTML and JSON live only under
ignored `build/` directories.

The timeout is a warning about performance, not an N01 measurement. This was
one synthetic opening request in a development browser context, without a
release-device sample, precise total/request timing, frame trace, cancellation
distribution, or tournament. The current implementation checks the stop probe
between search steps and after a leaf evaluation; the existing move generator
and reducer are not themselves interruptible. N02/N03 therefore remain open.
The worker-side elapsed field starts when its message handler receives the
request; transfer, startup, and page-side application time still need a
controller-level measurement.

## Next implementation gate

Add Web JS/Wasm adapters and native runners behind a common application
interface, then wire request/game/revision ownership into local game lifecycle.
The controller must terminate stale workers, reject replies from an old
request or position, and revalidate returned intents through `GameSession.apply`.
It must handle a worker failure before `READY` as retryable instead of inventing
a move. Keep the existing watch-mode MaxN control active until the bounded
runner's quality and timing have been compared; user-visible training mode,
per-seat profiles, save migration, and endgame strategy are later gates.
