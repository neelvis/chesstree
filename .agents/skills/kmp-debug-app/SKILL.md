---
name: kmp-debug-app
description: Diagnose ChessTree failures across shared Kotlin, Android, iOS, and Web using focused reproduction evidence. Use for crashes, incorrect game state, UI/input issues, lifecycle problems, build failures, or cross-platform divergence.
---

# KMP app debugging

Diagnose before changing production behavior.

1. Capture the failing target, environment/toolchain, reproduction steps, expected
   behavior, logs/stack trace, and whether other targets reproduce it.
2. Trace from the earliest relevant application frame or incorrect state transition.
   Separate domain, shared presentation, platform integration, build/tooling, and
   external-service ownership.
3. For game bugs, reduce the issue to an initial immutable board state plus action
   sequence and test the rules engine independently of rendering.
4. For UI bugs, distinguish authoritative game state from selection, pointer,
   animation, lifecycle, and rendering state.
5. Inspect only the path implied by evidence. Verify framework/toolchain behavior in
   current official documentation rather than relying on memory.
6. Reproduce with the smallest non-destructive check. Never request production
   credentials or private user data.
7. Implement a fix only when explicitly requested and only in the owning layer.

Return reproduction status, evidence, most likely owner, root cause and confidence,
affected file references, smallest fix or next diagnostic, cross-target impact, and
validation performed.

