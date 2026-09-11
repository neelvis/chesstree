---
name: checkers-domain
description: Design, implement, debug, or review ChessTree checkers rules and game-state transitions in deterministic common Kotlin. Use whenever work affects legal moves, captures, kings, promotion, turns, results, replay, or synchronization.
---

# Checkers domain

First identify the exact ruleset. If the repository and request do not settle a
material rule, ask only for that product decision and do not encode a guess.

Keep the engine pure and platform-independent:

- immutable board and game state;
- explicit coordinate, piece, side, move/capture path, result, and rule-variant types;
- one authoritative legal-move/state-transition path used by UI, persistence, replay,
  and future networking;
- deterministic behavior with injected clock/randomness if ever needed;
- no Compose, platform input, animation, storage, or transport types in the domain.

Preserve invariants: only legal pieces occupy playable squares; turn ownership is
explicit; illegal actions cannot mutate state; capture chains and promotion follow
the selected variant; terminal/draw outcomes are reproducible.

Use table-driven and scenario tests for initial setup, ordinary moves, mandatory and
multi-captures, competing capture paths, kings, promotion timing, blocked/no-piece
endings, draw rules, invalid coordinates/actions, and serialization/replay when in
scope. Prefer small board fixtures that make the rule under test obvious.

UI selection and animation may derive from domain state but never override it.
Future servers must revalidate commands and remain authoritative; client state must
support ordered replay/resynchronization without identity based on object references.

