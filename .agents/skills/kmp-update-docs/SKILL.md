---
name: kmp-update-docs
description: Update existing ChessTree KMP documentation after code and tests are complete. Use only for behavior, architecture, setup, target, or contract sections made inaccurate by the change.
---

# KMP documentation update

Implementation and tests are already complete. Inspect the scoped diff and update
only documentation made inaccurate by it.

- Prefer editing existing canonical docs over creating parallel explanations.
- Keep target names, prerequisites, run/test commands, source-set ownership, and
  support status exact. Never document an unconfigured or unverified target as done.
- Explain material KMP decisions for a reader new to multiplatform development.
- Document chosen checkers variant semantics when behavior depends on them.
- For persistence/network/auth changes, document authoritative contracts, schema or
  protocol versioning, security boundary, and migrations that actually exist.
- Do not rewrite unrelated wording, add speculative roadmaps, or modify code/tests.

Return sections changed, rationale, and remaining documentation gaps.

