# E2 pilot: pawn progress versus bounded CONTROL

Date: 2026-10-01. Requirements: [approved bot spec](spec.md), section 9.2 and
BOT-D01–04. This is a development pilot, not release-strength acceptance.

## Frozen protocol and evidence

The opt-in JVM test ran 48 games: two origins, two resource protocols, two focal
evaluation modes, and all six seat assignments against fixed greedy and bounded
CONTROL references. Origins were StandardGame and the synthetic changed-file
pawn position. Policies were fixed, with no learning or randomness. The fixed
work protocol used depth 2 and 128 nodes/evaluations. The equal-time protocol
used depth 3, 20,000 nodes/evaluations and a monotonic 900 ms soft deadline.
Games stopped after 60 applied plies or a rules-produced terminal outcome.

The manifest was written before warmup and measurement. It records source
hashes, complete initial/final state, seat/block identities, versions, policies,
limits, every intent and search statistics. Every game passed strict public
reducer replay with both GameState and canonical full-state equality. There
were zero illegal moves, input mutations, replay mismatches, or harness failures.
The two mixed-material fixture guards also passed; their checked positions
required king defense. Their WHITE pawn-progress gate was correctly inactive.

Retained data: [full compressed report](e2-arena-pilot.tsv.gz) and
[mixed-material guards](e2-arena-fixture-guards.tsv). The report contains its
own configuration and source identities; the archive is a reproducible gzip
with zero timestamp. The independent reviewer reported no material harness
finding before its tool session hit a usage limit; the coordinator also
inspected the schedule, scoring, state encoding, and replay implementation.

## Results and interpretation

| Protocol | Focal mode | Finished | Unfinished at 60 plies | Failures |
| --- | --- | ---: | ---: | ---: |
| Fixed work | CONTROL | 0 | 12 | 0 |
| Fixed work | PAWN_PROGRESS | 0 | 12 | 0 |
| Equal time | CONTROL | 0 | 12 | 0 |
| Equal time | PAWN_PROGRESS | 1 | 11 | 0 |

The single finished game was a two-player draw; the candidate's normalized
reward was 0.75. No result, placement, or reward was invented for unfinished
games. Conservative candidate-minus-control reward bounds are [-1, 1] for
fixed work and approximately [-0.938, 0.979] for equal time. They are sensitivity
bounds, not confidence intervals. These results establish neither improvement
nor noninferiority. Two dependent origins are insufficient for the release
tournament or a reliable confidence interval.

Among the equal-time focal moves, CONTROL completed depths 1/2/3 on 67/70/120
moves; PAWN_PROGRESS on 68/69/120. Both reached the deadline on 137 of 257
moves. Their search medians were approximately 900 ms; the largest observed
focal search was 900.78 ms. Across the equal-time pilot there were no searches
over twice the soft deadline. This warmed JVM observation does not establish
phone/Web SLOs, end-to-end request latency, or cancellation/frame performance.

The pilot is useful evidence that the harness is reproducible and the single
pawn heuristic does not solve game completion. The 60-ply cap also censors most
opening games. Do not simply count the sole completed result as a strength gain.
The next algorithm experiment follows the existing spec's Paranoid candidate:
compare scalar MAX/MIN/MIN search against exhaustive reference values, then
measure completed depth and tactical decisions under matched resources. Its
conservative opponent model needs quality evidence before app integration.

Reproduce the frozen pilot explicitly:

```sh
CHESSTREE_BOT_ARENA_PILOT=true ./gradlew :gameDomain:jvmTest \
  --tests 'com.chesstree.game.domain.bot.BotArenaPilotTest' --rerun-tasks \
  -Pkotlin.incremental=false --console=plain
```

Without opt-in the routine fixture guard runs and an explicit skip record is
written; no new tournament is implied. The pilot took 606 seconds including
the two test methods on this Mac. Full release corpus, calibrated difficulty,
balanced 600-game comparison, and human play sessions remain open.
