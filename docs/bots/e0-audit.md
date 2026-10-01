# Bot product implementation: E0 audit

Date: 2026-09-30. Repository baseline: `000b555`.
Status: repository audit and JVM baseline experiment delivered; the full E0
acceptance gate remains open as listed below.

Source: [Bot Product Spec v1](../ChessTree_Bot_Product_Spec_v1.md).
The source specification was an untracked user-owned file at task start and is
preserved. This English implementation record supplies repository evidence and
does not silently change product scope, game rules, or acceptance thresholds.

## Scope of the first delivery

Establish the existing rules and bot architecture, run the shared rule baseline,
and add a reproducible JVM diagnostic experiment before choosing a replacement
search representation. This slice changes diagnostic test code and documentation.
Later persistence and lifecycle changes require the strict delivery workflow.

The full requested product remains four profiles, three difficulty levels,
human-versus-two-bot training, saved settings, cancellable bounded search, and
quality comparison on Android, iOS, and Web. Those features are not delivered by
this audit.

## Findings that change the implementation plan

| Area | Existing repository evidence | Required adjustment |
| --- | --- | --- |
| Rules and targets | `gameDomain` already shares rules across Android, iOS, JS, Wasm, and JVM. The UI has Android, iOS, and Web entry points. | E0 verifies existing behavior; it does not redesign the board or rediscover whether this is KMP. |
| Search | `MaxNBot` already uses the authoritative generator/reducer, typed evaluation weights, and bounded node/evaluation work. | Preserve a versioned control before optimizing. Existing MaxN is a baseline, not proof of the spec's strength or timing criteria. |
| Local mode | `ChessNavigationRoot` offers watching three bots and disables human board input during that mode. | Human-versus-two-bot training is a new local mode with per-seat settings, not a rename of self-play. |
| Online mode | The server already creates games with one or two bot seats and validates their moves with revision checks. | Explicitly decide whether the new local v1 also replaces online bots. No new backend is required for the audit. |
| Learning | Local self-play learns after a game. The server loads its shared policy for each turn and learns once per completed game. | New v1 configurations must be fixed for the agreed lifetime; do not let live policy changes invalidate fixed difficulty or comparisons. |
| Saves | `GameSnapshotCodec` v1 stores scenario ID and move intents, without per-seat settings or engine/rule/evaluation versions. Platform save stores have a load method, but no application call to `GameSaveStore.load()` was found. | F02 needs both a migration contract and a verified startup restore path; saving a text snapshot alone does not establish process-restart recovery. |
| Local lifecycle | The current effect captures a session, searches via `Dispatchers.Default`, and checks session equality before applying the move. Search itself has no cancellation polling. | Retain reducer validation and add explicit game/request/position identity plus cooperative cancellation. A coroutine cancellation request alone does not establish the N03 computation-stop bound. |
| Utility | Current search/learner value second place above third; ties have explicit utilities. | Record baseline semantics and choose a versioned normalized tournament reward before E2 acceptance. |
| Web execution | Browser off-thread execution requires an explicit worker boundary; moving work into a coroutine alone does not establish off-thread execution. | Bring the worker/serialization/cancellation prototype forward before expensive search integration. |
| Search limits | Current limits count expanded nodes and leaf evaluations. Initial move generation, transitions, and training-sample work also cost time. | A node cap is not a time bound. Measure the entire request and distinguish queue/search/result application later. |
| Completion | Root branches share quotas and may be cut at different depths. Only node and leaf counts are returned. | Add completed-iteration semantics before relying on a 'last completed depth' fallback. |
| Reproduction | Current move order and strict greater-than comparisons give stable tie behavior for a fixed state/configuration. | Freeze code, policy, state and work budgets in the baseline. A seed alone will not reproduce future time-limited searches. |

The concrete rule mapping and 12 starting boundary cases are in
[rules-contract.md](rules-contract.md).

## Decisions and open inputs

- D01: Use the existing shared Kotlin rules as authority. Measure their cost
  before duplicating state transitions in a mutable make/unmake representation.
- D02: Preserve current MaxN and `BotPolicy.DEFAULT` as the initial control.
  The diagnostic experiment does not update policy weights or imply a new
  tournament reward contract.
- D03: Retain existing game semantics during this slice. No repetition,
  no-progress, resignation, or clock rules are introduced. Arena resource caps
  must produce incomplete results, not synthetic draws.
- D04: Treat Android, iOS, and both configured Web targets as required. JVM is a
  rules/server and experiment target, not a desktop UI product.
- D05: Continue with the specification's local-v1 scope. The owner was asked
  whether online bot replacement must also be included and has not requested
  that expansion. Server compatibility remains a constraint when changing shared
  search APIs; replacing deployed online behavior is not part of this slice.
- D06: The owner supplied running Android/iPhone emulators and Chrome as the
  development verification environments. Their exact versions are recorded below.
  Simulator/JVM measurements cannot accept real-phone performance SLOs.
- D07: On 2026-09-30 the owner confirmed preserving the existing preference for
  higher placement. `arena-reward-v1` normalizes the current utility to [0, 1]
  without changing preference order; see the rule contract. No new draw, clock,
  or resignation rules are included in the initial implementation.

Useful integration sources:

- `composeApp/src/commonMain/kotlin/com/chesstree/app/ChessNavigationRoot.kt`
- `composeApp/src/commonMain/kotlin/com/chesstree/game/data/GameSnapshotCodec.kt`
- `composeApp/src/commonMain/kotlin/com/chesstree/game/data/GameSaveStore.kt`
- `server/src/main/kotlin/com/chesstree/server/Application.kt`
- `server/src/main/kotlin/com/chesstree/server/GameMoveRules.kt`

## Revised stage order and model use

| Stage | Concrete next output | Model assignment |
| --- | --- | --- |
| E0 | Repository audit, rules baseline, diagnostic experiment, owner decisions and reference devices | Astra High for unresolved decisions; Sol High for focused source tracing and the diagnostic harness |
| E1a | Cost profile of authoritative transitions; worker/native execution and cancellation prototype; request identity and version design | Sol High for implementation; Astra High for state/ownership risks |
| E1b | Search representation only after the profile justifies it; differential state/transition evidence | Sol High; Astra High review of restoration and special-move invariants |
| E2 | Frozen control configuration, replayable Arena, explicit incomplete outcomes, fixture calibration and fixed tournament scoring | Sol High; Luna Medium only for fixtures whose expected outcomes are already settled |
| E3 | Time-bounded completed iterations, ordering/cache, Paranoid candidate and equal-resource comparison | Sol High; Astra High at strategy acceptance or a reproduced unresolved regression |
| E4 | Profile behavior and endgame evaluation with measured strength/style limits | Sol High; Astra High for unexpected strategic effects |
| E5 | Local training UI, per-seat settings, versioned saves, lifecycle integration and target execution | Sol Medium for routine UI; High for concurrency and persistence |
| E6 | Held-out corpus, fixed tournament, product sessions and release evidence | Astra High independent audit |

Model overrides are applied only through controls exposed by the running host.
The coordinating chat's active model cannot be silently switched by its own
tool calls. Where available, bounded subtasks use an explicit model override;
an unavailable override must be reported, not simulated by changing a label.

The original 45-day estimate is unvalidated. Re-estimate after the transition
profile and worker prototype, retaining time for human fixture review and device
sessions. Existing code saves implementation work but adds compatibility work.

## Verification record

Initial baseline command:

```sh
./gradlew :gameDomain:jvmTest --console=plain
```

Result on baseline `000b555`: 82 tests, zero failures/errors/skips. This includes
7 existing bot tests and 14 engine tests. It is evidence of the current automated
suite only, not completion of the spec's independently reviewed fixture corpus.

Host: Mac17,3, Apple M5, 24 GiB RAM; macOS 27.0 (26A428);
Gradle 9.5.0 with launcher Java 21.0.11 and test runtime Java 25.0.3;
Xcode 27.0 (27A266a). No release-mode device measurements, frame
traces, memory profiles, thermal runs, 1,000-request SLO sample, or 600-game
tournament have been performed.

Available development surfaces, verified from the host inventory:

| Surface | Observed environment | Evidence limit |
| --- | --- | --- |
| Android emulator | Running `sdk_gphone16k_arm64`, Android 17 / API 37 | Runtime available; app scenarios and search timing not measured in E0. |
| iOS simulator | Booted iPhone 18 Pro, iOS 27.0 | Simulator available; app scenarios and search timing not measured in E0. |
| Browser | Installed Google Chrome 154.0.8037.92; owner reports an open signed-in session | Browser performance and worker execution not yet measured. |

Account credentials supplied for later app checks are intentionally absent from
repository artifacts and diagnostic reports.

Actual Gradle tasks were discovered with `:gameDomain:tasks --all`. Future shared
production changes must compile Android, iOS device/simulator, JS, and Wasm;
this JVM-only diagnostic slice does not establish those targets as verified.

## Reproducible baseline experiment

Implemented
`gameDomain/src/jvmTest/kotlin/com/chesstree/game/domain/bot/BotBaselineExperimentTest.kt`.
It prepares ten reachable consecutive opening states using public legal
transitions, covering all three seats, and runs each state twice at depth 2,
128 expanded nodes, 128 evaluations, and the frozen default policy. It checks
identical intents, training samples and counters; validates each chosen intent
with the public reducer; checks the state string before/after; and checks both
work limits. Fixture prefixes and selected moves are recorded as explicit
actor/coordinate/promotion tokens.

```sh
./gradlew :gameDomain:jvmTest --tests 'com.chesstree.game.domain.bot.BotBaselineExperimentTest' --console=plain
```

Result: passed after the final metadata edit. For an intentional repeat when
Gradle reports the test up to date, append `--rerun-tasks`. Do not compare an old
generated report with new source without running the experiment again.

The generated report is `gameDomain/build/reports/bot-baseline/experiment.tsv`.
The final E0 run is retained as [e0-jvm-baseline.tsv](e0-jvm-baseline.tsv), SHA-256
`d29e37d0dbd4599d56dc09868f4b55d89f5acc9ca3258cf688eb852c499bb9ed`.
Its production source is baseline `000b555`; schema, policy weights, test JVM,
OS, and architecture are embedded in the report.

The 20 request durations ranged from **685.81 to 865.90 ms**, with median
**701.88 ms**. Expanded nodes were 20–21 and leaf evaluations were 128 for every
fixture. Timings include `chooseDecisionWithStats`, including root legal
generation and training-sample construction, but exclude fixture preparation,
post-search intent validation, queueing, serialization, and UI application.
The first invocation is the first in this test, not a proven cold process.

This small, correlated opening sample is not a strength benchmark, a representative
position corpus, a release build comparison, or a p95/p99 SLO test. Other apps and
emulators were running on the host; power/thermal conditions were uncontrolled.
An earlier run before the report-only metadata edit had median 780.99 ms, which
also demonstrates why these timings must not be used as stable device thresholds.

The baseline request durations exceed the proposed 350 ms ordinary-level budget
on this host. The evidence supports profiling legal generation, transitions,
evaluation, and training-sample overhead before selecting optimization work.
It does not identify which component dominates or prove real-device performance.
No candidate algorithm, cache, deadline, cancellation guarantee, or strength gain
has been accepted.

## Review and model execution record

Two focused source audits and the diagnostic harness implementation ran with
explicit GPT-6 Sol / High overrides. An independent GPT-6 Astra / High reviewer
accepted the four E0 artifacts with no material findings, checking their hashes,
rule/utility claims, integration evidence, harness behavior, and report statistics.
The reviewer consumed the recorded passing tests and did not rerun builds.
Device execution, the broader fixture corpus, and future stages were outside
that review. This record was appended after the reviewed content was accepted.

## E0 exit gate and next handoff

Still required before accepting E0 in full:

1. Fix real-device performance references and measurement conditions; current
   emulators are suitable for development checks, not physical-phone SLO claims.
2. Review the rule fixture expectations independently of search implementation.

The next implementation slice may prepare infrastructure while these decisions
are pending, but must not accept a search strategy or alter public game rules.

Exact next action: on Sol High, profile root move generation, generated-move
transitions, evaluation, and training-sample construction on the same frozen
positions. Keep the recorded control intact and report component costs before
choosing an optimization. Then implement the bounded execution prototype,
including a browser worker proof and explicit request/position identity. Escalate
state-restoration or cancellation design risks to Astra High with a concrete
reproducer or contract. Reuse this audit instead of repeating repository discovery.
