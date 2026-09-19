# QMix for Android TV

The minimal Android TV application is contained in the single `android/app` module.
Gradle does not calculate the APK version itself: during configuration, it runs
`go run ./internal/buildinfo/cmd/version -format=json` from the repository root
and takes `versionName` from `version` and `versionCode` from `androidVersionCode`.

## Pinned toolchain

- minSdk 23, targetSdk 36, compileSdk 36;
- JDK 17;
- Gradle 8.13 (checked-in Wrapper with the distribution SHA-256);
- Android Gradle Plugin 8.13.2;
- Kotlin/Compose Compiler plugin 2.3.21;
- Compose BOM 2026.05.01 and Compose for TV Material 1.1.0;
- Media3 ExoPlayer 1.11.1;
- Android Build Tools 35.0.0.

Go 1.25+, JDK 17, and the Android SDK with platform 36/build-tools 35.0.0 are required.
The local `android/local.properties` file may define `sdk.dir` if necessary and is
not committed.

## Local checks

```bash
cd android
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebugAndroidTest
./gradlew --no-daemon :app:printSharedVersion
```

Run the instrumentation suite only on an Android TV emulator/device:

```bash
cd android
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

## Backend and guest addresses

The first setup screen is intentionally blank. Enter the backend API base URL
and the guest web origin; there is no production-looking fallback host. After a
room is created successfully, both canonical addresses are stored in the app's
private `SharedPreferences` and restored after process or device restart. Host
tokens are never stored with these settings and are never added to invitation
URLs or QR codes.

HTTPS remains supported and is recommended for every public or remote
deployment. A trusted-LAN deployment may use addresses such as
`http://192.168.1.20:8180`. Before the first HTTP request, the TV app requires a
one-time acknowledgement that HTTP provides **no transport confidentiality**:
room traffic and host credentials can be observed or modified by devices on the
local network. Do not expose an HTTP QMix deployment to an untrusted network.

Android cannot enumerate a host entered at runtime in a domain-scoped network
security rule. The application therefore permits cleartext at the platform
policy level, then restricts its own endpoint inputs to absolute `http` or
`https` URLs without user info, queries, or fragments and presents the warning
above. This platform opt-in applies to every HTTP request the app makes; it is
not a claim that arbitrary HTTP is safe or confidential.

The suite installs the APK, launches the activity through `LEANBACK_LAUNCHER`,
checks the initial focus, and sends D-pad OK. CI uses Android TV API 36
(`android-tv`, x86, `tv_1080p` profile).

The process logger emits Android diagnostics under the stable Logcat tag `QMix`.
The build-time default is `WARN`; routine state transitions, reconnect scheduling,
and playback progress remain below that level. To enable debug diagnostics at
runtime on a connected TV or emulator, set the standard per-tag Android logging
property, then restart or relaunch the app:

```bash
adb shell setprop log.tag.QMix DEBUG
adb logcat -s QMix:D
```

Restore the default with `adb shell setprop log.tag.QMix WARN`. Records use the
stable components `app/host-session`, `room-api/creation`,
`room-sync/sse/reconnect`, and `playback/lifecycle`. They contain only finite
operation and cause categories: host tokens, authorization/OAuth data, request
or response bodies, room codes and IDs, exception messages, and URLs or query
parameters are never emitted.

## Room synchronization

The application-scoped host session owns one `SequentialRoomRepository` while a
room is active. The repository treats SSE as invalidation only: the four room
change events trigger a complete `GET /rooms/{code}` reconciliation, while
heartbeats and payload data are ignored. REST reads are serialized and
coalesced, so an event received during a request causes exactly one follow-up
request without allowing older responses to overwrite newer state.

The SSE connection reconnects with capped exponential backoff and jitter. A
fixed 15-second REST refresh repairs silently missed events. REST retains the
15-second call timeout; the long-lived SSE client disables call and read
timeouts and owns reconnect policy explicitly. A 404 ends synchronization,
network failures preserve the last room state as stale, and ending the host
session cancels the request, event stream, retry, and periodic timers.

`HostSessionController` publishes each current-session repository update as a
`HostingState.LiveRoom`, carrying the safe guest invitation, synchronization
state, queue-command pending input, and local playback state used by the TV
presentation. Start/Next is serialized by `QueueAdvancementCoordinator`. Only a
fresh, connected authoritative snapshot may select playback media; selection
identity is `track_id`, so repeated snapshots preserve position, pause,
completion, and errors. A new selection uses `/rooms/{code}/current/stream` and
prepares/plays once. Final completion is local, while ENDED with queued work
requests one coordinated advance. Retry Current performs a token-bound GET and
re-prepares only when the authoritative current still matches. Stale snapshots,
reconnecting snapshots, replaced-media callbacks, and stale retry callbacks
cannot drive playback. The live-room UI presents the server-selected track
separately from the actual local playback state and provides focused Play/Pause,
Retry Current, explicit Next, and seek −10/+10 actions. Seek actions appear only
for a known seekable timeline and clamp through the playback engine. Android
media Play, Pause, and Play/Pause keys route to the same coordinator without
intercepting D-pad Left/Right globally. Invite remains a reversible presentation
state, and Back closes Invite before ending the host session and returning an
explicit `EXIT_ACTIVITY` result to the UI. Foreground recovery and assembled-flow
verification are outside #122 and remain owned by #123.

## Coverage

The required gate is at least 95% instruction coverage for all bytecode in the
`com.qmix.tv` package. Only the generated Android classes `R` and `BuildConfig`
are excluded; handwritten UI/state/domain classes and Compose code are not.
The report combines JVM/Robolectric and native instrumentation coverage:

```bash
cd android
./gradlew --no-daemon :app:jacocoDebugCoverageVerification
```

The command requires a connected emulator/device because it runs
`connectedDebugAndroidTest` itself. The HTML/XML reports are located in
`app/build/reports/jacoco/jacocoDebugReport/`.
