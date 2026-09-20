# QMix — Architecture

## 1. Overview

QMix is a collaborative music player. A host starts the player on Google TV and
creates a room; friends join it from their phones through a link (PWA, no
account required) and add supported Spotify, YouTube, VK, or Yandex Music links
to a shared queue. The backend resolves each link to track metadata, locates an
audio source, and streams it to the player. All participants see the current
track and queue in real time.

The key MVP design choice is to accept links from the supported services while
always delivering audio through one streaming backend. The resolver converts a
link into metadata, and the streaming backend locates and serves the audio
stream.

## 2. Scope

**Included in the MVP:**
- Rooms: host creation and guest access by code or link
- Shared queue: append tracks, reorder them, and skip to the next track
- Resolution of Spotify, YouTube, VK, and Yandex Music links to track metadata
- Audio streaming through a single streaming backend
- Real-time state synchronization (current track and queue) over SSE
- A TV application (Kotlin + Media3) and a guest PWA

**Intentionally excluded from the MVP:**
- Accounts, authentication, and personal playlists
- Voting, likes, and ratings
- Public or searchable rooms
- Torrents and peer-to-peer sources
- Offline mode

## 3. Stack

| Layer | Technology | Rationale |
|---|---|---|
| Backend | Go | One binary, simple deployment, and good HTTP/SSE support; link resolution uses public endpoints plus yt-dlp |
| Realtime | SSE + REST | Sufficient for the queue and current track; simpler than WebSocket and works through proxies |
| State | In-memory, no database | One store supports multiple ephemeral rooms; no persistence is required for the MVP |
| TV | Kotlin + Media3 (ExoPlayer) | Standard Android TV stack with built-in streaming and D-pad support |
| PWA | Minimal SPA | Guests can join without installing an app or creating an account |

The backend uses Go's standard library by default. For solved problems, it uses
maintained libraries rather than custom implementations. Link resolution and
audio lookup are implemented as plugins (see section 7). The resolver uses
public endpoints (Spotify oEmbed and Open Graph metadata) plus yt-dlp for
YouTube. (`oklookat/synchro` was rejected because it is a CLI for transferring
likes that requires authorization for every service and does not support
YouTube; see qmix#2.) Additional dependencies must be established solutions
that cover the complete requirement.

## 4. Components

```
                    ┌──────────────────────────────┐
                    │          Backend (Go)        │
                    │                              │
  TV app ──────────►│  Rooms + Queues (in-memory) │
  (Kotlin/Media3)   │        REST + SSE           │
                    │            │                │
  PWA guest ───────►│            │                │
  (phone, no auth)  │            ▼                │
                    │   ┌──────────────────┐      │
                    │   │ Resolver plugin  │      │
                    │   │ URL → Track meta │      │
                    │   └──────────────────┘      │
                    │            │                │
                    │            ▼                │
                    │   ┌──────────────────┐      │
                    │   │ StreamBackend    │      │
                    │   │ Track → audio    │      │
                    │   └──────────────────┘      │
                    └──────────────┬───────────────┘
                                   │ audio stream
                                   ▼
                              TV player
```

- **Backend** — a single service that stores multiple rooms and their queues,
  exposes REST + SSE, and invokes the resolver and streaming plugins.
- **Resolver plugin** — returns track metadata (title, artist, duration, and
  source URL) for a supported link.
- **StreamBackend plugin** — locates and serves an audio stream for a track,
  including Range/seek support.
- **TV app** — the host player. The current implementation includes the Android
  TV scaffold, room creation, a QR invitation, live room synchronization, queue
  advancement, authoritative Media3 playback coordination, focused local playback
  controls, and token-bound foreground recovery. Returning from the background
  cannot resume audio or issue queue commands until a fresh matching room read
  establishes the authoritative current; replacement selections remain paused.
- **PWA** — the guest page for submitting supported links and viewing the queue
  and current track, with a dark theme.

## 5. Data model

**Room (internal)**
- `Code` — short room code used in `/r/{code}`
- `HostToken` — secret authorizing host-only operations
- `Queue` — ordered list of tracks
- `Current` — current track (`*Current`, with the last host-reported `State` and
  position in `PosSec`; the position is not advanced by a server clock)
- `LastActivity` — last queue or playback mutation time used to expire idle
  rooms

The in-memory `Store` holds multiple rooms. The public room representation
contains only `code`, `current`, and `queue`; it never exposes `HostToken`.

**Track**
- `id` — internal track identifier
- `url` — original supported Spotify, YouTube, VK, or Yandex Music link
- `title`, `artist` — metadata returned by the resolver
- `duration_sec` — duration in seconds
- `resolved_by` — resolver plugin that produced the metadata

The current track's stream is not stored in `Track`. M3 resolves it on demand
through `StreamBackend` and caches the URL with a TTL
(`QMIX_STREAM_CACHE_TTL`).

**Queue**
- An ordered list of `Track` values
- Supported operations: append, exact reorder, and skip to the next track
- Arbitrary deletion is not part of the queue contract

## 6. API surface

**REST**
- `POST /rooms` — create a room → `{code, host_token, url}` (`url` = `/r/{code}`).
  Creation is admitted by a per-client token bucket before allocation; rejection
  is `429` with `Retry-After` and the public `rate_limited` envelope. When the
  process-wide live-room bound is full, it returns `503`, `Retry-After`, and the
  public `room_capacity_exhausted` envelope
- `GET /rooms/{code}` — get public room state (`code`, `current`, and `queue`; no `host_token`)
- `POST /rooms/{code}/queue` — append a track with `{url}`; the request body is
  limited to 4 KiB and each room holds at most 100 queued tracks. When the
  shared yt-dlp capacity queue is full, resolution is rejected with `503` plus
  `Retry-After`. This is the canonical submission route
- `GET /r/{code}` — get the guest room page (no login)
- `POST /r/{code}/queue` — compatibility alias for the same queue-submission
  operation. Its historical `201 {"status":"accepted","track":...}` success
  body is retained; the canonical route retains its historical bare track body
- `PATCH /rooms/{code}/queue` — reorder the queue with `{"order": [trackID, ...]}` (host only; exact permutation of IDs)
- `POST /rooms/{code}/skip` — advance to the next track (host only)
- `PATCH /rooms/{code}/player` — accept the host's exact bounded JSON
  `{track_id,state,pos_sec}` report (host only). `state` is `playing`, `paused`,
  `ended`, or `error`; `pos_sec` is a non-negative last-reported position, not a
  server clock. The report must match the current track atomically. A mismatch
  or missing current returns 409 without mutation. `playing`, `paused`, and the
  safe guest-visible `error` state update current and publish `player_state`;
  `ended` publishes the same exact payload and clears only that matching current
  while preserving the queue. Accepted reports refresh room activity.
- `GET /rooms/{code}/current/stream` — stream the current track with Range/seek support: 200 / 206 / 416; 404 when there is no current track; 503 plus `Retry-After` when the shared yt-dlp capacity queue is full (qmix#130)
- `GET /healthz` — unconditional liveness: `200 {"status":"ok"}`
- `GET /readyz` — current yt-dlp readiness: `200 {"status":"ready"}` only
  while the configured/default executable is resolvable and executable;
  otherwise `503 {"status":"unavailable"}`. The check never executes yt-dlp or
  accesses the network.

Application errors written by public HTTP handlers use one JSON envelope:
`{"error":"<code>","message":"<text>"}`. Health/readiness status documents and
HTTP media-range responses retain their endpoint-specific bodies. Codes and
messages are fixed public classifications; wrapped resolver/backend
errors, submitted URLs, paths, credentials, and host tokens are never copied
into responses. If the response transport for `GET /rooms/{code}/events` does
not support flushing, the endpoint returns `500` with `streaming_unsupported`
and `streaming unsupported` in this envelope. Both queue-submission aliases use
this mapping:

| Failure | Status | `error` | `message` |
|---|---:|---|---|
| Room missing or expired | 404 | `room_not_found` | `room not found` |
| Malformed JSON | 400 | `bad_request` | `invalid json body` |
| Body over 4 KiB | 413 | `request_too_large` | `request body too large` |
| Blank URL | 400 | `invalid_url` | `url must not be empty` |
| Resolver rejects URL syntax | 400 | `invalid_url` | `invalid track url` |
| Unsupported service | 422 | `unsupported_service` | `unsupported track service` |
| Anonymous resolution unavailable | 422 | `unsupported_service` | `track is not publicly available` |
| Queue at capacity | 409 | `queue_full` | `queue is full` |
| Room submission rate exhausted | 429 | `rate_limited` | `room submission rate limit exceeded` |
| Resolver capacity exhausted | 503 | `overloaded` | `track resolver is temporarily overloaded` |
| Resolver/upstream failure | 502 | `upstream_failure` | `track service is temporarily unavailable` |

The only queue status change in qmix#136 is invalid resolver URLs on
`POST /rooms/{code}/queue`: they now return 400 instead of 422, matching the
existing guest route and distinguishing malformed input from a valid URL for an
unsupported service. All other queue statuses and both successful response
bodies remain unchanged. The guest web client already reads `message`; the
Android client does not submit queue links, so neither client requires a format
migration.

Host operations (skip, reorder, and player reports) require the
`X-Host-Token` header issued when the room is created. Player reports accept at
most 4 KiB and exactly the three documented keys; unknown, missing, duplicate,
null, malformed, or trailing JSON is rejected. Player errors carry only the
allowlisted `error` state: decoder details, upstream content, request values,
and credentials are never accepted, stored, published, logged, or returned.

**SSE**
- `GET /rooms/{code}/events` — event stream:
  - `queue_snapshot` — complete state on connection or reconnection (`Last-Event-ID`)
  - `queue_updated` — queue changed (append or reorder)
  - `track_changed` — current track changed
  - `player_state` — exact `{track_id,state,pos_sec}` player report or initial
    skip state; reconnect snapshots carry the latest accepted current state

## 7. Plugin interfaces

**Resolver** — `url → Track metadata`
```go
type Resolver interface {
    // Resolve returns track metadata for a URL.
    Resolve(ctx context.Context, url string) (*Track, error)
}
```
Unauthenticated Spotify resolution uses oEmbed. VK and Yandex Music use
best-effort Open Graph metadata from public pages, with an explicit 422 when
metadata is unavailable. YouTube uses `yt-dlp --dump-json` behind an
injectable runner interface. Resolver selection is based on the link's domain.
Links without an anonymous resolution path (such as non-public VK or Yandex
Music metadata) and unknown domains return 422 with a human-readable error;
service failures return 502. YouTube metadata extraction uses `--no-playlist`:
a watch URL containing both `v` and `list` resolves only the selected `v` video,
while a playlist-only URL without a video ID returns the unsupported-service
422 before a subprocess starts. The resolver neither uses user accounts nor
fabricates metadata.

Spotify also supports an authorized path. When
`QMIX_SPOTIFY_CLIENT_ID`/`QMIX_SPOTIFY_CLIENT_SECRET` are set, track links are
resolved through Client Credentials (`accounts.spotify.com/api/token`) and the
Web API (`api.spotify.com`), providing the full artist list and duration. oEmbed
remains the fallback without credentials and for non-track links.

VK also supports an authorized path (qmix#9). When `QMIX_VK_TOKEN` is set, VK
audio links are resolved through `audio.getById` (api.vk.com, v5.131, POST with
the token in the body and the mobile User-Agent of the client in `cmd/token-vk`;
without it, VK returns a placeholder instead of the track). The anonymous Open
Graph path remains the fallback without a token and for non-audio links.

Yandex Music also supports an authorized path (qmix#10). When `QMIX_YM_TOKEN`
is set, track links (`/album/{a}/track/{t}` and bare `/track/{t}`) are resolved
through the maintained `goym` client (api.music.yandex.net,
`GET /tracks/{id}`; `album_id` is not required). The anonymous Open Graph path
remains the fallback without a token and for non-track links.

**StreamBackend** — `track → audio stream`
```go
type StreamBackend interface {
    // Stream returns an audio stream for a track and honors the client's Range header.
    Stream(ctx context.Context, track *Track, rangeHeader string) (*Result, error)
}

type Result struct {
    Body          io.ReadCloser // audio stream
    ContentType   string        // audio MIME type, for example audio/webm
    Status        int           // 200 / 206 / 416 to return to the client
    ContentLength int64         // complete resource size, or -1 if unknown
    ContentRange  string        // "bytes start-end/total" for 206, "bytes */total" for 416
    AcceptRanges  string        // "bytes" if the upstream supports Range
}
```
The MVP implementation uses **yt-dlp**. Source selection is explicit: a track
whose resolver identity is `youtube` is looked up using its original source URL,
while Spotify, VK, Yandex Music, and any other resolver identity use the
metadata search `artist - title` (`yt-dlp --skip-download --dump-json
--no-playlist -f bestaudio "ytsearch:..."`). An arbitrary URL is never treated
as directly streamable without the YouTube resolver identity. All stream
lookups use `--no-playlist`, so a direct watch URL cannot expand its `list`
parameter. The resolved direct audio URL
is cached for about five minutes (thread-safe TTL cache with race-free
concurrent misses); direct-path entries are keyed by source URL and search-path
entries by artist/title. The external yt-dlp invocation is behind an injectable
`Runner` (as in M2), and audio HTTP requests use an injected `http.Client`.

The HTTP proxy (`GET /rooms/{code}/current/stream`) forwards the client's Range
header upstream and reproduces the upstream status and headers: a complete
stream returns 200; a satisfied Range returns 206 with `Content-Range`; and an
invalid or unsatisfiable range returns 416. `Content-Type`, `Content-Length`,
`Content-Range`, and `Accept-Ranges` are forwarded. Upstream errors (no link:
404; search or network failure: 502) are not converted to 500. Responses are
served through `stream.ServeStream`.

**yt-dlp capacity limiter** (qmix#130). One shared `internal/ytdlpcap.Limiter`
bounds how many yt-dlp subprocesses run at once, because the Compose service is
capped at 512 MiB and a single yt-dlp process can spike to roughly 100–200 MiB
RSS. The composition root constructs exactly one limiter in `NewApp` and
injects the same instance into both launch sites: the resolver's YouTube
metadata call and the stream backend's search call. A slot is acquired before
the subprocess starts and released the moment it returns, on every path
including error and cancellation, so a canceled request (including a canceled
shared singleflight load) never leaks capacity. Callers that find no free slot
wait in a bounded FIFO queue; waiting is context-aware, so a canceled request
leaves the queue promptly. No room/store mutex is held while waiting — capacity
waits happen before the store lock, in the HTTP handler path. Once the queue is
also full, further lookups fail fast with the typed
`ytdlpcap.ErrOverloaded`, which the HTTP layer maps to **503** with a
`Retry-After` header on the host add-track, guest add-track and stream
endpoints (stable JSON error bodies; the guest code is `overloaded`). The
audio HTTP fetch after a resolved URL is not a subprocess and is never gated.
Defaults: at most 2 concurrent subprocesses with a queue of 8 waiting callers
(`QMIX_YTDLP_MAX_CONCURRENT`, `QMIX_YTDLP_QUEUE_LIMIT`), which keeps
worst-case usage near 400 MiB inside the 512 MiB limit with headroom for the
Go runtime and proxy buffers.

**Room creation admission** (qmix#155). `POST /rooms` passes through one
process-wide non-blocking token-bucket registry before calling `Store.CreateRoom`:
each client may burst 5 creations and replenishes 10 tokens per minute. A denial
allocates no room and returns exactly HTTP 429, `application/json`, a positive
integer `Retry-After`, and
`{"error":"rate_limited","message":"room creation rate limit exceeded"}`. The
limiter expresses denial through an opaque typed admission error whose
package-owned constructors select fixed status, code, and message mappings for
room creation, future room queue rate limits, and future live-room capacity.
Callers can read but cannot supply or mutate public metadata; nil, zero-value,
and unknown denials map to a fixed safe 503 fallback. The HTTP boundary
centrally formats positive integer Retry-After delta-seconds; later admission
work can reuse this contract without being implemented by qmix#155.
The registry is mutex-protected but never waits, starts no goroutines or timers,
and is capped at 4096 identities. When full, it deterministically reclaims the
least-recently-seen bucket among inactive buckets that have fully replenished.
If no bucket is eligible, an unseen identity fails closed while known identities
continue against their existing token state. Admission occurs before the Store
mutex is acquired, so contention or rejection cannot stall unrelated room
operations. Queue submissions, live room counts, body/queue/TTL rules, and the
yt-dlp capacity limiter are unchanged.

The identity trust boundary is the transport peer. With the default empty
`QMIX_TRUSTED_PROXY_CIDRS`, `RemoteAddr` is canonicalized to an IPv4 or IPv6
address and all forwarding headers are ignored. If and only if that immediate
peer belongs to an explicitly configured trusted CIDR, QMix accepts one
unambiguous `X-Forwarded-For` field, parses address-only entries, walks from
right to left through trusted hops, and selects the first untrusted address (or
the leftmost address when every hop is trusted). Malformed/empty entries,
host-and-port entries, or multiple XFF fields fall back to the immediate peer.
IPv4-mapped peers and XFF entries are canonicalized to IPv4. Mapped trusted
prefixes from `/96` through `/128` are converted to equivalent IPv4 prefixes;
broader mapped forms are rejected at startup because they cannot match safely
after address canonicalization. `Forwarded` and `X-Real-IP` are never
consulted. This prevents an Internet
client from selecting a new rate-limit identity by spoofing proxy headers;
operators must list only reverse proxies that overwrite/sanitize XFF.

**Room submission admission** (qmix#156). Every room incarnation owns one
non-blocking token bucket shared by the canonical
`POST /rooms/{code}/queue` route and guest compatibility
`POST /r/{code}/queue` alias. The default burst is 10, replenished at 30 tokens
per minute. Reusing an expired room code creates a new incarnation and therefore
a fresh bucket. Exhaustion fails immediately with HTTP 429, a positive integer
`Retry-After`, and the same `rate_limited` envelope on both aliases; resolver
work does not start and queue, activity, and SSE state do not change.

The Store validates incarnation existence before body parsing and queue capacity
after parsing. Only a nonblank, bounded, syntactically valid request with room
capacity consumes a token, immediately before resolver work and outside
`Store.mu`; resolver rejection or failure does not refund it. Admission reserves
one queue slot under `Store.mu` before consulting the limiter, and revalidates
both incarnation and limiter identity afterward. In-flight reservations count
against the 100-track capacity, including for direct Store appends. They are
released on admission denial, stale incarnation, resolver failure, and every
append outcome. Expired work cannot inherit or charge a replacement bucket;
an old limiter result is discarded before resolver work starts. Every Store
constructor creates an explicit per-incarnation bucket; nil and invalid
limiters fail closed with the fixed room-submission denial. The 100-track
queue limit therefore retains 409 precedence, while the separate process-wide
yt-dlp concurrency and wait-queue limiter can still return 503 after room
admission has been consumed. Alias-specific successful response bodies remain
unchanged.

**Live-room capacity** (qmix#157). The Store admits at most 256 live rooms by
default (`QMIX_MAX_LIVE_ROOMS`). `CreateRoom` checks room-map cardinality under
the Store mutex, so concurrent creation cannot oversubscribe and no separate
counter or semaphore can drift. Capacity rejection is a typed admission error
mapped only by `POST /rooms` to `503`, `room_capacity_exhausted`, the fixed
message `room capacity is temporarily exhausted`, and a positive integer
`Retry-After`. That delay is the configured janitor cadence rounded up to at
least one second and is advisory, not a guaranteed expiry time. Existing-room
operations remain usable while full. The existing empty/non-empty expiry path
deletes rooms and releases slots while preserving incarnation, stale-reference,
and subscriber invalidation behavior.

## 8. State management

- All state is held **in memory** in one process, without a database.
- One `Store` supports multiple rooms.
- The `Store` exclusively owns mutable room records, room incarnation identity,
  per-incarnation submission limiters, host authorization, queue limits, expiry
  checks, activity timestamps, and mutation ordering. HTTP handlers pass room
  codes plus immutable operation references/results; they never retain live room
  pointers or access the room map or Store mutex.
- Public room views, append/reorder results, SSE payloads, event snapshots, and
  current-stream inputs are copied values with no mutable aliases into stored
  room state. `CreateRoom` returns only immutable code/token credentials.
- Resolver and stream backend calls run after Store snapshots/preflight and
  outside Store locks. Append commits revalidate the original room incarnation
  and queue capacity, so deletion, expiry, or code reuse during resolution
  cannot mutate a replacement room.
- A Store mutation holds the Store-before-Hub lock order through non-blocking
  event publication. Every Hub registered by a Server receives the committed
  event; registration is idempotent, and snapshots read the same Hub used by
  that Server's subscription rather than a mutable Store-wide sink. Mutation
  results and events therefore describe the same committed snapshot, skip
  events remain ordered as `track_changed`, `player_state`, then
  `queue_updated`, and SSE connection snapshots capture room state plus their
  event-ID boundary atomically.
- Hub subscriptions and publications are keyed by room code plus Store-assigned
  incarnation generation. Expiry disconnects that exact incarnation before its
  code can be reused, so a stale SSE stream cannot observe a replacement room.
- Empty rooms (no queued or current track) expire after 12 hours of inactivity.
  Non-empty rooms expire after 24 hours without a queue or playback mutation,
  bounding memory retained by abandoned queues.
- SSE clients can reconnect; on connection they receive a `queue_snapshot`
  containing the complete state.
- State loss on backend restart is acceptable because MVP rooms are ephemeral.

## 9. Deployment

- `cmd/qmix` reads all runtime `QMIX_*` variables once into one typed
  `internal/config.Config` before constructing the logger and application. That
  aggregate owns address, logging, optional resolver credentials, the shared
  yt-dlp executable, deadlines, and capacity limits, stream cache TTL, and
  current room lifetime settings. The composition root constructs the shared
  yt-dlp capacity limiter, resolver, stream backend,
  handlers, and readiness check once; `App.Handler()` has no environment reads,
  executable resolution, or dependency construction.
- Runtime variables are `QMIX_ADDR`, `QMIX_LOG_LEVEL`, `QMIX_LOG_FILE`,
  `QMIX_VK_TOKEN`, `QMIX_YM_TOKEN`, `QMIX_SPOTIFY_CLIENT_ID`,
  `QMIX_SPOTIFY_CLIENT_SECRET`, `QMIX_YTDLP_BIN`,
  `QMIX_STREAM_CACHE_TTL`, `QMIX_YTDLP_METADATA_TIMEOUT`,
  `QMIX_YTDLP_SEARCH_TIMEOUT`, `QMIX_YTDLP_MAX_CONCURRENT`,
  `QMIX_YTDLP_QUEUE_LIMIT`, `QMIX_ROOM_CREATE_RATE_PER_MINUTE`,
  `QMIX_ROOM_CREATE_BURST`, `QMIX_ROOM_CREATE_IDENTITY_LIMIT`,
  `QMIX_ROOM_SUBMISSION_RATE_PER_MINUTE`, `QMIX_ROOM_SUBMISSION_BURST`,
  `QMIX_MAX_LIVE_ROOMS`, and `QMIX_TRUSTED_PROXY_CIDRS`. Defaults are respectively `:8080`, `warn`,
  `qmix.log`, empty credentials, `yt-dlp`, `5m`, `30s`, `60s`, `2`, `8`, `10`,
  `5`, `4096`, `30`, `10`, `256`, and an empty trusted-proxy list.
  Build and Compose orchestration variables are outside this runtime contract.
- Missing or blank runtime values use their defaults. Explicit malformed
  levels/durations fail startup with one secret-safe JSON record. It retains
  `error_kind: invalid_configuration` and adds only the predeclared
  `config_variable` and correction `guidance`; supplied values and wrapped error
  details are never emitted. Other startup categories remain detail-free.
  Metadata and search timeouts must be positive. Cache TTL must be positive or
  negative (negative disables caching); explicit zero is invalid.
  `QMIX_YTDLP_MAX_CONCURRENT` must be a strictly positive integer. For
  `QMIX_YTDLP_QUEUE_LIMIT`, a positive integer sets the limit, while missing,
  blank, zero, or negative values use the documented default of 8. Malformed
  or overflowing capacity integers fail startup. Room creation and room
  submission rates must be in `1..60000`; their bursts must be in `1..10000`,
  and the room-creation identity limit must be in `1..65536`. Values outside
  those bounded domains fail startup rather than being clamped. The
  maximum live-room setting must be a strictly positive integer. The
  trusted-proxy setting must be empty or a comma-separated list of valid
  IPv4/IPv6 CIDRs. Those failures use
  the same safe diagnostic fields and never echo supplied values. Startup also
  fails safely when yt-dlp is missing or non-executable. `/readyz` rechecks the
  executable without invoking it, while `/healthz` remains unconditional.
- The process subscribes to `SIGINT` and `SIGTERM` with
  `signal.NotifyContext`. Shutdown closes the HTTP listener immediately, gives
  active HTTP, SSE, and audio-stream handlers an 11-second grace period, then
  cancels their request contexts and force-closes connections within a
  documented 12-second total deadline. The room janitor is stopped and joined
  exactly once on normal shutdown and listen/startup failure.
- One service: **docker-compose** with one `backend` container.
- The container is built from `Dockerfile` (multi-stage build, static Go binary).
- Port `8080`, configured through the `QMIX_ADDR` environment variable.
- Streaming requires **yt-dlp** for audio lookup. It is included in the Docker
  image (`apk add yt-dlp`, with the version pinned in `Dockerfile`). Outside the
  container, the binary must be on PATH or specified with `QMIX_YTDLP_BIN` for
  both metadata resolution and streaming. Link-cache behavior is configured
  through `QMIX_STREAM_CACHE_TTL` (default
  `5m`; a negative value disables the cache). The metadata resolver and stream
  search apply separate server-side yt-dlp deadlines (30 seconds and 60 seconds
  by default), configured with `QMIX_YTDLP_METADATA_TIMEOUT` and
  `QMIX_YTDLP_SEARCH_TIMEOUT`; invalid or non-positive explicit values fail
  startup. Concurrent yt-dlp subprocesses are bounded by one shared capacity
  limiter (default 2 running, 8 queued) sized for the 512 MiB `mem_limit`;
  excess requests are rejected with 503 plus `Retry-After`. Proxy variables
  used to access
  YouTube (`HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY`) are passed through Compose.
- CI (GitHub Actions): gofmt + vet, build, `go test -race`, and a coverage gate
  of at least 95%. The `docker` job builds the image, checks yt-dlp inside it,
  and smoke-tests `/healthz`. Optional credentialed service tests run only from
  trusted `main` in `service-integration.yml`. `live.yml` runs a live YouTube
  smoke test with a checksum-verified yt-dlp on the NAS self-hosted runner after
  pushes to `main`, manual dispatches, and a weekly schedule. Pull-request code
  and secrets remain on hosted runners and outside these trusted workflows.
- Deployment constraints: a dedicated Compose project, `mem_limit: 512m`,
  `restart: on-failure:3`, and external port `8180` (reserved for qmix in the
  8100–8199 range).
- Backend diagnostics are structured JSON with a default `WARN` level. One
  injected logger attributes records to `server/http`, `store/rooms`, `sse`,
  `resolver`, or `stream`, fans accepted records to stderr and a persistent
  file capped at 10 MiB, and excludes request bodies, authorization data, tokens,
  query strings, and secret-bearing upstream URLs. Compose persists `/var/log/qmix/qmix.log`
  below `agent-apps-data/qmix/logs`; an unprivileged no-follow preparation
  helper creates the bind source and grants the non-root backend access through
  a supplemental host group. Docker's JSON log stream is separately capped at
  three 10 MiB files. `QMIX_LOG_LEVEL` controls verbosity and `QMIX_LOG_FILE`
  controls the file for non-Compose runs.

## 10. Out of scope

- Voting, likes, and track ratings
- Public and searchable rooms
- Torrents and peer-to-peer audio sources
- Accounts, authentication, and personal data
- Offline mode and client-side caching
- Multiple hosts and transfer of room control
