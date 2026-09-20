# Cross-platform validation matrix

Read this reference when planning tests or validating a feature. Derive actual task
names from the current Gradle model; never guess a passing command.

## Execution discipline

- During implementation, run only the smallest reproducer or focused test that
  exercises the current edit. Run the complete affected-target matrix once the
  diff and acceptance criteria are stable.
- After a correction, rerun only checks invalidated by that correction. Do not
  repeat an already-passing full matrix for documentation-only or unrelated edits.
- Prefer quiet/plain build output and bounded tool output. Return summaries on
  success and retain detailed logs only for failures; large successful logs become
  unnecessary model input on later turns.
- For long Gradle, Xcode, browser, or simulator jobs, wait 30-60 seconds between
  status polls unless the process reports progress or needs input. Avoid repeated
  short polls that create agent turns without new evidence.
- Assign build execution to one owner. A reviewer should consume recorded results
  and run an additional check only when a finding needs independent reproduction.

## Shared domain and state

- Run focused `commonTest`-backed tests on an available concrete target.
- Compile every affected required target.
- Verify deterministic state transitions, cancellation, serialization, boundary
  mapping, and error paths relevant to the change.

## Android

- Compile the affected Android variant and run focused unit tests.
- Use instrumentation/UI tests only for behavior requiring Android runtime evidence.
- Manually cover lifecycle restoration, back behavior, touch input, density, and
  accessibility when relevant.

## iOS

- Compile the Kotlin framework for an available simulator/device target.
- Build the Xcode scheme when host/tooling permits.
- Manually cover app lifecycle, safe areas, touch input, dynamic type/accessibility,
  and Swift/Kotlin boundary behavior when relevant.

## Web

- Compile and bundle the configured browser target (normally `wasmJs` for shared
  Compose UI).
- Run browser tests when configured.
- Manually cover supported browsers, viewport resize, pointer/keyboard input,
  focus, refresh/deep-link behavior, accessibility, loading, and asset delivery.

## Shared graphical board

- Check board geometry at narrow, wide, portrait, and landscape sizes.
- Check both player orientations and unambiguous coordinates.
- Check selection, legal-target indication, captures/chains, promotion, terminal
  state, input lock during transitions, and restoration after recreation/refresh.
- Verify keyboard/pointer and touch paths without making animation timing part of
  the game rules.

Report each required target as passed, failed, or not run with the exact reason.
Compilation on one target is never evidence for another.
