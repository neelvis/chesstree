# Bot quality baseline and first ordering experiment

Status: stage 0 complete; first stage 1 candidate measured and rejected. This is delivery evidence
for the accepted bot requirements, not a new feature specification or a playing
strength claim.

## Scope and authorization

The owner requested execution of [the plan](README.md) on 2026-10-19. The work
reuses accepted [BOT-D01–04, BOT-SP01–04 and BOT-QF01–05](../spec.md). No business
requirement, game rule, difficulty budget, wire/export field, dependency, or
deployment setting changes. The checkout starts at
`22005a6bb8b1eadf1d4599af4092d428e157c2e6` on `develop`; pre-existing deployment
and server changes, supplied planning files, and concurrent unrelated work are
preserved. There are no commits, pushes, or deployments.

## Corpus and trust checks

- All 474 source moves and all 14 manifest prefixes are checked against SHA-256,
  including the manifest's newline-terminated prefix convention. Every move is
  applied through the public reducer. Promotions at plies 295 and 463 retain
  their explicit choices. Full initial-to-prefix history is available; search
  receives the current 36-state repetition window.
- `botWire` independently decodes the exported initial state and proves exact
  equality with `StandardGame`, including identities, flags, rights, statuses,
  controllers, turn and phase. Domain tests acquire no transport dependency.
- Seven synthetic cases cover forced recapture through every legal intermediate
  Red reply, an unchecked recapture through both opponents, Red check evasion,
  mate/army transfer, Black's post-transfer response, a genuine two-active-player
  mate, and all four promotion choices. Their tests prove stated continuations,
  not a uniquely optimal bot move.
- Six held-out states are frozen by independent seeded legal trajectories from
  the standard start: seed/prefix 104729/24, 130363/25, 155921/26, 181081/42,
  206369/43, 232003/44. Selection does not inspect the reported game, evaluation
  scores, or bot choices. The six trajectories share a starting position and do
  not constitute six independent strength trials. All root seats are covered.
- Recorded queen, shuffle, exchange, promotion and conversion decisions remain
  strategic hypotheses until an alternative is independently justified. Mate
  attribution, army transfer and terminal ranking are verified invariants. The
  recorded SAN is never a best-move oracle.

## Measurement contract

The opt-in probe records source hashes, revision and dirty status; fixture and
replay provenance; seed, policy, profile, model, cache, depth, history and work/time
limits; selected legal move; completed horizon/depth; actual work and stop reason;
cache hits, retained entries and evictions. Internal attempt diagnostics distinguish
ordinary and tactical nodes/evaluations and identify discarded attempts and
unresolved checked players. They do not change `BoundedBotStats` or any wire/export
field. Diagnostic parity tests compare results, completed callbacks and exact stop
probe counts with diagnostics disabled/enabled.

The fixed-work control uses 2,000 nodes/evaluations, depth 4, tactical depth 4,
Paranoid, positional evaluation v3, Universal catalog 2, repertoire v1, seed 0,
repetition coefficient .50, and a cold 2,048-entry exact cache. Each configuration
runs twice without a time cutoff and asserts identical choices, work, attempts
and cache counters. This is a repeatable diagnostic budget, not a new Strong
preset. Timed samples use the existing 30-second soft limit and 1,000,000 ceilings.

Named feature terms describe the selected immediate child, not the minimax leaf
score. A separate fixed status term prevents eliminated-player penalties being
counted repeatedly in each feature; terms reconcile with the control evaluator.
Positional contributions are recorded as an aggregate delta. Terminal results are
excluded from heuristic decomposition and tuning.

## Reproduction

From the repository root:

```sh
docs/bots/agent-plan-20261019/run-baseline.sh
```

The script runs fixture/codec/diagnostic/safety checks and forces the opt-in
measurement task to execute even if Gradle previously cached that task. Default
output: `gameDomain/build/reports/bot-quality/oct19-baseline.tsv`. Optional
`CHESSTREE_BOT_QUALITY_WORK`, `CHESSTREE_BOT_QUALITY_OUTPUT`,
`CHESSTREE_BOT_QUALITY_CASES` and `CHESSTREE_BOT_QUALITY_TIMED=true` select a work
ceiling, destination, known comma-separated case IDs, and a 30-second timed mode.
Unknown case IDs fail rather than producing a misleading empty successful report.
`CHESSTREE_BOT_REPORTED_GAME_PATH` may point to a byte-identical source outside the
checkout; source identity is resolved independently from the repository directory.

## First ordering experiment: rejected for production

The first candidate saved moves only from exact, fully completed interior
subtrees. The second also admitted a legal refutation move returned by a valid
alpha-beta cutoff. Neither stored a score or bound in its hint table; all legal
moves remained searchable, original tie ordering was retained, and interrupted
nodes could not publish a hint. The table was request-local, full-state keyed,
Paranoid-only, and capped at 2,048 entries. Independent review found no concrete
safety issue, and eight focused tests covered exhaustive shallow references for
all root seats, ties/repertoire, real intermediate replies, unresolved checks,
interruptions, cache independence and fixed-work repeatability.

The candidate is **not enabled or retained in production source**. It is preserved
as [an opt-in experiment patch](experiments/saved-move-ordering.patch), including
its tests and probe switch. Apply it only in an experimental checkout, then run:

```sh
CHESSTREE_BOT_QUALITY_SAVED_MOVES=false \
  CHESSTREE_BOT_QUALITY_OUTPUT=build/reports/bot-quality/control.tsv \
  docs/bots/agent-plan-20261019/run-baseline.sh
CHESSTREE_BOT_QUALITY_SAVED_MOVES=true \
  CHESSTREE_BOT_QUALITY_OUTPUT=build/reports/bot-quality/candidate.tsv \
  docs/bots/agent-plan-20261019/run-baseline.sh
```

For the timed sample, add `CHESSTREE_BOT_QUALITY_TIMED=true`,
`CHESSTREE_BOT_QUALITY_WORK=1000000` and
`CHESSTREE_BOT_QUALITY_CASES=queen-exposure-139,rook-shuffle-193,exchange-267,held-out-155921-26`.
The script reruns the measurement rather than trusting Gradle's cached test task.

| Fixed work, 27 cases | Control | Bound-hint candidate |
| --- | ---: | ---: |
| Unchanged selected decisions, including terminal result | 27 | 27 |
| Completed CHECKS / EXCHANGES / terminal | 22 / 4 / 1 | 20 / 6 / 1 |
| Sum of actual nodes, first repeat | 14,430 | 14,134 |
| Sum of actual evaluations, first repeat | 43,787 | 42,970 |
| Both-repeat deterministic result/work/trace/cache checks | Pass | Pass |

The two extra EXCHANGES completions were synthetic unchecked recapture and
Black's post-transfer reply. At the latter, work fell from 2,000 evaluations
(`EVALUATION_LIMIT`, depth 4 CHECKS) to 1,004 (`DEPTH_COMPLETE`, depth 4 EXCHANGES).
The independently selected 26-ply Black held-out position used 790 versus 287
nodes at the same 2,000-evaluation cap. Conversely, promotion-463 evaluations
increased from 1,445 to 1,735 without a better retained horizon. Fixed-work elapsed
sums were also worse for the candidate; these sequential JVM samples are not a
controlled timing distribution.

| Timed JVM position | Control ms | Candidate ms | Retained result in both |
| --- | ---: | ---: | --- |
| queen-exposure-139 | 30,005 | 30,005 | depth 3 CHECKS; White g1–g2; TIMEOUT |
| rook-shuffle-193 | 30,000 | 30,000 | depth 3 CHECKS; White d2–f1; TIMEOUT |
| exchange-267 | 5,984 | 5,899 | depth 4 CHECKS; Black k9–b7; TACTICAL_LIMIT |
| held-out-155921-26 | 5,644 | 6,343 | depth 4 CHECKS; Black e10–k9; TACTICAL_LIMIT |

The last timed case required 4,859 versus 6,072 evaluations. No timed move,
completed depth or retained horizon improved. The current experiment therefore
does not justify production complexity or a strength claim. A horizon label or
node reduction on one position is insufficient evidence. The supplied exchange
positions still cap optional exchanges, and verified continuation improvements
for the queen/shuffle hypotheses remain unresolved.

Raw reports: [final diagnostic-only baseline](measurements/final-baseline.tsv),
[fixed experimental control](measurements/fixed-control.tsv),
[exact-only hints](measurements/fixed-exact-hints.tsv),
[bound hints](measurements/fixed-bound-hints.tsv),
[timed control](measurements/timed-control.tsv),
[timed bound hints](measurements/timed-bound-hints.tsv). Each records the actual
measured source hashes. The experiment patch recreates the second candidate from
the final diagnostic-only implementation; the measured builds explicitly passed
the ordering switch rather than relying on a changed default.

The final diagnostic-only implementation was rerun after removing the rejected
candidate. All 54 result/work/cache/trace rows exactly match the experimental
ordering-off control, excluding elapsed milliseconds. Source hashes in the final
baseline match the delivered active sources.

## Validation and review

| Check on final active sources | Result |
| --- | --- |
| Complete domain JVM suite | 202 tests, zero failures |
| Android host domain bot tests | 94 tests, zero failures |
| Wire JVM tests, including initial-state codec equivalence | 9 tests, zero failures |
| Android host local bot application tests | 26 tests, zero failures |
| Shared app Android compilation | Pass |
| Shared app iOS Simulator arm64 compilation | Pass |
| Shared app JS and Wasm compilation | Pass |
| Domain JS and Wasm test compilation | Pass |
| Worker JS compilation and development/production bundling | Pass |
| ChromeHeadless runner lifecycle tests | 3 executed, zero failures/skips |
| Real production-bundle Chrome worker smoke tests | 2 executed, zero failures |
| Final scoped diff and experimental patch applicability | Pass |
| Independent final static review | No material actionable findings |

The general JVM suite includes opt-in methods that return without measurements
when their environment flags are absent. The 27-case measurements above were
executed separately with explicit opt-in; the suite count is not a quality-corpus
size or number of independent experiments.

The complete compile/test gate used:

```sh
./gradlew :gameDomain:jvmTest \
  :gameDomain:testAndroidHostTest --tests '*bot.*' \
  :botWire:jvmTest \
  :composeApp:testAndroidHostTest --tests '*LocalBot*Test' \
  :composeApp:compileAndroidMain \
  :composeApp:compileKotlinIosSimulatorArm64 \
  :composeApp:compileKotlinJs :composeApp:compileKotlinWasmJs \
  :gameDomain:compileTestKotlinJs :gameDomain:compileTestKotlinWasmJs \
  :botWorker:compileKotlinJs :botWorker:jsBrowserDevelopmentWebpack \
  --continue --console=plain
CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  ./gradlew :composeApp:jsBrowserTest \
  --tests '*BrowserLocalBotRunnerCacheTest*' --rerun \
  -Pkotlin.js.browser.karma.browsers=ChromeHeadless --console=plain
```

The browser runner tests cover worker reuse, stale replies, game replacement,
cancellation, watchdog, malformed replies and handler cleanup. The real worker
test used the newly bundled production worker: cold / warm / new-game searches
returned the same completed move with 20 / 0 / 20 new evaluations and 89 / 3 / 54
ms elapsed. These are shallow diagnostic requests, not whole-game speed results.
A separate real depth-4, tactical-depth-4 request was terminated after its legal
fallback, its handlers retired, and a replacement worker completed normally.

Detailed local validation logs remain under
`work/bot-improvement-20261019/`: `final-baseline.log`, `target-checks.log`,
`chrome-lifecycle.log`, `real-worker-smoke.log`, and independent review records in
`subtasks/`. The smoke harness is in that directory's `browser-smoke/`; it uses
the repository's existing Karma dependencies. Compile/test evidence is invalidated
by changes to affected source, dependencies, configuration or requirements.

Not run: physical Android/iOS device UI, an Xcode app build, interactive full-board
Strong pause/resume, navigation, saved-game restoration and new-game replacement
on each platform. The remaining manual scenario is to start and restore a Strong
game, verify board responsiveness during thinking, pause/resume during a long
search, navigate away, and replace the game. Headless runner/worker checks do not
establish phone performance, UI behavior or a universal cooperative-overrun bound.

## Safety and remaining stages

An unresolved mandatory check still abandons its whole attempt. No static
stand-pat, hypothetical pass, skipped evasion or partial completed depth is added.
Earlier completed choices and legal fallback retain their authority. Both real
opponents act in the synthetic exchange continuations. Elimination uses the
reducer's actual mate attribution and changes turn order and army controllers.

The current report establishes a baseline and evaluates a first ordering candidate.
It does not complete selective tactical-frontier work, evaluation-term ablations,
typed cache bounds/successive-turn benchmarks, opponent-model arena experiments,
or release quality acceptance. Those remain the subsequent slices of the plan.
