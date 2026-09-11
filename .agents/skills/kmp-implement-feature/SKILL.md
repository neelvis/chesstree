---
name: kmp-implement-feature
description: Implement an approved ChessTree Kotlin Multiplatform feature in a bounded stage. Use when a design and acceptance criteria already exist; do not use for unplanned cross-target architecture changes.
---

# KMP feature implementation

Implement only the approved stage and preserve user-owned changes.

- Re-read the accepted behavior, file scope, and platform matrix before editing.
- Put portable domain, state, data contracts, and Compose UI in common code; keep
  entry points and platform APIs thin and injected.
- Follow `../../references/kmp-architecture.md` for new boundaries or dependencies.
- For game behavior, make the pure rules engine authoritative and keep transient UI
  and animation state separate.
- Do not introduce auth, networking, persistence, multiplayer, modules, or generic
  abstractions unless they are part of the approved stage.
- Add no new dependency until target support and version compatibility are verified
  in official documentation.
- Inspect the scoped diff and run the narrowest checks that validate the stage on
  every affected configured target. Use `kmp-add-tests` separately when the user
  asked for a test-only phase; otherwise add tests required by the approved stage.
- Do not commit unless the user requested or already approved a commit.
- Never add agent attribution or `Co-authored-by` trailers.

Return changed files, delivered behavior, validation evidence per target, and any
remaining manual or unconfigured-target checks.

