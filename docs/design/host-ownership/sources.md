# #306 — numbered primary sources and fetched support

All sources below were actually retrieved. IDs are registered in `sources-ledger.json`; verified verbatim support is attached to each ledger entry. `primary-fetch-manifest.json` covers 1–16; `primary-extra-fetch-manifest.json` covers 17–20. Each stores retrieval time, resolved URL, raw/text SHA256, byte count and task-local text path. `fetched-support-checks.json` binds 1–16; the extra manifest binds the four pinned-code quotes. Small support quotations live in the ledger, not repeated here.

**Tool limitation:** this leaf had `web_search`, HTTP and file-reading tools, but no exposed `web.run`. The explicit tool-specific request therefore cannot be claimed fulfilled. Direct retrieval of official primary text/code replaced it for research; parent should repeat with `web.run` if that exact route is required for acceptance. Search snippets and `/opt/data/tmp/qmix-304/concurrency-patterns-research.md` are not evidence for this decision.

## Sources

| ID | Retrieved primary URL | Supports / does not support |
|---|---|---|
| [1] | https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-job/ | Job state table: New is not active or complete; cancellation versus full children-completion. Does not prescribe application resource accounting. |
| [2] | https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-start/-l-a-z-y/ | LAZY postpones dispatch; unstarted uncancelled child can remain incomplete. Does not make body-finally a before-start cleanup owner. |
| [3] | https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-job/invoke-on-completion.html | Immediate/already-complete handler behavior; concurrent, unspecified execution context; fast, nonblocking, nonthrowing handler. No permission for Main-affine disposal from callback. |
| [4] | https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/ | Capacity, cancellation, undelivered elements and synchronous arbitrary-context hook. Resource example assumes a closer valid in that context. |
| [5] | https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-send-channel/try-send.html | Non-suspending failure/closed/full result and absence of undelivered callback on failure. Does not prove business eligibility, durability or consumption. |
| [6] | https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.selects/select.html | Clause bias and prompt cancellation; selection is not an integrated host fairness proof. |
| [7] | https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.selects/select-unbiased.html | Clause order randomized; no deterministic service-step guarantee is documented. |
| [8] | https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/run-test.html | Normally single-threaded test coroutine execution and virtual-time dispatcher limits; inability to finish uncooperative cancellation by timeout alone. Not a native Looper simulation. |
| [9] | https://kotlinlang.org/docs/lincheck-guide.html | JVM concurrency testing/exploration framework; does not authorize installation or assert compatibility with qmix. |
| [10] | https://kotlinlang.org/docs/lincheck-testing-strategies.html | Model checking versus stress, sequential-consistency limitation, reproducibility difference and unsupported features. Not exhaustive Android/network/relaxed-memory proof. |
| [11] | https://developer.android.com/media/media3/exoplayer/hello-world | Required single application-thread access and application Looper selection. No guarantee that a dead Looper can execute disposal. |
| [12] | https://developer.android.com/reference/androidx/media3/exoplayer/source/MediaSource.MediaPeriodId | Period/window sequence identity separate from textual content identity. Analogical support only for qmix PreparationId. |
| [13] | https://raw.githubusercontent.com/androidx/media/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImplInternal.java | Actual enumerated message protocol, payloads, handler release, component cleanup→Looper release→processed condition. Also actual blocking release and dead-thread fast path, explicitly not adopted. |
| [14] | https://doc.akka.io/libraries/akka-core/current/typed/interaction-patterns.html | Typed protocols, asynchronous tell not processed, correlated request/results, pipe-to-self pattern. No adoption/prevalence/owner-approval inference. |
| [15] | https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.9.0/kotlinx-coroutines-core/common/src/Job.kt | qmix-version Job states, full-child completion and completion handler contract, independently of newer current API docs. |
| [16] | https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.9.0/kotlinx-coroutines-core/common/src/CoroutineStart.kt | qmix-version LAZY behavior, cancellation-before-entry and orphan-lazy pitfall. |
| [17] | https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/d8d6f8f37978b8e202d93b34f23f101df9c5724d/kotlinx-coroutines-core/common/src/channels/Channel.kt | Immutable 1.9.0 source: unsuccessful trySend cannot deliver/call undelivered handler; hook runs synchronously in arbitrary context. |
| [18] | https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/d8d6f8f37978b8e202d93b34f23f101df9c5724d/kotlinx-coroutines-core/common/src/selects/Select.kt | Immutable 1.9.0 select bias/randomized alternative and cancellation semantics. |
| [19] | https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/d8d6f8f37978b8e202d93b34f23f101df9c5724d/kotlinx-coroutines-test/common/src/TestBuilders.kt | Immutable 1.9.0 single-thread/virtual-time test documentation. |
| [20] | https://raw.githubusercontent.com/androidx/media/8c6678b657ede1e7883fc164ef73ed483c7796c3/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/MediaSource.java | Immutable 1.11.1 declaration of period UID and windowSequenceNumber. |

## Version and passage traceability

* `main/android/gradle/libs.versions.toml`: Media3 1.11.1; Coroutines 1.9.0. No version was changed.
* `primary-version-pins.json`: Media3 annotated tag ref `915b73c447aed6471aa33380a95126121a79375d` resolves to commit `8c6678b657ede1e7883fc164ef73ed483c7796c3`; Coroutines 1.9.0 resolves directly to commit `d8d6f8f37978b8e202d93b34f23f101df9c5724d`.
* Current Kotlin API pages expose 1.11.0. Their relevant Job/start/channel/select/test facts were checked against fetched qmix-version sources 15–19; no dependency upgrade is inferred.
* HTML text extraction drops navigation/script/style and strips blank/leading whitespace. Its local line numbers are **not original source line numbers**. For source 13, use method names/constants plus version/commit and raw digest, not stripped-text line numbers as GitHub line anchors.
* Source 13 relevant full methods: constructor Looper/Handler ownership; `release`; `handleMessage` release dispatch; `releaseInternal`; targeted message delivery on dead thread. The evidence excerpt file is `ExoPlayerInternal-1.11.1.txt.evidence.md`; no blocking API is copied into the proposal.
* Source 17 is unmodified raw code: `trySend` contract around lines 69–78 and arbitrary-context undelivered hook around 680–702. Source 18's bias contract is lines 13–47; source 19's test scheduling documentation is around 54–61; source 20's `MediaPeriodId` and sequence field is around 208–232.

Source facts support constraints and precedents. The proposed operation ledger, token seams, quota, bounds, cutover order and resulting feasibility judgment remain design reasoning and unexecuted verification obligations, not facts proven by any cited library document.
