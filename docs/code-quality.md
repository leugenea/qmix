# Code quality: lizard erosion history

The routed `erosion` CI job produces a `lizard-erosion` artifact (`report.json`
and `report.md`) and a per-PR comment. See [qmix#197](https://github.com/leugenea/qmix/issues/197).
On **every push to `main`**, including documentation-only pushes, a separate
`erosion-history` job converts that report into one JSON array of five named
metrics: overall, Go, Kotlin, and JS erosion as percentages, plus the overall
count of functions with CCN > 10. The format is `customSmallerIsBetter`; each
row has `name`, `unit`, and `value`. Other analyzers (for example jscpd) can
publish under a different benchmark name on the same dashboard. PR workflows
never write history.

**Chart:** once Pages is configured and the first deployment completes, visit
<https://leugenea.github.io/qmix/dev/bench/>. The `gh-pages` branch is the
history data store (`dev/bench/data.js`) and is viewable directly in the
repository even before Pages is enabled. Maintainers create an **empty orphan
`gh-pages` branch once before the first run**: the benchmark action cannot
create it. The owner must set **Settings → Pages → Build and deployment → Source:
GitHub Actions**; CI does not change this repository setting. On every
successful history write, `erosion-pages` checks out the updated branch and
deploys a Pages artifact. This explicit deploy is needed because a branch push
using `GITHUB_TOKEN` does not itself trigger a Pages build. A failed deployment
stays visible as a red `erosion-pages` job without failing the stable `CI result`.
GitHub queues at most 100 pending history jobs; if a burst exceeds that limit,
maintainers must inspect missing runs and repair the history manually.

An alert is a **commit comment** when *any one* metric rises strictly more than
10% relative to the previous point for that metric (`110%` ratio). It does not
fail CI; a publisher error appears as a failed `erosion-history` job but does not
block the stable `CI result`. The alert is a prompt to investigate, not a merge
gate. A prior zero and any positive value trigger an alert; a low high-CCN count
can also jump over 10% with one additional function. If a language has no
functions, its erosion is reported as 0% (zero mass); its first analyzed
high-CCN function can therefore trigger such a zero-baseline alert. Values are
not rounded before comparison. Lizard's Kotlin parsing is approximate, and
some constructs can be misidentified; check the underlying report before
acting. See [qmix#198](https://github.com/leugenea/qmix/issues/198).
