# Experimental ChessTree opening repertoire

Requirements: [accepted BOT-OB01–05](spec.md#accepted-amendment-a-chesstree-opening-repertoire).
Repertoire version: 1. This is a bundled development candidate, not established
opening theory or a demonstrated strength improvement.

## Provenance and contents

Six nine-ply lines start at `StandardGame.scenario.initialState`. Each line
contains one pawn advance, one knight development, and one bishop development
for each of WHITE, RED, and BLACK, in the actual turn order. Three pawn families
are represented with both single and double advances. There are 49 distinct
complete-position entries. Alternative lines share their initial entry.

The [offline generator](../../gameDomain/src/jvmTest/kotlin/com/chesstree/game/domain/bot/BotOpeningBookGeneratorTest.kt)
selects pawn moves that open a legal bishop route, checks that the subsequent
knight move preserves bishop access, and applies each move through the public
rules reducer. Selection uses stable piece/coordinate ordering. Original army
geometry is checked separately for each actor; ordinary chess symmetry is not
assumed. The generated [static corpus](../../gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/bot/BotOpeningBookData.kt)
contains the exact position signatures and all replay tokens for every line.
These are engine-generated experimental development plans with no imported
database, external license, or asserted expert endorsement.

Reproduce the corpus offline:

```sh
BOT_GENERATE_OPENING_BOOK=1 ./gradlew :gameDomain:jvmTest \
  --tests '*BotOpeningBookGeneratorTest*' -Pkotlin.incremental=false
```

Generation changes product source and requires the normal replay/target gate
afterward. The generator is never called from the application.

## Selection and fallback

Lookup uses exact canonical text, with length-prefixed fields and normalized
collection ordering. It includes every piece identifier, type, army, coordinate,
and moved flag; castling rights including explicit rook IDs; every en-passant
target and eligible player; controllers, participant state, actor, ply, and phase.
Unknown, changed, or deviated positions return no preference. Runtime lookup
performs no move replay or legal-tree generation. The caller intersects
preferences with the current legal root moves.

The bounded search keeps its ordinary root order and fallback. Only a completed
root search may use seeded book ordering to choose between **exactly equal**
actor scores. A higher score always wins. Interrupted deeper work retains the
previous completed choice and diagnostics. No completed depth means the ordinary
legal fallback, with no book influence. Each actor has an independent deterministic
seed rotation with no shared random state.

Wire version 3 carries the seed, repertoire version, and whether the repertoire
changed the completed choice. The request fixes its versions; mismatched replies
are rejected. Version 0 disables the book. Watch play uses positional evaluation
3 and repertoire 1, with seed 0. General runner defaults retain CONTROL/book 0.

## Evidence and limits

Common tests replay every entry and candidate through the public reducer; check
all actors and both pawn distances; reject changed rights, flags, controllers,
turns, and en-passant fields; normalize collection order; reproduce seeded
ordering; and exercise an unexpected opponent reply. Search tests cover exact
ties, higher-score precedence, interrupted-depth retention, and ordinary fallback.

The [opening observation](e3-strategic-play.md) found no changed book decisions
under its default policy. The search preferred its own continuations or left the
small repertoire. Equal-score tests demonstrate functional selection, not a
strength gain. Wider independently reviewed lines and matched tournament evidence
remain necessary before claiming the repertoire improves playing strength.
