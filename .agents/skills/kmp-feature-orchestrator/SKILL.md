---
name: kmp-feature-orchestrator
description: Coordinate a production-quality ChessTree feature across design, shared implementation, Android/iOS/Web validation, tests, and review. Use for new behavior or changes spanning layers, source sets, contracts, or state semantics.
---

# KMP feature orchestrator

Coordinate discovery, design, implementation, validation, and review without
repeating repository exploration. User scope and authorization take precedence.

## Subagent context

When spawning discovery, testing, or review agents, use `fork_turns: "none"` by
default. Provide a self-contained brief with the objective, owned files or
responsibility, constraints, acceptance criteria, and expected output. Never copy
the entire conversation into the brief. If conversation history is essential,
pass only the number of recent turns needed for that subtask.

## Route and protect the workspace

Use `kmp-refactoring-orchestrator` for a small behavior-preserving change with no
source-set, dependency, schema, public-contract, or concurrency impact.

Before edits, identify existing changed/untracked paths and treat them as user-owned.
Never reset, clean, stash, overwrite, or broadly stage them. Use one writer per file.
Do not commit or rewrite history without the required user authorization.

## 1. Discover and design once

Read root rules, current Gradle/source-set structure, and only relevant code/docs.
Establish:

- observable behavior and the exact checkers ruleset decisions involved;
- common versus platform responsibilities and dependency direction;
- affected Android, iOS, and Web entry points;
- stages, file ownership, acceptance criteria, tests, validation, and risks;
- external API/schema semantics when applicable, including authority and versioning.

Before inventing a coordinate table, notation, serialization format, protocol
mapping, or other durable representation, locate the repository's authoritative
tests, reference assets, and existing anchors and reconcile them. If a requested
format loses state needed for deterministic restore, special moves, or future
compatibility, surface that limitation before implementation rather than rebuilding
the codec later.

Use `kmp-design-feature` semantics. Present the plan before implementation unless
the user explicitly authorized end-to-end execution. A material rules, security,
protocol, or product ambiguity blocks only the affected slice; ask for the minimum
missing decision rather than guessing.

## 2. Implement in coherent slices

Keep adjacent implementation, tests, corrections, and documentation in one context
when practical. Delegate only independent substantial work, and pass bounded file
ownership and acceptance criteria.

For each slice:

1. Make the minimum production change following the shared/platform boundaries in
   `../../references/kmp-architecture.md`.
2. Add behavior-focused tests, including common domain/state tests and platform
   tests only where the boundary requires them.
3. Inspect the diff and run affected tests plus necessary compilation for every
   affected configured required target.
4. Fix failures inside approved scope and rerun only invalidated checks.
5. Update existing docs only when behavior, commands, architecture, or contracts
   became inaccurate.

Do not claim Web, iOS, or Android support based on another target. If a required
target is not configured or cannot be built on the host, report the exact gap and
the smallest remaining validation step.

## 3. Review and complete

Perform a distinct final review against acceptance criteria. Use an independent
reviewer when the change affects authentication/privacy, persistence migration,
real-time protocol, shared public APIs, concurrency/state integrity, destructive
behavior, or repeated unexplained failures. Review the changed surface, not the
whole repository.

Start an independent review only after the intended diff is stable. Give the
reviewer a read-only snapshot and existing validation results; do not ask it to run
the full matrix again unless independent reproduction is material to a finding. If
the user changes the contract during review, stop or defer that review until the new
contract is implemented.

Use `../../references/validation-matrix.md` for the final gate. Material unresolved
findings or failed required checks prevent a success claim.

Return delivered behavior, changed files/commits, tests and compile results per
target, review findings and resolutions, documentation impact, preserved user
changes, and exact remaining manual work.
