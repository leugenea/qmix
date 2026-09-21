# Coroutine ownership and queue advancement (qmix#178)

This is the first bounded migration under #139. Repository SSE, player-state
publishing, authoritative playback, retained event history, and UI collection
keep their existing contracts and remain owned by the later issues.

`QMixApplication` owns the process parent scope. Each queue coordinator receives
that scope and a `QueueMutationContext`, creates a child session Job, and cancels
it on close. Foreground loss cancels the active command/reconciliation Job and
closes admission until the host's matching foreground read restores it. No
coordinator creates a detached root scope. Production mutation uses
`Dispatchers.Main.immediate`; the synchronous dispatcher marshals legacy
host callers onto that context and preserves immediate Boolean admission.
Host foreground stop/start and recovery delivery enter that context before
acquiring the legacy foreground transition lock, preventing a lock inversion
with main-thread observers.
Tests inject their own scope and dispatcher, including `StandardTestDispatcher`.

Unresolved command state is separate from the coroutine operation. The owned
lazy Job is registered before pending notifications, so a reentrant foreground
loss cancels that admission before it can dispatch. Next and
ended share one gate. A determinate rejection settles without a GET; success,
malformed success, and transport uncertainty require the command's own fresh
GET. A failed GET leaves the command unresolved; a later authoritative signal
may start another GET, never another POST. Coroutine cancellation prevents an
old operation from applying results or clearing a newer operation. Ended is
consumed once per current selection; changing away and back creates a new
selection without numeric generations.

Every API operation uses one suspend OkHttp primitive. Cancellation calls
`Call.cancel()`. The callback owns and closes the response around decoding,
then delivers only a decoded value; prompt coroutine cancellation discards a
result that has not yet resumed its caller. Status mappings, DTO checks,
redacted logging, redirect rejection, and disabled transport retries remain.

The only production compatibility surfaces are:

| Surface | Removal owner |
| --- | --- |
| `RoomApiClient.playerReporter(parentScope)` | #180 |
| `RoomApiClient.roomFetcher(parentScope)` | #181; repository usage leaves in #179 |
| `RoomApiClient.createRoomBlocking()` | #182 |

Player-report request construction failures are classified as `FAILED` before
any request is sent, so invalid server-issued header data cannot escape the
owned adapter as an uncaught exception containing credentials.

Both callback adapters require an explicit parent scope and return cancellation
handles for their child Jobs. Cancellation suppresses callbacks instead of
converting cancellation into a transport failure. Callback-controlled test
fixtures adapt existing assertions to suspend fakes; they are not shipped.

Coroutines core, Android, and test are pinned together at 1.9.0, the version
already present in the reviewed strict dependency-verification metadata and
resolved Android graph. No lifecycle dependency is introduced. The Android SBOM
continues to derive its inputs from `releaseRuntimeClasspath`; generated SBOMs
remain ignored under `dist/sbom/`.
