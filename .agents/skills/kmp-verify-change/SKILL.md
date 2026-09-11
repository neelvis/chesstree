---
name: kmp-verify-change
description: Validate a scoped ChessTree Kotlin Multiplatform change with target-aware compile, test, static, and manual checks. Use before declaring Android, iOS, or Web behavior complete.
---

# KMP change verification

Do not modify production behavior or widen into a repository-wide test run.

1. Read root rules and inspect the diff to identify affected source sets, consumers,
   behavior, and required targets.
2. Inspect Gradle tasks and choose the narrowest real tasks; never invent commands.
3. Apply `../../references/validation-matrix.md`. Compile every affected configured
   required target and run focused tests/static checks.
4. For shared UI, check responsive geometry, touch/pointer/keyboard input, lifecycle
   restoration, accessibility, and intentional platform differences as relevant.
5. For game logic, validate the chosen ruleset and deterministic state transitions.
6. Report unavailable host tools or an unconfigured target as not run, not passed.

Return acceptance criteria, exact commands and results, passed/failed/not-run status
for Android/iOS/Web, manual checks, essential failure evidence, and remaining risk.

