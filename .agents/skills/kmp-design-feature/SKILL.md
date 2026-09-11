---
name: kmp-design-feature
description: Design a Kotlin Multiplatform feature for ChessTree before implementation. Use for a staged plan covering shared code and Android, iOS, and Web impact without changing files.
---

# KMP feature design

Design only; do not modify production, test, configuration, or documentation files.

1. Read root `AGENTS.md`. Inspect the current Gradle targets and only the feature's
   relevant source sets, callers, tests, and documentation.
2. Define observable behavior and unresolved product rules first. For gameplay,
   make the checkers ruleset explicit rather than guessing variant semantics.
3. Place responsibilities using `../../references/kmp-architecture.md`. Identify
   what belongs in common code and every necessary platform boundary.
4. Produce small ordered stages with expected files, contracts, migration/compatibility
   impact, acceptance criteria, tests, and target-specific validation.
5. Call out current repository gaps such as an unconfigured Web target. Do not hide
   prerequisite work inside a feature stage.
6. Avoid speculative auth/multiplayer infrastructure, premature modules, and
   dependencies without a concrete need.

Keep the plan concise enough to review. Explain material KMP trade-offs in plain
language and stop before implementation.

