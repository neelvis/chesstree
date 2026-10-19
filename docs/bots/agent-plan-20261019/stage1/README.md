# Stage 1: tactical evidence and repeated king-attack work

## Outcome

The delivered change reuses exact king-attacker sets between tactical-frontier
classification and evaluation of that same immutable state. It preserves the
search tree, selected moves, evaluator weights, cache keys, difficulty budgets,
wire versions and terminal rules. A safe unchecked three-player leaf now needs
three attacker-set calculations instead of six; checked cases also avoid repeated
queries already answered in that state. The context is lazy, local to one
frontier call, and rejects use with another state instance. It uses the existing
rules helper, including frozen kings and transferred army controllers.

This is a measured computation improvement, **not evidence of stronger play**.
The reported queen defense still chooses the same move at the frozen work cap.
The selective exchange frontier remains unresolved. Stage 1's tactical-quality
objective is therefore only partially achieved; the accepted optimization and
verified expectations provide a safer base for further experiments.

Scope remains the approved [bot requirements](../../spec.md), BOT-D01–04,
BOT-SP01–04 and BOT-QF01–05, and [Stage 1 of the plan](../README.md#stage-1-make-tactical-search-useful-within-the-budget).
No business requirement amendment was needed. No commits, pushes or deployment
were performed. Separate deployment/server and fast-opening changes are excluded.

## Matched-work comparison

The final comparison alternates context off/on by case and repetition within one
JVM. Each of 27 frozen cases runs twice per variant with cold bounded caches,
seed 0, Paranoid, positional evaluation v3, Universal v2, depth 4, tactical cap 4,
2,000 nodes/evaluations and the exact last 36 historical states. There is no time
cutoff in this fixed-work experiment. Both variants use the same final source;
the off switch retains the original rules-helper calculation path.

| Measure | Context off | Context on |
| --- | ---: | ---: |
| Case executions | 54 | 54 |
| Sum of measured search time | 67,307 ms | 66,092 ms |
| Nonterminal retained CHECKS decisions | 44 | 44 |
| Nonterminal retained EXCHANGES decisions | 8 | 8 |
| Terminal results | 2 | 2 |
| Final evaluation-limit stops | 38 | 38 |
| Final tactical-limit stops | 6 | 6 |
| Final depth-complete stops | 8 | 8 |

Per-group before/after decisions are also unchanged (counts below are unique
cases rather than repeated executions):

| Frozen group | Cases | Retained horizons off → on | Changed decisions |
| --- | ---: | --- | ---: |
| Reported | 14 | 13 CHECKS + 1 terminal → same | 0 |
| Synthetic | 7 | 3 CHECKS + 4 EXCHANGES → same | 0 |
| Held-out | 6 | 6 CHECKS → same | 0 |

Every final result, attempt trace, actual work count and cache statistic matched
across variants and repetitions. All 108 executions also matched the corresponding
[Stage 0 final baseline](../measurements/final-baseline.tsv) on these fields.
All nonterminal selected moves were applied through the public reducer.

The final elapsed sum is 1.8% lower. An earlier identical paired protocol measured
66,467 vs 64,819 ms (2.5% lower); its source manifest also contains the then-unused,
subsequently removed direct-check helper. These are small same-host samples,
without a confidence interval or platform-wide speed guarantee. Some short cases
and two held-out cases were slower in the final sample. No work was credited as
saved, no budget increased, and no completed depth fabricated.

Raw data: [final fixed-work comparison](measurements/fixed-context-paired.tsv),
[exploratory comparison](measurements/fixed-context-exploratory.tsv).
Each contains source hashes, fixture identities, policy, score decomposition,
normal/tactical work, attempt discard reasons and bounded-cache statistics.

## Independently verified tactical expectations

Three ordinary JVM regressions now enforce these finite public-reducer lines;
the larger legal-alternative report remains opt-in.

- At queen-defense-145, all three legal king evasions (Kh3, Kg2, Kg1) allow
  the recorded Red Rc8 followed by Black Nxf2 to capture White's original queen.
  This is a conditional two-opponent punishment, not proof that independent Red
  must cooperate. Kg2 is not uniquely responsible for the queen loss.
- At queen-defense-142, Qf1, Red Bm7, Black Ng4, Qe1, Red Rc8, Black Nf2,
  Qxf2 is a legal queen-preserving continuation through both opponents. It is not
  a best-move proof or a win against every reply. The bot still chooses Kh2 in
  the fixed-work corpus, so this vulnerability remains open.
- Plies 295–301 promote White's original pawn to a queen, capture Red's queen,
  then lose the promoted piece to Black. Material changes White 25→24,
  Red 22→13, Black 20→20. This exchanges the original pawn for Red's queen;
  it improves White relative to Red by eight material points and reduces White
  relative to Black by one. It does not establish optimal play or final placement.
  A blanket ban on temporary promoted-queen sacrifices would reject this useful
  exchange.
- Existing synthetic expectations preserve checked king evasions, forced and
  unchecked recaptures, promotion choices, mate attribution/army transfer, Black
  control after transfer, and a genuine two-active-player mate. All remain green
  in the fixed-work equivalence run; no recorded SAN is a golden move.

Expanded evidence: [legal continuations and alternatives](measurements/tactical-evidence.tsv).

## Why EXCHANGES remains incomplete

The context change deliberately leaves branching and the cap unchanged. Every
intermediate actor still makes a real legal move; the search retains all legal
check evasions and can extend when any active king is checked. There is no pass,
unchecked stand-pat substitute for a checked frontier, or partial-root completion.

The existing exchange search combines capture/promotion responders with checks
on all active kings. At remaining zero, a checked king makes the entire attempt
incomplete. The previously completed result survives. This can happen before
resources are exhausted: promotion-295 retains depth-3 CHECKS after 703 nodes /
1,638 evaluations and stops with TACTICAL_LIMIT. Reordering alone cannot prove
such a checked leaf safe. The larger branching tree also exhausts the global
work/time allowance on other cases.

For exchange-267, depth-1/2/3 EXCHANGES reaches zero remaining plies with
Red, Black, and White+Red checked respectively; depth-4 CHECKS then exhausts
2,000 evaluations. For exchange-272 the checked sets are Red, Red, White, and
depth-4 CHECKS again exhausts evaluations. Some witnesses have a checked third
player awaiting its turn. For promotion-295, EXCHANGES depths 1–3 cap with
Black/Red/Red checked; depth-4 CHECKS caps with Black checked at ply 303, so
depth-4 EXCHANGES is never attempted. Both synthetic recapture cases complete
depth-1 EXCHANGES before later attempts encounter separate unresolved checks.

The final traces distinguish normal work, tactical work, local cap witnesses and
global exhaustion. They show actual checked actors rather than inferring the
cause from a retained horizon label. Pending responders are not a forced-capture
rule. Fewer repeated king queries reduce elapsed overhead but cannot resolve a
capped tree or repair an evaluator's preference.

Selective removal of unrelated quiet moves was not enabled: a third actor can
change a recapture, remove check or change mate authorship. No safe generic
filter was established. Cross-horizon exact reuse needs a certificate that the
actual subtrees agree; a cached CHECKS score alone cannot prove this. Caching
failed capped searches would require full tactical context and exact entry
alpha/beta windows, since another window may prune before the failed branch.
These remain research candidates, without new production cache formats.

## Rejected direct-check ordering pilot

A bounded experiment added a small priority for a moved piece directly checking
an opponent king, after existing capture priorities. It retained all moves,
original root tie policy, actual turns, stop checks and completion authority.
The helper and dedicated tests are isolated in a
[re-applicable patch](experiments/direct-check-ordering.patch); no such ordering
or option remains active. Patch applicability was checked against this Stage 1
source state. The older saved-move patch remains a historical Stage 0 experiment.

The eight-case pilot repeated each case twice. No reported position changed its
selected move or retained horizon. Some node counts increased despite equal
2,000-evaluation caps; promotion-295 increased both nodes and evaluations:

| Case | Control nodes | Candidate nodes | Control evals | Candidate evals |
| --- | ---: | ---: | ---: | ---: |
| queen-exposure-139 | 226 | 306 | 2,000 | 2,000 |
| queen-defense-142 | 214 | 290 | 2,000 | 2,000 |
| exchange-267 | 508 | 546 | 2,000 | 2,000 |
| promotion-295 | 703 | 1,144 | 1,638 | 1,671 |
| synthetic-forced-recapture | 281 | 209 | 242 | 211 |

The unchecked synthetic recapture reached depth 4 CHECKS instead of 3, choosing
the same verified safe move, but this isolated benefit did not justify reported
regressions and extra position-copy work. Control work is deterministic and taken
from the context comparison; this pilot was not an alternating elapsed-time trial.
The candidate was rejected before a larger corpus/time campaign.
[Raw pilot](measurements/direct-check-pilot.tsv).

## Timed, target and browser checks

The selected Strong JVM sample alternates variants by case, with the real
30-second soft limit and unchanged 1,000,000 work ceilings. There is one run per
variant per case; this is supporting evidence, not a statistical strength trial.

| Case | Off/on time (ms) | Off/on evaluations | Retained result in both |
| --- | ---: | ---: | --- |
| queen-defense-142 | 30,004 / 30,000 | 18,403 / 18,318 | depth 3 CHECKS, h3→h2, TIMEOUT |
| rook-shuffle-193 | 30,000 / 30,000 | 19,320 / 20,571 | depth 3 CHECKS, d2→f1, TIMEOUT |
| exchange-267 | 7,292 / 7,022 | 5,970 / 5,970 | depth 4 CHECKS, k9→b7, TACTICAL_LIMIT |

No case gained a retained horizon or different move. Timed work can vary with
runtime noise: the queen case did slightly less work with reuse, while the rook
case did more. The exchange case visited an identical tree and capped before the
deadline. [Raw timed comparison](measurements/timed-context-paired.tsv).

The stable shared-code matrix passed:

| Target/check | Result |
| --- | --- |
| Domain JVM | 218 tests, no failures/errors/skips |
| Android domain host, bot scope | 106 tests, no failures/errors/skips |
| Wire JVM | 10 tests, no failures/errors/skips |
| Android app host, local-bot scope | 26 tests, no failures/errors/skips |
| Android app compile | PASS |
| iOS Simulator arm64 Kotlin compile | PASS |
| JS and Wasm app compile | PASS |
| JS and Wasm domain test compile | PASS |
| JS worker compile/development bundle | PASS |
| Chrome runner lifecycle | 3 tests, no failures/skips |

Counts include registered opt-in probe methods; the explicitly enabled corpus
executions and expanded tactical evidence are recorded separately above. The
three new queen/promotion assertions run ordinarily, without opt-in. Chrome runner
tests use a fake worker and cover request identity, retained-worker replacement,
stale replies, cancellation/timeout/invalid replies and repetition validation.
They are not execution evidence for the production search worker.

The production worker bundle passed all six real Chrome tests. Each request
uses an independently codec/reducer-validated reported state and 36 historical
positions. Every READY, PROGRESS and final MOVE is checked against the complete
public-reducer-validated legal move set; request identity and work caps are also
asserted. Four cold fixed-work samples completed as follows:

| Reported case | Worker elapsed | Retained depth/horizon | Nodes/evaluations |
| --- | ---: | --- | ---: |
| queen-defense-142 | 12,217 ms | 2 CHECKS | 214 / 2,000 |
| rook-shuffle-193 | 11,922 ms | 2 CHECKS | 126 / 2,000 |
| exchange-267 | 10,374 ms | 3 CHECKS | 508 / 2,000 |
| army-transfer-474 (two active players) | 3,219 ms | 2 CHECKS | 371 / 2,000 |

The real Strong queen-defense request stopped at 30,002 ms with TIMEOUT,
retaining depth-2 CHECKS after 430 nodes / 4,647 evaluations. It returned before
the 35-second per-request watchdog. A separate Strong worker was terminated
immediately after its legal READY reply; a fresh replacement completed the same
reported position with no late retired-worker reply. This is real-worker
cancellation/replacement evidence, not an app UI pause/restore test.

The first harness run passed four fixed-work tests, then hit Karma's default
30-second inactivity timeout during the 30-second Strong test. The harness-only
inactivity timeout was raised to 45 seconds; the entire six-test rerun passed.
The bot's 30-second soft cap and the test's 35-second request watchdog remained
unchanged. No production code changed after the stable target matrix.

These Chrome samples run only the delivered context-on worker. They establish
legality, real budget behavior and replacement, not a browser before/after speed
improvement. [Raw replies](measurements/chrome-worker-results.json) and
[validated fixture manifest](measurements/worker-fixtures/manifest.json) retain
request provenance and all legal root moves.

[Independent review](review.md) found no material actionable correctness issue
in the context-reuse slice. [Validation record](validation.json) records exact
commands and source identities. Remaining manual gaps: Android/iOS phone UI,
Xcode app/simulator execution, Strong pause/resume and saved-game restore through
the app UI, packaged Wasm UI and wider supported-browser coverage. Kotlin iOS
compilation and Chrome worker tests do not establish those scenarios.

## Reproduction

Run from the repository root with the usual project JDK/Android SDK and Chrome.
Serialize these commands rather than running Gradle or measurements concurrently.
The probes are opt-in and explicitly rerun tests because Gradle does not track
these environment flags as test inputs.

```sh
CHESSTREE_BOT_QUALITY_KING_CONTEXT=both \
CHESSTREE_BOT_QUALITY_OUTPUT=build/reports/bot-quality/stage1-context-final.tsv \
  docs/bots/agent-plan-20261019/run-baseline.sh

CHESSTREE_BOT_QUALITY_KING_CONTEXT=both \
CHESSTREE_BOT_QUALITY_TIMED=true CHESSTREE_BOT_QUALITY_WORK=1000000 \
CHESSTREE_BOT_QUALITY_CASES=queen-defense-142,rook-shuffle-193,exchange-267 \
CHESSTREE_BOT_QUALITY_OUTPUT=build/reports/bot-quality/stage1-context-timed.tsv \
  docs/bots/agent-plan-20261019/run-baseline.sh

CHESSTREE_BOT_STAGE1_EVIDENCE=true \
  ./gradlew :gameDomain:jvmTest --rerun --tests '*BotStage1TacticalEvidenceTest'

CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
CHESSTREE_NODE_BIN='/Users/k/.gradle/nodejs/node-v24.16.0-darwin-arm64/bin/node' \
  docs/bots/agent-plan-20261019/stage1/run-worker-smoke.sh
```

The worker script generates independently codec/reducer-validated requests with
36-state histories, builds the production worker and runs six Chrome tests.
The Node/Chrome paths above identify this host; override them on another machine.
This script uses the repository's existing Kotlin/JS browser-test dependencies;
there are no new library or build-configuration changes.
