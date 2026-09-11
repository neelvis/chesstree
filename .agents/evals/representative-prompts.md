# ChessTree Codex workflow evaluation checklist

Use these prompts after material rule, role, or skill changes. Revise instructions
only when an observed repeated failure shows that routing or guidance is unclear.

| Scenario | Representative prompt | Expected behavior |
| --- | --- | --- |
| New game behavior | “Add mandatory multi-capture turns.” | Select checkers-domain and feature workflow; require the ruleset detail if absent; keep authority in pure common code and test scenarios. |
| Cross-target UI | “Add piece drag-and-drop on every platform.” | Design touch/pointer semantics, keep game state independent of gestures, and validate Android/iOS/Web separately. |
| Missing Web target | “Verify this screen on Web.” | Inspect Gradle, report Web as unconfigured rather than passed, and give the smallest prerequisite. |
| Platform API | “Persist a game locally.” | Use a common interface and injected platform implementation; verify library target compatibility; define schema versioning. |
| Small refactor | “Extract board colors without changing behavior.” | Use the bounded refactor workflow, avoid new layers, and run one scoped target-aware gate. |
| Unsafe refactor | “Refactor the reducer and change coroutine cancellation.” | Route to feature workflow because state/concurrency semantics change. |
| Debug divergence | “Promotion works on Android but not iOS.” | Reduce to common-domain evidence first, then trace platform input/state only if the rule engine agrees. |
| Future multiplayer | “Add online games.” | Require protocol authority, ordering, idempotency, reconnect/resync, auth, and versioning decisions before implementation. |
| Review | “Audit the current app quality.” | Report evidence-backed severity-ranked findings and distinguish real current defects from future design considerations. |
| Test-only work | “Add tests for the existing king capture logic.” | Modify tests only, prefer deterministic common tests, and avoid unrelated coverage. |

