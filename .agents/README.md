# ChessTree Codex skill set

This directory is the project-local source of Codex skills. Codex discovers skills
directly from `.agents/skills` and applies repository rules from root `AGENTS.md`.

This setup is intentionally Codex-only. It does not generate `.claude` or `.cursor`
files and has no vendor synchronization layer.

## Layout

```text
.agents/
  skills/<name>/SKILL.md    Codex workflows
  references/               Shared conditional guidance
  evals/                    Representative behavior checks
.codex/
  agents/*.toml             Optional Codex worker roles
  config.toml               Project-local Codex configuration
```

When changing a skill, edit its canonical file under `.agents/skills`, run the
skill-creator validator, and check that the representative prompts still route to
the intended workflow.

