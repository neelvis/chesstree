# Certified horizon reuse: focused prototype source review

## Scope and result

Reviewed the stable diff against `b68e363` for `BoundedMaxNBot.kt`,
`BotSearchCache.kt`, and `BotSearchDiagnostics.kt`, with focused inspection of
`BotCertifiedHorizonReuseTest`, `BotSearchCacheHorizonAliasTest`, existing cache
tests, and the probe's variant/cache setup. Applied the earlier
[design proof](proof.md). No production/test edits or builds were performed.

**No material actionable correctness findings.** The reviewed implementation
satisfies the certificate separation and admission guards in the proof. This is
not performance acceptance or a stronger-play claim. The coordinator-reported
focused-test run had one invalid test premise under correction; no passing-suite
claim is made by this reviewer.

Reviewed source SHA-256:

- BoundedMaxNBot: `25f243d87b7c88f2182e2be8ad5bd0932a474b587fe974dc8d0aa0098a81162e`
- BotSearchCache: `f642e30a2ba7ffe247b4b6f3f6e3ef8bf8c1e7870ed80b9553d14ecefc173d21`
- BotSearchDiagnostics: `f7bc7ad20fea2752d66d47906ac2c1ebcd9cddaba9a18f9fbc9e7ef83e0e20e4`

## Findings checked

### Certificate and CHECKS separation

`actualResponders` (BoundedMaxNBot lines 426–427) canonicalizes CHECKS gates,
ordinary/tactical keys and cutoff diagnostics to the original empty set. Virtual
responders are propagated through the actual transition calls when certification
is enabled, including the root transition. `respondersAfter` retains the existing
active-player/capture/promotion/quiet semantics. CHECKS therefore does not acquire
EXCHANGES expansion accidentally.

`cachedScores` (429–435) unconditionally taints certification on every CHECKS
exact search hit while the candidate is enabled. It does not reuse a score as
proof of the omitted EXCHANGES tree, even when incoming virtual contexts differ.
Normal CHECKS cache keys can consequently remain unchanged.

The unchecked positive-remaining frontier branch (549–559) records divergence
before calling `staticScores`, so an evaluation-cache hit cannot hide a required
EXCHANGES continuation. Remaining-zero checked nodes still return null, and
remaining-zero unchecked/terminal/disabled-continuation leaves remain the same
bounded leaves in both horizons.

### Completion, windows and vectors

Both eligible call wrappers snapshot cutoffs and uncertified state before their
recursive work. Null results return before `rememberExact`. Its admission guard
(446–454) requires no descendant cutoff, the existing successful global stop
check, CHECKS mode and unchanged uncertainty counter. The alias key contains the
entry virtual responders, not the canonical CHECKS empty set, while retaining
all other context fields.

With no descendant cutoff and no divergent/unknown leaf, the two finite trees
are equal, so MAX_N vectors and Paranoid vectors have exact score/tie semantics.
No new window key is required for that exact value. Root repetition, profile and
repertoire decisions remain outside the cached vectors and are not aliased.
Existing root completion/cancellation/retry logic is untouched.

The existing single cache-admission stop-probe call remains single. Alias
admission adds no second global probe, counter reset or resource allowance.

### Cache ownership, provenance and copies

`SearchEntry` holds scores and provenance in the existing bounded partitions.
Ordinary exact, tactical exact, evaluation and transition keys remain distinct.
Both insertion and lookup defensively copy score arrays; root score mutation
cannot alter the stored CHECKS or EXCHANGES values. Replacement moves provenance
with the entry; eviction, obsolete-ply removal and `clear` retain boundedness and
existing ownership. Configuration still separates model, root actor, evaluator,
policy and tactical depth, while full state equality preserves rights/status/
controller/ply context.

`rememberSearch` now reports actual admission. The caller counts a certificate
only when alias admission returns true, including completed-partition rejection
of shallower work. Alias-hit diagnostics run only when an existing tagged entry
actually supplies a successful exact lookup, not on attempted insertion.

### Experimental flag and warm caches

Off disables new certification but can read compatible aliases previously stored
by an enabled request. This is mathematically safe exact-score reuse, not an
uncertified cross-flag conversion. The probe explicitly documents this behavior
and constructs a new cache per case/repeat/variant, so its off/on comparison is
not contaminated by on-primed aliases. Existing warm/switch tests compare
completed decisions under this declared admission-only switch semantics.

Do not describe this switch as disabling all alias reads. A future experiment
requiring that stronger meaning must reject tagged reads or clear/isolate caches.
No change is needed for the currently documented protocol.

## Acceptance and validation limits

Focused tests cover cache copying, provenance replacement, collision/context
separation, tiny bounds, clear/obsolete-ply handling, multiple seats/models/modes,
plain CHECKS cache uncertainty, real responders, captures/promotions/transfer,
interruptions and shared limits. The known failing test premise is outside the
reviewed production logic and remains with its owner.

Final acceptance should compare useful analysis and time against the committed
baseline as well as prototype off/on: `SearchEntry` adds a wrapper allocation
for ordinary search entries even with new certification off. That is a measured
performance consideration, not a demonstrated defect. Decline/reject the
candidate if hashing, alias insertion or eviction erases useful benefit.

This review did not run tests, platform/browser checks, or inspect unrelated
working-tree changes. Passing gates and frozen corpus/Strong/browser evidence on
the actual accepted source remain coordinator responsibilities. Source changes
to any guarded path invalidate this review to the extent of the changed logic.

## Final evidence and restoration review

A final read-only review independently recomputed all 108 paired rows and matched
all grouped totals to summary.json. All 54 on/off pairs retained the same move,
depth, horizon, source and stop. The 65,726→65,798 ms comparison, alias-hit split
(reported 2 / synthetic 366 / held-out 0), promotion evaluation/eviction increase,
and unchanged army-transfer work reconcile with the raw rows. The rejection is
supported; no wider strength or committed-baseline speed claim was inferred.

The archived patch checksum matches validation.json. Module diffs against
b68e363 are empty for gameDomain, botWire, botWorker and composeApp; candidate-only
tests are absent. The report distinguishes corrected test expectations, restored
Gradle-cache evidence and unrun prototype platforms/timed checks. No material
documentation or decision issue was found. No builds or edits were performed by
the reviewer.
