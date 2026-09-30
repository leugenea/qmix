# Guest web app: current MVP contract (qmix#140)

The guest web app is the login-free browser UI at `/r/{code}`. Opening a link
or scanning the host TV's QR code requires neither an account nor installation.
The MVP supports online link submission and live current-track/queue viewing;
it does not promise event replay, guaranteed installation, or offline operation.

## Invitation examples

The backend returns a relative guest web app URL such as `/r/ABCD` from
`POST /rooms`. Android combines it with the configured guest origin; the QR
contains that absolute URL without a host token, query, or fragment.

| Deployment | Guest web app origin | Guest web app URL / QR contents |
|---|---|---|
| Trusted LAN, insecure HTTP | `http://192.168.1.20:8180` | `http://192.168.1.20:8180/r/ABCD` |
| Public or remote, recommended HTTPS | `https://qmix.example` | `https://qmix.example/r/ABCD` |

These addresses are examples, not fallback settings or published services. The
phone must be able to reach the guest origin. Its routes must serve `/r/`,
`/assets/`, and the same-origin `/rooms/{code}/events` endpoint used by the UI;
HTTPS deployments terminate TLS at a reverse proxy in front of the HTTP backend.

Trusted-LAN HTTP is supported but provides no transport confidentiality or
integrity. Network peers can observe or modify room traffic and host credentials;
Android requires a one-time acknowledgement before its first HTTP request. Do
not expose this HTTP deployment to untrusted networks. HTTPS is supported and
recommended for public or remote deployment.

An ordinary private LAN HTTP origin is not a secure context for service workers.
Localhost/loopback development exceptions do not make a remote LAN origin secure
when opened from a phone. The manifest provides presentation metadata (name,
icons, colors, and display preference), not proof of installability. This MVP has
no service worker, offline page-loading contract, or offline submission queue,
even on HTTPS. Browser-specific installation options are not an MVP guarantee;
ordinary HTTP asset caching is not an implemented offline mode.

## Snapshot-only recovery

Every successful `GET /rooms/{code}/events` connection starts with a fresh
`queue_snapshot` containing current and queue, including on reconnect. The
server ignores `Last-Event-ID`; event IDs sequence live updates but do not
provide retained history. Missing intermediate events are not replayed.

The guest web app applies the snapshot, then live `queue_updated`,
`track_changed`, and matching `player_state` payloads. It remains visibly
reconnecting until the new stream supplies a snapshot and ignores obsolete
connections. Android treats those four room event names as invalidations,
ignores payload data, and reconciles through serialized/coalesced REST
`GET /rooms/{code}` reads, also after SSE reopens. Its 15-second periodic REST
refresh is a recovery mechanism, not event replay.

## Current MVP acceptance

| Check | Expected result / existing evidence |
|---|---|
| Open the guest web app URL directly or scan the TV QR | Room UI opens without login or an installation step; `TestGuestPageServesRoomUIWithoutAuthentication` in [`guest_page_test.go`](../internal/server/guest_page_test.go) and `qr_decodes_to_absolute_guest_url_without_host_token` in [`GuestInviteTest.kt`](../android/app/src/test/java/com/qmix/tv/GuestInviteTest.kt) cover the page and QR contracts. |
| Connect, mutate the room, disconnect, and reconnect with `Last-Event-ID: 1` | The first event is a fresh `queue_snapshot`, not replay; `TestIntegrationSSE` and `TestIntegrationPlayerReportsAndReconnect` in [`integration_mandatory_test.go`](../internal/server/integration_mandatory_test.go) verify latest state over a real socket. |
| Restore the guest web app stream | Connection status stays reconnecting until the new snapshot, then current/queue reflect it; the disconnect/reconnect test in [`app.test.js`](../internal/server/guest/app.test.js) covers this without a browser installation assumption. |
| Reopen the Android SSE stream | Reopen triggers a REST reconciliation; `reopened_sse_after_a_completed_get_starts_an_immediate_refresh` in [`SequentialRoomRepositoryTest.kt`](../android/app/src/test/java/com/qmix/tv/SequentialRoomRepositoryTest.kt) covers this distinct client contract. |
| Inspect manifest and deployment expectations | Metadata is served, but no install prompt or offline result is required. Trusted-LAN HTTP remains explicitly insecure; public/remote HTTPS is recommended. Neither deployment adds replay, a service worker, or an offline queue. |

The historical [guest UI screenshot](screenshots/qmix-pwa-md3.png) illustrates
an earlier layout. Its historical filename is not current PWA/installability
acceptance evidence.

## Deferred post-MVP work

- [#141 — retained SSE history and Last-Event-ID replay](https://github.com/leugenea/qmix/issues/141).
- [#142 — guest installability for secure deployments](https://github.com/leugenea/qmix/issues/142).
- [#143 — define and implement a useful guest offline mode](https://github.com/leugenea/qmix/issues/143).
