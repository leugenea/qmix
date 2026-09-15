# QMix

A collaborative music player supporting multiple music services: the host runs
the player on Google TV, friends add links from VK / Yandex Music / Spotify to
a shared queue, and the backend resolves tracks and streams audio.

## Status

Completed:

- **M0** — foundation: Go backend scaffold with `/healthz`, Makefile, docker-compose, and CI.
- **M1** — rooms and queues: create/get, add track, skip/reorder, and SSE events.
- **M2** — URL-to-track resolver through public endpoints. Spotify uses oEmbed; anonymous VK and Yandex Music resolution first attempts Open Graph metadata and returns 422 if metadata is unavailable.
- **token-vk v2** — api.vk.com token with the `audio` scope (unblocks #9).
- **token-ym** — small CLI for obtaining a Yandex Music OAuth token through device flow, without sqlite (closes #12).
- **Authenticated Spotify resolution** (#8) — Client Credentials (`accounts.spotify.com/api/token`) + Web API (`api.spotify.com`); oEmbed remains the fallback when credentials are absent.
- **Authenticated VK resolution** (#9) — VK audio links are resolved through `audio.getById` (api.vk.com, v5.131, using the mobile client User-Agent from `cmd/token-vk`); without `QMIX_VK_TOKEN`, the anonymous Open Graph metadata path remains available.
- **Authenticated Yandex Music resolution** (#10) — music.yandex.ru track links (`/album/{a}/track/{t}` and `/track/{t}`) are resolved through the maintained `goym` client (api.music.yandex.net, `GET /tracks/{id}`); without `QMIX_YM_TOKEN`, the anonymous Open Graph metadata path remains available.
- **Integration tests** for the main scenarios — required `integration-mandatory` CI job.
- Updated toolchain: Go 1.25 / alpine 3.24, with compose configured for deployment constraints (port 8180, `mem_limit`, `restart: on-failure:3`).
- **M5** — guest PWA, completed in #55.

In progress / next:

- **M3 acceptance** — the streaming implementation is complete, but M3 remains pending until the required 10-minute continuous playback and repeated seek acceptance passes through the QMix proxy.
- **M4** — the single-module Kotlin/Compose for TV scaffold, Media3 playback engine, and room creation/QR flow are implemented via #71, #72, and #73. Live room synchronization and coordinated playback remain to be completed (see [`android/README.md`](android/README.md)).
- **M6** — Definition of Done (E2E + v0.1.0).
- Automatic Yandex token refresh through `QMIX_YM_REFRESH_TOKEN` (`goym` does not provide built-in refresh support, so this is a separate task).

## Streaming

Audio for the current track is served by **StreamBackend**, which searches
YouTube through `yt-dlp` by default. When the host presses *skip*, the track
becomes current and the player retrieves its stream from
`GET /rooms/{code}/current/stream`.

How it works:

1. The backend searches YouTube using the track metadata (`Artist - Title`) and
   `yt-dlp --skip-download --dump-json -f bestaudio "ytsearch:..."`.
2. The direct audio URL is cached for approximately 5 minutes, so repeated
   requests for the same track do not trigger another search.
3. The endpoint streams audio with **HTTP Range/seek** support: a full stream
   returns `200`, a satisfiable range returns `206 Partial Content`, and an
   invalid range returns `416`. The `Content-Type`, `Content-Length`,
   `Content-Range`, and `Accept-Ranges` headers are forwarded to the client.

**Requirement:** `yt-dlp` must be installed in `PATH` on the backend host (or
its path must be set through `QMIX_YTDLP_BIN`). The Docker image already
includes `yt-dlp` at the version pinned in `Dockerfile`. Configure the cache
with `QMIX_STREAM_CACHE_TTL` (default: `5m`). If YouTube is available only
through a proxy, set `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` in the environment;
compose passes them to the container, where they are used by both `yt-dlp` and
the audio HTTP proxy.

## Integration tests

The required test tier is `make test-integration`: HTTP scenarios run against a
real socket with the full `App.Handler()`, without network access or secrets:

- `/healthz`;
- room lifecycle: create -> get -> add track -> skip/reorder (host token);
- SSE: snapshot on connection, mutation events, and snapshot on reconnection;
- adding a track through a mock resolver (unknown link -> 422);
- streaming through a fake `yt-dlp` (configured with `QMIX_YTDLP_BIN`) and a
  local mock upstream with Range support: 200/206;
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

After the acceptance harness is reviewed and merged to `main`, run the `live`
workflow manually on `main` with **stream_acceptance** enabled. The blocking
test streams a long-form track through
`GET /rooms/{code}/current/stream` for 10 continuous minutes, then verifies
repeated non-adjacent Range seeks, including a fresh lookup after the five-minute
direct-URL cache expires. The fixture is pinned by YouTube video ID and must
report a duration of at least 10 minutes. Playback fails if any read makes no
progress for five seconds; its byte target and pacing are derived from the
selected format's duration and Content-Length so the run consumes 10 minutes of
media over 10 wall-clock minutes. The workflow bypasses Go's test cache and
uploads the timestamp, tool versions, selected media identity, elapsed time,
byte count, lookup count, and seek results as the `qmix-stream-acceptance`
artifact. Link the successful run from the acceptance issue before marking M3
complete.

## Running

```bash
make run
curl localhost:8080/healthz

# or with Docker (external port 8180)
eval "$(go run ./internal/buildinfo/cmd/version -format=env)"
export VERSION COMMIT DIRTY ANDROID_VERSION_CODE
docker compose up --build
curl localhost:8180/healthz
```

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
- TV host: Kotlin + Compose for TV + Media3; scaffold, playback engine, and room creation/QR are implemented, while live synchronization and coordinated playback remain in progress
- Guests: login-free PWA (completed in #55)
