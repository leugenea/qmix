# Decision record: approved Option B, October 1, 2026

## Scope assessment

**Yes: #306 is a bounded documentation-only publication. No runtime changes are
needed or included.** It preserves the exact reviewed decision/matrices and
static review evidence, adds an approval/provenance wrapper, and links the future
design from the current architecture. Production, tests, CI, toolchain,
dependencies and the frozen candidate remain unchanged. This is not the
implementation deliverable of #277.

## Accepted decision

The owner approved **Option B: typed hosting messages, one process-owned
operation/resource ledger, and one callback-free shared actual-authority gate
G**, then authorized sequential continuation. The authoritative recorded
[October 1 owner decision](https://github.com/leugenea/qmix/issues/306#issuecomment-5926044159)
and [#306 approved continuation](https://github.com/leugenea/qmix/issues/306)
lift the prior repository-doc/PR exclusion for this publication only. Bounded
replacement implementation scope still needs independent decomposition review;
no parallel implementation, candidate resurrection or unrelated work follows.

The accepted choices include:

1. The exact queue/playback/publisher/host writer and pure-state/after-unlock
   emission seams inventoried in [matrices-v3 §1](matrices-v3.md#1-shared-gate-writer-and-emission-inventory-p-from-s),
   not a host eligibility mirror or a whole domain-algorithm rewrite.
2. The narrow repository Command4/event64 handoff and retained refresh-terminal
   amendment, plus visible Call/EventSource callback/response ownership. These
   expand the earlier #304 exclusions only to the enumerated seams.
3. Process-global quarantine/seal lifetime, fixed pool/descriptor/ownership
   budgets, persistent-ring K4 service and causal ENDED capacity grants.
4. Fail-closed native callback saturation, rather than silently overwriting an
   already-owned accepted value; native viability remains untested.
5. Warning confirmation's durable-save exception outside G: End before exact
   confirmation commit returns false; an already committed confirmation remains
   true even if later cancelled. Cancel cannot steal the save claim.
6. Exact G activation claim as effect-start compatibility. Stop/End before the
   claim suppresses unstarted work; no physical external-call entry atomicity
   with arbitrary concurrent Stop is promised.
7. Non-awaiting FINAL ownership handoff, distinct from final continuation ACK,
   whole Job/subtree terminal and physical disposition. The same original
   charges remain until all retirement conjuncts hold; STREAM full-sequence
   backpressure is unchanged.

These choices are approved; the original files' questions O1–O9 are historical,
not unanswered current owner questions. Their stated allocation, external
scheduling, affinity and feasibility assumptions remain limitations to prove
or respect, not unconditional guarantees.

## Preserved contracts and limits

Public synchronous signatures and truthful admission remain: true means exact
admission, not successful business completion. Durable HTTP warning
acknowledgement precedes successful confirmation and any traffic. Committed
publication precedes separately eligible effects; reentrant observer Stop/End
suppresses still-unstarted effects. Real preparation identity survives metadata
refresh and rejects A→B→A/null/reprepare stale commands at both deferred
boundaries.

All nine whole-flow contract families and all 71 historical meanings across
15 suites remain verification obligations. Cases 21–27 retain their explicit
stale historical excerpt-binding warning; whole-file hashes are not assertion
or behavior equivalence.

PAUSED retains one active process-network owner and one immutable pending
record, original two-second deadlines and no predecessor overtaking. Fullness
holds all unstarted recovery/retry/new-session admission. Explicit authenticated
DELETE remains prompt, independent of old reports/PAUSED/room join, with its
original three-second deadline. Setup waits for the full detached room tree,
required affine disposition and finalizer processing, **not** process P/D debt.
Missing-room #279 behavior is unchanged and separately owned.

The approved conditional structural envelope is 98 records, 578 cells/payloads,
784 simultaneous descriptors and 176 declared ownership bundles. These are
neither measured heap/byte counts nor bounds on library internals. First-service
490 selector quanta and the nine-stage 4410 admission-to-POST selector bound
require the specified eligibility, capacity, finite-callout, live-consumer and
separately bounded dispatch/entry assumptions. Neither is a wall-clock or
completed-POST guarantee. Dead affinity or uncooperative external cleanup may
retain bounded quarantined charges indefinitely; successful Setup is not
fabricated.

## Exact evidence and provenance

| Artifact | SHA-256 | Meaning |
|---|---|---|
| [design-v3.md](design-v3.md) | `cd21825f4ee3b234c79edb94ff6a9a920aff6576a7f538a18ecdb8c213827c93` | Exact reviewed author proposal; original bytes/wording |
| [matrices-v3.md](matrices-v3.md) | `f07c4cb970eb6ab5e0eb4d6f00bc17f727a8057d8d865e8cdef0a1ea25800065` | Exact reviewed matrices, including all 71 bindings |
| [design-v3-independent-review.json](design-v3-independent-review.json) | `9f4f3308d0fa3880cb6eac9b293b058680319a049b970dff23288508db4c766a` | Independent paper-coherence PASS, not runtime acceptance |

The reviewed source comparison used clean main
`9454d705a2eddc2669c964a552395f1ede35088a`, frozen candidate tree
`e5863aa14ce4d90db83d6b758315cb75cbc61bdc`, and staged binary patch SHA-256
`ea4609b89e1730e59b0730dceb9f26c310ee7b86ae61b54c14fce72d5329f16b`.
The [exact frozen manifest](frozen-304-manifest.json) identifies all 37 paths.
The patch/archive stay out of maintained documentation and are not implementation
inputs to cherry-pick. The prior v1/v2 failed review identities and v3 correction
history remain in the exact v3 report and [publication evidence](publication-evidence.json).
The newer approval does not rewrite those earlier failed verdicts.

Primary sources constrain the proposal; they do not prove its ledger, fairness
or runtime. [sources.md](sources.md), [sources-ledger.json](sources-ledger.json)
and the compact [retrieval/support index](publication-evidence.json) retain
numbered URLs, passage/support fingerprints, version pins and source-specific
limitations. Local capture paths in historical files are provenance only.
The [manifest](publication-manifest.json) records every exact copy and explicitly
selected evidence transformation; no reviewed prose is normalized.

## Verification and later delivery

The completed independent review found no blocking static protocol contradiction
in the exact v3 artifacts. No model, prototype, build, JVM/native test, lint,
installation or runtime experiment is part of this publication. Static artifact
hash/link/inventory checks do not replace the required delivery gates.

Every later behavior-changing head needs meaningful regression oracles,
source/unit verification, full hosted JVM/lint/native API 36 TV execution,
combined JaCoCo INSTRUCTION coverage ≥95% with practical unrounded headroom,
and all unchanged non-JVM/quality gates. Android execution on the NAS remains
compile-only under the approved resource policy; no local JVM/lint/runtime loops.
A routed green aggregate with skipped runtime work is not runtime proof.
Exact-snapshot review and exact merge-SHA post-merge verification remain required.

[LEAN #305](https://github.com/leugenea/qmix/issues/305) alone owns demonstrated
redundant-guard simplification and final assembled structural metrics/docs:
effective owner identity sites ≤4, approximately 500 physical controller lines
as a soft target with quantified material deviations requiring an owner decision,
aggregate extracted size, CCN ≤10 and no new clones/unchanged zero baseline.
It may reverify upstream safety, never finish it. #277 retains original and
refined acceptance; #70/#175/#62 consume or reverify the final host seam without
duplicate implementation. #4/#6, release/hardware, Previous, Material redesign,
server/transport policy changes, new dependencies and #279 remain outside this
publication and implementation adoption scope.
