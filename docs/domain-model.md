# Three-Player Chess Domain Model

## Purpose and boundaries

The domain lives in `gameDomain/src/commonMain` and is shared by Android, iOS,
and Web. It does not depend on Compose, platform APIs, storage, or networking.
Board topology, legal move generation, and game-state transitions are shared
deterministic code.

The model follows established chess-engine design practices: position is
separate from move history, a piece's type is separate from its placement, and
a confirmed move is separate from the user's intent. The three-player variant
also distinguishes an army's color from the player who currently controls it.

## Core entities

| Entity | Responsibility |
| --- | --- |
| `PlayerId` | A player's seat and the color of their original army: white, red, or black. |
| `ArmyColor`, `ArmyControl` | An army's immutable color and its single current controller. |
| `BoardFile`, `Square`, `ThreePlayerBoardNotation` | Algebraic square names and a bijective mapping between all 96 board cells and machine coordinates. |
| `BoardCoordinate`, `ThreePlayerBoardTopology` | The authoritative machine address of one of 96 cells and the connections between straight and diagonal directions. |
| `PieceId` | Stable piece identity across moves and changes of control. |
| `PieceType`, `Piece` | Piece type, original army, square, and whether it has moved before. |
| `PieceAppearance` | Semantic colors for a piece's base and body in the shared UI. |
| `ParticipantStatus` | Active, checkmated, or stalemated. |
| `Position` | Immutable piece placement and positional rights (castling and en passant). |
| `MoveIntent` | A player's request: source, destination, and optional pawn promotion. |
| `Move` | A move confirmed by the domain, including the piece, move type, and capture. |
| `Turn` | The player to move and the global ply number. |
| `GameState` | Aggregate of the current position, participants, turn, and phase. |
| `GameOutcome` | Final ranking, a three-way draw, or a draw between the two remaining players while the eliminated player keeps third place. |

## Key invariants

- A position cannot contain two pieces with the same `PieceId` or on the same
  square.
- A piece map's key must match the ID of its value.
- A game has exactly three participants.
- Only an active participant may move.
- A finished game has no next turn; an ongoing game does.
- An army's color does not change after the first checkmate. `ArmyControl`
  transfers the entire army atomically to another player. Pieces have no
  individual controller, so partial army transfers cannot be represented.
- A piece's base always uses its original army color. Its body uses the current
  controller's color: these match initially, and the piece becomes two-toned
  after an army transfer. The UI chooses the concrete RGB/Material colors.
- Maps and sets passed into aggregates are copied, so later mutation of the
  original mutable collection cannot change domain state.
- A king is not a valid pawn-promotion choice.
- Board topology, rather than coordinate ranges, determines whether a square
  exists and whether a line continues through the center.

## Manual test scenarios

Named starting states are in
`composeApp/src/commonMain/kotlin/com/chesstree/game/presentation/scenario/ManualGameScenarios.kt`.
They are declared in `commonMain`, so Android, iOS, JS, and Wasm use the same
position. Choose a scenario from the menu above the board; selecting one resets
the game state and selected piece.

Define a new scenario with the shared DSL:

```kotlin
val example = gameScenario("example") {
    title = "Rook test"
    description = "An arbitrary sparse position."
    turn(PlayerId.WHITE, ply = 1)
    piece("white-king", ArmyColor.WHITE, PieceType.KING, BoardCoordinate(0, 3, 0))
    piece("white-rook", ArmyColor.WHITE, PieceType.ROOK, BoardCoordinate(0, 1, 1))
    piece("red-king", ArmyColor.RED, PieceType.KING, BoardCoordinate(2, 3, 0))
    piece("black-king", ArmyColor.BLACK, PieceType.KING, BoardCoordinate(4, 3, 0))
}
```

After defining a scenario, add it to `ManualGameScenarios.all`. A coordinate is
the triple `vertex`, `column`, `row`; the constructor validates each component.
`ThreePlayerBoardNotation` converts coordinates to and from algebraic notation
such as `a1` for display, logs, and user input. The mapping covers all 96 cells
and common tests verify uniqueness and reversibility.

A scenario may use a position unreachable from the normal starting state and
any combination of up to 16 pieces from each original army. Each active or
stalemated player must have one king; an already checkmated player must have
none. `checkmated(...)` marks the participant as eliminated and transfers their
army to the player who delivered mate. The initial position is trusted: check,
checkmate, and stalemate are not calculated when it is created. After the first
applied move, the reducer automatically checks the player whose turn is next.

## Domain move services

`LegalMoveGenerator` builds pseudo-legal and legal moves only for the player
whose turn is recorded in `GameState`. It accounts for occupied squares, blocked
rays, each army's current controller, a pawn's initial two-square move,
promotion, and king safety. The attack map is currently internal to the
generator. Attacks from all active opponents are combined, so simultaneous
checks by two players are supported. Kings cannot be captured as ordinary
pieces.

`GameReducer.reduce` is the only entry point for applying a `MoveIntent`. Success
returns a new immutable `GameState` and canonical `Move`; failure returns a
typed reason without changing the original state. The reducer applies ordinary
moves, captures, promotion, castling, and en passant, updates positional rights,
and advances to the next active player. If the next player has no legal moves,
the reducer declares checkmate or stalemate, transfers the army after checkmate,
and ends the game when required.

If two active players remain after a move and the material is reduced to
`K vs K` or `K+N vs K`, the reducer finishes the game as a `TwoWayDraw`: both
players share first place and the previously eliminated player keeps third. A
stalemated player's immobile king remains on the board but is not counted in
this material check. Replaying an older saved history that contains moves after
such a position is still allowed; the rule takes effect on the next new move,
ending the stale game without losing history.

`Move` stores the rook's castling movement explicitly, so a confirmed move can
be replayed without deriving the plan from the position again. `Position` stores
multiple en-passant targets: after two consecutive double pawn moves, the third
player can have two such opportunities at once.

## Move log and recovery

`GameLogCodec` builds the move log from confirmed `GameSession` history without
creating a second source of game state. Each ply is written as an extension of
standard algebraic notation (SAN) for three players:

```text
<N>: <Army>.<SAN>
```

`Army` is `W`, `R`, or `B`. The main notation uses standard piece letters `Q`,
`K`, `N`, `B`, and `R`, capture marker `x`, source-file or rank disambiguation,
castling `O-O`/`O-O-O`, promotion `=Q`/`=R`/`=B`/`=N`, and `+`/`#` suffixes.
Square names come from `ThreePlayerBoardNotation`, including the additional
files and ranks on the three-sided board. Example: `12: R.exd8=N+`.

On import, the `N` and `Army` metadata are intentionally not validated: SAN is
matched against legal moves in the current position, and the selected move is
applied through `GameReducer`. Unrecognized or inapplicable lines are skipped;
the UI reports the number restored out of all non-empty lines. Check and mate
suffixes are optional on input. For compatibility, import also accepts the
original format ending in a `From->To` pair. New exports always use SAN and
therefore preserve the selected piece when a pawn is promoted.

The Compose dialog and platform actions are outside the domain. Android uses
the system file picker, iOS uses the system save/share menu, and Web downloads
through the browser. Clipboard support is also implemented separately per
platform.

## Why the model is in `commonMain`

The rules are deterministic and use no platform features. One shared domain
prevents Android, iOS, and browser behavior from diverging. Platform layers
should display `GameState` and send `MoveIntent`; they must not decide whether a
move is legal.

## Shared board

The adaptive Compose board in `commonMain` is shared by Android, iOS, and Web.
Its geometry uses a regular hexagon to construct 96 unique quadrilateral cells.
Cell colors alternate by adjacency, with `A1` treated as dark. The external
file/rank labels match the reference SVG.

The UI lets a player select a piece they control and sends a move intent when a
highlighted square is tapped. Hints come from `LegalMoveGenerator`, so they
account for occupancy, check, castling, and en passant. `MovementDirections`
remains a low-level description of geometric paths and is not itself used as
move authorization.

## Application and online play

The game rules live in `gameDomain` and do not depend on UI, networking, or
databases. Manual scenarios live in `composeApp` as UI support and use the shared
domain. `composeApp` renders state and sends typed intents. Local game persistence
and its snapshot codec also live in `composeApp`; this storage format is separate
from the network API format.

For online games, the server validates each move again with the same shared
engine. The database stores participants, game revisions, and immutable move
commands; API DTOs and SQL models are not domain types. Full state responses include
a server-generated position snapshot so clients can restore the current board without
replaying the journal. The move journal remains the recovery source for legacy or
invalid snapshots, while WebSocket messages report new revisions. See
[`api.md`](api.md) for the contract and
[`architecture.md`](architecture.md) for module boundaries.

## Open rules

- Clocks and time controls.
- Position repetition, the 50/75-move rules, and other draw policies besides the
  explicitly implemented `K vs K` and `K+N vs K` cases in
  [`three-player-chess-rules.md`](./three-player-chess-rules.md).
