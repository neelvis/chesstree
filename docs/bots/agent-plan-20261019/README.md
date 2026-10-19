# Agent task: improve ChessTree bot playing quality

## Recorded progress

- [Stage 0 baseline and rejected saved-move ordering](stage0-report.md).
- [Stage 1 iteration: exact king-attack reuse, verified tactical continuations,
  target/Chrome checks and rejected direct-check ordering](stage1/README.md).
  Fixed-work decisions remain unchanged; tactical-quality improvement and a safe
  selective exchange frontier remain open. Each report identifies its own source
  state; historical experiment patches are not active production behavior.

## Assignment and authorization

Improve tactical reliability and conversion of an advantage in the existing
three-player variant. Use the recorded 474-ply game as evidence and a regression
corpus, not as a script the bot must imitate. Deliver measured improvements under
the existing Strong budget before considering more thinking time.

This is an implementation plan prepared at the owner's request, not a dispatch
or permission to deploy. Existing approvals remain in [the bot spec](../spec.md),
especially BOT-D01–04 and BOT-QF01–05. Map each proposed change to those requirements.
Do not request approval again for unchanged approved behavior or routine algorithm
choices. If a material requirement must change, present the concrete amendment
for explicit approval before that production slice; continue independent research.

Read repository `AGENTS.md`, the project profile and relevant delivery/domain
skills. Use standard delivery for isolated search/evaluation work, and strict
delivery if changing worker lifecycle or serialization. Preserve all user edits;
the current tree also contains separate deployment/server changes. Inspect the
current branch and diff before editing. Reference baseline: commit
`22005a6bb8b1eadf1d4599af4092d428e157c2e6`; do not reset to it.

## Inputs and established facts

- [Complete export](reported-game.txt): source file
  `chess_party_2026-10-19_08-12-12.txt`, copied byte for byte. SHA-256 and exact
  replay prefixes are in [fixtures.json](fixtures.json).
- [Annotated excerpts](game-excerpts.md): queen loss, rook shuffle, three-player
  exchanges, both promotions, and final mate attribution/army transfer.
- [Previous diagnosis and cache experiment](../e6-oct17-search-diagnosis.md).
- [Current rules](../../three-player-chess-rules.md),
  [domain contract](../../domain-model.md), and the spec's decision-critical links.

All 474 exported moves were replayed through the public reducer. Final ranking
is White / Red / Black. The game contains 231 depth-two, 162 depth-three,
80 depth-four and one depth-one decisions. Every retained horizon is CHECKS;
107 decisions stopped with TACTICAL_LIMIT, the other 367 with TIMEOUT.
These facts identify a search-quality question; they do not prove a better move
exists at every recorded position or establish tournament strength.

The export spans requests with repetition penalties 12 and later50. Do not assume
every move came from an identical worker build. Engine/evaluation version fields
do not identify the exact deployed commit. The reserved trailing diagnostic zero
does not measure cache hits. Re-establish a controlled baseline from source.

## Boundaries to preserve

- Common deterministic Kotlin remains the rules authority. Use the public reducer
  to validate fixtures and selected moves; preserve castling, en passant,
  promotion, turn order, mate attribution, army transfer and terminal rankings.
- Required targets: Android, iOS, Kotlin/JS Web and Kotlin/Wasm Web. No desktop UI
  target. Preserve cancellation, request identity, legal fallback and responsive
  background execution.
- Keep Strong at30 seconds soft /35 seconds browser watchdog, current1,000,000
  work ceilings and configured maximum depth unless separately justified under
  the approved spec. Keep Beginner/Normal unchanged. Never fake a completed depth.
- No new draw rule, forced capture rule, automatic resignation, forced non-rook
  move, coordination between bots, speculative infrastructure or dependency upgrade.
- No self-play learner, neural network, opening-book expansion or external engine
  integration in this task. Generic two-player engine techniques need adaptation.
- No commits, pushes or deployments without a separate explicit instruction.

## Stage 0: establish a trustworthy quality baseline

Own the fixture loader, opt-in JVM probes and diagnostic evidence. Start from
`BotReportedCacheProbeTest`, `BotReportedGameStrategyProbeTest`, existing tactical
and cache tests. Add a reusable fixture reader rather than another one-off test.

1. Verify the attached checksum and474 move rows. Decode the initial position in
   a layer allowed to use its codec; domain-only tests may use `StandardGame`
   after verifying equivalence. Do not add a dependency from domain to wire/UI.
2. Apply exactly the first N `move|` rows for `prefix_plies=N`. Preserve promotion
   and the complete prior history, including the current repetition window.
   Assert every applied move succeeds. SAN excerpts alone cannot restore rights.
3. Use all14 supplied cases, plus at least six synthetic tactical/safety cases and
   six independently selected held-out positions. Include all root seats and
   a genuine two-active-player state, not only this game's three-player positions.
4. Before tuning, classify cases as verified tactical expectations, strategic
   hypotheses or terminal invariants. Prove a claimed bad trade by an explicit
   legal continuation including both opponents. Multiple acceptable moves are
   allowed; do not make the recorded SAN a best-move oracle.
5. Record source identity, fixture, seed, policy/profile, root actor, time/work
   limits, completed depth/horizon, actual nodes/evaluations, stop reason,
   selected move, score terms, and cache hits/evictions. Separate normal-search
   and tactical-frontier cost; record why an iteration was discarded.

Acceptance: one repeatable command produces the baseline, terminal fixtures match
the attached facts, and fixed-work runs reproduce choices. Keep expanded analysis
in opt-in reports first. Do not silently repurpose the export's reserved field;
if a maintained wire/export extension is necessary, version and test compatibility.

## Stage 1: make tactical search useful within the budget

Primary ownership: `BoundedMaxNBot.kt`, its move ordering/frontier helpers and
focused domain tests. First reproduce why the supplied positions never retain
EXCHANGES. Distinguish branching cost, tactical-limit aborts, alpha-beta behavior
and an evaluator limitation before choosing a remedy.

Compare bounded candidates: better first-move ordering using saved moves,
fewer repeated horizon calculations, and a selective tactical frontier that
expands exchanges/defenses without blindly expanding every unrelated quiet move.
Do not apply capture-only quiescence mechanically: a third player's intermediate
move can remove a check, affect a recapture or change mate authorship.

Mandatory safety: no hypothetical pass; no static stand-pat while a relevant
king is checked; all legal check evasions remain available; do not label an
unresolved cutoff as a complete safe position or let one path's limit silently
turn an inferior partial root choice into a completed result. If safe bounds
cannot be established, retain the previously completed result honestly.

Acceptance: existing tactical/cancellation tests remain green; the queen-exposure,
recapture, promotion-sacrifice and multi-actor cases have independently justified
expectations; designated exchange cases complete useful exchange analysis under
matched work or show a documented reason. Measure completion rate and mistakes,
not a requirement to print EXCHANGES on every position. Keep the 30-second cap.

## Stage 2: improve plans and conversion, one term at a time

Primary ownership: `PositionalBotEvaluation.kt`, `BotPlayingProfile.kt` only when
needed, and focused evaluation tests. Keep terminal placement the dominant
objective. Make score contributions inspectable before adjusting weights.

Investigate rook activity on useful open lines, coordination, safe king pressure,
passed-pawn support/blockades, and phase-sensitive king activity. In this variant,
evaluate opportunities and risks around eliminating a player and gaining their
army; avoid counting a merely threatened army transfer as already-owned material.
Any imminent-mate term must respect actual turn order and attribution.

Introduce and ablate one term at a time. Ordinary mobility alone and a larger
repetition penalty are insufficient evidence of progress. Repeated defensive
moves remain legitimate. Exclude terminal scores from heuristic tuning.

Acceptance: verified tactics do not regress, the rook-shuffle cases prefer a
validated safe improvement when available, useful pawn sacrifices remain possible,
and held-out conversion continuations improve at matched resources. If no better
continuation is established, report the hypothesis as unresolved rather than
inventing a golden move. Do not hard-code the two recorded promotion lines.

## Stage 3: improve cache usefulness without corrupting search

Primary ownership: `BotSearchCache.kt`, cache integration in `BoundedMaxNBot.kt`
and `BotSearchCacheTest`. Freeze earlier search/evaluation changes as a named
baseline so cache effects can be isolated. The current exact-only cache is the
correctness control; retain a cache-off path.

Add saved-move ordering first. Investigate typed exact/lower/upper-bound entries
with depth/horizon validity, using bounds only when justified by the current
root-perspective search. Never treat an interrupted partial result or cutoff
bound as exact. MAX_N vector search and Paranoid scalar alpha-beta require
separate validity reasoning; reuse generic legal transitions independently.

Full state, rights, controller changes, statuses, policy, root actor and tactical
context must distinguish incompatible results. Root repetition/style adjustments
remain fresh. Do not remove ply or history context from a key until equivalence
is proved for that entry type. Keep collision equality, defensive score copying,
bounded retention and game-switch clearing. A different stored move order must
not silently alter an explicitly required tie policy.

Acceptance: differential tests against an exhaustive shallow reference and the
uncached equivalent search cover narrow windows, cutoffs, collisions, all actors,
policy changes, rights, promotion, army transfer, history and interruptions.
Measure cold, warm, and actual successive-turn reuse in Chrome and JVM; report
memory bounds and eviction rates. A warm identical-position win alone does not
establish a whole-game benefit. Reject a cache change that adds complexity without
an evidenced benefit or improves speed by returning less trustworthy decisions.

## Stage 4: compare independent-opponent models

Primary ownership: opt-in arena/probe code first. Do not switch production
`LocalBotGameRequests` from Paranoid to MAX_N based on one appealing continuation.
Freeze evaluator, fixtures, style and budget across the comparison.

Run the corpus under both models, then a pilot of at least12 paired games with
seat rotations and fixed reproducible work limits. Predeclare seeds, starting
states, caps and resource limits; add a separate smaller timed Chrome sample.
Track final placement, tactical errors, repeated positions, conversion, incomplete
games and fallback rates. Treat a move-cap stop as incomplete experimental data,
not a new product draw. Neither opponents' cooperation nor non-cooperation may
be inferred as a variant rule.

Acceptance: choose a production model only with consistent evidence and no safety
regression. A small pilot can justify further testing, not a rating or statistical
strength claim. If evidence is mixed, keep the current model and report results.

## Integration, review and handoff

Work in small named slices: baseline corpus, tactical change, evaluator change,
cache change, model experiment. Keep tests/docs with their owning slice. Parallel
agents may investigate or own disjoint files; serialize shared-file edits and
resource-heavy builds. Give each agent a compact packet and preserve other work.

Run focused domain and request/lifecycle checks first. On a stable final diff,
compile affected Android, iOS Simulator, JS, Wasm and worker targets; run the
available Chrome lifecycle/real-worker tests. Exercise Strong pause/resume,
new-game replacement, restore and cancellation. Do not report phone UI or an
unbuilt target as verified. Review cache bounds and multi-actor tactical safety
independently before integration.

Deliver a compact before/after table for the frozen corpus and held-out cases,
raw report paths and reproduction commands, accepted/rejected candidates,
remaining failures, target results and exact manual gaps. Update only documentation
made inaccurate by delivered behavior. Success means fewer demonstrated tactical
errors and better placement/conversion at comparable resources; depth, cache-hit
count and shorter games are supporting measurements, not substitutes.
