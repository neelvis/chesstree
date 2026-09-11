---
name: kmp-platform-parity
description: Assess or restore behavioral parity for a ChessTree feature across Android, iOS, and Web. Use when a shared feature behaves differently, a target is newly added, or platform support needs an evidence-based audit.
---

# KMP platform parity

Treat parity as shared product semantics with intentional platform interaction
differences, not pixel identity.

1. Define a compact behavior matrix: domain result, visible states, inputs,
   lifecycle/restore, accessibility, error handling, and packaging/deep-link needs.
2. Establish the common implementation and each platform entry point from code and
   tests. Do not assume a target works because code resides in `commonMain`.
3. Classify differences as intentional, missing implementation, framework constraint,
   defect, or not yet configured. Cite evidence.
4. Put fixes in the lowest correct shared layer. Keep genuine platform differences
   behind narrow injected boundaries; avoid copying whole feature implementations.
5. Validate with `../../references/validation-matrix.md` and report each target
   separately.

Do not add compatibility shims, duplicate UI, or a second Web target without a
documented browser/support requirement. Return the matrix, findings, changes if
requested, and unresolved target-specific checks.

