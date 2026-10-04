# Coupled releases: inert implementation and owner activation

The #334 workflow is **disabled without valid public configuration and exact
approval**. Merging it does not create a signing key, environment, tag, Release
or GHCR package, and does not satisfy #323. Owner setup and the real RC remain
#335. Fixture certificates and hosted smoke receipts are test-only evidence.

## What the implementation executes

`release.yml` handles existing `v*` tag pushes only. A read-only public preflight
fetches current remote main/tags, peels the approved tag, checks main ancestry,
exactly one local tag at HEAD and the existing shared calculator. It rejects
missing, empty, unknown, duplicate, non-finite or incorrectly typed configuration.
It gates job admission before signing secrets are referenced. Signing repeats
that check; publication repeats it immediately before complete-set validation.
RCs always use `prerelease=true`, `latest=false` and no image aliases. They do
not wait for product issues #62/#6 to close. Stable admission is a separate,
initially disabled gate, requiring an explicit non-release product receipt.

Unsigned schema-1 intermediates remain supported and reject signatures. Final
schema 2 contains twelve unchanged Go targets, `qmix-android` (signed non-debug
APK, minSdk23), `qmix-go-sbom`, `qmix-android-sbom`, `qmix-licenses`, the versioned
inventory and fixed `SHA256SUMS`: exactly eighteen files. The Android BOM is
regenerated against the signed bytes using the existing generator `--apk` input.
Every row, file hash, provenance/method, supported signature/pinned certificate,
APK identity, static Go metadata, BOM root and license archive is checked.

Native hosted amd64/arm64 jobs build and execute the existing Dockerfile without
QEMU: OCI labels, exact `--version`, `/healthz` and `/readyz`. Their local image
config digests are **not registry manifest digests**. The writer validates saved
archive hashes and loaded image configs before remote writes. It then obtains
actual pushed platform digests and checks the actual two-platform manifest.

Release publication creates an explicitly verified-tag draft, uploads only the
validated allowlist without clobber, reads metadata/assets and re-downloads and
verifies bytes before publishing. Published metadata/assets are read and downloaded
again. Full-version Release/image conflicts stop reruns. The workflow uses a
serialized non-cancelling release queue; existing PR concurrency is unchanged.

## #335: owner-only setup, not performed by #334

Do not send keystores, passwords, private key material or verification codes in
chat, issue comments, PRs or logs. Use the GitHub web secret editor or `gh secret
set` with stdin on the owner's trusted machine. Do not use CLI `--body`
for secrets, shell tracing, or passwords in arguments. Use a private directory
outside the checkout, never a cache, artifact or Docker build context.

1. Choose and record the trust/visibility/ref policy. Main ancestry is an
   admission check, **not a secret-security boundary**: a repository writer
   can change executable workflow/script code. In `repository-writers` mode,
   explicitly accept that all repository writers can reach repository secrets.
   In `protected-environment` mode, the owner must first create a dedicated
   release environment (never `github-pages`), configure required reviewers,
   prevent self-review/bypass as appropriate to the GitHub plan, and allow only
   the intended release tag refs. Verify actual protections through the GitHub
   UI/API; a public JSON declaration cannot prove them. Restrict tag creation
   and workflow modification under the chosen trust policy. Do not activate a
   protected mode if required protections are unavailable under current visibility.
2. Create or select the permanent Android release keystore using a trusted JDK
   `keytool` offline. There is no generated production fallback. Back up the
   encrypted keystore, alias and both passwords in the owner's independent
   secret storage; test restoration and certificate export before activation.
   Loss of the private key breaks future APK upgrade continuity. Keep its
   certificate SHA-256 public; never publish the keystore/private key.
3. Export the selected certificate with env password input and compute the pin
   on that machine (these commands do not create a key):

   ```bash
   # Set these only in a trusted private session, without tracing.
   : "${QMIX_RELEASE_STORE_PASSWORD:?}" "${QMIX_RELEASE_KEY_ALIAS:?}" "${KEYSTORE_PATH:?}" "${PRIVATE_DIR:?}"
   umask 077
   keytool -exportcert -keystore "$KEYSTORE_PATH" -alias "$QMIX_RELEASE_KEY_ALIAS" \
     -storepass:env QMIX_RELEASE_STORE_PASSWORD -file "$PRIVATE_DIR/release-cert.der"
   sha256sum "$PRIVATE_DIR/release-cert.der"
   base64 -w 0 "$KEYSTORE_PATH" > "$PRIVATE_DIR/keystore.base64"
   # Linux base64 shown; use the trusted platform's equivalent elsewhere.
   ```

4. Set exactly these four secrets in the **chosen** scope (repository, or the
   dedicated release environment): `QMIX_RELEASE_KEYSTORE_BASE64`,
   `QMIX_RELEASE_KEY_ALIAS`, `QMIX_RELEASE_STORE_PASSWORD`,
   `QMIX_RELEASE_KEY_PASSWORD`. For repository scope:

   ```bash
   gh secret set QMIX_RELEASE_KEYSTORE_BASE64 --repo leugenea/qmix < "$PRIVATE_DIR/keystore.base64"
   printf '%s' "$QMIX_RELEASE_KEY_ALIAS" | gh secret set QMIX_RELEASE_KEY_ALIAS --repo leugenea/qmix
   printf '%s' "$QMIX_RELEASE_STORE_PASSWORD" | gh secret set QMIX_RELEASE_STORE_PASSWORD --repo leugenea/qmix
   printf '%s' "$QMIX_RELEASE_KEY_PASSWORD" | gh secret set QMIX_RELEASE_KEY_PASSWORD --repo leugenea/qmix
   ```

   For environment scope, append `--env "$RELEASE_ENVIRONMENT"` to each command;
   remove unneeded duplicate repository secrets. Read back secret **names and
   scope only**, never values. Securely remove transient base64/certificate files
   according to the owner's storage policy; keep the independent backup.
5. Confirm `ghcr.io/leugenea/qmix` package ownership, package visibility,
   repository linkage and Actions permissions. The publisher authenticates with
   the job `GITHUB_TOKEN`, requesting repository pull/push scope. Only an
   authenticated explicit `MANIFEST_UNKNOWN`/`NAME_UNKNOWN` is absence; 401/403,
   transport failure, malformed 404 or unavailable token is UNKNOWN and denies.
   A missing package is not evidence of usable write permissions. Resolve initial
   namespace/permission/visibility questions as owner setup, not by a dummy push
   or automatic fallback registry. Verify public anonymous pull after real publication
   if public distribution was chosen; authenticated readback alone does not prove it.

## Public variables: exact schema, no implicit defaults

Repository variables are `QMIX_RELEASE_POLICY` and `QMIX_RELEASE_APPROVAL`.
The following is a **template**, not an activated owner choice. Replace every
placeholder, keep both `enabled` fields false during setup, and do not save it as
an enabled policy until the owner has checked the preceding steps.

```json
{
  "schemaVersion": 1,
  "enabled": false,
  "trust": {"mode": "repository-writers", "environment": "", "acknowledged": false},
  "certificateSha256": "REPLACE_WITH_64_LOWERCASE_HEX_PUBLIC_CERTIFICATE_SHA256",
  "image": "ghcr.io/leugenea/qmix",
  "stable": {"enabled": false, "aliases": [], "latest": false}
}
```

Trust must have exactly `mode`, `environment`, `acknowledged`: writers mode
requires the empty environment; protected mode requires an existing safe name
(1–64 alphanumeric/underscore/hyphen characters, not `github-pages`). Admission
requires `acknowledged=true` and top-level `enabled=true`. Stable has exactly
`enabled`, `aliases`, `latest`; aliases are unique explicit selections from
`major`, `minor`, `latest`, at most three. They resolve to `vMAJOR`,
`vMAJOR.MINOR`, `latest`. GitHub Release latest is the separate Boolean `latest`;
image alias choice does not implicitly change it. No owner choices are installed.

Approval has exactly these fields, and must match the tag's **peeled commit**:

```json
{
  "schemaVersion": 1,
  "tag": "vX.Y.Z-rc.N",
  "commit": "REPLACE_WITH_EXACT_40_LOWERCASE_HEX_ACCEPTED_MAIN_SHA",
  "productReceipt": ""
}
```

An RC requires the empty receipt. Stable requires `stable.enabled=true` plus
an explicit HTTPS `https://github.com/leugenea/qmix/issues/NUMBER` (optionally
`#issuecomment-NUMBER`) product receipt, not release trackers #67/#323/#334/#335.
The implementation does not infer acceptance from issue closure. Choose an
otherwise-untagged eligible accepted main SHA for stable: the calculator rejects
putting stable on an already RC-tagged commit. See [versioning.md](versioning.md).

After the owner approves exact files on the trusted machine, public variables
can be set from files without putting JSON into shell expressions:

```bash
gh variable set QMIX_RELEASE_POLICY --repo leugenea/qmix < "$POLICY_FILE"
gh variable set QMIX_RELEASE_APPROVAL --repo leugenea/qmix < "$APPROVAL_FILE"
gh variable get QMIX_RELEASE_POLICY --repo leugenea/qmix
gh variable get QMIX_RELEASE_APPROVAL --repo leugenea/qmix
```

Read back and compare strict JSON. Approve the selected tag/SHA before its owner
push. The guard cannot pass before the tag exists remotely; local review of the
planned calculator identity is not runtime admission. If a tag run is denied,
fix owner configuration and explicitly rerun that exact run; do not move tags.
Tag creation/push itself requires #335 authorization and is not part of #334.

## Real RC, immutable conflicts and partial recovery

Record the exact tag, peeled SHA, workflow/run attempt and admission receipt.
Before publication, invalid/missing keys, wrong password/alias/pin, unsigned or
debug APK, bad identity/BOM/checksum or image archive fail without remote writes.
The writer checks existing Release and full-version/platform tags first; reruns
refuse conflicts even if content appears identical. Mutable stable aliases are
changed only when explicitly selected. Do not retry a failed partial run blindly.

On any failure after writes, retain `production-publication-receipt`: observed
Release ID, platform digests, manifest and completed aliases. A CLI/network failure
can leave a write with unknown result; missing receipt fields do not prove absence.
Read exact Release metadata/draft assets and registry refs with authenticated APIs
and compare hashes. Document the observed partial state and owner recovery decision
in #335. The pipeline never deletes a draft, clobbers an asset, overwrites a
full-version image, forces a tag, hides tags from the calculator or rolls back a
registry transaction. A new eligible main SHA/version is the smallest ordinary
recovery; any manual exceptional cleanup needs a separately recorded owner decision.

For #335 acceptance, download the **published** final set and verify inventory,
`SHA256SUMS`, pinned signing certificate and BOM roots; pull the published manifest
by digest on both native platforms and compare labels/identity/readiness. Install
the downloaded RC APK on a clean real TV/emulator selected by the owner, verify
package/version/certificate, and launch its Leanback entry. Record immutable asset
URLs/hashes, registry platform/index digests, public visibility result, device/API
and install/launch evidence. These actual receipts, permanent-key/setup decisions
and real RC proof—not controlled mocks or disposable fixture results—complete #335
and the remaining #323 acceptance.

## Unprivileged #334 verification

`make test-release-pipeline test-release-payload test-public-readiness` executes
focused controlled protocol/validation tests, not SDK or real publication proof.
The existing `ci.yml` RC/stable producers generate private disposable test keys
under `RUNNER_TEMP`, sign using SDK 35.0.0, rebuild signed-byte BOMs and clean keys
on every exit. Separate jobs download the final set, verify it, install/launch
on a wiped hosted TV, execute the complete twelve-target native Go matrix, and
build/run native Linux amd64/arm64 images. `CI result` requires each applicable
proof job to succeed. Fixture public pins/receipts are separate from final assets;
private keys/passwords never enter source, caches, artifacts or Docker contexts.
Hosted GREEN establishes #334's controlled acceptance only. It is not real RC proof.
