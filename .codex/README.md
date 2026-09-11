# Codex project setup

Codex reads repository instructions from `AGENTS.md`, discovers workflow skills in
`.agents/skills`, and loads optional worker roles from `.codex/agents`.

This project intentionally has no Claude or Cursor adapters. Change canonical skills
under `.agents/skills`; do not mirror them into `.codex/skills`.

Representative invocations:

- `Use kmp-feature-orchestrator to add ...`
- `Use checkers-domain to review ...`
- `Use kmp-verify-change on the current diff.`

The Context7 integration is optional and reads `CONTEXT7_API_KEY` from the
environment. Never store the key in this repository.

