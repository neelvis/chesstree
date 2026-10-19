# Stage 1 independent review

Two independent delegated source/evidence reviews reported no material actionable
correctness findings in the active context-reuse slice. Reviews were read-only;
all builds and measurements were serialized by the coordinator.

## Scope and identity

Base commit: `22005a6bb8b1eadf1d4599af4092d428e157c2e6`. The tree contains uncommitted
Stage 0 diagnostics/fixtures plus this Stage 1 change and unrelated owner work.
The final [paired source manifest](measurements/fixed-context-paired.tsv) identifies
the exact bot/rules/probe source bytes reviewed and measured.

Active production review scope:

- `BotKingAttackContext.kt`: lazy empty/nonempty attacker-set caching, exact-state
  ownership and authoritative frozen/controller attack rules.
- `BotEvaluationMode.kt` and `MaxNBot.kt`: identical scores and score-addition order,
  terminal behavior, identity rejection and unchanged context-off control.
- `BoundedMaxNBot.kt`: frontier guards, active-player check classification,
  mandatory checked-frontier cap, stop-call placement, bounded cache admission,
  complete-root publication and public/internal path equality.
- `BotKingAttackContextTest.kt` and `BoundedBotKingAttackReuseTest.kt`: mode/policy
  equivalence, controller changes/frozen kings, misbound contexts, both models,
  cache on/off, local caps, cancellation/timeout and completed callbacks.

No cross-state/global attack cache, public setting, dependency, wire-version or
worker-lifecycle change was introduced. Common production code has no JVM API.
The direct-check ordering candidate was removed and retained only as an
experimental patch. Review found no change to check-evasion coverage or tie policy.

## Acceptance evidence review

The second reviewer independently matched all 108 fixed-work rows against Stage
0 and the two context variants, including result/work/attempt/cache fields.
The measured elapsed reduction supports modest overhead savings only.

The reviewer also checked reported exchange/promotion cap witnesses and the
ordinary queen/promotion continuation tests. It confirmed that unresolved checks
at the tactical cap and later global exhaustion explain retained CHECKS results.
A conditional legal queen-preserving line does not prove a unique best move or
forced win. The promotion line exchanges the original pawn for Red's queen,
without proving a placement improvement. No improved tactical reliability,
tournament strength or conversion claim is justified by this slice.

## Limits

These reviews did not independently rerun builds or performance trials. Their
source review does not establish runtime/browser/mobile behavior; recorded target
and Chrome results are summarized separately in the Stage 1 report. Unrelated
deployment/server changes, the fast-opening plan, broader Stage 0 additions and
the rejected experimental patch are outside the final active-code review scope.
