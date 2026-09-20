---
name: kmp-repo-review
description: Review ChessTree KMP code for correctness, security, cross-platform architecture, game-rule integrity, maintainability, performance, and testing gaps. Use for repository-wide or scoped code review without implementation.
---

# KMP repository review

Review as a senior Kotlin Multiplatform and graphical game engineer. Respect the
requested scope and do not modify files.

Review a stable diff or named revision. If the reviewed files change materially
during the pass, stop and request a stable snapshot instead of repeatedly rereading
the worktree. Reuse recorded build/test evidence and do not run broad validation
unless the user requested it or a specific finding needs reproduction. Keep search,
file reads, and command output bounded to evidence relevant to the scoped review.

Prioritize:

1. Incorrect checkers rules, invalid state transitions, and data loss/corruption.
2. Authentication/authorization, secret storage, input validation, transport trust,
   schema/versioning, and future multiplayer authority risks where code exists.
3. Coroutine cancellation, races, lifecycle ownership, freezes/leaks, and stale UI.
4. Source-set leaks, unsupported dependencies/APIs, target divergence, broken
   interop, and false cross-platform claims.
5. Compose state/performance, responsive input/accessibility, maintainability, and
   missing behavior tests.

Verify findings against code and current official APIs. Do not report speculative
future features as present bugs; label architectural constraints only when current
code creates a concrete cost or unsafe boundary.

List actionable findings by Critical, High, Medium, then Low. Each finding must have
affected files/lines, evidence and impact, a concrete remediation, and the missing
test where applicable. State reviewed and unreviewed scope and explicitly say when
no material findings exist.
