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

6. Localize every user-visible string through the shared Compose resource
   catalog. Keep the default locale and every supported translation in sync;
   do not add UI copy directly in composables, platform entry points, or only
   one locale. Check localized names, placeholders, accessibility labels, and
   newly added resources together.
7. For platform-specific UI, gate device capabilities on actual support and
   derive layout from the device's available dimensions or safe areas. Do not
   assume a feature exists on every device in a platform family or use one
   hard-coded size across materially different device models.

Do not add compatibility shims, duplicate UI, or a second Web target without a
documented browser/support requirement. Return the matrix, findings, changes if
requested, and unresolved target-specific checks.
