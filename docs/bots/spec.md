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

The immediate slice connects the existing bounded-search prototype to local
watch play. It must keep page input responsive, clear thinking state after every
outcome, cancel on stopping/leaving/replacement, and reject stale results after
restart, undo, or restore (F03–F08, F11). Policy is fixed during an active game
(F09–F10). This infrastructure slice alone does not meet the full v1 scope.

| Concern | Required source |
| --- | --- |
| Full product behavior and release thresholds | Product spec sections 4–9 and 12 |
| Rules and placement | [Rules contract](rules-contract.md) |
| Execution, cancellation, identity, fallback | [Execution decision](e1-execution-decision.md) |
| Current implementation evidence and gaps | [Strategic candidate](e3-strategic-play.md); [watch integration](e1-watch-integration.md) |
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

## Verification and remaining scope

Requirement evidence belongs in stage reports, not in normative requirements.
The full v1 still requires human versus two bots, four recognizable profiles,
three calibrated difficulties, settings persistence and replay diagnostics,
quality corpus and tournament evidence, and measured N01–N07. Emulator and
simulator checks provide development evidence; they do not establish phone
performance thresholds. Proposed numeric thresholds remain acceptance targets
pending the calibration explicitly allowed by the product spec.
