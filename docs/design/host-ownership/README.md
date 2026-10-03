# Retired host ownership design archive

**Status: superseded by qmix#312; historical evidence only.** The October 1,
2026 Option B decision and its paper protocol are not the current implementation
contract. The owner selected a simpler sequential FIFO host loop instead; see
[the current architecture](../../../ARCHITECTURE.md#sequential-android-host-ownership-qmix312).
The versioned artifacts below retain their original bytes and reviews, not an
obligation to adopt a ledger, shared authority gate, bounded inbox or admission
reservations.

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

## Historical adoption boundary

The adoption instructions, issue dispositions and implementation constraints in
this archive describe the abandoned Option B delivery path. They are superseded
by the canonical [#312](https://github.com/leugenea/qmix/issues/312) contract and
must not be imported into its implementation. Static paper review is not native
runtime, coverage or final-candidate acceptance evidence. Final independent
full-snapshot review and hosted Android/JVM/coverage gates remain separate checks
of the actual sequential-loop candidate.
