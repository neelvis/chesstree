---
name: kmp-refactoring-orchestrator
description: Deliver a bounded behavior-preserving ChessTree KMP refactor with focused tests and cross-target validation. Use only when contracts, dependencies, source-set ownership, schemas, and state/concurrency semantics remain unchanged.
---

# KMP refactoring orchestrator

Use this low-overhead workflow only when observable behavior is preserved, the work
has settled interfaces, and it does not alter dependencies, Gradle targets, public
contracts, persistence/wire schemas, authentication, navigation, or concurrency and
state-machine semantics. Otherwise route to `kmp-feature-orchestrator`.

1. Record affected files, user-owned changes, explicit equivalence criteria, and
   the target/source-set validation scope.
2. Use one implementation context and the minimum edits. Do not move code to
   `commonMain` merely to raise a sharing metric; dependency and behavior must be
   valid on every consumer.
3. Keep or add focused tests that assert observable equivalence, not the new
   structure. Documentation is a no-op unless existing docs became inaccurate.
4. Inspect the diff separately, then run one affected final gate using
   `../../references/validation-matrix.md`.
5. Fix findings in the same context and rerun only invalidated checks.

Stop and hand off to the feature workflow if a supposedly structural change exposes
a rules, platform, lifecycle, protocol, persistence, or state-semantic decision.

Return preserved behavior, files changed, validation by target, review coverage,
documentation impact, and remaining manual QA.
