# King-shuffling observation from the pre-improvement recording

Date reviewed: 2026-09-30. Source: user-supplied
`ScreenRecording_09-29-2026-01-49-35_1.mov` (8.28 seconds, 1206 × 2622).
The recording predates the current E0/E1 diagnostic work; its exact engine
revision, saved policy weights, and move log are not present in the clip.

## What the recording establishes

The mobile browser shows the local three-bot watch mode. The visible move count
rises from 292 near 0.26 s to 297 near 3.88 s and 303 near 8.02 s. The board
still contains all three kings and several pawns. Across these eleven plies,
the kings repeatedly move between nearby squares; the visible pawns do not
advance. The game continues to produce moves, so the symptom is passive play
and recurrence, rather than a frozen interface or an illegal-move rejection.

The clip is too short and does not expose the move log, complete position rights,
or policy weights. It does not by itself prove that an identical full game state
repeats, that a particular pawn move wins, or that today's bot makes the same
choices. These are separate questions for a replayable fixture.

## Mechanisms in the current search worth testing

1. `MaxNBot` defaults to depth two. It sees the root move and one opponent's
   reply, while the second opponent and a long pawn race lie beyond its normal
   horizon. A long-term gain from advancing a pawn can therefore be invisible.
2. Nonterminal evaluation scores material, king-in-check safety, direct check
   pressure, and legal-move count. It has no explicit pawn advancement,
   promotion-distance, repeated-position, or no-progress feature. A safe king
   move that preserves or increases mobility can outscore a useful pawn move.
3. Root moves are generated in stable piece-ID order. `StandardGame` IDs put
   surviving `*-back-*` pieces before `*-pawn-*` pieces, and equal search scores
   retain the first move. When the king is the only surviving back-rank piece,
   ties can favor a king move. This is a conditional mechanism, not a measured
   explanation for any individual move in the recording.
4. The game rules have no automatic repetition or no-progress result. The
   search receives only a `GameState`, not session history. It therefore has no
   history signal to discourage a reversible move cycle. A new game-ending draw
   rule would require a separate product decision; a search preference to avoid
   unproductive repetition can be evaluated without silently changing the rules.
5. The local watch mode loads a saved, learned policy before each move. The
   recording does not reveal those weights, so their contribution is unknown.

Relevant implementation: [MaxNBot.kt](../../gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/bot/MaxNBot.kt)
(search and evaluation), [LegalMoveGenerator.kt](../../gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/LegalMoveGenerator.kt)
(move order), [StandardGame.kt](../../gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/scenario/StandardGame.kt)
(piece IDs), and [ChessNavigationRoot.kt](../../composeApp/src/commonMain/kotlin/com/chesstree/app/ChessNavigationRoot.kt)
(three-bot watch mode and policy load).

## Required strategy check

Obtain a saved move log and policy snapshot from a reproducing game if
available. Add an independently reviewed late-game fixture with all three
players active, legal pawn advances, and candidate king shuffles. Record the
baseline root scores and selected continuation before adjusting the evaluator.
Test for tactical safety, promotion progress, and recurrent moves over a fixed
node budget. Judge candidate changes against the frozen MaxN control using
equal-resource games and positions, including cases where a defensive king
move is correct. Do not declare a win from appearance alone or reject all
reversible king moves.

This video strengthens the case for an endgame evaluation and cycle-sensitive
search study in E4. It does not justify dropping mobility from the current
policy: the E1 cost probe showed that zeroing its weight changed all ten tested
opening decisions. The immediate architecture review should preserve the
control's behavior while finding a safe performance path; the strategy change
needs its own measured candidate and acceptance evidence.
