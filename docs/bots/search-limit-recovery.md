# Local tactical limit recovery: verification

Date: 2026-10-01. Scope: [BOT-SP01–04](spec.md#accepted-amendment-recover-from-a-local-tactical-search-limit).
The owner approved the linked amendment with "го" before implementation.

## Delivered behavior

A tactical continuation cap discards the entire incomplete root attempt. Search
can then try the next check-aware depth within the same time, node, and evaluation
budgets. A failed CHECKS attempt skips that depth's optional EXCHANGES attempt.
Only fully completed root attempts replace the selected move. Previously
completed defenses and fallback behavior remain intact. Global stop signals are
checked again at the recovery boundary, including the final configured depth.

No rules, evaluator weights, repetition penalties, seed behavior, difficulty
budgets, or export fields were changed by this work. Pre-existing repetition
preference edits in the working copy were preserved in both comparison variants.

## Matched position comparison

The two supplied exports have identical initial state, configuration, seeds, and
229 moves. They represent one repeated trajectory, not independent game samples.
Six positions from that trajectory were replayed through the public reducer.
Each variant used a fixed maximum of 2,000 nodes and 2,000 evaluations, depth four,
tactical depth four, Paranoid search, positional evaluation, catalog two, book one,
and the recorded actor's seed and recent history. These are fixed-work comparisons,
not reproductions of the exports' timed runner behavior or phone performance.

The baseline was a temporary copy of the current search with only the recovery
change removed. This preserved the pre-existing repetition edits. Temporary
comparison sources were removed after execution. All twelve selected decisions
were accepted by the public reducer.

| Before export move | Baseline completed depth/horizon | Candidate completed depth/horizon | Baseline stop | Candidate stop |
| --- | --- | --- | --- | --- |
| 1 | 3 / CHECKS | 3 / CHECKS | Evaluation limit | Evaluation limit |
| 10 | 1 / CHECKS | 2 / CHECKS | Tactical limit | Evaluation limit |
| 37 | 1 / CHECKS | 2 / CHECKS | Tactical limit | Evaluation limit |
| 64 | 1 / CHECKS | 2 / CHECKS | Tactical limit | Evaluation limit |
| 124 | 1 / CHECKS | 1 / CHECKS | Tactical limit | Evaluation limit |
| 179 | 1 / CHECKS | 2 / CHECKS | Tactical limit | Evaluation limit |

The fixed-work baseline at move 124 completed depth one; the timed export used
fallback at that move. These differing resource conditions must not be conflated.
Greater depth does not establish stronger play or elimination of shuffling.

Source provenance: repository HEAD during comparison was
`668933acb64dbb8d059942f895ffe75a3044ba17`, with preserved uncommitted repetition
changes. SHA-256 of the original search source variants before temporary class
renaming:

- Baseline: `7d3b04efa628935127a4c6550ec72a92443b7ab21c9d30884427731fdebd25ba`.
- Measured candidate: `5991309dcb6e1457dc19138ee31293a809bf7217733ac67e5665806b65a2171d`.
- Final candidate: `b099c2851e0ca40336e112525865407c33ea594e178e7877e23328505a14a99d`.

The final candidate adds an immediate global-stop probe after clearing the local
cap. This does not consume nodes/evaluations under the always-CONTINUE comparison
probe. Its behavior is covered by the final regression suite. Engine version three
is retained for existing export compatibility; use build/source provenance, not
that version number alone, for historical search comparisons.

## Regression and target verification

`BoundedBotTacticalTest` covers recovery from incomplete depth-one CHECKS,
EXCHANGES-to-deeper-CHECKS recovery at recorded move 10, fixed-work result and
callback reproducibility, legal application, preservation of completed defense,
and cancellation/timeout at both retry and final-depth boundaries. Shared node and
evaluation limits cannot be reset by recovery. Existing third-player and forced
recapture protections continue to pass.

The final validation command passed: 163 JVM tests and 154 Android host tests,
with no failures or skipped tests.

```text
./gradlew :gameDomain:jvmTest :gameDomain:testAndroidHostTest :gameDomain:compileAndroidMain :gameDomain:compileKotlinIosArm64 :gameDomain:compileKotlinIosSimulatorArm64 :gameDomain:compileKotlinJs :gameDomain:compileKotlinWasmJs --console=plain
```

This verifies domain tests on JVM and Android's host runner and compilation of
the affected shared domain for Android, iOS device/simulator, JS, and Wasm. No
application packaging, browser execution, or physical device gameplay was run.
The remaining manual check is to watch a fresh STRONG bot game on Android, iOS,
and each Web runtime, export diagnostics, and check responsiveness and search
results under the real timed runner. Broader matched tournaments remain required
before claiming a playing-strength improvement.
