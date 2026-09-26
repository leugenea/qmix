# Code quality: complexity erosion and duplication

The routed `erosion` CI job analyzes production Go (`cmd/`, `internal/`), Kotlin
(`android/app/src/main/`), and guest-app JS (`internal/server/guest/`). It
excludes tests, generated headers and paths, build output, and vendored code.
It uploads a `code-erosion` artifact (`report.json` and `report.md`) and adds or
updates one same-repository PR comment. The report and job summary use the same
Markdown. Complexity never fails the job by itself; a failed analyzer, malformed
CSV, unreadable Kotlin file, or Kotlin parse error fails closed instead.

Go and JS functions use hash-pinned **lizard 1.24.0**; Kotlin uses the repo-owned
`kotlin_complexity.py` backed by hash-pinned **tree-sitter 0.25.2** and
**tree-sitter-kotlin 1.1.0**. The Python binding 0.26.0 crashed in prototype
traversal, whereas repeated full-tree runs with 0.25.2 succeeded; the pins and
wheel hashes live in `.github/requirements-lizard.txt`. Both jobs installing
those wheels select Python 3.13 explicitly; the hash-pinned Linux x86_64 wheels
also support local Python 3.11–3.14. To run the local routing tests, install
those dependencies in a virtual environment first:

```sh
uv venv -p /usr/bin/python3.13 .venv
uv pip install -p .venv/bin/python --require-hashes --no-deps -r .github/requirements-lizard.txt
PATH="$PWD/.venv/bin:$PATH" make test-workflow-routing
```

No JVM analysis is run.

## Informational hotspot ranking (qmix#242)

Run `make hotspots` locally with the hash-pinned lizard/tree-sitter Python
requirements above and the checksum-verified jscpd v5.3.2 binary from the
[duplication instructions](#duplication-qmix200):

```sh
TMPDIR=/path/to/scratch JSCPD_BIN=/path/to/verified/jscpd make hotspots
# To retain both reports at a known location, optionally add:
# HOTSPOTS_OUT=/path/to/ranking
```

The target runs the existing erosion and duplication analyzers on the same
production files and exclusions, then writes `report.md` and `report.json` in a
scratch directory (or `HOTSPOTS_OUT`). It reads the current checkout's `main`
ref by default; use `HOTSPOTS_REF=HEAD` to select another ref and
`HOTSPOTS_DAYS=30` to change the default 90-day lookback. The script's
`--now` option accepts an ISO-8601 instant for reproducible fixture runs.
Churn uses Git's `--since-as-filter` (Git 2.37+) so an older-dated commit
cannot hide newer commits behind it in a non-monotonic history.
`make test-hotspots` runs the fixture tests. This is local and informational:
findings never fail CI; missing or malformed inputs return exit 2.

For each function with **CCN > 10**, score = its erosion mass
(`CCN × √NLOC`) × the number of commits touching its file in the window.
Functions rank separately within Go, Kotlin, and JS, since Kotlin's CCN is not
comparable with lizard's. jscpd clones are grouped by their unordered file set;
score = the group's sum of duplicated lines × the **highest** file churn in
that set. Churn is **file-level**, not function- or fragment-level: an unrelated
edit in the same file counts. Git uses `--no-renames`, so changes under an old
path are not attributed to a renamed file. Zero-churn entries follow ranked
entries as `cold` and are never top hotspots; owner-approved exceptions follow
as `skipped` with their reasons. Only ranked entries receive numeric ranks.

For each recurring pass: run the ranking on current `main`, review the top N
ranked entries (not cold or skipped), and tackle each chosen hotspot in its own
pull request. Keep behavior unchanged; for untested code, add characterization
tests in a separate first commit and require them to pass before the refactor
and after it. Include the hotspot's before → after CCN/NLOC or clone size,
churn and score, plus a brief explanation of why the code is easier to follow.
Do not split functions or merge unrelated copies solely to improve a metric.
The owner chooses the cadence and any exceptions; no automated schedule or new
gate is introduced.

Both analyzers select the same production scope, but their CCN values have
**different definitions and are not directly comparable**. For every function,
`mass = CCN × sqrt(NLOC)`; erosion is the fraction of a language's mass in
functions with **CCN > 10** (strictly greater). A zero-mass language has 0%
erosion. The JSON schema is version 2, records `analyzer` per row, and has a
separate summary and top five mass contributors for each language. There is
**no overall/cross-language erosion or overall high-CCN count**: combining
incompatible CCN/NLOC measurements into one percentage would be misleading.

## Pull-request complexity gate (qmix#199)

The routed, pull-request-only check is named **`complexity-gate`** in the PR
checks list. It uses the same production scope and exclusions as `erosion`,
analyzing the base and head commits with the head checkout's hash-pinned lizard
and tree-sitter Kotlin counter. The fixed limit is **CCN > 10**: a newly added
function above 10 fails, as does an existing function whose CCN rises to a
value above 10. Existing high-CCN functions that stay the same or improve pass;
there is no aggregate or per-language erosion threshold. The Go/JS and Kotlin
CCN definitions differ, so comparisons are within each language only.

Named functions first match by stable analyzer ID (file and qualified signature,
including Kotlin owner, receiver and parameter types). For unmatched high-CCN
functions, including renamed or moved functions and anonymous lizard rows with
unstable `$n` IDs, the gate pairs one-to-one with an unmatched base function of
the same language when their source bodies are sufficiently similar (at least
0.8 similarity after collapsing whitespace and omitting the declaration/name
line; for one-line functions, compare only the text after the body opener).
A matching renamed or moved function passes if its CCN does not rise; if it
rises above 10, the match remains visible as `renamed+worsened` and fails.
An unmatched high-CCN function fails as added. Anonymous functions at or below
10 do not require matching. Matching depends on source-body similarity, so a
substantial rewrite together with a rename or move may be classified as added;
review the finding rather than ignoring it. A split that lowers the original
function's CCN and adds only functions at or below 10 passes.

On failure the job summary lists each offending file, function, **CCN before →
after**, and reason (`added`, `worsened`, or `renamed+worsened`). The
`complexity-gate` artifact contains the JSON report. Analyzer failures fail
closed rather than reporting a pass. To run its fixtures locally, install the
hash-pinned dependencies described above and run `make test-complexity-gate`.
The repository owner must make the **`complexity-gate`** check required in
branch protection; this workflow does not change branch protection or the
existing `CI result` aggregate. No bypass label is provided.

## Kotlin counting contract

The counter reports every `function_declaration` with a block or expression
body, including local named functions, methods of anonymous objects, extension
functions, and overloads. Bodyless declarations are not rows. The score is
`1 +` one each for `if`, `for`, `while`, `do-while`, `catch`, every non-`else`
`when` entry (comma-separated alternatives count once), `&&`, `||`, and `?:`.
Decisions in lambdas (including Compose content lambdas) belong to the enclosing
function; a nested named function has its own row and its decisions are not
counted in its parent's CCN. Safe calls (`?.`) are not decisions.

NLOC counts physical, nonblank source lines in the whole declaration after
masking comments, **excluding nested named function declarations and their
terminating semicolons from the parent** so its NLOC and CCN describe the
same code. If parent and nested function share a line, remaining parent code
on that line still counts. This is a documented source-line policy, not an
assertion of equivalence with lizard NLOC.

Kotlin function ID grammar is `relative/file.kt::link.link...`, with links in
**lexical nesting order** from outermost owner to the body-bearing function:

- Named class, named object, and companion object: their literal name; an
  unnamed companion uses `Companion`.
- Function: `name@Receiver(T1,T2)`; omit `@Receiver` when absent. Each
  enclosing function contributes its own link, even when bodyless.
- Secondary constructor: `constructor(T1,T2)`; it qualifies nested functions
  but has no metric row of its own.
- Property and parameter default (including primary-constructor and function
  parameters): `<prop:name>` and `<param:name>`. Accessors add `<get>` or
  `<set>` after the property; an `init` block adds `<init>`. An enum entry adds
  `<enum:name>`.
- Anonymous object: `<object:Super1,Super2>` (omit `:Super...` without
  supertypes). Its containing property/argument provides the binding link.
- Lambda: `<lambda:callee>` for a call's trailing lambda, otherwise `<lambda>`;
  anonymous functions use `<anonymous-fun>`. Arguments add `<arg:name>` for a
  named argument or `<arg:1>`, `<arg:2>`, ... for positional arguments.
- Control flow: `<if>` with `<then>`/`<else>` for block arms, `<when>` for each
  arm, `<try>` with `<catch>`/`<finally>`, and `<for>`/`<while>`/`<do>`.

Type and call-target text removes comments and whitespace outside
backtick-escaped names; parameter names/defaults/return types and source
positions are not included. Thus `file.kt::outer(Int).Local.call()` and
`file.kt::Local.outer(Int).call()` are different, as are overloads
`file.kt::C.outer(Int).local()` and `file.kt::C.outer(String).local()`.
Indistinguishable sibling non-function scopes receive source-order `#2`,
`#3`, ... on their scope link: multiple `init` blocks are `C.<init>` and
`C.<init>#2`, so swapping their bodies swaps those IDs. Two identical
function base IDs receive source-order `#2`, `#3`, ... at the end. Inserting
lines, editing bodies, or reordering *distinct named* siblings does not
change IDs; identical-scope duplicates and positional call arguments depend
on order. The separate `owner` field retains only the named class/object
chain, not enclosing functions or anonymous objects. A syntax `ERROR` or
`MISSING` node, invalid UTF-8, or unreadable source terminates analysis with
file and line diagnostics; the sole grammar exception is the invisible
`_class_member_semi` immediately before `}` in a valid single-line
class/interface body (including one nested inside a multiline class).

## Pull-request clone gate (qmix#202)

The routed pull-request check is named **`clone-gate`**. It runs the same
checksum-verified jscpd v5.3.2, `.jscpd.json` (10-line minimum, default
50-token minimum, mild matching), and production Go/Kotlin/guest-JS sources
as the informational `duplication` job. The PR head is scanned once with
`--baseline-from-ref <PR base SHA> --fail-on-new-clones`; jscpd scans the
base ref internally. A clone is new when jscpd's baseline mode marks it absent
from the PR base. Existing clones are tolerated; overall duplication percentage
does not gate the PR. Base code is not executed.

On failure, the job summary lists each new clone's format, both file and line
ranges, line count, and token count. The `clone-gate` JSON artifact has
`new_clones[]` with the same fields and paths relative to the repository root.
Missing, malformed, or failed analysis is an error, not a passing report.
Run local report fixtures with `make test-clone-gate`.

**Limitation:** With the pinned jscpd v5.3.2 baseline mode, these changes
new copying were reported as new clones and failed the PR: moving a file
containing an existing clone (`internal/roomsubmission/limiter.go`); inserting
a comment before an existing cloned block; inserting the same comment into
both copies of a clone; adding a non-ASCII string before a clone; and making
the same whitespace change in both copies of a clone. Each affects code next
to or inside an existing clone. The `clone-gate` check is informational and
not required until [qmix#229](https://github.com/leugenea/qmix/issues/229)
removes the existing clones from `main`. The owner can then make `clone-gate`
required in the repository ruleset. If a PR fails only for one of these
reasons, the owner makes an explicit merge decision; the workflow provides
no bypass or override. An unrelated PR need not remove existing duplication.
The workflow does not alter rulesets or branch protection or add the gate to
`CI result`.

## History and alerts

On **every push to `main`**, including documentation-only pushes,
`erosion-history` publishes two `customSmallerIsBetter` benchmark series into
one `gh-pages:dev/bench/data.js` history. **`Code erosion`** keeps its six
existing metric names: Go, Kotlin, and JS erosion percentages (`%`), and the
three corresponding counts of functions with CCN > 10 (`functions`). Each row
has `name`, `unit`, and unrounded `value`. The benchmark name is deliberately
different from the old `Lizard erosion` series: the Kotlin analyzer and
no-aggregate methodology are a baseline break. **`Code duplication`** adds
eight unrounded jscpd metrics from the normalized `code-duplication` artifact:

| Scope | Percentage metric (`%`) | Count metric (`clones`) |
| --- | --- | --- |
| Overall | `Duplication` | `Duplication clones` |
| Go | `Go duplication` | `Go clones` |
| Kotlin | `Kotlin duplication` | `Kotlin clones` |
| JavaScript | `JS duplication` | `JS clones` |

The normalized duplication JSON has `schema_version: 1` and supplies zeros for
formats absent from a scan. History publishing keeps the `erosion-history` job
and serializes both series in that job; if only one analyzer succeeds, its
series can still be recorded. After merge, the owner should delete the obsolete
`Lizard erosion` entry from `gh-pages:dev/bench/data.js` once; this workflow
does not mutate old chart history. PR workflows never write history. See
[qmix#197](https://github.com/leugenea/qmix/issues/197),
[qmix#198](https://github.com/leugenea/qmix/issues/198),
[qmix#201](https://github.com/leugenea/qmix/issues/201), and
[qmix#228](https://github.com/leugenea/qmix/issues/228).

**Chart:** once Pages is configured and the first deployment completes, visit
<https://leugenea.github.io/qmix/dev/bench/>. The `gh-pages` branch stores
`dev/bench/data.js`; maintainers must create an empty orphan `gh-pages` branch
before the first run. The owner sets **Settings → Pages → Build and deployment →
Source: GitHub Actions**. `erosion-pages` deploys a snapshot after each
successful history write because a branch push using `GITHUB_TOKEN` does not
itself trigger a Pages build. After editing `gh-pages` (for example, cleaning
up old chart data), re-run only the `erosion-pages` job of the latest `main` CI
run using the Actions UI **Re-run job**, or `gh run rerun RUN_ID --job JOB_ID`.
The upload and deploy use the current run attempt's artifact name, so no manual
artifact deletion is needed. A failed history or Pages job stays red without
blocking the stable `CI result`. GitHub queues at most 100 pending history
jobs; inspect and repair missing points after a larger burst.

An alert is a **commit comment** when any erosion or duplication metric rises
strictly more than 10% relative to its previous point (`110%` ratio), not a
merge gate: `fail-on-alert: false` keeps the workflow green. A zero baseline
followed by zero does not alert; zero followed by a positive value alerts.
One additional clone or high-CCN function may exceed the threshold for a small
count. Values are not rounded before comparison. Investigate the source
report before acting.

## Duplication (qmix#200)

The unprivileged `duplication` job runs on pull requests touching production
Go, Kotlin, or guest JS, and on every push to `main` (including documentation-only
pushes) for the history described above. `.jscpd.json` selects those formats and
excludes tests, generated/build/vendor/third-party paths. The reporter enumerates
the same production files as erosion, additionally excluding generated source
headers. A checksum-verified **jscpd v5.3.2** Linux x64 binary is downloaded
from `https://github.com/kucherenko/jscpd/releases/download/v5.3.2/jscpd-linux-x64-gnu.tar.gz`;
its reviewed SHA-256 is
`97259f222ea7f6d51a0f2faa98ed5889430233e0fb8d2c129f23d84aff4b93b2`
(the v5.3.2 release `checksums.txt` agrees). The owner-approved minimum is
**10 lines**, with jscpd's default **50 tokens** and `mild` matching mode.
At the five-line default, all three Kotlin matches were noise: two
import-only lists and a seven-line UI scaffold. In v5.3.2 an import
`--ignore-pattern` trial did not remove those matches. Raising the token
minimum to 80 would also lose the substantive 17-line Go limiter clone
(59 tokens), so the 10-line minimum removes the Kotlin noise while retaining
four meaningful Go clones. `--threshold 100` means the score cannot fail the
job; install, empty scans, malformed output, and report errors do fail it.

The `code-duplication` artifact holds `report.json` and `report.md`; the same
Markdown is appended to the job summary and the single PR comment shared with
erosion. The comment is updated in place only for same-repository,
non-Dependabot PRs by the artifact-only publisher. Forks still get a summary
and artifact without a privileged comment. The comment contains distinct
`<!-- qmix-lizard-erosion -->` and `<!-- qmix-jscpd-duplication -->` markers
when both analyzers have reported. Partial runs replace only the produced
section and retain the other section from the existing comment; no empty
placeholder marker is posted. Config-only changes can update just duplication,
while erosion inputs can update just erosion. Source changes run both reports.
The summary table labels percentages as `Duplication, %`, not line counts.

**JSON consumer contract for #201/#202:** `report.json` is the validated native
jscpd v5.3.2 JSON (`statistics`, `duplicates`) plus `schema_version: 1`, with clone file
`name` values changed from absolute runner paths to repository-relative POSIX
paths. Absent source formats are filled with zero metrics. `statistics.total`
and `statistics.formats.{go,kotlin,javascript}` contain unrounded `percentage`
values in **percent units** (e.g. 1.5 means 1.5%, not 150%), `clones`,
`lines`, `duplicatedLines`, and `sources`. Each `duplicates[]` row has
`format`, `lines`, `tokens`, and `firstFile` / `secondFile` objects with `name`,
one-based `start` and `end` line ranges;
other native fields (including `fragment`, `kind` and `isNew`) are retained.
The top five clones sort by descending lines, then tokens, then source path.
The denominator counts jscpd-analyzed lines; duplicated lines count one
fragment per clone. Use this artifact rather than scraping rounded Markdown.
The baseline clone gate (#202) reuses the same scope and threshold; this
informational job does not implement the gate or history.

For a local report, download that release archive, verify the reviewed SHA-256
with `sha256sum -c -` **before extraction**, then run:

```sh
TMPDIR=/path/to/scratch JSCPD_BIN=/path/to/verified/jscpd make duplication
```

The target prints the path to `report.md` in a scratch subdirectory and also
writes normalized `report.json`. No npm or JVM is needed. On the current
production scope with the adopted 10-line minimum, the baseline is **0.5030%
overall, four Go clones** (Go 0.8368% / 4, Kotlin 0% / 0, JavaScript 0% / 0).
The clone gate (#202) reuses this scope and threshold with jscpd baseline
mode; this informational job remains separate from the gate and history.

## Hotspots left as is

The owner maintains this list after deciding a hotspot should remain unchanged;
it starts empty. Use one bullet per decision with a one-line reason, e.g.
`- \`cmd/a.go::functionName\` — reason` for an erosion function ID, or
`- clone: \`cmd/a.go, internal/b.go\` — reason` for a clone file set.
Clone file order does not matter. Do not add entries without the owner's decision.
