# Certified horizon reuse: independent design and proof review

## Scope and conclusion

Read-only review of the proposed candidate against committed bot baseline
`b68e363`, the current core/cache implementation, and accepted BOT-SP01–04 /
BOT-QF01–05. No production files, tests or builds were changed/run.

**No mathematical blocker found**, provided the separation and guards below are
implemented. This approves the bounded experiment design, not an unseen
implementation, its performance, or a playing-strength claim.

## State and certificate invariants

Carry virtual EXCHANGES responders through every actual transition in CHECKS.
Use the existing rule: capture/promotion resets to other active actors; quiet
moves remove their actor and intersect with active actors. This must include the
root transition, normal search transitions, checked tactical transitions, army
transfer and elimination. The virtual set is hypothetical *metadata* about the
EXCHANGES gate; it never represents a hypothetical move/pass.

Actual CHECKS expansion, CHECKS ordinary/tactical cache keys, and CHECKS cutoff
diagnostics continue to use empty responders. A CHECKS score is independent of
the virtual set, so keeping existing empty-responder score keys is sound.
Certification is context-dependent: any CHECKS exact search-cache hit increments
an `uncertifiedCounter` and directly returns its normal score without publishing
a fresh alias for that call. An enclosing candidate whose counter changed cannot
certify. This prevents a score found under one virtual set from proving a tree
under another virtual set, without changing ordinary CHECKS cache keys.

At unchecked CHECKS leaves with positive remaining tactical depth, increment the
same counter if EXCHANGES would expand: virtual responders are nonempty, or
`allowNewExchange` is true and a legal capture/promotion exists. Increment before
calling `staticScores`, including when that function hits its evaluation cache.
Evaluation-cache hits are otherwise safe: unlike a skipped search subtree, a
classified static leaf has already established its horizon equivalence.

A terminal state, disabled tactical continuation, or unchecked remaining-zero
frontier is the same bounded leaf in both horizons. A checked remaining-zero
frontier aborts both and can never certify. This describes current bounded
semantics; it does not newly establish a complete response cycle at every static
leaf.

Snapshot both `cutoffs` and `uncertifiedCounter` at each eligible call before its
recursive work. Admit an EXCHANGES alias only after a non-null result, unchanged
counters, and the existing successful global-stop/admission guard. A null or
failed/aborted parent publishes no alias. Completed descendants may already have
admitted valid exact aliases; their incomplete parent never gains that authority.

## Inductive proof

For an unchecked positive-remaining frontier, the new counter identifies exactly
the two conditions under which EXCHANGES extends and CHECKS stops. If neither
condition holds, both return the same static score from the same state. Terminal,
disabled and unchecked zero-remaining leaves also agree. A checked frontier has
the same all-legal ordered moves in both horizons and the same remaining-depth
transition. Normal nodes likewise have the same ordered moves and depth update.

Therefore, by induction over normal and tactical remaining depth, a fully
searched CHECKS subtree with no divergent/unknown descendant has the same legal
transition tree, actors, terminal rankings, evaluations and tie order as EXCHANGES
with the entry virtual responder context. Existing deterministic ordering is
horizon independent. No legal evasion or intermediate actor is removed.

MAX_N consequently returns the same complete vector. In Paranoid, unchanged
`cutoffs` means neither this node nor any visited descendant pruned a legal
continuation. All children were resolved, so its full vector follows the same
root-component optimization and tie choices as the equivalent EXCHANGES tree.
Exact cached children are valid vectors, but unknown CHECKS cache hits deliberately
block certification because they lack a tree-shape proof. Thus an admitted alias
is exact rather than a lower/upper bound. Its entry alpha/beta window need not be
part of the key. Changing the window later cannot invalidate an exact vector;
it can only make the saved exact work more useful.

This proof would fail if aliases were admitted after any descendant cutoff, if
unknown CHECKS cache hits were ignored, or if the candidate also changed move
ordering/selection. Those are excluded from this experiment.

## Key, cache and switch requirements

- The alias key changes the horizon to EXCHANGES and preserves the entry full
  state, configuration/root actor/model, normal depth or tactical remaining,
  `allowNewExchange`, and **entry virtual responders**. Do not copy the canonical
  empty CHECKS responders into it when virtual responses are pending.
- Never alias root decisions, repetition penalties, profile preferences or
  opening-book influence. Ordinary root score mutation remains after exact
  score retrieval; arrays must be copied on both admission and retrieval.
- No extra exact search entries for trivial static frontier leaves. Normal and
  live tactical calls already eligible for exact storage can certify; an
  unchecked early-return frontier cannot start flooding the search cache.
- Store alias provenance on the exact score entry in the existing bounded
  partitions. No separate retained history/table. Count an alias hit only when
  that tagged entry actually supplies returned scores, not merely on admission.
  `clear`, eviction/replacement and game switching must handle the tagged entry
  exactly as ordinary exact entries. Transitions/evaluations keep their types.
- The experimental flag's semantics must be explicit. If off is meant to match
  an uncontaminated baseline even after an on request, it must decline tagged
  alias reads as well as new alias writes, or tests/measurements must use cleared
  separate caches. Reusing a mathematically exact alias is safe, but otherwise
  an off/on warm ablation can measure the same aliases on both sides.
- Use the existing admission stop check once; do not add an unconditional second
  stop-probe call merely for alias admission. Time/cancellation/global budgets
  remain shared. Aliases may reduce actual counted work and permit an additional
  completed attempt, but they may never promote partial root work.
- Keep original plain exact entries readable without a certificate. A CHECKS hit
  from such an entry is safe for its ordinary score and must taint certification.

## Required regression emphasis

1. Root capture/quiet third actor/late recapture: CHECKS still stops as before and
   diagnostics still show empty responders; virtual pending responders prevent
   an unsafe alias at an unchecked positive-remaining leaf.
2. Same CHECKS key visited with different virtual responder sets: cache hit
   blocks the enclosing certificate; a proven alias for context A is not reused
   as proof for B. Include a pre-populated plain CHECKS cache.
3. Divergent CHECKS leaf served by an evaluation-cache hit: difference is still
   recorded, so that hit cannot hide a required EXCHANGES continuation.
4. Positive control: capture-free quiet subtree proves equivalence and a later
   actual EXCHANGES lookup consumes a tagged alias. All models/root seats and
   exact root ties/repertoire retain exhaustive shallow answers.
5. Paranoid narrow-window descendant cutoffs, null local-check caps and all
   cancellation/global-limit exits admit no enclosing alias. Larger resources
   may legitimately produce more completed iterations; no partial publication.
6. Exact key separation and vector copying, promotion, en passant/castling rights,
   transfer/status/two-active-player cases, tiny-cache eviction, clear/game switch
   and enabled/disabled requests using one cache.
7. Frozen corpus and held-out off/on repeats with provenance hit/admission counts,
   unchanged uncached answers and bounded entries. Measure useful retained
   analysis and elapsed cost; no acceptance claim from alias count/depth alone.

## Acceptance boundary

This remains an exact-reuse implementation choice under existing accepted
requirements. It does not approve selective quiet filtering, larger tactical
limits, a changed search model, a check stand-pat, or bound-as-exact reuse.
The prior ordering candidates stay isolated. Final integration requires source
review and the coordinator's serialized domain/target/browser checks on the
actual candidate; this design review is not their substitute.
