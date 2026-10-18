# E5: repetition preference from the October 11 report

Date: 2026-10-11. Requirements: [BOT-D04 and the approved bot specification](spec.md).
This is one fixed-work strategy experiment, not release-strength acceptance.

## Evidence and question

The owner-provided `chess_party_2026-10-11_13-18-09.txt` export contains 97
applied plies and diagnostics for every move. The three seats use Universal / Strong,
profile catalog 2, positional evaluation 3, repertoire 1, and the Paranoid search
model. The measured mean request time is 2,527 ms. The search completed depth 1
on 68 plies, depth 2 on 27, and returned a legal fallback on two; no move completed
depth 3 or 4. This supports the observation that a longer budget alone still
leaves most decisions with a short horizon.

The experiment asked whether the current opponent model or a stronger preference
against a recent return would better interrupt the rook shuffle without sacrificing
material. It replays all 97 exported moves through the public reducer, then runs
two independent 18-ply continuations from the same resulting state. Both use the
same policy values from the export, Universal / catalog 2, positional evaluation,
depth 4, a fixed 250-node / 400-evaluation budget, and up to 36 prior positions.
The existing Paranoid model is compared with the MaxN control. The repetition
coefficient is compared at 0.12 and 0.50 material units per prior own-layout return,
capped at two occurrences. The 0.50 default is used by normal bot construction; the
probe sets the coefficient explicitly to reproduce both values in one JVM run. Return
counts use the same maximum 36-position history window as the bot.

The opt-in probe is `BotReportedGameStrategyProbeTest`. It reads the report path
from `CHESSTREE_BOT_REPORTED_GAME_PATH` and writes the continuation rows to
`gameDomain/build/reports/bot-baseline/reported-game-models.tsv`. Reproduce the paired
run with:

```sh
CHESSTREE_BOT_REPORTED_GAME_PROBE=true \
CHESSTREE_BOT_REPORTED_GAME_PATH=/path/to/chess_party_2026-10-11_13-18-09.txt \
./gradlew :gameDomain:jvmTest --tests 'com.chesstree.game.domain.bot.BotReportedGameStrategyProbeTest' --console=plain
```

The TSV records the coefficient on every move row and has one ending summary per model
and coefficient. The test asserts the observed return and fallback counts for all four
continuations.

## Results

| Model and repetition coefficient | Repeated own-layout returns | Legal fallbacks | Depth 1 / 2 / 0 | Captures | Pawn moves | Minor-piece moves | Ending material (White / Red / Black) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Paranoid, 0.12 | 2 | 0 | 16 / 2 / 0 | 2 | 2 | 2 | 28 / 33 / 34 |
| Paranoid, 0.50 | 0 | 0 | 17 / 1 / 0 | 2 | 2 | 3 | 28 / 33 / 34 |
| MaxN, 0.12 | 4 | 3 | 14 / 1 / 3 | 7 | 4 | 6 | 26 / 29 / 27 |
| MaxN, 0.50 | 3 | 2 | 16 / 0 / 2 | 9 | 2 | 5 | White checkmated Black; unfinished overall |

The larger preference reduced repeated own-layout returns from two to zero in the
Paranoid continuation, with no fallback or material difference in this sample.
It did not materially increase captures or pawn progress. The MaxN comparison
was more tactical in these short continuations but produced more fallbacks; its
single checkmate outcome is not evidence of better playing strength. These
results do not establish a tournament improvement or calibrated difficulty.

## Implementation and remaining checks

The current bounded bot now subtracts 0.50 material units per prior own-layout
return, capped at two, before comparing root choices. This changes only search
preference; it does not add a repetition draw, alter elimination, or forbid a
defensive return. The move remains subject to ordinary completed-search, tactical,
legality, and terminal-outcome handling.

Focused JVM tests cover reported-rook shuffle continuation, history preference,
search completion/fallback, and tactical defenses. A synthetic checked-king position
also puts every legal king defense in recent history; the bot still chooses a safe king
move and reports the expected repetition penalty. Full release-quality tournament
acceptance remains open. The next useful evidence is a broader set of independent
reported and synthetic shuffle positions under equal work, including defensive
king returns and materially losing alternatives.
