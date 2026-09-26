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
Both analyzers select the same production scope, but their CCN values have
**different definitions and are not directly comparable**. For every function,
`mass = CCN × sqrt(NLOC)`; erosion is the fraction of a language's mass in
functions with **CCN > 10** (strictly greater). A zero-mass language has 0%
erosion. The JSON schema is version 2, records `analyzer` per row, and has a
separate summary and top five mass contributors for each language. There is
**no overall/cross-language erosion or overall high-CCN count**: combining
incompatible CCN/NLOC measurements into one percentage would be misleading.

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

## History and alerts

On **every push to `main`**, including documentation-only pushes,
`erosion-history` converts the report into six `customSmallerIsBetter` metrics:
Go, Kotlin, and JS erosion percentages, and the three corresponding counts of
functions with CCN > 10. Each row has `name`, `unit`, and unrounded `value`.
The benchmark name is **`Code erosion`**, deliberately different from the old
`Lizard erosion` series: the Kotlin analyzer and no-aggregate methodology are
a baseline break. After merge, the owner should delete the obsolete
`Lizard erosion` entry from `gh-pages:dev/bench/data.js` once; this workflow
does not mutate old chart history. PR workflows never write history. See
[qmix#197](https://github.com/leugenea/qmix/issues/197),
[qmix#198](https://github.com/leugenea/qmix/issues/198), and
[qmix#228](https://github.com/leugenea/qmix/issues/228).

**Chart:** once Pages is configured and the first deployment completes, visit
<https://leugenea.github.io/qmix/dev/bench/>. The `gh-pages` branch stores
`dev/bench/data.js`; maintainers must create an empty orphan `gh-pages` branch
before the first run. The owner sets **Settings → Pages → Build and deployment →
Source: GitHub Actions**. `erosion-pages` deploys a snapshot after each
successful history write because a branch push using `GITHUB_TOKEN` does not
itself trigger a Pages build. A failed history or Pages job stays red without
blocking the stable `CI result`. GitHub queues at most 100 pending history
jobs; inspect and repair missing points after a larger burst.

An alert is a **commit comment** when any one metric rises strictly more than
10% relative to its previous point (`110%` ratio), not a merge gate. A zero
baseline followed by a positive value alerts, and one additional high-CCN
function may exceed the threshold for a small count. Values are not rounded
before comparison. Investigate the per-function report before acting.

## Duplication (qmix#200)

The routed, unprivileged `duplication` job runs on pull requests touching
production Go, Kotlin, or guest JS. `.jscpd.json` selects those formats and
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
jscpd v5.3.2 JSON (`statistics`, `duplicates`), with clone file
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
The future baseline gate (#202) must reuse the same scope and threshold; this
informational job does not implement a gate or history.

For a local report, download that release archive, verify the reviewed SHA-256
with `sha256sum -c -` **before extraction**, then run:

```sh
TMPDIR=/path/to/scratch JSCPD_BIN=/path/to/verified/jscpd make duplication
```

The target prints the path to `report.md` in a scratch subdirectory and also
writes normalized `report.json`. No npm or JVM is needed. On the current
production scope with the adopted 10-line minimum, the baseline is **0.5030%
overall, four Go clones** (Go 0.8368% / 4, Kotlin 0% / 0, JavaScript 0% / 0).
This is an informational baseline, not a gate; #201/#202 should use the same
10-line minimum, default 50-token minimum, `mild` mode, and production scope.
