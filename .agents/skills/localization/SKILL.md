---
name: localization
description: Add or update user-visible text in ChessTree and keep the shared resource catalog translated across supported locales.
---

# ChessTree localization

Use this skill whenever changing user-visible wording, accessibility labels, or
locale-specific presentation.

- Store UI strings in `composeApp/src/commonMain/composeResources/values/strings.xml`
  and provide matching entries in every supported locale directory, currently
  `values-en` and `values-de`.
- Treat the default `values` catalog as Russian. Keep resource names and format
  placeholders consistent across catalogs, and translate the meaning naturally
  rather than copying Russian wording into other locales.
- Before finishing, check the changed resource key across all locale files and
  search the changed UI for newly hard-coded text. Include accessibility-facing
  labels and descriptions in the same review.
- Do not change the app's locale-selection behavior or translate domain data
  unless the request includes that scope.
