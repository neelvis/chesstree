# October 17: shallow search and rook shuffling

Status: diagnosis only. No production behavior changed.

## Reproduction and evidence

The owner paused the Chrome bot game after 272 plies. The original export was
`chess_party_2026-10-17_20-07-23.txt`; the same paused game was downloaded again
as `chess_party_2026-10-17_20-14-26.txt` after the first file was removed.
All 272 moves, including promotion, replayed successfully through the public
`GameReducer`. Black was checkmated by White after exported move 194; the domain
records elimination at the next turn's ply 195 and transfers Black's army to White.

The export records Universal / Strong for every seat, positional evaluation 3,
Paranoid search, and profile catalog 2. All 272 requests timed out, averaging
2,537 ms: 265 completed depth 1 and seven depth 2. Only four completed the
EXCHANGES horizon; 268 retained CHECKS. After move 194 there are 78 plies without
a capture or check, including 55 rook moves. The final pawn move is 216.

Exported repetition penalties are 0 on 263 moves, 12 on eight, and 24 on one.
These match the historical 0.12 coefficient at material weight 100, whereas
the current uncommitted local implementation uses 0.50. The export does not
identify an exact deployment commit; treat build identity as unverified.

## Timed experiment

Fixture: after 248 exported moves, White to move with 99 legal moves. Replay,
history window 36, exported policy, Paranoid, positional evaluation, profile
catalog 2, tactical depth 4, normal maximum depth 4, repetition coefficient 0.12.
The opening book cannot influence this late position. Node/evaluation ceilings
were raised to 1,000,000 to isolate elapsed time, rather than stop at the normal
20,000 limits. These are single JVM measurements, not browser benchmarks or
proof that one selected move is objectively best.

| Search budget | Completed result | Selected move | Evaluations consumed |
| --- | --- | --- | ---: |
| 2.5 seconds | Depth 1, CHECKS | White rook h1–g1 | 2,155 |
| 10 seconds | Depth 2, CHECKS | Controlled black queen l11–l12 | 9,294 |
| 30 seconds | Depth 2, CHECKS | Controlled black queen l11–l12 | 25,856 |

Depth 1 completed at 114–144 ms; depth 2 completed at about 3.05 seconds.
The 30-second run never completed depth 3. The normal 20,000 evaluation ceiling
would prevent consuming all the work shown in that run even if its timer grew.
Search node/evaluation counts include discarded attempts, not only the returned
result's work.

An isolated control disabled tactical extension, without recommending that as a
product change. At 10 seconds it still returned depth 2 and the same queen move;
depth 2 completed at 3.15 seconds. This rules out tactical expansion as the main
cause on this specific fixture. Other checked or capture-heavy positions can
have different cost distributions.

After warmup, 20-operation JVM averages were roughly 0.67 ms for root legal
generation, 0.49 ms for one transition, 0.67 ms for positional evaluation, and
0.10 ms for style preference. These different operations are not additive
components of one profile and were not measured in Chrome.

Raw evidence and the opt-in probe source are retained, outside normal builds,
in `work/bot-budget-20261017/`. Both focused JVM diagnostic runs passed.
The temporary test was removed from the normal source set after measurement.

## Cause and practical implications

- Every searched transition resolves the next actor's full legal move list.
  Positional evaluation computes legal moves for the other active actors too.
  Legal generation tests candidate moves against king safety using copied
  positions and attack geometry. This work repeats over many similar positions.
- Iterative search tries CHECKS and EXCHANGES separately at every depth.
  A partially searched attempt cannot replace the last completed result.
  Thus extra time can be spent without changing the actual move played.
- With three active players, ordinary depth 1 sees no opponent reply; depth 2
  sees only one opponent reply. Conditional tactical extensions do not replace
  a complete ordinary turn cycle.
- Rooks have no dedicated positional evaluation term; raw legal mobility is
  rewarded. At a short horizon, harmless-looking rook moves can therefore score
  better than a plan needing several moves. This is a mechanism supported by
  code, not a proof of the best alternative in every recorded position.
- Repetition preference compares recent complete controlled-army layouts. Small
  intervening changes can permit the same rook maneuver without an exact layout
  return. A stronger penalty alone does not establish a useful winning plan.

Longer thinking is warranted by the owner's preference and changes the selected
move here. It must be combined with more effective computation and measured
playing-quality checks. Disabling tactical safety, declaring an artificial draw,
or simply claiming that a different move is stronger are unsupported remedies.

## Approved implementation and follow-up measurements

The owner approved BOT-QF01–05 in [the bot spec](spec.md), including caching.
Strong now has a 30-second soft budget, a 35-second browser watchdog and
1,000,000 node/evaluation ceilings. Other difficulty presets retain their limits.
Maximum nominal depth remains four; deeper search is still conditional on work.

`BotSearchCache` reuses completed exact subtrees, evaluations and legal
transitions. Full state equality conservatively includes ply, rights, controller
changes and participant status; search keys also include policy, evaluation,
root perspective, model and tactical context. A subtree containing any alpha-beta
cutoff is not stored as an exact result. Root repetition/style preferences remain
fresh, and score arrays are copied. Native ownership is serial; browser workers
retain a cache between compatible requests only after a validated completed reply.

The first undifferentiated 2,048-entry LRU gave no benefit on these fixtures.
The revised admission protects 256 root transitions and up to 512 completed normal
search nodes, leaving 1,280 transient entries. It avoids duplicate static-frontier
score entries and memoizes lookup-key hashes without replacing equality checks.

| Next exported ply | 2.5-second uncached depth | 30-second uncached depth | 30-second cached depth | Cached selection |
| --- | ---: | ---: | ---: | --- |
| 49 | 2 | 3 | 3 | White rook a1–b1 |
| 139 | 2 | 3 | 3 | White queen e2–f2 |
| 249 | 1 | 2 | 2 | Controlled black queen l11–l12 |

All results retained the CHECKS horizon. All three used the same current 0.50
repetition coefficient, profile and search settings; only budget/cache varied.
The final cached samples performed 20,129 / 18,322 / 29,119 new evaluations,
versus 19,959 / 18,159 / 26,572 in the corresponding 30-second uncached samples.
They reused 56 / 229 / 468 static evaluations and 115 / 157 / 261 transitions.
These single timed runs show working reuse and modest throughput differences,
not a demonstrated depth or playing-strength improvement attributable to caching.
Warm repeated-search tests demonstrate reduced actual evaluations/nodes while
preserving decisions on focused positions. Broader quality calibration remains
open; this change does not claim to solve every passive sequence.

Reproduce the opt-in report with a supplied `CHESSTREE|2` export:

```sh
CHESSTREE_BOT_CACHE_PROBE=true \
CHESSTREE_BOT_CACHE_REPORT=/absolute/path/to/export.txt \
./gradlew :gameDomain:jvmTest --tests 'com.chesstree.game.domain.bot.BotReportedCacheProbeTest' --console=plain
```

`CHESSTREE_BOT_CACHE_VARIANT=long-cached` selects only that variant. Output is
`gameDomain/build/reports/bot-baseline/oct17-cache-comparison.tsv`. Fixed-work
cache parity tests are in `BotSearchCacheTest`; wall-clock sample outcomes can
vary with runtime conditions.

## Validation checkpoint, October 18

- Focused JVM domain checks: 52 passed, including 11 cache tests for exactness,
  interruption, configuration isolation, eviction, history and tactical reuse.
- Android host app-state/diagnostic checks: 19 passed. Wire JVM checks: eight
  passed. JS ChromeHeadless lifecycle checks: three passed, including cancellation,
  hard watchdog, stale identity, new-game replacement and malformed responses.
- Shared app compilation passed for Android, iOS Simulator arm64, JS and Wasm;
  the worker compiled and the JS development distribution bundled successfully.
  An initial Android incremental-compiler cache error was resolved by rerunning
  the affected gate with `-Pkotlin.incremental=false`, without source/config changes.
- A real bundled-worker ChromeHeadless smoke test repeated the same complete
  depth-one position: 90 ms / 20 new evaluations cold, 3 ms / zero new evaluations
  warm, then 50 ms / 20 evaluations after changing game identity. All selected
  the same move. These small-position timings are not a whole-game speedup claim.
- Independent static review accepted cache exactness and worker ownership after
  fixing an outdated Web repetition-penalty validator. Valid current penalties
  and maximum-weight export/wire round trips have regression coverage.

Interactive Chrome inspection of the new full application could not run because
the Mac was locked. Remaining manual check: start or restore a Strong bot game in
the local build, verify thinking/board responsiveness, pause during a long search,
resume, and replace the game. iOS/Android device UI execution and broader playing-
strength calibration were not performed. No deployment was made.
