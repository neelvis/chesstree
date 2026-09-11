---
name: kmp-bugfix-orchestrator
description: Coordinate an evidence-driven ChessTree bug fix through diagnosis, minimal implementation, regression tests, cross-target validation, and review. Use for crashes, incorrect game behavior, build failures, or Android/iOS/Web regressions that should be fixed end to end.
---

# KMP bug-fix orchestrator

Fix the demonstrated defect without redesigning unrelated behavior.

## 1. Evidence and diagnosis

Use `kmp-debug-app` semantics. Capture the failing target, expected/actual behavior,
minimal reproduction, environment, logs/trace, and relevant recent changes. Determine
whether ownership is checkers domain, shared presentation/data, a platform boundary,
build/tooling, or an external service.

For game defects, reduce the report to an immutable initial state and action sequence.
For cross-platform divergence, establish whether the shared engine/state already
differs before investigating rendering or input. State the root-cause hypothesis,
confidence, smallest fix, regression test, affected targets, and validation plan.
Do not edit until evidence supports a responsible fix. Ask for missing information
only when the gap materially changes behavior or ownership.

## 2. Scoped implementation

Preserve user-owned changes and modify the lowest correct owning layer. Do not add
platform forks to mask a shared defect or change shared semantics to hide one
platform's integration bug. Avoid dependency upgrades and unrelated cleanup.

Add a regression test that fails for the demonstrated cause and passes with the fix.
Prefer common deterministic tests for game/domain/state defects and focused platform
tests for genuine entry-point, lifecycle, interop, browser, or input defects.

## 3. Verification and review

Inspect the fix diff separately and validate with
`../../references/validation-matrix.md`. Run the reproducer/regression test and
compile every affected configured required target. When a target cannot run, name
the remaining manual reproduction precisely.

Use an independent review for authentication/privacy, persistence/data loss,
real-time ordering, concurrency/state integrity, public contracts, or repeated
unexplained failures. Failed required checks or material unresolved findings prevent
a success claim.

Return root cause and evidence, files changed, regression coverage, exact results by
target, review outcome, preserved user changes, and remaining risk. Do not commit
unless authorized and never add agent attribution trailers.
