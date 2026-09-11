---
name: update-skill
description: Create or update ChessTree's project-local Codex skills under .agents/skills and validate them. Use for this repository's agent instructions; do not generate Claude or Cursor adapters.
---

# Update ChessTree Codex skills

`.agents/skills` is the canonical Codex skill directory for this repository.

1. Read `/Users/k/.codex/skills/.system/skill-creator/SKILL.md` and follow it.
2. Edit only the requested project skills and directly related shared references,
   role definitions, or root `AGENTS.md`.
3. Keep descriptions discriminating and put conditional detail in `.agents/references`.
4. Do not create `.claude`, `.cursor`, or mirrored `.codex/skills` trees.
5. Validate every new or changed skill with the skill-creator `quick_validate.py`.
6. Review `.agents/evals/representative-prompts.md` and update it only when routing
   expectations genuinely changed.

Return canonical paths changed, validation results, and any behavior that still
needs a live forward test.

