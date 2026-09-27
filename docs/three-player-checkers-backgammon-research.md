# Three-player checkers and backgammon: rules research

Research date: 2026-09-27. Status: candidate selection, not an approved game specification.

## Recommendation

For capture-based checkers, provisionally select Nikolai Chernykh's hexagonal
Trioshashki as the design reference. For short backgammon, select the current
TrigammonX two-dice mode as the contemporary reference. If long nardy is the
intended product, use Eranardy with the separately identified 2020 amendments as
the alternative research baseline.

These are recommendations for discussion. None of the inspected descriptions is
yet a complete, unambiguous implementation contract. No application code changed.
The selections are based on game design, not confirmed permission to reuse a
particular product. The intellectual-property qualification below applies.

"Modern" here distinguishes a currently developed edition from a dated original
publication. Search-engine crawl dates, archive-upload dates, and website footer
years do not establish a rules revision. This search did not establish a single
internationally standardized three-player ruleset for either game family.

## Checkers candidates

### Trioshashki: provisional product choice

The author-attributed [Samara federation article](https://samarafed.ucoz.ru/publ/2-1-0-5)
was posted on 25 August 2009. Its linked
[author package](https://samarafed.ucoz.ru/files/3checersbox.zip) contains a rules
document dated 26 August 2009; its text was inspected.

Confirmed core:

- Hexagonal cells; men move to one of three forward neighbours.
- Captures are compulsory in six directions; a chain may take both opponents'
  pieces. The player chooses a capture route without a maximum-count requirement.
- Promotion occurs at the farthest corner. A man with further captures continues
  as a man; new king powers cannot be used immediately in that turn.
- Kings use six directions and land immediately beyond the captured piece.
- The stated objective is defeating both opponents through capture or blockade.

The text has an incomplete opening sentence and inconsistent colour sequencing.
It does not clearly settle king travel distance, defeated-piece handling, or
automatic draw conditions. Its accompanying board diagram has not been verified
as a coordinate specification. This is a promising reference, not a modern
federation-approved competition standard.

### Checkers for three players: newer dated alternative

James C. Turner Sr.'s [US7717428B2 description](https://patents.google.com/patent/US7717428B2/en)
was published on 18 May 2010, following a 2008 filing. It gives three rectangular
bases, a triangular central area, twelve pieces per player, and a last-player-with-
pieces victory condition. Central-area movement differs from movement inside
bases. A king can only be captured by another king.

This is newer than the 2009 Trioshashki publication, but its geometry and king
immunity make it less suitable for the familiar checkers experience I recommend.
The inspected description also does not provide a satisfactory complete policy
for draws and blocked players. Publication as a patent is evidence of the
described design, not evidence of popularity, competitive balance, or adoption.

### Other variants considered

- [Petal-board checkers](https://slotobzor.com/nastolnye-igry/kak-igrat-v-shashki-vtroem/):
  the available secondary account freezes promoted pieces and ranks players by
  kings, then remaining men. This changes the objective substantially; its rules
  revision date and original source were not established.
- [Faustus, author's 2009 discussion](https://jpneto.github.io/world_abstract_games/yahoo_club/post_1523.html):
  an older hexagonal design with asymmetric relationships between opponents,
  intended to reduce alliance problems. The linked full game package could not
  be retrieved, so this is a lead rather than a selected specification.
- [Chinese Checkers, publisher instructions](https://www.winning-moves.com/images/ChineseCheckers_Rules.pdf):
  explicitly supports three players, but is a race with jumps rather than the
  capture-and-promotion game intended here. Keep it a separately named game if
  requested later.

## Backgammon candidates

### TrigammonX: most contemporary candidate found

The [official overview](https://trigammonx.com/how-to-play-trigammonx/) describes
three players, fifteen pieces each, and a shared 36-point board. The
[project history](https://trigammonx.com/trigammonx-history/) attributes its origins
to Bill Bailey and Dennis Angelica and describes subsequent redevelopment.

The [live game](https://trigammonx.com/xgame/Play-TrigammonX.html) was inspected,
including its How to Play panel: version **5.1.4 Public Beta**. This identifies the
observed software build, not a separately dated rules edition.

Confirmed core:

- Official two-dice mode: doubles give four moves of the rolled value.
- An exposed opposing piece may be hit onto the bar; re-entry takes priority.
- Bearing off starts with all pieces in the final six points of the player's path.
- Both dice must be used when a complete legal play exists.
- The first player to bear off all fifteen pieces wins.
- Optional three-dice mode: distinct values give three moves; a pair gives four
  moves of that value plus the remaining die; a triple gives nine moves.

The quick-start page promises complete rules inside the game, but the inspected
panel only supplies a summary. Before implementation, establish exact starting
points, complete paths, bar-entry mapping, opening ties, higher-die priority,
oversized bear-off, scoring, resignation, and stall handling. Do not infer these
from the name or import them silently from another Trigammon variant.

The history page still says public access is unavailable, while the live game is
accessible as Public Beta. Prefer the directly observed build for availability;
the documentation is not fully synchronized. Balance has not been independently
validated in this research.

### Eranardy / long nardy: alternative if non-hitting play is intended

[An interview with Nerses Nersisyan](https://dalma.news/ru/armyanskie-nardy-soobrazim-na-troih/)
describes his three-player design and the added set of fifteen pieces.

[The book author's amendments](https://chramov1.wixsite.com/nardy/нарды-на-троих)
are explicitly dated **24 October 2020**. They discuss long-nardy play, allow
mixed blockades, constrain single-colour six-point blockades relative to both
opponents, and score pairwise outcomes while the remaining players continue.
The proposed acceleration uses three dice: distinct values are played normally;
any pair or triple instead gives exactly four moves of the repeated value,
discarding the unmatched die.

These are that author's amendments, not a verified official revision from the
original inventor. The page is not a complete standalone setup-and-movement
manual. Mixed-blockade deadlocks, head-release rules, forced dice use, full paths,
and termination need explicit resolution. The author's estimated increase in
game length is an anecdotal estimate, not a measured benchmark.

### Older short-backgammon references

[William Sethares's TriGammon page](https://sethares.engr.wisc.edu/games/trigammon.html)
provides a printable board and a concise adaptation: a player encounters a
different opponent in each half of the journey. It relies on ordinary backgammon
rules and does not establish a recent revision date. Useful comparison material,
but not the most modern candidate found.

The name itself is ambiguous: [Wayne Borland's 1985 Trigammon description](https://patents.google.com/patent/US4556221A/en)
uses twenty pieces per player in two distinct groups of ten, with separate home
tables. It must not be conflated with the fifteen-piece TrigammonX description.

## Decisions before implementation

1. **Game family:** short backgammon with hitting, long nardy without hitting, or
   two separately named modes. A clarification was requested; no choice is assumed.
2. **Checkers experience:** my recommendation is active kings and elimination,
   using Trioshashki as the reference rather than frozen-king scoring.
3. **Finishing:** my proposed defaults are last survivor for elimination checkers
   and first bear-off for short backgammon. Long-nardy placement scoring must be
   specified separately. These preferences do not fill gaps in a source by fiat.
4. **Completeness:** approve explicit board diagrams, setup tables, movement and
   capture examples, promotion timing, elimination, draws, and exceptional turn
   outcomes before writing an engine. Identify every addition as a ChessTree rule.

The next deliverable should be one versioned rules specification per selected
mode, separating inherited rules from product decisions. Different geometries
must retain their own movement definitions; the existing chess board's paths
cannot establish the legality of moves in these games.

## Intellectual-property qualification

This is a preliminary distinction between rights, not a freedom-to-operate opinion
for a particular distribution territory.

- **Mechanics versus expression:** Article 1259(5) of the
  [Russian Civil Code, published by Rospatent](https://rospatent.gov.ru/ru/documents/grazhdanskiy-kodeks-rossiyskoy-federacii-chast-chetvertaya)
  excludes ideas, principles, methods, and systems from copyright. The
  [US Copyright Office's game guidance](https://www.copyright.gov/register/tx-games.html)
  likewise distinguishes methods of play from protectable explanatory text and
  artwork. This supports independently implementing mechanics, but does not
  grant a licence to copy another game's manual, code, diagrams, or assets.
- **TrigammonX:** the [official site](https://trigammonx.com/)
  uses trademark notices and an all-rights-reserved notice. No affirmative reuse
  licence was found in the inspected pages. Treat it as a design reference, not
  as reusable software or an asset library. A TM symbol alone does not prove
  registration; see [USPTO guidance](https://www.uspto.gov/trademarks/basics/what-trademark).
- **Trioshashki:** the inspected article and author package do not establish a
  general commercial reuse licence. Online availability and author attribution
  are not substitutes for permission to reproduce protected materials.
- **Patents:** the Turner candidate has the identifiable US7717428B2 patent;
  current enforceability and application to a proposed digital implementation
  have not been verified. Eranardy's inventor interview reports Armenian patent
  1863 A2, whose current status and claims were not inspected. These are research
  leads, not findings that ChessTree infringes either patent. Patents are
  territorial, as explained by [WIPO](https://www.wipo.int/en/web/patents/protection).

Recommended product approach: write original rule explanations, software, and
artwork; use descriptive ChessTree mode names; retain source attribution in the
research. Before adopting a specific patented design or another product's brand,
check the relevant claims and marks for the intended release countries with an
IP professional. Changing a name or adding an author credit does not itself
resolve other rights. Target countries and the exact adopted design are still
needed for a concrete clearance assessment.
