# Three-Player Chess: Rules

Document status: a text specification transcribed from the original rules PDF.
The PDF is not included in this repository. The wording has been normalized for
implementation, but rules not specified in the original have intentionally not
been inferred.

## 1. Board and players

- Three players take part: white, red, and black.
- Colors are assigned by drawing lots.
- The board consists of three sectors connected through a shared central area.
  Files, ranks, and diagonals continue through the center along the lines shown
  in the source diagrams. The board is therefore not a normal rectangular grid.
- The source notation uses files `A`–`N` and ranks `1`–`12`. Not every formal
  letter-and-number pair is necessarily a board square.
- White occupies the side between `A` and `H`, red between `A` and `N`, and
  black between `H` and `N` (source diagram 1).
- Each player has a standard set of pieces: a king, queen, two rooks, two
  bishops, two knights, and eight pawns.
- White and black place their queen on a square of their own color; red places
  the queen on a white square.
- Turn order is fixed and clockwise: white, red, black, then white again.

## 2. Piece movement

In general, pieces move and capture according to standard chess rules, adjusted
for the geometry of the three-sided board.

### 2.1. Pawn

- Any pawn may move one or two squares on its first move.
- Before reaching the center, a pawn moves forward along its file and captures
  on the two adjacent diagonals.
- In the central area, a pawn gains a third capture direction, but an ordinary
  move continues only along the file it occupies. After a capture, the pawn
  continues along the file of its destination square (for example, after a
  capture on `K9`, it moves along file `K`).
- On reaching the last square of its route, a pawn promotes at the player's
  choice to a queen, rook, bishop, or knight. Promotion to a king is forbidden.

### 2.2. Rook

- A rook moves any number of unobstructed squares along files and ranks.
- In the central area, a line may continue into another sector; valid
  continuations are defined by the board geometry (source diagrams 5 and 6).

### 2.3. Knight

- The knight keeps the chess move of two squares in one direction and one
  perpendicular square, following the connections between board squares.
- The set of reachable squares in the center differs from a rectangular board
  and must follow the topology in source diagram 8.

### 2.4. Bishop

- A bishop moves any number of unobstructed squares along diagonals of its color.
- Diagonals form arcs and continue through the central area into other sectors
  (source diagrams 9 and 10).
- The square color remains the same throughout a bishop's path.

### 2.5. Queen

- A queen combines the movement of a rook and a bishop.
- In the center, it can continue along the corresponding straight and curved
  lines into other sectors (source diagrams 11 and 12).
- Source diagram 12 specifically shows that the queen cannot reach `K5` from
  the depicted position: it can move along black diagonals or to adjacent
  squares.

### 2.6. King

- A king moves to one adjacent square according to the board geometry and may
  not move into check.
- Its neighbors in the center are defined by source diagram 14.
- Source diagram 14 specifically shows that the king cannot reach `K9` from the
  depicted position: it can move along white diagonals or to adjacent squares.
- Castling exists: the example game contains short castling by white and black
  (`0-0`). The source does not further specify the other castling conditions.

## 3. Check and checkmate

- Check can come from pieces belonging to one or two opponents at the same time.
- A checkmated player is immediately eliminated and takes third, last place.
- The checkmated player's king is removed from the board.
- If a discovered or double check makes the author of mate unclear, determine it
  based on turn order and whether the next player can remove the mating piece.
  Examples are described in section 8 of the source.

### 3.1. Attributing mate in complex positions

Diagrams 8.1–8.3 establish these principles:

1. If a player's move opens an attack line for another player's piece and causes
   mate, the mate may be credited to the owner of the revealed attacking piece.
2. If the next player in turn order must move and can capture the mating piece,
   the current position is not yet treated as final checkmate.
3. For double check, consider all required intermediate moves. The player whose
   attack remains decisive afterward is the author of mate.
4. In diagram 8.3, a red knight checks two kings at once: black escapes, while
   white remains mated by the red knight. Red is credited with the mate.

The engine should implement these cases as a distinct mate-attribution rule
rather than determining attribution solely from the color of the piece that
made the last move.

## 4. Play after the first checkmate

- All pieces of the eliminated player, except the removed king, stay on their
  squares.
- Those pieces come under the control of the player who delivered checkmate.
- A player controlling an additional army still makes only one move on their
  turn, choosing any piece among the armies they control.
- Two armies under the same player's control are allied and cannot capture each
  other's pieces.
- When one of the two remaining active players is checkmated, they take second
  place and the player who delivered mate takes first.

Modeling consequence: army color and the player currently controlling that army
are separate properties. After checkmate, a piece keeps its color but changes
controller.

## 5. Stalemate

- A stalemated player stops participating in the game.
- All of their pieces stay on the board but lose the right to move.
- If these pieces block the remaining players, they may be captured.
- Exception: the stalemated player's king remains immobile and invulnerable
  until the end of the game.
- Any piece other than a king may move adjacent to the stalemated player's king.
- If another player becomes stalemated, or the remaining players agree to a
  draw, the game ends in a draw among all three players.

## 6. Rare positions

The source specifically confirms that these situations are allowed:

- Two kings may stand next to each other if the board geometry and game state
  do not create a prohibited attack (see diagram 14 and source section 7).
- White and black pawns under the control of the white king are allied: they
  cannot capture one another, and both may promote to queens.

## 7. Notation

- Algebraic notation uses square letters and numbers up to 12.
- The source uses Cyrillic abbreviations for the king, queen, rook, bishop, and
  knight. A pawn has no letter.
- `:` means capture, `+` means check, `++` means double check, `x` means
  checkmate, and `0-0` means short castling.
- One move number groups one move each by white, red, and black, in that order.

## 8. Rules not fully specified by the source

### 8.1. Project decisions

The following variant rules have been chosen for deterministic implementation:

- Short and long castling use standard restrictions: neither the king nor the
  relevant rook has moved, the squares between them are clear, and the king is
  not in check or passing through an attacked square. The right is tied to a
  specific rook and is lost if that rook moves or is captured.
- After a pawn's two-square move, each active opponent may capture it en passant
  on their next turn. Skipping that opportunity consumes only that opponent's
  right, so multiple en-passant targets may coexist in one position.
- Checkmate and stalemate are checked when the turn would pass to a player. This
  allows an intermediate player to remove a discovered check.
- If multiple controllers are attacking, first select the sole new attacker
  introduced by the last move; next select the player who just moved if they are
  among the attackers; otherwise use a deterministic player order. This fallback
  is only needed for positions whose attribution is ambiguous in the source.
- If one player is checkmated and another is stalemated, leaving one active
  player, the final ranking is: active player first, most recently eliminated
  player second, and the earlier eliminated player third. Two stalemates still
  produce a draw among all players, as in the source rule.
- If exactly two active players remain after a move and the board contains
  either only their two kings or those kings plus one knight belonging to one
  player, the game immediately ends in a draw between them. They share first
  place, and the previously eliminated player remains third. In either case,
  an immobile, invulnerable king belonging to a stalemated player may also
  remain on the board; it does not count as material for the active players.
  This rule is applied after checking for mate and stalemate caused by the move.

Before implementing a complete engine, settle and document these remaining
questions:

1. The complete machine-readable list of squares and their connections. The
   diagrams show the geometry but do not provide an adjacency table.
2. The exact mapping of all starting squares to global coordinates `A`–`N` and
   `1`–`12`; the text and diagrams are not sufficient to reconstruct every
   coordinate without separate verification.
3. Whether a rook retains castling rights if it comes under another player's
   control before its original king is eliminated, if such a position becomes
   representable.
4. Draws by repetition, the 50/75-move rules, and other insufficient-material
   cases besides the documented `K vs K` and `K+N vs K` cases.
5. Whether voluntary resignation is possible and how it affects remaining
   armies and rankings.
6. Complete attribution rules for rare multiplayer checkmates beyond the
   deterministic fallback adopted by the project.

Until these decisions are made, the code must not silently inherit a particular
standard chess variant.

## 9. Source

The original source was a three-page scan containing the rules and starting
position, piece-movement diagrams, and examples of discovered/double check and
rare positions. The source PDF is not included in this repository.
