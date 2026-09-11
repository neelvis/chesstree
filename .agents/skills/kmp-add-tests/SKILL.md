---
name: kmp-add-tests
description: Add focused tests for an already implemented ChessTree KMP change without broadening production behavior. Use for shared game/domain/state tests or necessary Android, iOS, and Web boundary tests.
---

# KMP add tests

The behavior already exists. Add only tests needed to protect it.

- Start with observable behavior and risk, not implementation structure.
- Prefer deterministic common tests for checkers rules, reducers/state holders,
  serialization, mapping, and use cases.
- Add platform tests only when lifecycle, interop, storage, transport, browser, or
  input behavior cannot be proven in common tests.
- Use fakes through existing interfaces; do not add production-only seams or a new
  mocking framework without a demonstrated need.
- A minimal testability refactor is allowed only when behavior remains identical and
  the reason is explicit.
- Cover relevant success, boundary, failure, cancellation, stale-event, and restore
  paths. For rules, include illegal moves and invariants, not only happy paths.
- Do not increase unrelated coverage or update documentation.
- Run only added/modified tests plus the compilation needed to prove their source
  sets and consumers remain valid.

Return behavior covered, test files, commands/results by target, and high-risk
manual scenarios that remain.

