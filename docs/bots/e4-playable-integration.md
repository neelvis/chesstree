# E4 local playable integration checkpoint

Date: 2026-10-01. Implementation based on commit `4873160`.
This report updates the evidence for the previously approved
[bot specification](spec.md), product sections 4–9 and 12. It does not declare
release acceptance, playing-strength superiority, or calibrated difficulty.

## Delivered local flow

The local game now offers **Play against bots**. A new-game screen selects the
human's color and independently selects each other seat's style and difficulty.
The four styles are Universal, Attacking, Positional, and Endgame Specialist.
The three difficulty presets use the initial product budgets:

| Preset | Soft search budget | Browser hard watchdog | Maximum nominal depth |
| --- | ---: | ---: | ---: |
| Beginner | 100 ms | 150 ms | 2 |
| Normal | 350 ms | 500 ms | 3 |
| Strong | 900 ms | 1,200 ms | 4 |

Each preset has 20,000 node and evaluation caps and four tactical continuation
plies. Depth and horizon diagnostics describe the completed work; a preset's
maximum depth is not a promise that every move reaches it. Native stop checks
remain cooperative. A long individual rules/evaluation operation can overrun the
soft budget or delay cancellation. These presets have not been calibrated to
ratings, ordering of strength, or phone performance thresholds.

The acting bot receives only the public session and its own frozen seat setup.
The human can act only on the human seat's turn, including any armies that the
rules assign to that controller. Pause preserves bot assignments; it never lets
the human play another seat's turn. Resume continues the same game. Undo removes
the last human decision and all bot replies following it. Watch mode remains
available and now also pauses/resumes its frozen configuration.

Opening setup/history, leaving, changing scenarios, restart, undo, and import
retire the active request or invalidate its game/revision identity. Every returned
move still passes the public session reducer. Thinking text identifies the acting
seat. Existing explicit failure/retry and legal fallback paths remain in use.

Settings change only when starting a new game. New human games freeze the default
policy and do not run the legacy global postgame learner. Watch games freeze the
legacy policy captured at creation; later learning cannot mutate their saved
configuration. Russian, English, and German catalogs include the new text.
Setup and history use ordinary screen content rather than nested modal windows:
the browser's accessibility tree otherwise remained attached to a dismissed popup.

## Search candidate and style boundary

The general domain constructor preserves legacy MaxN defaults. New configured
local games explicitly choose the **Paranoid candidate** with positional evaluator
version 3 and repertoire version 1. This uses one fixed root player's scalar
utility and alpha-beta pruning; other actual actors minimize that component.
Each bot independently searches from its own root. There is no shared private
analysis or move coordination between seats. The opponent model remains an
empirical candidate requiring comparison against MaxN under matched resources.

Positive tactical depth first completes a check-resolving horizon, then attempts
an exchange-response horizon at the same nominal depth. Captures/promotions track
actual remaining responders; intermediate players make actual legal moves. Any
active king in check must be resolved before a static frontier is accepted.
Reaching the continuation cap while check remains interrupts that root iteration.
An interrupted root never replaces the last fully completed depth/horizon.
`CHECKS` completion does not claim a completed exchange response cycle. Even
`EXCHANGES` remains a finite heuristic search, not an objective safety proof.

A root preference discourages returning to one of the last 36 complete relevant
positions, ignoring only the absolute ply number. Its penalty is 0.12 pawn units
per prior occurrence, capped at two. This adds no repetition draw or elimination
rule. The candidate's initial legal fallback uses current-position capture,
exposure, promotion, and development ordering. It is not a searched tactical
proof. Legacy MaxN/static controls retain their earlier fallback ordering.

Styles choose among completed alternatives within 0.1 pawn units of the best
common backed-up root score. Attacking prefers safe pressure near enemy kings;
Positional prefers pawn support and lower exposed material; Endgame Specialist
prefers pawn progress/support with a material-phase multiplier. Catalog 2 Universal prefers safe pawn progress and early minor-piece development.
Saved catalog 1 Universal retains its original absence of a style preference. Terminal utility stays dominant. Exact common-score/style
ties can use the accepted repertoire; a repertoire preference cannot force a
worse common result. Focused independent scalar-tree tests cover all root seats,
stable selection, the close boundary, and terminal-win priority. They do not
establish profile recognizability or noninferiority at the product thresholds.

## Persistence and diagnostic compatibility

`LocalBotGameConfig` freezes the human seat, each bot's profile/difficulty/seed,
base policy, and version tuple: rules 1, engine 3, evaluation 3, repertoire 1,
profile catalog 2 for new games. Saved catalog 1 remains supported with its original
behavior. Unsupported versions, duplicate/missing seats, malformed
fields, and unsupported enum values are rejected.

Saved/exported games use `CHESSTREE|2`: known scenario, full initial rules state,
validated move intents, frozen setup, paused/running state, and move-indexed bot
search diagnostics. The initial state must fully match its known scenario,
including special rights. Replay uses the public session rules. Legacy
`CHESSTREE|1` move-only records remain manual games; no historical bot settings
are fabricated. A corrupt or unreadable startup save is reported and is not
silently overwritten by the default initial session.

Startup loads the durable last game. Imported games open paused so their position
can be inspected. An incompatible bot snapshot is rejected as a whole and leaves
the current game intact. Existing SAN-only import remains a manual-game path.
The codecs bound records to 10,000 moves/diagnostics and a 1,000,000-character
envelope; diagnostics validate move-index/player association and typed facts.
Oversized records report a save error and disable export rather than throwing
during screen rendering or state restoration saving.

History displays the ordinary move list. Its Copy/Save actions export the full
snapshot, including elapsed request time, node/evaluation counts, completed
depth/horizon, stop reason, fallback source, repertoire influence, and recurrence
penalty. The preceding move prefix identifies each searched position. Cache hits
are zero because this implementation has no transposition cache. No account,
authentication, or unrelated personal data belongs to this record. Request time
includes dispatch/worker overhead. Seed plus a wall-clock deadline is not claimed
to reproduce a search exactly; the domain's fixed-work path retains stable order
for debugging.

The existing state codec is now shared by all app targets through `botWire`,
which has Android and both configured iOS targets in addition to JVM/JS/Wasm.
No library versions were upgraded. Shared dependency placement and target APIs
were checked against [Kotlin documentation](https://kotlinlang.org/docs/multiplatform/multiplatform-add-dependencies.html)
and the [Android KMP library guide](https://developer.android.com/kotlin/multiplatform/plugin).
Worker transport is version 5; full-state JSON remains version 1. Native and both
browser runners carry model/profile/difficulty/catalog/history; browser replies validate
those echoes and completed progress ordered by depth, then horizon.

## Verification evidence

Automatic gates passed on this Mac:

- All domain JVM checks: 157 methods; wire JVM checks: 7 methods.
- Affected Android host checks: 39 methods, including tactical request boundaries,
  independent seat settings, human-round undo, schema migration, replay, malformed
  diagnostics, and preservation of unreadable startup saves.
- Android debug APK; domain Android and both iOS architectures; shared app iOS
  simulator/device compilation; JS and Wasm production distributions; server JVM.
- Full Xcode simulator build and command-line installation/launch of the debug
  apps in the supplied Android emulator and iPhone simulator.

Method counts include opt-in utilities that return without running experiments
when disabled; they are not counts of tournament games. The final catalog 2 /
transport 5 gate passed with exit 0 in `/tmp/chesstree-catalog2-full-gate.log`.
The final simulator Xcode build passed in `/tmp/chesstree-catalog2-xcode.log`.
Both rebuilt native apps were installed and launched using platform tools.
`git diff --check` passed. Native interactive checks remain unavailable.

Actual in-app browser checks (the broader catalog 1 flow, then the catalog 2 correction):

| Target | Observed flow |
| --- | --- |
| JS | White human; Red Attacking/Beginner and Black Endgame/Strong; human move and both replies; pause; reload with the same setup; undo 3 moves to 0; resume; copy/paste full snapshot; restore 3/3 moves paused; incompatible rules version rejected without replacing the game |
| Wasm | Red human; White Universal/Normal and Black Positional/Normal; initial White bot move, Red human move, both following replies; pause at 4 moves; reload with the same setup; undo the human round to 1 move |
| Narrow browser | Setup readable at 390 × 844 after reload; all selected settings and Start/Cancel visible; temporary viewport override reset |

After rebuilding transport 5, JS loaded the existing catalog 1 save unchanged.
A new catalog 2 game used Red Universal/Beginner and Black Endgame/Strong,
completed the human move and both replies, exported catalog 2 with typed
diagnostics, and restored all three moves paused. The sampled replies were
a legal fallback at 133 ms and completed depth-1 EXCHANGES at 922 ms.
The new Wasm build loaded its old save, then created a catalog 2 Red-human game
with White Universal/Normal and Black Positional/Normal, completed its initial
bot move and the human move plus both replies, then paused and undid the human
round from four moves back to one.

JS produced no captured console errors in the earlier catalog 1 flow. Wasm reported a Kotlin dependency
`wasmExports.memory` deprecation while the game continued; no bot retry failure
was observed in this smoke flow. Copy/paste exposed the full frozen setup and
search facts: the sampled Beginner reply was a legal fallback at 131 ms, and
Strong retained a completed depth-1 exchange horizon at 921 ms. These two samples
are not latency distributions or evidence of a required depth. The in-app browser
reported file save, but its download observer did not deliver a file; actual file
download is not claimed verified. Native GUI checks remain unavailable because
the Mac is locked. Installed/launched apps are not native gameplay evidence.
Live browser resizing initially scaled the existing drawing surface; the narrow
check used reload. Live resize/rotation acceptance remains open.

Detailed source fingerprints, raw probes, exported test snapshot, and proof images
are retained under `work/bot-playability-20260930/`; that working directory is not
part of the deliverable commit. Published probe summaries follow below; raw trajectories are
[Universal catalog 1](e4-universal-catalog1-long-games.tsv),
[Endgame catalog 1](e4-endgame-catalog1-long-games.tsv), and
[Universal catalog 2](e4-universal-catalog2-long-games.tsv).

## Endgame observations

The earlier fixed-work recurrence experiment and rejected direct king-pressure
candidate remain development evidence. The pressure candidate was removed after
an adverse paired trajectory; no evaluation version 4 is enabled.

The current long-game probe uses the actual Strong preset's nominal depth 4,
900 ms search budget, 20,000 node/evaluation caps, q4, repertoire 1, history 36,
and independent identical profile selection for all seats. It validates every
move through the public reducer. Unfinished games at the cap are censored and
never counted as draws. The standard start is reachable; the pawn and mixed
fixtures are synthetic, with unproven reachability. They are not reconstructions
of the supplied recording and do not establish a forced win.

| Profile/catalog | Standard start | Changed pawn file (synthetic) | Mixed material (synthetic) |
| --- | --- | --- | --- |
| Universal / 1 | 240 plies, unfinished; 34 repeated positions; 0 fallbacks | 44 plies, two-way insufficient-material draw; 0 repeats/fallbacks | 240 plies, unfinished; 130 repeats; 0 fallbacks |
| Endgame / 1 | 223 plies, White first / Black second / Red third; 0 repeats; 1 fallback | 69 plies, two-way insufficient-material draw; 0 repeats; 1 fallback | 56 plies, two-way insufficient-material draw; 0 repeats/fallbacks |
| Universal / 2 | 240 plies, unfinished; 2 repeated positions; 0 fallbacks | 69 plies, two-way insufficient-material draw; 0 repeats; 1 fallback | 56 plies, two-way insufficient-material draw; 0 repeats/fallbacks |

The two-way draws place Red and Black jointly ahead of White. Each cell is one
trajectory at seed 0. Repeated positions compare the full rules state except ply.
Wall-clock stopping can change completed work between runs; these observations
support retaining the balanced candidate for further evaluation, not a measured
strength improvement or proof that passive endgames have been eliminated.

## Acceptance that remains open

The owner subsequently observed active early piece development followed by
repetitive play in an actual game. The detailed game report is pending; diagnosing
that recurrence is a separate follow-up. The three probe trajectories above do
not establish that the recurrence problem is resolved.

The new functional slice advances F01–F12 and the approved tactical/endgame work.
Full product acceptance still needs the broader mandatory tactical corpus,
predeclared balanced strength tournaments, profile recognizability/noninferiority,
calibrated difficulty separation, and measured phone/browser SLO distributions.
Native touch, lifecycle pause/resume, restoration, and stale cancellation must be
exercised after unlocking the Mac. Physical-phone performance is unmeasured.
Long restoration/replay and the legacy watch postgame learner still have synchronous
Web work; smoke play does not close responsiveness acceptance for those paths.
The previous control remains in the domain for comparison. No deployment or
promotion of Paranoid to a validated release winner was performed.
