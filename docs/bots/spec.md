# Local chess bots: specification reading map

Feature ID: local-chess-bots
Status: implementation of the previously approved product spec; release acceptance remains open.

## Purpose and canonical requirements

Provide playable, independent computer opponents in local three-player chess on
Android, iOS, Kotlin/JS Web, and Kotlin/Wasm Web. The existing
[product specification](../ChessTree_Bot_Product_Spec_v1.md) remains the canonical
documented intent: R01–R09, F01–F12, N01–N07, profiles, difficulty, and quality
criteria retain their IDs and meaning. It is a user-owned planning source and is
preserved in its original language. This English entrypoint records decisions
and routes implementation; it does not replace proposed targets with observed
prototype results or declare the feature complete.

## Confirmed decisions

- BOT-D01 (R02): Prefer higher placement. A sole win is better than second, second
  is better than third, and a two-player draw is better than second. Use the
  existing rules-authoritative outcome and the reward mapping in the
  [rules contract](rules-contract.md).
- BOT-D02 (R01–R09): Preserve the implemented ChessTree variant documented in
  the rules contract. Do not introduce a repetition draw or a new elimination
  rule to address passive search behavior.
- BOT-D03 (F03–F08, F11): Integrate the bounded local search using background
  execution, a current-request identity, legal fallback, explicit failure,
  cancellation, and public-reducer validation. An interrupted partial depth is
  not a completed search result.
- BOT-D04 (profiles/endgame): Address passive late-game play with reproducible
  positions and measured strategy candidates. King moves remain legal and can
  be necessary. The supplied recording alone does not prove a winning pawn line.

This is an entrypoint to existing approved scope, not a new or recovered set of
requirements. Any material requirement change needs a reviewable draft and
explicit owner approval before its implementation.

These decisions come from the owner's ongoing authorization to implement the
product spec, confirmation of placement preferences, and request to improve
the passive bot shown in the recording.

## Current acceptance slice and reading map

The current slice integrates human versus two independent bots and watch play,
with frozen per-seat styles/difficulty, pause/resume, human-round undo, full
snapshot restoration, and replay diagnostics. It keeps execution, stale-result,
and public-reducer boundaries from F03–F08/F11. This functional slice does not
establish the full v1 playing-quality or performance thresholds.

| Concern | Required source |
| --- | --- |
| Full product behavior and release thresholds | Product spec sections 4–9 and 12 |
| Rules and placement | [Rules contract](rules-contract.md) |
| Execution, cancellation, identity, fallback | [Execution decision](e1-execution-decision.md) |
| Current implementation evidence and gaps | [Playable integration checkpoint](e4-playable-integration.md); historical [strategic candidate](e3-strategic-play.md) and [watch integration](e1-watch-integration.md) |
| Opening repertoire behavior and provenance | [Experimental repertoire](opening-repertoire.md) |
| Passive behavior evidence | [Recording review](king-shuffle-video-review.md) |

## Accepted amendment: a ChessTree opening repertoire

Status: **accepted; implementation and quality acceptance in progress**. Proposed
on 2026-10-01 after the owner suggested openings and piece development; explicitly
approved with "Ага" in response to the linked draft and confirmed by "Продолжай".
Approval covers BOT-OB01–05 and the acceptance/exclusion paragraphs below.
This section does not change
the approval state of the existing product specification. Activity, king safety,
pawn structure, and phase-aware evaluation already belong to its section 7;
the opening repertoire below is additional scope.

Purpose: give bots coherent early development choices while preserving ordinary
play, independent opponents, and the established placement objective.

- BOT-OB01: A game begins from the normal ChessTree starting position. A repertoire
  guides individual bot moves; it never skips turns, moves another player's
  pieces, or starts the user in an unexplained intermediate position.
- BOT-OB02: Repertoire continuations must belong to the implemented three-player
  ChessTree rules and match the complete relevant position, including the actual
  actor, active players, controllers, castling rights, and en-passant rights.
  Ordinary two-player opening names or move lists are not evidence of validity.
- BOT-OB03: Every selected move passes the same legality, current-request, and
  tactical decision process as other bot moves, within the same difficulty
  budget. A repertoire preference must not force a move over a demonstrably
  better result found by that process. Missing, invalid, incompatible, or
  deviated lines fall back to ordinary search without disrupting play.
- BOT-OB04: Bots choose independently. With an explicit seed and a fixed work
  budget, identical complete inputs and versions reproduce their selection.
  Diagnostics record the repertoire version and whether a line influenced a
  move. An active game does not silently change repertoire version.
- BOT-OB05: The initial repertoire is bundled and works offline on Android,
  iOS, and Web. Each included line has recorded provenance and validation;
  experimental lines are not described as established optimal openings.

Acceptance: replay every included branch through the public rules engine;
exercise all three seats and an unexpected opponent reply; reject mismatched
rights and stale requests; reproduce selection with a fixed seed/work budget;
and compare with the same evaluator/search without repertoire under matched
resources. Include tactical exceptions where defense or a better result must
override the book preference. Publication as a strength improvement requires
quality evidence under the existing product acceptance framework. Legality
alone does not establish opening quality.

Scope excludes importing ordinary chess opening databases unchanged, network
or language-model calls during a game, forced opponent cooperation, and new
game rules. The repertoire size and line contents are implementation candidates,
not an unsupported promise of established ChessTree theory.

## Accepted amendment: recover from a local tactical search limit

Status: **accepted; implemented and verified for the scoped domain change**.
Verification is recorded in [the recovery report](search-limit-recovery.md);
full playing-quality acceptance remains open. Prepared on 2026-10-01 in
response to the owner's request to improve search after reviewing two local
game exports. The owner explicitly approved the linked draft with "го" after
reviewing BOT-SP01–04 and the acceptance criteria. Approval covers this amendment
as presented; existing approved requirements remain in force.

Purpose: use the remaining difficulty budget for useful search when one tactical
continuation cannot be completed, without treating an unresolved check or a
partially searched set of root moves as a completed result.

- BOT-SP01: Reaching a local tactical continuation limit abandons the affected
  search attempt, not automatically the entire move request. If another bounded
  attempt is available under the configured depth and resource limits, search
  may continue. Optional exchange enrichment must not by itself prevent trying
  a deeper check-aware search under the remaining budget.
- BOT-SP02: Only a fully completed root attempt can replace the previously
  completed choice or be reported as completed depth. An unresolved mandatory
  response to check is not converted to a static leaf, silently skipped, or
  represented as a hypothetical pass by an intermediate player.
- BOT-SP03: All attempts share the existing time, node, and evaluation budgets.
  Cancellation and global resource exhaustion stop the request. Retrying does
  not reset counters or create an unbounded extension. If no attempt completes,
  retain the established legal fallback behavior.
- BOT-SP04: Diagnostics report the depth and horizon of the actual completed
  choice, or zero depth for fallback. Identical complete inputs, versions, and
  fixed work budgets reproduce the search result. Timed runs may differ because
  of scheduling, as already documented in the product specification.

Acceptance:

1. A reproducible position where exchange enrichment hits a local tactical cap
   and a deeper check-aware attempt can complete must retain that deeper result
   when the shared budget permits it.
2. An incomplete attempt must never publish a completed-depth callback or
   replace a completed defense. If all attempts remain incomplete, a legal
   fallback is returned; existing forced-recapture and third-player-check
   protections remain covered.
3. Exercise cancellation, timeout, node/evaluation exhaustion, fixed-work
   reproducibility, and legal application through the public reducer across
   retry boundaries.
4. Compare the recorded opening, the position before export move 124, and
   selected shuffle positions under matched work budgets before and after the
   change. Record completed depth/horizon, fallback, stop reason, and consumed
   work. Do not claim increased playing strength solely from greater depth;
   tournament evidence remains part of the existing release acceptance.
5. Run focused domain tests and compile affected Android, iOS, Kotlin/JS, and
   Kotlin/Wasm targets available on the host; explicitly identify unavailable
   checks.

Scope excludes changing game rules, evaluation weights, repetition penalties,
seed/restart behavior, difficulty budgets, or the public export format. Existing
uncommitted repetition-preference work must be preserved. This amendment does
not promise depth four on every position or establish full playing-quality
acceptance.

Evidence: both owner-provided exports (`chess_party_2026-10-01_18-35-36.txt` and
`chess_party_2026-10-01_18-41-02.txt`) contain the same 229 moves and seeds:
209 tactical-limit stops, 226 depth-one choices, two depth-two choices, and one
fallback at move 124. They are one repeated trajectory, not two independent
game samples. `BoundedMaxNBot` propagates the tactical cap through the global
interruption flag; `BoundedBotTacticalTest` currently enforces abandonment of
an incomplete root attempt and retention of completed defensive choices.

## Verification and remaining scope

Requirement evidence belongs in stage reports, not in normative requirements.
Human versus two bots, frozen settings, and replay diagnostics now have
functional evidence in the current checkpoint. Full v1 still requires four
recognizable profiles, three calibrated difficulties, broader quality corpus and
tournament evidence, and measured N01–N07. Emulator and
simulator checks provide development evidence; they do not establish phone
performance thresholds. Proposed numeric thresholds remain acceptance targets
pending the calibration explicitly allowed by the product spec.
