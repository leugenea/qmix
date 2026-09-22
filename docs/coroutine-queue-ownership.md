# Coroutine ownership, queue advancement, player reporting, and playback (qmix#178, qmix#180, qmix#181)

This records the bounded coroutine migrations under #139. Repository SSE and
authoritative playback have migrated; retained event history and UI collection
remain owned by later issues.

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

`PlayerStatePublisher` also owns a child session Job of the application scope and
uses the same injected mutation context for synchronous playback calls and
coroutine resumptions. One report Job and one periodic Job replace the manual
scheduler, serial executor, cancellation handles, and numeric generation. Track
replacement, foreground loss, close, and parent cancellation structurally cancel
owned work. Selection identity plus active Job identity prevents late completion
from mutating a replacement. Virtual-time tests cover the 7.5-second cadence,
coalescing, transition priority, reconciliation, and cancellation boundaries.

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

The host owns one foreground-recovery child Job in the same application scope.
A stop cancels that Job before it can resume on the serialized mutation dispatcher;
only a successful matching-room result from the still-owned Job reopens queue
commands and playback, and changed media remains paused. Playback retry and
report-conflict GETs also own cancellable child Jobs; track replacement, stop,
and close cancel their old-selection work. PlaybackEngine mutations run through
the injected immediate Android main dispatcher. The publisher listener is
supplied at construction, without a later replacement bridge.

The only production compatibility surface remaining for #182 is
`RoomApiClient.createRoomBlocking()`; repository, queue, playback, conflict,
and foreground recovery all call the suspend `fetchRoom` API directly.

Player-report request construction failures are classified as `FAILED` before
any request is sent, so invalid server-issued header data cannot escape the
suspend API as an exception containing credentials.

Coroutines core, Android, and test are pinned together at 1.9.0, the version
already present in the reviewed strict dependency-verification metadata and
resolved Android graph. No lifecycle dependency is introduced. The Android SBOM
continues to derive its inputs from `releaseRuntimeClasspath`; generated SBOMs
remain ignored under `dist/sbom/`.
