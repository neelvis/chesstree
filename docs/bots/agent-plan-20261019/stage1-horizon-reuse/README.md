# Stage 1 follow-up: certified cross-horizon reuse

## Decision

**Rejected for production.** The bounded prototype was safe in the exercised JVM
cases, but did not improve the reported decisions or completed horizons and did
not reduce total elapsed time. The complete implementation and tests are retained
as an [isolated patch](experiments/certified-horizon-reuse.patch). All production
and probe source was restored byte for byte to commit `b68e363`.

The requested commit was created first: `b68e363`, "Improve bot diagnostics and
reuse king attack analysis". It contains the previously delivered Stage 0/1 bot
source, fixtures, tests and evidence. Unrelated deployment/server and fast-opening
changes were excluded. No push or deployment was performed.

This follow-up preserves the approved [bot requirements](../../spec.md),
BOT-D01–04, BOT-SP01–04 and BOT-QF01–05. It is a technical experiment under
[Stage 1 of the plan](../README.md#stage-1-make-tactical-search-useful-within-the-budget),
not a new requirement or a change to bot authority, rules or budgets.

## Question and implementation

Can a completed CHECKS subtree prove that EXCHANGES would search the same
continuations, allowing exact reuse and leaving more of the existing budget for
useful analysis?

The candidate carried virtual exchange responders while CHECKS ran. These did
not change CHECKS expansion, keys, reported responders or move order. An
unchecked positive-remaining leaf rejected certification if EXCHANGES would
continue because a responder remained or an optional capture/promotion existed.
The rejection happened before returning even an evaluation-cache hit.

A fully completed normal/tactical subtree could publish an EXCHANGES alias only
when it had no descendant alpha-beta cutoff, no divergent frontier and no
uncategorized CHECKS exact-search hit. Such a hit proves a CHECKS score, not an
unvisited exchange tree, so it conservatively blocked enclosing certification.
Terminal leaves, tactical-depth zero and unchecked zero-remaining leaves retained
their existing semantics. Checked zero-remaining leaves still abandoned the
attempt. No trivial static-leaf alias flood was added.

Aliases retained the full state, configuration, depth, responders and tactical
context. Existing bounded cache storage held the copied exact scores plus an
origin flag; no second score table or global memo was introduced. An alias was
counted as stored only when existing admission policy accepted it. Root style,
repetition and opening preferences remained fresh. The public path remained off
throughout the experiment; the internal probe enabled the candidate. The off
switch disabled new certificates, while compatible exact aliases in a deliberately
pre-populated cache remained readable. Measured runs always used fresh caches.

Independent [proof review](proof.md) and [source review](code-review.md) found no
material correctness issue. This does not establish native/browser execution or
playing strength.

## Frozen-corpus comparison

Protocol: the same 14 reported, seven synthetic and six held-out cases; both root
models covered in focused tests, Paranoid in this comparison; seed 0, positional
v3, Universal v2, maximum depth 4, tactical depth 4, 2,000 nodes/evaluations,
36-state history and cold cache capacity 2,048. Each case ran twice per variant,
alternating order within one JVM, without a time cutoff: 108 executions total.

Every candidate move, source, completed depth/horizon and final stop reason
matched its off control. Repetitions were deterministic. All 54 off-control
results, work counts, cache statistics and attempt traces matched the committed
Stage 1 baseline after excluding the newly appended zero-valued experiment
counters. All selected nonterminal moves were public-reducer validated.

The table sums both repetitions. A certificate admission is not necessarily a
later hit, and a cache hit is not necessarily a saved node/evaluation.

| Frozen group | Off/on elapsed (ms) | Off/on nodes | Off/on evaluations | Actual alias hits |
| --- | ---: | ---: | ---: | ---: |
| Reported, 14 cases | 41,775 / 41,591 | 11,162 / 11,162 | 50,166 / 50,192 | 2 |
| Synthetic, 7 cases | 816 / 783 | 11,464 / 11,138 | 13,408 / 13,170 | 366 |
| Held-out, 6 cases | 23,135 / 23,424 | 6,234 / 6,234 | 24,000 / 24,000 | 0 |
| Total | 65,726 / 65,798 | 28,860 / 28,534 | 87,574 / 87,362 | 368 |

The candidate took 72 ms more overall (about 0.1%, too small for a meaningful
speed claim). Saved nodes/evaluations were concentrated in small synthetic
positions. Of 982 admitted aliases across both repetitions, only 368 were hit;
366 hits occurred in synthetic cases. The two reported hits were the same
army-transfer-474 position repeated twice and saved no counted work.

The reported queen-exposure, defense, rook-shuffle and exchange cases gained no
useful alias hit. Promotion-295 admitted 108 aliases per run without a hit,
increased evaluations from 1,638 to 1,651 and cache evictions from 2,602 to 2,723.
Reported cases had 270 extra evictions across both repetitions. Held-out cases
admitted 94 aliases without a hit and had 94 extra evictions. The additional
entries competed with useful transition/evaluation storage.

This is insufficient benefit for the added virtual-context tracking, cache value
metadata, proof obligations and maintenance cost. A separate committed-baseline
elapsed-time campaign was not run: the candidate already failed usefulness
against its own matched off control. That control includes the experimental
entry wrapper, so its elapsed timing is not a clean measurement of net overhead
against the committed implementation. No production speed claim follows.

Raw evidence: [all paired runs](measurements/fixed-paired.tsv),
[grouped totals and candidate source/test hashes](measurements/summary.json).
These measurements cover the frozen 2,000-work protocol; larger limits and other
runtimes were not evaluated. No stronger tactical reliability, improved placement
or resolved EXCHANGES cap was demonstrated. The existing reported tactical limitations remain open.

## Verification and restoration

The candidate was exercised with nine new differential search tests and eight
new cache contract tests, plus 46 existing tactical, cancellation, diagnostics
and context tests. Coverage included all root seats, both models, all evaluation
modes, zero/positive tactical depth, fully completed sparse trees, real alias
work savings, both-opponent recapture lines, checked evasions, promotion, army
transfer/two-active-player states, cache off/tiny/cold/warm/configuration switches,
unknown CHECKS hits, copying, eviction, collisions and interruption authority.

The first 63-method focused run had one incorrect new test premise: a one-node
budget can finish a root whose children are all static leaves. That test was
corrected to assert retention of the actually completed depth-1 CHECKS result;
a one-evaluation budget separately verifies fallback with no completed root.
The corrected nine-test class passed; production was unchanged by this correction.
The other 62 methods in the initial run had passed. The paired corpus then passed.

The patch was generated before removing the candidate. Its applicability against
the restored source was checked with `git apply --check`. Restored production and
probe files match `b68e363`; candidate-only test files exist only in the patch.
The restored domain check succeeded with 218 zero-failure test results reused
from Gradle's build cache for the unchanged tested inputs; this is not a claim of
new runtime execution. All measured Stage 1 source/test hashes match, and the
four affected modules have an empty diff against `b68e363`.
[Validation record](validation.json).

No candidate Android/iOS/JS/Wasm build, real-worker timed run or phone/UI check
was performed: the prototype was rejected before integration. The previously
passed [Stage 1 target and Chrome checks](../stage1/README.md#timed-target-and-browser-checks)
still apply to the unchanged delivered source. They do not validate the archived
prototype. The existing phone UI, Xcode/native simulator, pause/resume/save-restore
and packaged Wasm manual gaps remain unchanged.

## Reproduction

Use a checkout based on `b68e363` with the patch absent. Preserve unrelated edits.
The patch deliberately leaves public production use disabled.

```sh
git apply docs/bots/agent-plan-20261019/stage1-horizon-reuse/experiments/certified-horizon-reuse.patch

./gradlew :gameDomain:jvmTest \
  --tests '*BotCertifiedHorizonReuseTest' \
  --tests '*BotSearchCacheHorizonAliasTest' \
  --tests '*BotSearchCacheTest' --tests '*BotSearchDiagnosticsTest' \
  --tests '*BoundedBotTacticalTest' --tests '*BoundedBotKingAttackReuseTest' \
  --tests '*BotKingAttackContextTest' --console=plain

CHESSTREE_BOT_QUALITY_HORIZON_REUSE=both \
CHESSTREE_BOT_QUALITY_OUTPUT=build/reports/bot-quality/certified-horizon-paired.tsv \
  docs/bots/agent-plan-20261019/run-baseline.sh
```

Run builds/measurements serially. Gradle probe execution is explicitly rerun
because environment flags are not test inputs. Do not treat source/fixture
identity or a higher alias count as evidence of stronger play.
