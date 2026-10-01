# Future host ownership design — approved Option B

**Status: owner-approved design, not implemented or runtime-accepted.** The
October 1, 2026 decision approves Option B and the enumerated scope/policy choices.
Canonical main at `9454d705a2eddc2669c964a552395f1ede35088a` does **not** implement
this protocol. This publication changes documentation only.

Start with the [decision and current status](decision.md). Then read the exact
reviewed [design-v3.md](design-v3.md) and [matrices-v3.md](matrices-v3.md).
The latter contains the complete writer, allocation, linearization, progress,
eight-class regression and 71-case/15-suite migration inventories.

## Authority and historical wording

The [owner decision on issue #306](https://github.com/leugenea/qmix/issues/306#issuecomment-5926044159)
supersedes the original artifacts' pre-approval process wording. The versioned
files retain their original bytes: phrases such as “recommend”, “owner decision
required”, “not approved”, “fresh review pending”, and “no repository write
is authorized” describe their authors' historical boundaries, **not current
approval status**. Likewise, the review's `architecture_approved=false` and
`implementation_authorized=false` describe the review's remit before the later
owner decision. They are not a denial or reversal of that decision.

The review certifies static coherence of a paper protocol only. Its PASS does
not prove native feasibility, runtime behavior, coverage, eventual resource
cleanup, or a bounded implementation-session size. Owner approval does not turn
those unexecuted obligations into accepted results.

## Published evidence

- [Exact independent v3 review](design-v3-independent-review.json): completed
  static PASS with zero blocking findings; original SHA-256 is retained.
- [Exact author delivery checks](delivery-checks-v3.json): byte preservation,
  arithmetic and read-only guards, not an independent review or executed model.
- [Numbered source explanations](sources.md) and [citation ledger](sources-ledger.json):
  original historical research material, retaining its retrieval/tool limitations.
- [Publication evidence](publication-evidence.json): compact retrieval support,
  pinned versions, source fingerprints, legacy suite provenance and prior-review
  dispositions. This is an explicitly derived index, not a replacement verdict.
- [Frozen candidate manifest](frozen-304-manifest.json): 37-path provenance; the
  failed patch is neither implemented nor imported by this publication.
- [Publication manifest](publication-manifest.json): exact hashes and transformation
  rules for the published files and retained task-local inputs.

Original task-local absolute paths and code-formatted historical filenames in
these artifacts are provenance, not repository-relative links. Raw web captures,
source dumps, runtime/cache logs, patches and source archives are deliberately
not copied here. The compact index binds their hashes/locators; the actual
versioned primary-source URLs and canonical main source references remain
traceable. The frozen unmerged candidate is identified by its manifest and
patch digest, never by a fabricated commit or runtime result.

## Adoption boundary

Issue [#306](https://github.com/leugenea/qmix/issues/306) owns this design/docs
publication. [#277](https://github.com/leugenea/qmix/issues/277) retains final
assembled behavior, metric and evidence acceptance. Replacement implementation
drafts and native dependency changes require independent validation before
creation. [#304](https://github.com/leugenea/qmix/issues/304) remains open/frozen
until a complete approved replacement graph exists;
[#305](https://github.com/leugenea/qmix/issues/305) retains later demonstrated
simplification and final metrics. No safety obligation is deferred to LEAN.

Production adoption needs one coherent activation of actual host/coordinator
G authority and all allocation/retirement contracts, not an unused ledger,
optional no-op ownership adapter or partially migrated head. If those seams
cannot fit a focused unit without mixed authority, stop for an explicit delivery
size/compatibility-stage decision. That is an implementation-planning boundary,
not permission to reopen the approved protocol or add another paper task by
default. Full source/runtime regressions, independent exact-snapshot review,
hosted JVM/lint/API 36 TV and combined instruction coverage remain mandatory.
