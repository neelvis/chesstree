# E3: positional evaluation and an experimental repertoire

Date: 2026-10-01. Requirements: product specification section 7 and
[BOT-D01–04 / accepted BOT-OB01–05](spec.md). This is a measured development
slice; full release acceptance remains open.

## Delivered candidate

Evaluation version 3 adds safe knight/bishop activity and development, pawn
support and progress with blockage/exposure discounts, threatened-piece penalties,
king-ring pressure from both active opponents, and early queen restraint while
minor pieces remain at home. Material determines phase. King mobility regains
its existing weight as opposing major/minor material disappears.

The original absolute material component did not directly reward reducing an
opponent's material. The candidate subtracts one quarter of each active opponent's
nonking material, using the existing positive material weight. The coefficient
stays constant when an opponent leaves. Current controllers own all scoring;
pawns retain original army geometry. Terminal placement rewards stay exact.
The frozen CONTROL and pawn-only evaluation version 2 remain available.

The evaluator reuses attack geometry from the rules engine. It shares already
generated legal lists with base evaluation and king-mobility adjustment. Search
continues to use public-authority legal transitions, actual actor order, full-root
completion, existing deadlines, and cancellation. Version 3 does not add tactical
extensions, a new search model, or repetition rules.

The [repertoire](opening-repertoire.md) contains six experimental nine-ply lines
and 49 complete positions. Its preferences only resolve exact completed-root
score ties. Watch play selects POSITIONAL/book 1; other request defaults remain
CONTROL/book 0. Worker wire version 3 carries the repertoire diagnostics and seed.

## Fixed-work opening observation

The opt-in JVM observation runs nine plies from the standard start for each of
three seeds and configurations CONTROL, POSITIONAL, and POSITIONAL with book.
Every request uses depth 2, 128 nodes, and 128 evaluations; every move passes the
public reducer. Retained rows: [81 decisions](e3-opening-probe.tsv).

| Configuration | Pawn moves | Minor-piece moves | Queen moves | Book changed choice |
| --- | ---: | ---: | ---: | ---: |
| CONTROL | 12 | 0 | 15 | 0 |
| POSITIONAL | 9 | 15 | 3 | 0 |
| POSITIONAL + book | 9 | 15 | 3 | 0 |

CONTROL completed depth 1 on 24 moves and depth 2 on three. Both positional
configurations completed depth 1 on all 27. Seeds repeated the same trajectories;
these are dependent observations, not nine independent quality games. Equal
work does not imply equal elapsed time or equal completed depth. Timing rows
are descriptive warm JVM measurements with a fixed configuration order, not
release SLOs or a controlled performance comparison.

This supports a narrow development-behavior claim: the candidate develops more
minor pieces in the observed opening. It does not establish tactical strength,
placement improvement, reduced late-game repetition, or noninferiority. The book
made no different choices here; it needs better coverage and quality evidence.

Reproduce the observation:

```sh
CHESSTREE_STRATEGY_PROBE=1 ./gradlew :gameDomain:jvmTest \
  --tests '*BotStrategicOpeningProbeTest*' -Pkotlin.incremental=false
```

## Verification status

Focused JVM gates pass for corpus replay, seed/version guards, completed-root
tie selection, interrupted-depth diagnostics, minor development, exposure and
blockage with equal material, obligatory king defense, capture preference,
promotion, empty policy, and exact terminal rewards. Independent source review
resolved repeated legality calculation and confounded fixture assertions;
no material finding remains in that reviewed delta.

The final gate passed 135 domain test methods, seven wire tests, and eleven
runner/activity host tests. Domain counts include opt-in utility methods that
return without starting an experiment; the 48-game E2 pilot was not rerun.
The 19 focused repertoire/search/positional tests passed, including a public-reducer
weak-opponent mate and army-transfer fixture. Logs:
`/tmp/chesstree-strategic-final-targets.log` and
`/tmp/chesstree-strategic-stable-material.log`.

Android APK, both iOS architecture compilations, both production Web
distributions, and JVM server compilation passed. A separate development Web
packaging gate passed. Full iOS Xcode build passed; the final Android and iOS
apps were installed and launched in the supplied emulators. Native manual
gameplay/lifecycle checks remain pending because the Mac is locked. Build and
launch success are not native gameplay verification.

Actual fresh production UI checks in the Codex in-app browser passed:

- JS watch advanced to 30 moves, restarted during search to zero, advanced to
  17 moves, and stopped. The count remained 17 on the later check.
- Wasm watch advanced to 31 moves and stopped. The count remained 31 on the
  later check.

Screenshots are retained under `work/bot-playability-20260930/e3-js-stopped.jpg`
and `e3-wasm-stopped.jpg`. These checks do not measure Chrome performance or prove
late-game strategy. The shared production worker SHA-256 is
`40f6a0308ecf78eb8a06503ce398e59a2dd1ea07cbd4789c747d44f339c5b517`.

Source SHA-256 fingerprints for the retained opening observation:

| Source | SHA-256 |
| --- | --- |
| PositionalBotEvaluation.kt | bce353a7f9615890d47ab4ddd5ba067b763e42e241dfe3a003494f86bd8fcf93 |
| BotEvaluationMode.kt | f29e089eaf6dd04d9ff80a01775cdd01d19919919e487f1529a6c90d58e21f60 |
| BoundedMaxNBot.kt | 8edead97a4aa7992457ec9ba1d19cb670281b54fe1b319976113bade1727edb9 |
| BotOpeningBook.kt | 3d8c27e1776390f8dab2ee53d76082a30eeefe3401e10043386ae4c3faf9740c |
| BotOpeningBookData.kt | e6c2b0f5a3dc670f2b89c0df5152bc95397a63aa422748d9232e110b09a50f50 |

Full quality corpus, matched tournaments, tactical continuation, human-versus-two
mode, profiles/difficulties, persistence, and measured phone/Web SLOs remain open.
Web postgame learning/replay still has a synchronous UI path.
