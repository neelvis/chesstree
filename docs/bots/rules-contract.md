# Bot rules baseline

Status: E0 repository baseline, 2026-09-30. Baseline revision: `000b555`.

This document maps the bot product specification's R01–R09 to the existing
ChessTree implementation. It does not introduce a new game variant. The product
rules remain in [three-player-chess-rules.md](../three-player-chess-rules.md),
and the shared model remains in [domain-model.md](../domain-model.md).
`GameReducer.reduce` is the public authority for applying a move.

The label `baseline-000b555` identifies this audit only. It is not a deployed
`rulesVersion`, a persistence schema version, or a claim of release acceptance.

## Rule decisions and remaining scope

| ID | Existing behavior and evidence | Consequence for bots |
| --- | --- | --- |
| R01 | Checkmate eliminates a participant. Their king is removed and remaining armies they control transfer to the author of mate. `GameReducer.applyCheckmate`. | First mate is not necessarily terminal. Search must continue with the resulting controllers and active players. |
| R02 | `GameOutcome` supports ranked first/second/third, a three-way draw, and a two-way draw with the eliminated player third. On 2026-09-30 the owner confirmed preserving the preference for higher placement, including a two-way draw above second place. | Preserve the existing objective. Record raw outcomes independently of the normalized tournament score below. |
| R03 | Forced outcomes are resolved when the turn would pass to the participant, after the intervening move. `MateAttributionPolicy.author` prefers the sole new attacker, then the last mover if attacking, then deterministic player order. | Reuse the reducer, including its pre-move state for attribution. Do not infer mate credit solely from the moving piece. |
| R04 | Legal moves exclude leaving the acting player's king attacked. Simultaneous attacks by active opponents are considered. `LegalMoveGenerator.legalMoves` and `attackingPlayers`. | Search only legal moves; do not replace an obligatory response to check with static evaluation in a future tactical extension. |
| R05 | A checkmated army changes controller; allied armies cannot capture each other. Stalemated pieces stay immobile; their king is invulnerable and still constrains other kings. Turns skip inactive participants. | Army color, controller, participant status, and the actual next turn all belong in a search position. Alternating signs or cycling blindly through three seats is invalid. |
| R06 | Second stalemate ends in a three-way draw. With two active players, K vs K and K+N vs K end in a two-way draw, allowing an inactive king to remain. A mixed mate/stalemate may produce a ranked outcome. Agreement is represented separately. Automatic repetition and 50/75-move draws are not implemented. | Do not invent new draw rules inside search or Arena. An experiment stopped at a move/resource cap is incomplete, not a draw. A rule change requires a product decision and a new version. |
| R07 | The board has 96 cells; `ThreePlayerBoardTopology`, `MovementDirections`, and `ThreePlayerBoardNotation` define geometry and notation. Pawn directions follow original army color; promotion has four choices. | Reuse geometry and legal generation. Board notation and topology tests already exist; older prose listing their reconstruction as open is stale relative to the implemented model. |
| R08 | The types are pawn, knight, bishop, rook, queen, and king. Two-tone appearance represents transferred control. | Do not infer new piece types from appearance. |
| R09 | Castling rights track army/side and a tied rook; moving or capturing relevant pieces removes rights. En passant retains separate eligibility for each active opponent's next turn and permits multiple targets. Clocks, resignation, and further draw policies are open rules. | Include complete special-move rights and eligibility in search state. Keep unsupported actions out of the initial bot variant; do not silently inherit ordinary chess semantics. |

Core sources, relative to the repository root:

- `gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/GameReducer.kt`
- `gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/LegalMoveGenerator.kt`
- `gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/GameState.kt`
- `gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/Position.kt`
- `gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/MateAttributionPolicy.kt`
- `gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/bot/MaxNBot.kt`

## Existing utility, not a calibrated win probability

| Outcome | Current MaxN score | Current learner utility |
| --- | --- | --- |
| First / second / third | 1,000,000 / 0 / −1,000,000 | 1 / 0 / −1 |
| Two tied first / eliminated third | 500,000 / 500,000 / −1,000,000 | 0.5 / 0.5 / −1 |
| Three-way draw | 0 / 0 / 0 | 0 / 0 / 0 |

The owner confirmed this preference ordering on 2026-09-30. For the planned
Arena, define `arena-reward-v1` as the affine normalization `(utility + 1) / 2`:
ranked first/second/third = 1/0.5/0; two tied first/third = 0.75/0.75/0;
three-way draw = 0.5 for every player. This keeps all existing preferences and
expected-utility comparisons while putting each player's reward in [0, 1].
Scores are not win probabilities and need not sum to one. The identifier is a
documented contract for future Arena implementation, not a deployed codec.

The specification's −0.03 and −0.05 tournament margins apply to this normalized
scale. Store raw `GameOutcome` values alongside the reward version. The E0
experiment freezes `BotPolicy.DEFAULT`, performs no learning, and does not yet
run a tournament or implement Arena scoring.

## Position and transition contract

The baseline is immutable `GameState`: piece IDs, original armies, types,
coordinates and movement flags; castling rights; all en-passant targets and
eligible players; army controllers; participant status with attribution and
elimination ply; actual turn and ply; phase and final outcome.

History is stored separately by `GameSession`. There is no existing repetition
counter to copy into search. A future repetition rule must define position
equivalence, relevant history, terminal handling, and cache applicability together.

`reduceGeneratedLegalMove` is an internal fast path for a move produced from that
exact state. It is not an independent legality validator. Public UI/server/Arena
intents must still pass through `GameReducer.reduce` or `GameSession.apply`.

Scenario initial states are trusted; creation does not normalize checkmate or
stalemate. A scenario with no legal moves may still carry `InProgress`. The
legacy bot returns null both for such a scenario and for a finished game. Future
typed search results must distinguish these cases and obtain outcomes from the
rules authority; the bot must not invent a terminal result from an empty list.

## Evidence catalog for the next fixture stage

The following existing tests supply concrete starting evidence. They are not the
specification's 60 independently reviewed rule positions or 200-position quality
corpus. Most are synthetic unit scenarios, not recorded reachable tournament
positions.

| Case | Existing test | Baseline behavior to preserve |
| --- | --- | --- |
| 1 | `GameReducerTest.generatedLegalMoveTransitionMatchesValidatedIntentReduction` | Generated-move fast path agrees with public intent reduction for its sampled legal transitions. |
| 2 | `GameReducerTest.rejectedMoveDoesNotMutateTheInputState` | Illegal intent cannot change the position. |
| 3 | `LegalMoveGeneratorTest.transferredArmyIsAlliedAndBlocksItsControllersPieces` | Shared control prevents allied captures. |
| 4 | `GameEngineTest.checkmateAutomaticallyRemovesTheKingAndTransfersTheArmy` | Elimination changes king occupancy and army ownership atomically. |
| 5 | `GameEngineTest.mateAttributionPrefersTheLastMoverAmongMultipleAttackers` | Attribution follows the explicit policy, including its new-attacker and fallback examples. |
| 6 | `GameEngineTest.castlingMovesKingAndItsExplicitlyTiedRook` | Castling moves the tied rook and revokes rights. |
| 7 | `GameEngineTest.castlingIsForbiddenThroughAnAttackedSquare` | Attacked transit squares forbid castling. |
| 8 | `GameEngineTest.eachOpponentGetsOnlyItsOwnNextTurnForEnPassant` | One opponent passing does not consume the other's opportunity. |
| 9 | `GameEngineTest.secondStalemateAutomaticallyFinishesAsThreeWayDraw` | The second stalemate is a three-way draw. |
| 10 | `GameEngineTest.kingAndKnightAgainstKingAutomaticallyFinishesAsDraw` | The implemented K+N vs K rule is terminal. |
| 11 | `GameEngineTest.stalematedPlayersKingDoesNotPreventBareActiveKingsDraw` | An inactive king is excluded from active material counting. |
| 12 | `LegalMoveGeneratorTest.promotionTargetProducesExactlyTheFourSupportedChoices` | Promotion offers queen, rook, bishop, and knight only. |

The source tests are in `gameDomain/src/commonTest/kotlin/com/chesstree/game/domain`.
Next, turn these cases into independently reviewed fixtures with stable IDs,
provenance, expected legal sets/outcomes, and exact state/replay data. Add missing
coverage for intermediate-turn mate attribution, simultaneous en-passant targets,
terminal ranking permutations, and any newly approved draw rules.
