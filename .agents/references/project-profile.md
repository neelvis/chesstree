# ChessTree KMP project profile

Use the user-level software-delivery skills for generic workflow.

## Product and targets

- Kotlin Multiplatform three-player chess application with local and online play.
- Required product targets are Android, iOS, and Web. The server/domain have JVM
  targets; there is no configured desktop Compose application.
- Read [kmp-architecture.md](kmp-architecture.md) when work changes source-set
  ownership, shared/platform boundaries, dependencies, or feature structure.
- Use the documented three-player ruleset whenever work affects legal moves,
  captures, kings, promotion, turns, results, replay, or synchronization.
- Use the project-local `kmp-platform-parity` for a dedicated parity audit.

## Validation

- Read [validation-matrix.md](validation-matrix.md) for planning or verification.
- Derive actual Gradle tasks from the current model; never guess commands.
- Compile every affected configured required target. Common tests do not prove
  platform entry points, lifecycle, input, accessibility, interop, or packaging.
- Report unavailable host tools or unconfigured targets as not run with the exact
  remaining manual check.

## Documentation

Keep target names, prerequisites, source-set ownership, run/test commands, and
support status exact. Explain material KMP decisions in plain language.
