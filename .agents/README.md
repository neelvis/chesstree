# ChessTree Codex project configuration

Reusable workflows come from `/Users/k/Documents/Codex/codex-skills`. This
directory contains only ChessTree-specific skills and references; Codex also
applies repository rules from root `AGENTS.md`.

Do not copy generic delivery, debugging, testing, review, documentation, or commit
skills back into this repository.

## Layout

```text
.agents/
  skills/<name>/SKILL.md    ChessTree/checkers-specific workflows only
  references/               Project profile and KMP-specific guidance
  evals/                    Representative behavior checks
.codex/
  agents/*.toml             Optional Codex worker roles
  config.toml               Project-local Codex configuration
```

When changing a local domain skill, run the skill-creator validator. Change common
workflows in `/Users/k/Documents/Codex/codex-skills` and check that repository
routing still resolves through `references/project-profile.md`.
