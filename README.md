# QMix

A collaborative music player supporting multiple music services: the host runs
the player on Google TV, friends add links from VK / Yandex Music / Spotify to
a shared queue, and the backend resolves tracks and streams audio.

## Status

Completed:

- **M0** — foundation: Go backend scaffold with `/healthz`, Makefile, docker-compose, and CI.
- **M1** — rooms and queues: create/get, add track, skip/reorder, and SSE events.
- **M2** — URL-to-track resolver through public endpoints. Spotify uses oEmbed; anonymous VK and Yandex Music resolution first attempts Open Graph metadata and returns 422 if metadata is unavailable.
- **M3** — streaming: `yt-dlp` StreamBackend plus an HTTP proxy with Range/seek support and a TTL link cache. The required 10-minute live acceptance passed on trusted `main` ([evidence](https://github.com/leugenea/qmix/actions/runs/34943135393)).
- **token-vk v2** — api.vk.com token with the `audio` scope (unblocks #9).
- **token-ym** — small CLI for obtaining a Yandex Music OAuth token through device flow, without sqlite (closes #12).
- **Authenticated Spotify resolution** (#8) — Client Credentials (`accounts.spotify.com/api/token`) + Web API (`api.spotify.com`); oEmbed remains the fallback when credentials are absent.
- **Authenticated VK resolution** (#9) — VK audio links are resolved through `audio.getById` (api.vk.com, v5.131, using the mobile client User-Agent from `cmd/token-vk`); without `QMIX_VK_TOKEN`, the anonymous Open Graph metadata path remains available.
- **Authenticated Yandex Music resolution** (#10) — music.yandex.ru track links (`/album/{a}/track/{t}` and `/track/{t}`) are resolved through the maintained `goym` client (api.music.yandex.net, `GET /tracks/{id}`); without `QMIX_YM_TOKEN`, the anonymous Open Graph metadata path remains available.
- **Integration tests** for the main scenarios — required `integration-mandatory` CI job.
- Updated toolchain: Go 1.25 / alpine 3.24, with compose configured for deployment constraints (port 8180, `mem_limit`, `restart: on-failure:3`).
- **M5** — guest PWA, completed in #55.

In progress / next:

- **M4** — the single-module Kotlin/Compose for TV scaffold, Media3 playback engine, room creation/QR flow, live room synchronization, queue advancement, authoritative local playback coordination, and focused local playback controls are implemented via #71, #72, #73, #101, #124, #121, and #122. Foreground recovery and assembled-flow verification remain owned by #123 (see [`android/README.md`](android/README.md)).
- **M6** — Definition of Done (E2E + v0.1.0).
- Automatic Yandex token refresh through `QMIX_YM_REFRESH_TOKEN` (`goym` does not provide built-in refresh support, so this is a separate task).

## Streaming

Audio for the current track is served by **StreamBackend**, using `yt-dlp`.
When the host presses *skip*, the track
becomes current and the player retrieves its stream from
`GET /rooms/{code}/current/stream`.

How it works:

1. Tracks resolved by the YouTube resolver are passed to `yt-dlp` using their
   original submitted URL, so streaming selects that exact video. Both metadata
   and stream lookups use `--no-playlist`: a watch URL containing `v` and `list`
   selects only the `v` video, while a playlist-only YouTube URL without a video
   ID is rejected as unsupported (`422`) before `yt-dlp` starts. Tracks from
   Spotify, VK, Yandex Music, and other non-YouTube resolvers keep the metadata
   search path (`Artist - Title`) via
   `yt-dlp --skip-download --dump-json --no-playlist -f bestaudio "ytsearch:..."`.
2. The direct audio URL is cached for approximately 5 minutes. Direct YouTube
   entries are keyed by source URL; metadata-search entries are keyed by artist
   and title.
3. The endpoint streams audio with **HTTP Range/seek** support: a full stream
   returns `200`, a satisfiable range returns `206 Partial Content`, and an
   invalid range returns `416`. The `Content-Type`, `Content-Length`,
   `Content-Range`, and `Accept-Ranges` headers are forwarded to the client.

**Requirement:** `yt-dlp` must be installed in `PATH` on the backend host (or
its path must be set through `QMIX_YTDLP_BIN`, which configures both metadata
resolution and streaming). The Docker image already includes `yt-dlp` at the
version pinned in `Dockerfile`. Configure the cache
with `QMIX_STREAM_CACHE_TTL` (default: `5m`). Metadata extraction and stream
search subprocesses have independent hard deadlines: 30 seconds and 60 seconds
respectively. Override them with `QMIX_YTDLP_METADATA_TIMEOUT` and
`QMIX_YTDLP_SEARCH_TIMEOUT` using Go duration syntax. Explicit malformed, zero,
or negative timeout values stop startup; a negative stream cache TTL disables
caching, while an explicit zero cache TTL is invalid. If YouTube is available only
through a proxy, set `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` in the environment;
compose passes them to the container, where they are used by both `yt-dlp` and
the audio HTTP proxy.

**Capacity:** one shared limiter bounds how many `yt-dlp` subprocesses run at
once across metadata resolution and streaming, because the Compose service is
capped at 512 MiB and a single `yt-dlp` process can spike to roughly
100–200 MiB RSS. Requests that find no free slot wait in a bounded FIFO queue;
when the queue is also full, add-track and stream requests fail fast with
`503` plus a `Retry-After` header instead of piling onto the overload. The
queue is never held while waiting for capacity, so room operations stay
responsive. See `QMIX_YTDLP_MAX_CONCURRENT` and `QMIX_YTDLP_QUEUE_LIMIT` in the
configuration table below.

## Room creation rate limit

`POST /rooms` is protected before room allocation by a non-blocking per-client
token bucket. The default burst is 5 room creations and the bucket replenishes
at 10 tokens per minute. Rejected requests return HTTP `429`,
`Content-Type: application/json`, a positive integer `Retry-After`, and the
fixed public envelope
`{"error":"rate_limited","message":"room creation rate limit exceeded"}`.
The limiter returns an opaque typed admission denial. Package-owned constructors
select an allowlisted status/code/message mapping for room creation, future room
queue rate limits, or future live-room capacity; callers can only read that
mapping and a normalized retry delta. Nil, zero-value, and unknown denials map to
a fixed safe `503` fallback. The HTTP boundary uses one shared positive integer
`Retry-After` delta-seconds formatter. This contract is available to later
admission limits without enabling those limits here. This limit does not apply
to queue submission or any other route.

The client identity is the immediate TCP peer address by default. `Forwarded`,
`X-Real-IP`, and `X-Forwarded-For` are all ignored for direct deployments, so a
client cannot evade the limit by supplying forwarding headers. For a deployment
behind a known reverse proxy, set `QMIX_TRUSTED_PROXY_CIDRS` to the proxy CIDRs.
Only then is a single `X-Forwarded-For` field accepted: QMix walks its addresses
right-to-left through trusted hops and uses the first untrusted address.
Malformed, empty, host-and-port, or multiple XFF fields fall back to the
immediate peer. `Forwarded` and `X-Real-IP` are never used. IPv4-mapped peer and
XFF addresses are canonicalized to IPv4. Mapped trusted prefixes with lengths
from `/96` through `/128` are canonicalized to equivalent IPv4 prefixes; broader
mapped forms are rejected at startup because they cannot be represented after
address canonicalization.

Examples:

```bash
# Direct exposure: leave proxy trust empty (the safe default).
QMIX_TRUSTED_PROXY_CIDRS= make run

# Behind reverse proxies on these private networks only.
QMIX_TRUSTED_PROXY_CIDRS="10.0.0.0/8,2001:db8:100::/48" make run
```

The identity registry is capped at 4096 entries by default. At capacity, QMix
deterministically reclaims the least-recently-seen inactive bucket only after it
is fully replenished. If none is reclaimable, a new identity fails closed with
429 while identities already in the registry continue according to their own
buckets. Cleanup runs inline; there are no per-identity goroutines or timers.

## Room submission rate limit

Each room incarnation has one non-blocking token bucket shared by
`POST /rooms/{code}/queue` and `POST /r/{code}/queue`; alternating aliases does
not increase capacity. The default burst is 10 syntactically valid submissions,
with 30 tokens replenished per minute. A room code reused after expiry receives
a fresh bucket. Exhausted requests return HTTP `429`, `application/json`, a
positive integer `Retry-After`, and
`{"error":"rate_limited","message":"room submission rate limit exceeded"}`
without starting resolver work or mutating room or SSE state.

Room existence, the bounded 4 KiB JSON body, URL validation, and an already-full
100-track queue are checked before a token is consumed. Once those checks pass,
one token is consumed before resolver work, including when the resolver later
rejects the URL or fails upstream. This admission limit is separate from the
100-track queue length and from the process-wide yt-dlp concurrency/wait-queue
limit: queue-full remains `409`, while exhausted yt-dlp capacity remains `503`
after room admission has been consumed.

## Live room capacity

The in-memory Store admits at most `QMIX_MAX_LIVE_ROOMS` live rooms (default
`256`) in one process. Capacity is checked atomically against the room map.
When full, `POST /rooms` returns `503`, a positive integer `Retry-After`, and
`{"error":"room_capacity_exhausted","message":"room capacity is temporarily exhausted"}`.
The retry delay is advisory and derived from the room janitor cadence; it does
not guarantee that a room will expire at that time. Existing-room REST, SSE,
queue, and streaming operations remain available at capacity. The ordinary
empty/non-empty expiry sweep releases slots; there is no separate capacity
lifecycle.

## Integration tests

The required test tier is `make test-integration`: HTTP scenarios run against a
real socket with the full `App.Handler()`, without network access or secrets:

- `/healthz`;
- room lifecycle: create -> get -> add track -> skip/reorder (host token);
- SSE: snapshot on connection, mutation events, and snapshot on reconnection;
- host player reports over `PATCH /rooms/{code}/player`, including pause, seek,
  safe error state, completion, exact SSE payloads, and reconnect consistency;
- adding a track through a mock resolver (unknown link -> 422);
- streaming through a fake `yt-dlp` (configured with `QMIX_YTDLP_BIN`) and a
  local mock upstream with Range support: exact YouTube URL selection,
  non-YouTube metadata-search fallback, and 200/206 responses;
- TTL: an empty room expires on the shorter TTL, while a recently active room
  with a queue survives that interval; abandoned non-empty rooms expire after
  24 hours.

The tests use the `integration` build tag, so regular `go test ./...` runs and
the coverage gate do not include them. CI runs them in the separate required
`integration-mandatory` job.

Network-dependent scenarios remain optional and isolated from pull requests.
Credentialed VK/Spotify/Yandex checks run after pushes to trusted `main` or a
manual dispatch of `main`; `live.yml` runs only trusted code on a self-hosted
runner.

### M3 live acceptance

The required acceptance passed on trusted `main` at commit `6726af8`; its logs
and the `qmix-stream-acceptance` artifact are attached to the
[workflow run](https://github.com/leugenea/qmix/actions/runs/34943135393).

To repeat the check, run the `live` workflow manually on `main` with
**stream_acceptance** enabled. The blocking test streams a long-form track through
`GET /rooms/{code}/current/stream` for 10 continuous minutes, then verifies
repeated non-adjacent Range seeks, including a fresh lookup after the five-minute
direct-URL cache expires. The fixture is pinned by YouTube video ID and must
report a duration of at least 10 minutes. Playback fails if any read makes no
progress for five seconds; its byte target and pacing are derived from the
selected format's duration and Content-Length so the run consumes 10 minutes of
media over 10 wall-clock minutes. The workflow bypasses Go's test cache and
uploads the timestamp, tool versions, selected media identity, elapsed time,
byte count, lookup count, and seek results as the `qmix-stream-acceptance`
artifact. Link each release-blocking run from its acceptance issue.

## Running

```bash
make run
curl localhost:8080/healthz
curl localhost:8080/readyz

# or with Docker (external port 8180)
make compose-up
curl localhost:8180/healthz
curl localhost:8180/readyz
```

## Startup configuration

The backend reads the runtime environment exactly once before constructing the
application. One typed configuration is then passed to logging, the resolver,
the stream backend, room storage, and the HTTP server; `App.Handler()` only
returns the already-built handler. Build metadata and Compose-only variables
such as `VERSION`, `COMMIT`, `QMIX_IMAGE_TAG`, and `QMIX_LOG_GID` are not runtime
application configuration.

| Variable | Default | Contract |
|---|---|---|
| `QMIX_ADDR` | `:8080` | HTTP listen address |
| `QMIX_LOG_LEVEL` | `warn` | `debug`, `info`, `warn`/`warning`, or `error` |
| `QMIX_LOG_FILE` | `qmix.log` | Persistent JSON log file |
| `QMIX_VK_TOKEN` | empty | Optional VK credential |
| `QMIX_YM_TOKEN` | empty | Optional Yandex Music credential |
| `QMIX_SPOTIFY_CLIENT_ID` | empty | Optional Spotify client ID |
| `QMIX_SPOTIFY_CLIENT_SECRET` | empty | Optional Spotify client secret |
| `QMIX_YTDLP_BIN` | `yt-dlp` | Required executable name or path, shared by metadata and streaming |
| `QMIX_STREAM_CACHE_TTL` | `5m` | Positive Go duration; a negative duration disables caching; zero is invalid |
| `QMIX_YTDLP_METADATA_TIMEOUT` | `30s` | Positive Go duration |
| `QMIX_YTDLP_SEARCH_TIMEOUT` | `60s` | Positive Go duration |
| `QMIX_YTDLP_MAX_CONCURRENT` | `2` | Positive integer; concurrent `yt-dlp` subprocesses allowed across metadata and streaming. The default keeps worst-case usage near 400 MiB inside the 512 MiB container, since one `yt-dlp` process can spike to 100–200 MiB RSS |
| `QMIX_YTDLP_QUEUE_LIMIT` | `8` | Positive integers set the number of callers that may wait for a subprocess slot; missing, blank, zero, or negative values use the default `8`. Further requests are rejected with `503` and `Retry-After` |
| `QMIX_ROOM_CREATE_RATE_PER_MINUTE` | `10` | Integer from `1` through `60000`; token replenishment rate per client identity for `POST /rooms` |
| `QMIX_ROOM_CREATE_BURST` | `5` | Integer from `1` through `10000`; maximum token-bucket burst per client identity |
| `QMIX_ROOM_CREATE_IDENTITY_LIMIT` | `4096` | Integer from `1` through `65536`; hard cap on retained client-identity buckets; unseen identities fail closed with `429` when no inactive full bucket can be reclaimed |
| `QMIX_ROOM_SUBMISSION_RATE_PER_MINUTE` | `30` | Integer from `1` through `60000`; per-room-incarnation queue-submission token replenishment shared by canonical and guest aliases |
| `QMIX_ROOM_SUBMISSION_BURST` | `10` | Integer from `1` through `10000`; maximum admitted queue-submission burst per room incarnation |
| `QMIX_MAX_LIVE_ROOMS` | `256` | Strictly positive integer; maximum live rooms in this process. Expiry releases capacity |
| `QMIX_TRUSTED_PROXY_CIDRS` | empty | Comma-separated IPv4/IPv6 CIDRs for immediate reverse proxies trusted to supply one `X-Forwarded-For` field; empty means all forwarding headers are ignored |

Missing or blank values use the listed defaults. `QMIX_MAX_LIVE_ROOMS` must be
a strictly positive integer; malformed, zero, negative, and overflowing values
fail startup. For
`QMIX_YTDLP_QUEUE_LIMIT`, explicit zero or any negative integer also uses the
listed default; malformed or overflowing integers are invalid. Room creation
rate must be from 1 through 60000, burst from 1 through 10000, and identity
limit from 1 through 65536. Room submission rate and burst use the same
respective bounded domains; out-of-range values are rejected rather than
clamped. Trusted proxy entries must each be an IPv4 or IPv6 CIDR; malformed or
empty list members are invalid. IPv4-mapped CIDRs must use prefix lengths from
`/96` through `/128`; QMix converts those to equivalent IPv4 prefixes and
rejects broader mapped forms without echoing the supplied configuration value.
An explicitly supplied invalid log level, duration, capacity integer, room
creation setting, or proxy list stops startup with one JSON record whose
`error_kind` is `invalid_configuration`; `config_variable` names the setting and
`guidance` gives a safe correction without including the supplied value.
Other startup failure categories do not include these detail fields. Startup
also stops if the configured/default `yt-dlp` cannot be resolved as an
executable; the check does not invoke it or access the network.

`GET /healthz` is unconditional liveness and always returns `200` with
`{"status":"ok"}` while the process can serve HTTP. `GET /readyz` rechecks the
required executable on every request and returns `200` with
`{"status":"ready"}` only while it remains resolvable and executable; otherwise
it returns stable `503` JSON `{"status":"unavailable"}`. Readiness never runs
`yt-dlp` and never performs network access.

The backend handles both `SIGINT` and `SIGTERM` through `signal.NotifyContext`.
It stops accepting new connections immediately, allows active HTTP, SSE, and
audio-stream work 11 seconds to finish, then cancels request contexts and
force-closes remaining connections within a 12-second total deadline. The room
janitor is stopped and joined exactly once on this path and on listen failure.

## Backend logs

The backend emits structured JSON to stderr (visible with
`docker compose logs backend`) and to one persistent file. The effective
default level is `WARN`. Set `QMIX_LOG_LEVEL` to `debug`, `info`, `warn`, or
`error`; invalid values stop startup with a configuration error. Set
`QMIX_LOG_FILE` to choose the file used outside Compose (default: `qmix.log`).

Compose fixes the container path at `/var/log/qmix/qmix.log` and persists it at
`agent-apps-data/qmix/logs/qmix.log` on the host. `make compose-up` runs an
unprivileged preparation helper that creates each directory without following
symlinks, sets the log directory to mode `0770`, and grants the non-root backend
write access through the host user's group. Compose refuses to create a missing
bind source; this keeps privileged containers from changing host ownership or
permissions.

```bash
# Raise verbosity for one run and follow Docker's standard stream.
QMIX_LOG_LEVEL=debug make compose-up COMPOSE_ARGS=-d
docker compose logs backend --follow

# Read or copy the persistent file through the running container.
docker compose exec backend tail -n 200 /var/log/qmix/qmix.log
docker compose cp backend:/var/log/qmix/qmix.log ./qmix-debug.log
```

The process fails before listening if the persistent file cannot be opened. The
persistent sink is capped at `10 MiB`; reaching that bound, or any other later
write failure, leaves console logging active, emits the structured JSON diagnostic
`persistent log write failed`, and disables the file sink for the rest of the process.
Existing log files are restricted to mode `0600`, and symlinks or non-regular
files are rejected. Rotate the file before it reaches the cap with an external
size/retention policy using `copytruncate` so the process keeps writing to the
same inode. Records use
stable `component` values including `server/http`, `store/rooms`, `sse`,
`resolver`, and `stream`. Request bodies, query strings, authorization headers,
host tokens, OAuth tokens, credentials, and secret-bearing upstream URLs are
never written to logs.

Compose also bounds Docker's standard JSON log stream to three `10 MiB` files.

## Build version

The server, CLI utilities, Docker image, and Android APK use one SemVer version.
`make version` prints the computed `version`, `commit`, `dirty`, and Android
`versionCode`; `make build` embeds them in every Go binary. Dev/RC/stable rules,
version bumps, and the Android contract are documented in
[`docs/versioning.md`](docs/versioning.md).

```bash
make version
make build
./bin/qmix --version
./bin/token-vk --version
./bin/token-ym --version
```

## Service tokens

Authenticated resolution for VK / Yandex Music / Spotify is enabled with
service tokens. They are read from the environment at startup, following the
same pattern as `QMIX_ADDR`:

| Variable | Service | Description |
|---|---|---|
| `QMIX_VK_TOKEN` | VK | api.vk.com access token (`audio` scope) |
| `QMIX_YM_TOKEN` | Yandex Music | Yandex Music OAuth token |
| `QMIX_SPOTIFY_CLIENT_ID` | Spotify | Application Client ID |
| `QMIX_SPOTIFY_CLIENT_SECRET` | Spotify | Application Client Secret |

Without tokens, the backend operates in anonymous M2 mode: Spotify uses oEmbed;
VK and Yandex Music first attempt Open Graph metadata and return 422 when it is
unavailable. Tokens are required only for the authenticated paths (issues
#8-#10).

### Obtaining credentials

**Spotify** — create an application in the
[Spotify Developer Dashboard](https://developer.spotify.com/dashboard) and
copy its Client ID and Client Secret.

**VK** — obtain an api.vk.com token with the `audio` scope through the
`cmd/token-vk` utility (see below), using a VK login (phone or email), password,
and confirmation code (2FA application / SMS / call).

**Yandex Music** — obtain a token through the `cmd/token-ym` utility (see below)
using Yandex OAuth device flow: the utility prints a URL and code for browser
confirmation.

### Supplying credentials

**Environment variables at startup:**

```bash
QMIX_VK_TOKEN=... QMIX_YM_TOKEN=... \
QMIX_SPOTIFY_CLIENT_ID=... QMIX_SPOTIFY_CLIENT_SECRET=... \
make run
```

**`.env` file** (ignored by `.gitignore`; do not commit it):

```bash
QMIX_VK_TOKEN=...
QMIX_YM_TOKEN=...
QMIX_SPOTIFY_CLIENT_ID=...
QMIX_SPOTIFY_CLIENT_SECRET=...
```

**docker-compose `env_file`:**

```yaml
services:
  backend:
    env_file: .env
```

**CI (GitHub Actions):**

```bash
gh secret set QMIX_VK_TOKEN
gh secret set QMIX_YM_TOKEN
gh secret set QMIX_SPOTIFY_CLIENT_ID
gh secret set QMIX_SPOTIFY_CLIENT_SECRET
```

Secrets are passed only to the optional trusted workflow in
`.github/workflows/service-integration.yml`. It runs after pushes to `main` or
manual dispatches of `main` and does not block the pull-request CI workflow.

Live YouTube checks (real `yt-dlp` from a residential IP) run in the separate
`live.yml` workflow on the self-hosted `nas` runner after pushes to `main`,
manually (`gh workflow run live`), and weekly on a schedule. Pull-request code
never runs on that persistent runner, and this workflow does not block main CI.

### Obtaining a VK token with `cmd/token-vk`

The `cmd/token-vk` utility obtains an api.vk.com token with the `audio,offline`
scopes through the `oauth.vk.com/token` password grant on behalf of the official
VK for Android client. This is the same flow used by supported VK clients such
as vkpymusic. The token is printed to stdout and **nothing is saved to disk**.
The login, password, and 2FA code are read from stdin rather than argv, so the
password does not enter shell history.

**Build and run:**

```bash
go run ./cmd/token-vk
# or build the binary:
go build -o bin/token-vk ./cmd/token-vk
./bin/token-vk
```

The utility prompts for the following values in order:

1. `Login (phone or email):` — VK login (phone or email).
2. `Password:` — VK password.
3. Confirmation code — the utility reports where it was sent (SMS /
   authenticator app / call) and prompts again if the code is incorrect.

**Output format** (machine-readable, one field per line):

```
access_token=...
user_id=...
expires_in=...
```

The `expires_in` field has three states:

| Value | VK response | Utility behavior |
| --- | --- | --- |
| `0` | The token does not have a scheduled expiration; the `offline` scope was applied | Silent |
| `>0` | The token is temporary; `offline` was not granted | Warning on stderr |
| `unknown` | The response omitted `expires_in` | Warning on stderr |

In the password grant flow, VK ignores the requested `scope` and returns the
application's own permission mask, so `offline` is not guaranteed. The actual
response must therefore be checked. The `unknown` state is intentionally
distinct from `0`: an absent field is not evidence that a token never expires.

> `expires_in=0` means only that there is no scheduled expiration. The token is
> still bound to a VK session and may be revoked early after a password change,
> session termination in security settings, or an anti-fraud action. See
> [Revocation and rotation](#revocation-and-rotation).

For convenience, pipe the value directly to `gh secret set` or an environment
file:

```bash
go run ./cmd/token-vk | sed -n 's/^access_token=//p' | gh secret set QMIX_VK_TOKEN
```

The token is printed only to stdout and is not logged. On errors such as an
incorrect password, flood control, or CAPTCHA, the utility writes a
human-readable message to stderr and exits with a nonzero status. To resubmit a
confirmation code, restart the utility.

### Obtaining a Yandex Music token with `cmd/token-ym`

The `cmd/token-ym` utility obtains a Yandex Music OAuth token through the Yandex
OAuth **device code flow** (`oauth.yandex.ru/device/code` +
`oauth.yandex.ru/token`) using the Yandex Music application credentials embedded
in the `synchro` sources (`remote/yandexmusic`). It saves nothing to disk and
logs no secrets; the token is printed to stdout in machine-readable form.

**Build and run:**

```bash
go run ./cmd/token-ym
# or build the binary:
go build -o bin/token-ym ./cmd/token-ym
./bin/token-ym
```

The utility prints the confirmation page URL and a `user_code`. Open the URL in
a browser, sign in to the Yandex account, and enter the code. After approval,
the utility prints the tokens:

```
access_token=...
refresh_token=...
```

For convenience, pipe the value directly to `gh secret set` or an environment
file:

```bash
go run ./cmd/token-ym | sed -n 's/^access_token=//p' | gh secret set QMIX_YM_TOKEN
```

The token is valid for approximately one year according to the response's
`expires_in` value; rerun the utility to obtain a new one. On errors
(`expired_token` when the code is not entered within 10 minutes, or
`access_denied` when access is declined on the page), the utility writes a
human-readable message to stderr and exits with a nonzero status.

### Obtaining a Yandex Music token through `synchro` (fallback)

> `cmd/token-ym` is the preferred method for Yandex Music. `synchro` is no
> longer used for VK; obtain VK tokens with `cmd/token-vk`.

The command comes from the `github.com/oklookat/synchro` CLI:

```bash
synchro account add yandexmusic
```

The CLI opens the Yandex OAuth page for sign-in and code confirmation, then
stores the token in its local `data/data.sqlite` database. `synchro` does not
provide a supported command for printing that token, so use `cmd/token-ym`
unless compatibility with an existing `synchro` account is required. Direct
database extraction is undocumented and not recommended.

### Security rules

- Tokens are **never committed** to the repository (`.env` and `.env.*` are in
  `.gitignore`).
- Tokens are **never logged**; their values are not written to logs or included
  in API responses.
- Tokens are **never included in fixtures** or test data.
- CI receives tokens only through GitHub Secrets, never through source code.

### Revocation and rotation

- **Spotify** — rotate credentials in the
  [Developer Dashboard](https://developer.spotify.com/dashboard): generate a
  new Client Secret, which invalidates the old one.
- **VK** — terminate active sessions in VK security settings, then obtain a new
  token through `cmd/token-vk`.
- **Yandex** — revoke the OAuth token in Yandex ID settings under Applications /
  Access management (the token is issued to the `qmix-token-ym` device), then
  obtain a new token through `cmd/token-ym`.

## Stack

- Backend: Go, REST + SSE, in-memory state
- TV host: Kotlin + Compose for TV + Media3; scaffold, room creation/QR, live synchronization, queue advancement, authoritative local playback coordination, and focused local playback controls are implemented; foreground recovery and assembled-flow verification remain owned by #123
- Guests: login-free PWA (completed in #55)
