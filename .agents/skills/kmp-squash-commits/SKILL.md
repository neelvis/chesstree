---
name: kmp-squash-commits
description: Squash only commits created in the current ChessTree work session into one scoped commit. Use only when the user explicitly requests a session squash.
---

# KMP squash commits

History rewriting is destructive. Before doing it, identify the exact session base
and show the user the commits, proposed one-line production-focused message, and
squash method. Wait for explicit approval even if earlier work used an autonomous
mode.

- Include only commits created in this session; never rewrite older or unrelated
  history.
- Preserve uncommitted and user-owned changes. Do not push unless explicitly asked.
- Never include agent attribution or `Co-authored-by` trailers.
- If only one commit exists, rewrite it only when requested or its message violates
  the agreed rule.
- After approval, verify that exactly one replacement commit remains and that status
  matches the pre-squash working tree.

Return replaced commits, final hash/message, working-tree status, and any force-push
follow-up if the history had already been published.

