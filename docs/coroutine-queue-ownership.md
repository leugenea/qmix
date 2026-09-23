# Coroutine ownership and host-session StateFlow (qmix#178, qmix#180, qmix#181, qmix#182)

This records the bounded coroutine migrations under #139. Repository SSE uses
snapshot-only reconnect; retained event history remains outside this migration.

`QMixApplication` owns the process parent scope. The host creates one room-session
Job beneath it and passes its child scope to the queue, playback, and reporting
factories. Each coordinator creates a child Job and cancels it on close; joining
the room-session Job therefore includes command, retry, report, and periodic work.
Foreground loss cancels the active command/reconciliation Job and
closes admission until the host's matching foreground read restores it. No
coordinator creates a detached root scope. Production mutation uses
`Dispatchers.Main.immediate`; synchronous host decisions enter the same
injected mutation context, preserving create, warning, and Back admission.
The application-owned host session exposes `StateFlow<HostingState>`; UI
collects it with the Activity lifecycle, while recreation replaces only the
collector. No host observer queue, transition monitor, or session generation
owns publication. Create requests and room/recovery collections are cancellable
child Jobs. Teardown has two phases: on the mutation context it closes admission
with `Ending`, detaches credentials, jobs, and coordinators, cancels owned work,
and closes coordinators without joining in a reducer or synchronous StateFlow
callback. `endRoom()` and Back return promptly (`EXIT_ACTIVITY` for Back).
An application-scope finalizer outside the mutation dispatcher joins the entire
detached session tree, including non-cancellable child cleanup. It re-enters the
mutation context, checks `Ending` identity, and opens create admission with
`Setup` publication; a synchronous Setup collector can create only after the
detached tree has finished. No old result can publish after detach. A foreground
stop likewise cancels collection/recovery without joining on the mutation
dispatcher; start defers recovery until the cancelled collection and recovery
have completed. Worker mutations suspend cancellably across the dispatcher
boundary; StateFlow callback timing is not used to infer coroutine ownership.
Tests inject their own scope and dispatcher, including `StandardTestDispatcher`.

`PlayerStatePublisher` also owns a child session Job of the room-session scope and
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

The temporary adapter register is empty. Creation uses the suspend
`RoomApiClient.createRoom()` API; repository, queue, playback, conflict, and
foreground recovery use suspend APIs directly.

Player-report request construction failures are classified as `FAILED` before
any request is sent, so invalid server-issued header data cannot escape the
suspend API as an exception containing credentials.

Coroutines core, Android, and test are pinned together at 1.9.0. Lifecycle
runtime Compose 2.9.4 is used for STARTED-state collection. The Android SBOM
continues to derive its inputs from `releaseRuntimeClasspath`; generated SBOMs
remain ignored under `dist/sbom/`.
