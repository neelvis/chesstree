# E1a: MaxN cost profile

Date: 2026-09-30. Rules/search baseline: `000b555` plus the E0 diagnostic
test, with no production code change. This report records a JVM diagnostic
experiment. It does not accept a new search strategy or a platform performance
service level objective.

## Method

`BotCostProbeTest` replays the same ten consecutive opening positions as the
[E0 baseline](e0-audit.md). It times five runs of each primitive per position:
root legal-move generation, one authoritative generated-move transition
(including the reducer's next-turn resolution), and construction of the
resulting training sample. It also runs MaxN twice per
position for each of two policies, alternating order across positions:

- Control: `BotPolicy.DEFAULT`, depth 2, 128 expanded nodes, 128 leaf evaluations.
- Diagnostic ablation: identical policy except the mobility weight is zero.

The ablation changes the bot's evaluation and therefore is **not** an acceptable
performance fix without quality evidence. The experiment checks legality,
repeatability within each policy, unchanged input state, and the search work
limits. One initial warm-up search is excluded. Preparation of positions and
post-search validation are excluded from timings. The primitive timings include
only the named call, while full-search timings include root generation, search,
and training-sample construction. They cannot be added as separate portions of
a total because the full search repeats those calls at many different states.

Run the focused probe:

```sh
./gradlew :gameDomain:jvmTest --tests 'com.chesstree.game.domain.bot.BotCostProbeTest' --console=plain
```

The final run passed. The generated data is
`gameDomain/build/reports/bot-baseline/cost-probe.tsv`; a retained copy is
[e1-jvm-cost-probe.tsv](e1-jvm-cost-probe.tsv), SHA-256
`03b73ff6c3e96150372988e2957db4a60b015a83110aa31ac615ba6a9bac83ef`.
The report embeds the exact versioned policy weights, JVM version, OS, and
architecture. The host was Mac17,3 / Apple M5 / macOS 27.0; emulators and Chrome
were available, but no app/device or browser run is included here.

## Observations

| Measured operation | Samples | Median | Range |
| --- | ---: | ---: | ---: |
| Root legal moves | 50 | 1.63 ms | 1.53–2.05 ms |
| One generated-move transition | 50 | 1.64 ms | 1.54–2.15 ms |
| One training sample | 50 | 2.06 ms | 1.97–2.54 ms |
| Full MaxN, default policy | 20 | 734.03 ms | 720.38–755.24 ms |
| Full MaxN, mobility weight zero | 20 | 276.45 ms | 263.29–313.41 ms |

Across the ten positions, the median of the paired per-position timing ratios
was **2.66**. Both policies performed 20–21 expanded nodes and 128 leaf
evaluations in each run. The chosen moves differed in **all ten** positions.

The evaluation function in `MaxNBot.kt` calls `LegalMoveGenerator.legalMoves`
for active players to
score mobility whenever its weight is nonzero. The ablation's large timing
difference is strong evidence that these repeated mobility calculations are a
major cost in the current search. It does not establish an exact share of
runtime: policies choose different continuations, the opening sample is small,
and the host's scheduling/JIT/power conditions are uncontrolled. The primitive
timings are also not a profiler of all leaf states.

The diagnostic policy's 276 ms median falls below the proposed 350 ms ordinary
soft budget on this host, but its different decisions and the lack of a
release/device sample prohibit treating that as a completed performance fix.

## Next decision

Historical checkpoint: the subsequent equivalent geometry cache and application
integration are recorded in [the watch integration report](e1-watch-integration.md).
This original measurement and zero-mobility diagnostic remain frozen. The scoped
Astra execution-design checkpoint was completed in the intervening stage; routine
implementation and review continue under the current model policy.

An equivalent optimization must preserve the versioned rules, legal moves,
terminal outcomes, and current mobility score on identical positions before
its speed can be compared. Options include caching safely applicable legal-move
counts or a search representation that avoids rebuilding immutable states, but
each needs evidence for special rights, control changes, repetition context,
and restoration after cancellation. A replacement evaluator would instead be a
new strategic candidate requiring the specified position corpus and tournament.

The execution prototype also needs explicit request/game/position identities,
cooperative stop checks, a completed result or legal fallback contract, and a
browser worker boundary for JS and Wasm. Review these state and lifecycle
contracts at the Astra High checkpoint before production changes in those areas.
