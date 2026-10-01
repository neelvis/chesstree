# E1 watch integration and pawn-progress candidate

This report freezes the earlier watch integration checkpoint. The current
positional evaluator, repertoire, wire version, and checks are in
[E3 strategic play](e3-strategic-play.md).

Date: 2026-10-01. Scope: development watch play under the existing approved
[product requirements](spec.md), especially F03–F11 and BOT-D03–04. The full v1
acceptance remains open. All changes are local and uncommitted; no deployment.

## Execution and ownership

The shared app controller sends immutable state and a game/request/revision
identity to a `LocalBotRunner`. Android and iOS use one background search owned
by a common mutex. Both Web targets use a dedicated Kotlin/JS worker running
the same domain rules; the Compose page does not run the move search.

Current watch settings are depth 3, 20,000 node/evaluation caps, a 900 ms search
budget, and a 1,200 ms page watchdog. These are development settings, not three
calibrated product difficulties. Native stop checks are cooperative; an existing
long generator/reducer call can overrun its budget or delay cancellation.

The worker transport is version 2; full-state JSON remains version 1.
`READY` establishes a legal fallback, `PROGRESS` publishes each fully completed
root iteration, and a final message reports the outcome. The browser retains
the latest completed iteration when the watchdog or a worker error ends the
request. It uses the READY fallback only when no completed iteration arrived.
Failure before READY exposes retry. An interrupted root iteration is never
published as complete. Explicit cancellation returns no move and terminates
the request-owned worker.

The controller revalidates every returned intent through `GameSession.apply`.
Stopping, leaving, replacing a game, restarting, undoing, and restoring retire
requests or increment position identity. Lifecycle activity carries an epoch,
so a quick inactive/active sequence cannot make an old request current again.
Policy is captured at game creation rather than reloaded on each bot turn.
Thinking and retry text is translated in the existing Russian, English, and
German catalogs.

Worker packaging now attaches the generated asset to JS and Wasm
`ProcessResources` Copy tasks. Production distributions and processed resources
contain the worker. Development and production distribution checks run in
separate Gradle invocations: combining them exposed Kotlin plugin shared-output
validation, which is not treated as a bot or resource-copy failure.

## Equivalent geometry optimization

`MovementDirections` caches finite, immutable board geometry: 480 nonpawn
type/coordinate entries and 288 original-army pawn entries. It retains the
original calculation, route ordering, and authoritative legal move generator.
Position occupancy, controllers, rights, and legal-move results are not cached.

Focused tests compare all 1,728 type/coordinate/army inputs against the original
calculation, check reuse, and reject mutation of returned lists. Forty frozen
search rows retain the same chosen move, legal count, node count, and evaluation
count for both the default policy and the earlier zero-mobility diagnostic.

The retained [geometry cost probe](e1-geometry-cost-probe.tsv) has a descriptive
warm JVM default-search median of 88.57 ms versus the historical 734.03 ms in
the [original probe](e1-cost-profile.md). This small Mac experiment is not N01:
cold initialization, memory, thermal behavior, controlled load, and phone/Web
distributions were not measured. The frozen original probe remains unchanged.

## Pawn-progress experiment

`CONTROL` version 1 preserves the original evaluation and remains the general
search/request default. Development watch requests explicitly choose
`PAWN_PROGRESS` version 2. Both use the same search, rules, and terminal
placement scores. This candidate is not the complete endgame profile.

For an active controller whose nonking material consists only of pawns, each
pawn receives a geometric progress bonus from its original army's forward route.
The per-pawn contribution is at most 0.9 times pawn material weight. The sum is
capped at 1.9 times that weight, below the smallest promotion material gain.
This corrects a reviewed cliff where promotion erased the other pawn bonuses
and could lower the score, or losing the last minor could improve it.

The [retained comparison](e1-endgame-probe.tsv) uses three synthetic full states,
not positions reconstructed from the owner's recording. Standard-game
reachability and an objectively forced winning line are unproven. Each mode has
identical initial state, policy, depth 2, 128 nodes, and 128 evaluations; these
diagnostic settings differ from the app's watch settings. Each rollout has 12 plies.

| Fixture | CONTROL first WHITE pawn turn | Candidate first WHITE pawn turn | Mandatory first king defense |
| --- | ---: | ---: | --- |
| Three free pawns | 2 | 1 | Not applicable |
| WHITE pawn on changed file | None in four WHITE turns | 2 | Not applicable |
| King defense first | 3 | 3 | Preserved by both modes |

The candidate improves the specific idle-pawn symptom with equal work caps.
It does not prove a strength gain or solve all material-rich king shuffling.
Geometric progress can reward blocked or vulnerable pawns; the total cap can
remove the progress gradient when several advanced pawns saturate it. Rich
positions, king support, pawn races, and third-player threats need broader
quality evidence. Thirteen focused evaluator tests include exact CONTROL and
terminal scores, transferred-army ownership, legal promotion, pawn loss,
sole-minor loss, and required king defense.

## Verification

The final watch-selection gate passed 112 domain JVM tests, seven wire JVM tests,
and ten application host tests. It built the Android APK, both iOS domain/app
architectures, both Web production distributions, and the JVM server. This
includes the corrected evaluator's 13 tests and equal-cap diagnostic comparison.
Both Web development distributions then built separately and included the worker.
The final production worker SHA-256 matches in both distributions:
`23d16f4a679c5fda314155f7df586ffc32afd164b56075a0e22737306efe24b0`.

Live development smoke evidence:

| Target/scenario | Observation | Limit |
| --- | --- | --- |
| Android Pixel 10 API 37.1, wire-integration control build | Watch reached 69 moves; Stop cleared thinking | Final pawn-candidate APK was installed and launched; its touch check was interrupted by automatic Mac lock |
| Final JS UI in Codex in-app browser | Watch reached 72 moves; Stop cleared thinking and later stayed at 72 | Not a Chrome performance or cancellation distribution |
| Final Wasm UI in Codex in-app browser | Watch progressed; Restart during search reset to zero; continued to 162; Stop worked | No frame/time/cancellation percentiles |
| JS UI, previous wire-v2 control build | Restart at 104 reset to zero; exit during search returned home | Smoke evidence, not exhaustive zero-stale-result acceptance |
| Direct worker from both final distributions | READY → PROGRESS depth 1 → MOVE depth 1; identity/evaluation version checked | Direct worker harness, distinct from adapter timeout/error injection |
| Direct worker termination after PROGRESS | Parent detached handler and terminated; completed intent retained; no final handled | Does not measure actual adapter hard-timeout races |
| Direct worker old schema request | Version 1 rejected by version 2 worker | App/worker assets must be deployed together |

The direct normal request had depth 1 / 20 evaluations at PROGRESS and depth 1 /
128 evaluations at the final response. Its interrupted next iteration was not
published as complete. Test pages and request fixtures are ignored build artifacts.
Screenshots and protocol records are in local working memory.

The final complete iOS Debug simulator app built, installed, and launched on
iPhone 18 Pro / iOS 27.0. Device Hub UI automation timed out after the owner
unlocked the Mac; gameplay and lifecycle interaction on iOS are not verified.
The physical Android phone is outside this emulator check. These checks do not
establish N01–N07 or release playing strength.

## Remaining acceptance gaps

Human versus two bots, per-seat profile/difficulty selection and save metadata,
replay diagnostics, tactical continuation, Paranoid comparison, the independent
rule corpus, the 200-position quality corpus, and the balanced release tournament
remain open. None of the three synthetic fixtures replaces the release corpus.

N01–N07 need representative release measurements and scenario evidence. Native
generation/reduction is not internally interruptible. Web postgame training
still reconstructs replay on the UI thread through the existing Default
dispatcher path; the search-worker integration alone does not make that path
responsive. Final release acceptance must address it and verify all platforms.

Reproduce the focused diagnostic comparison:

```sh
./gradlew :gameDomain:jvmTest \
  --tests 'com.chesstree.game.domain.bot.BotEvaluationModeTest' \
  --tests 'com.chesstree.game.domain.bot.BotEndgameProbeTest' \
  -Pkotlin.incremental=false --console=plain
```

Local working records retain exact commands, source fingerprints, and log pointers;
they are excluded from commits by default. Detailed independent review found
no remaining material source issue after the bonus-cap correction. That verdict
permits a development experiment and does not establish release playing strength.
