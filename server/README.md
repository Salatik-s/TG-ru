# FamilyGram access service

Node 24+, built-in HTTP/SQLite/crypto, no npm dependencies. Local validation: `npm test`.

## Android API contract

Base URL: `https://24projects.ru:8443`. Certificate verification is mandatory; do not add trust-all managers. All POST requests use `Content-Type: application/json`; response bodies and gateway credentials must not be logged.

1. Generate an EC `secp256r1` key in Android Keystore. Export only the public key in X.509 SubjectPublicKeyInfo DER, standard Base64 with no line wrapping.
2. POST `/v1/challenge` with `{ "public_key": "BASE64_SPKI", "action": "invite", "payload": { "code": "INVITATION", "name": "Phone name" } }`. Other actions are `status` and `config`, each with an empty `payload` object.
3. Response: `{ "device_id": "sha256-of-canonical-spki", "challenge_id": "uuid", "message": "opaque string", "expires_at": 1700000120 }`. Sign the exact UTF-8 `message` using `SHA256withECDSA` (DER encoded signature), without parsing, normalizing or adding a newline.

   The message has five newline-separated fields: `familygram-v1`, the returned challenge ID, a 43-character base64url nonce, the requested action, and a lowercase 64-hex payload commitment. Validate this shape, the installation ID and expected action before signing the original bytes. For `invite`, the last field is an **opaque server commitment** to its peppered code hash and validated name; a client cannot reproduce it from `{code,name}` and must not try. The authenticated TLS server binds the submitted invitation to that stored challenge. For `status` and `config`, the commitment is SHA-256 of `{}`. Raw invitation codes are not stored in challenge or invitation tables. `qa/interop.mjs` separately compiles the actual Java validator and verifies Java signatures against Node Access, including valid/invalid invitations; it requires a JDK and is deliberately outside deploy tests.
4. POST `/v1/complete` with `{ "challenge_id": "uuid", "signature": "BASE64_DER_SIGNATURE" }`.
5. Response: `{ "device_id": "...", "status": "pending", "failures": 0, "cooldown_until": 0, "server_time": 1700000000 }`. Successful invite submission requires separate owner approval. `config` succeeds only for approved installations and adds:

```json
{
  "gateway": {
    "protocol": "vless",
    "host": "24projects.ru",
    "port": 18443,
    "uuid": "unique-device-uuid",
    "security": "reality",
    "public_key": "REALITY-server-public-key",
    "short_id": "16-hex-characters",
    "server_name": "www.microsoft.com",
    "flow": "xtls-rprx-vision",
    "fingerprint": "chrome"
  }
}
```

Use this gateway through an embedded Xray client / per-app tunnel. This endpoint does not carry Telegram messages and does not provide an HTTP relay. Telegram login and end-to-end Secret Chat behavior belong to the Telegram client, not this access API. The generic push relay described below carries wake signals only.

Errors: `{ "error": "invalid_invite", "retry_after": 300 }`. HTTP 429: `invalid_invite`, `cooldown`, `rate_limited`, `too_many_challenges`; 403: `blocked`, `revoked`, `not_approved`; 401: `invalid_proof`; 410: `expired_or_replayed`; 409: `already_enrolled`; 503: `gateway_unavailable` / `unavailable`. Retry-After is also set when applicable. Blocked/revoked installations cannot issue fresh challenges; render the error state directly. API time fields are Unix seconds. Do not retry an already-used proof; request a new challenge.

## Security properties and limits

- One-use invitations have 120 random bits, expire in 24 hours by default, and are stored only as peppered hashes. All counters, approvals and invite use persist in SQLite.
- Every wrong code causes a 300-second server cooldown; the tenth consecutive wrong code blocks that installation. A correct code resets the count. Already-issued concurrent proofs cannot bypass cooldown.
- Each challenge lasts 120 seconds and is consumed even on an invalid signature. The signed challenge binds the server-stored action and validated payload. Neither bearer tokens nor owner secrets are put into the APK.
- Separate short-lived IP quotas limit reinstall/key-rotation guessing. Shared IPs are not permanently blocked. A reinstall creates a new identity; this is an installation lockout, not hardware attestation or an unbreakable device ban. Rooted devices can extract delivered gateway credentials, though they cannot export a correctly configured Keystore private key through this API.
- Approval issues a unique VLESS UUID. Revoke clears it, consumes outstanding challenges, and stops/restarts only this managed Xray child. Restart failure leaves the gateway stopped. Existing connections for other family devices briefly reconnect during membership changes.
- Owner actions use a filesystem-protected Unix socket, accessed through SSH + Docker exec. No owner route exists on the network API. Device names are untrusted labels; compare the full installation ID with the person/device you intend to approve.
- Private/loopback/link-local destination ranges are blocked in the managed gateway. This is not a general guarantee against every DNS rebinding technique. Only invite/approve people you trust to use the host's internet connection.
- Runtime data, private key and database belong only in `/opt/familygram/data`, mode 0700. Back up that directory securely; losing it loses device approvals. Never include it in a source/APK archive.
- Polling limits apply; use status checks on user action / no faster than once a minute while waiting for approval. Configure local app behavior to stop sensitive local features when a signed API request returns revoked/blocked; this service cannot remotely erase cached client data.

## Owner commands on the host

```sh
cd /opt/familygram/current/deploy
docker compose exec access node owner.mjs invite
docker compose exec access node owner.mjs list
docker compose exec access node owner.mjs approve FULL_DEVICE_ID
docker compose exec access node owner.mjs revoke FULL_DEVICE_ID
```

`invite 48` creates a 48-hour invitation. The command deliberately prints the one-use invitation for the owner to share privately; avoid collecting its output in logs. Blocking is permanent for the installation; owner approval only accepts `pending` devices, never silently unblocks guesses.

Windows shortcuts: `deploy/Manage.ps1 invite`, `deploy/Manage.ps1 list`, `deploy/Manage.ps1 approve FULL_DEVICE_ID`, `deploy/Manage.ps1 revoke FULL_DEVICE_ID`. `list` includes a fingerprint matching the app: the first 16 uppercase ID characters in groups of four. Verify that fingerprint with the device owner, then approve using the complete 64-character ID. `-PlanOnly` validates arguments without SSH.

## Deployment

Run `deploy/Deploy.ps1 -PlanOnly` first, then `deploy/Deploy.ps1` to connect interactively with the owner's SSH credentials. The script contains no password. Remote preflight requires amd64 Linux, existing Docker/Compose/Python3, unused 8443/18443, a valid existing Certbot certificate for `24projects.ru`, and the owner's saved VPN profiles. It does not install Docker, alter global nginx or change existing VPNs. If UFW is active, it adds only missing TCP 8443/18443 rules tagged `familygram`; pre-existing exact/ranged/application-profile/deny/source restrictions are retained. Activation rollback removes only rules added in that invocation. UFW is never enabled/reset automatically. Provider firewall configuration remains an owner action in the hosting panel. An existing unrelated `/opt/familygram` or Compose project causes refusal.

The isolated nginx container serves TLS on 8443, and managed Xray serves REALITY on 18443. Node/SQLite listens only inside the Compose network. A narrowly scoped certbot deploy hook reloads only this nginx container. No Docker socket is mounted. Runtime containers retain PID limits, no-new-privileges and read-only root filesystems; custom RAM limits were removed at the owner's request.

`import-vpn.py` reads the existing portfolio VPN's saved cache under its own service UID and optional locally saved export. It does not fetch a subscription or register another identity. The private snapshot `/data/egress.json` contains native encrypted VLESS/Trojan/Shadowsocks outbounds, regular candidates first and BS fallback after a complete regular sweep. It is not routed through the existing SOCKS listener, so the connector itself does not discard UDP. No production freedom/direct fallback is present. A selected route must return a nonce-matched MTProto resPQ over Obfuscated2; active routes are rechecked every30seconds. A failed route is withdrawn before another is selected. Health remains503 until a route is selected. This does not establish actual Telegram call behavior.

Startup allowance and Docker health start period are derived from the imported candidate count, allowing a full initial sweep without repeatedly relaunching the installer. The installer finally uses one existing approved UUID only in process memory to verify REALITY → VPN → Telegram MTProto; it reports an explicit skip when there is not yet an approved device. `Fix-Egress.ps1` applies this repair to the exact previously uploaded release without retransferring Xray. Refresh changed saved profiles by rerunning the installer; the snapshot is not a live provider subscription.

The installer pulls digest-pinned Node/nginx images before switching FamilyGram containers. Public application source and a preverified Linux Xray executable are mounted read-only; no image build or package installation runs on the VPS. At the owner's explicit request, available RAM is diagnostic only: custom container and heap memory limits and the admission threshold were removed. After startup it checks real TLS health, running/restart/OOM status and cgroup OOM kill events across a short observation window. A startup check is not a load guarantee. Activation failure prints FamilyGram container state/logs and restores the previous FamilyGram release; the access DB is intentionally not rolled back, preserving revocations. It never runs global prune or removes volumes. A manual stop is `cd /opt/familygram/current/deploy && docker compose down`; this preserves data and other projects. Do not expose the data directory or remove it as a routine rollback.

Xray v26.10.31 Linux amd64 archive is pinned by the SHA-256 supplied in its [official release](https://github.com/XTLS/Xray-core/releases/tag/v26.10.31). Its extracted ELF executable has a second checksum in `runtime/xray.sha256`, verified locally and remotely. REALITY configuration follows the [upstream documentation](https://github.com/XTLS/REALITY/blob/main/README.en.md). Node 24 and nginx 1.28 Linux amd64 image manifest digests are pinned in Compose; upgrades require explicitly refreshing those pins.

Local test evidence does not verify remote host capacity, firewall/provider reachability, certificate paths on the actual host, Android radio/background behavior, or live Telegram traffic. Complete a real device approval, connection, revoke and reconnect test after deployment.

TLS certificate discovery checks existing `/etc/letsencrypt/live/*` lineages, preferring `24projects-24projects.ru`, the name used by the portfolio installer. It verifies the trusted chain and `24projects.ru` hostname, expiry and private-key match, and requires live symlinks to resolve inside that lineage's archive. Only those two directories are mounted read-only. The archive keeps its actual lineage name inside the container so relative symlinks remain valid; the FamilyGram renewal hook reads the selected lineage from the release `.env`. No site certificate or DNS record is changed.

## Validation recorded on 2026-11-08

- Nine local security tests passed with Node 24.16.0, covering signatures/curves, payload binding, expiry/replay, single-use enrollment, persistent cooldown/blocking, parallel HTTP requests, IP quota expiry, public-key observer denial of service, revocation, and unavailable gateway behavior.
- `Deploy.ps1 -PlanOnly` passed; it made no remote connection. Bash syntax check passed. Docker/Compose runtime was not available locally; the remote preflight has not run.
- Optional gateway lifecycle test passed with an existing Xray 26.10.21 Windows binary and freshly generated temporary test credentials: configuration accepted, listening socket started, old TCP session closed during revoke reload, failed reload left the port closed. Deployment pins the newer upstream 26.10.31 binary by SHA-256; that Linux binary has not been executed here.
- The optional full REALITY-to-public-HTTPS test timed out. Direct HTTPS requests from this machine also timed out; this is not evidence that the remote path works. Default `npm test` skips the two gateway integration cases; supply `XRAY_TEST_BINARY` to run them. No deployment, real Android traffic, remote gateway handshake or post-deployment revocation is claimed.

## Generic host notifications

The signed `config` response now also contains `notifications: { endpoint, stream_url, token, expires_at }`. Only an approved installation with a valid P-256 proof receives these values. The endpoint is stable per installation, contains a 256-bit HMAC credential, and must be treated as private. It is registered with Telegram `account.registerDevice`, `token_type=4`, **from the logged-in Android client**. The host never receives Telegram account credentials, message bodies or a Telegram user session. [Telegram's Simple Push specification](https://core.telegram.org/api/push-updates) defines a PUT containing an opaque `version=number`, which is a prompt for the client to fetch updates over MTProto.

`PUT /v1/push/DEVICE_ID/HMAC` accepts at most 128 bytes and only a numeric `version` field. The service verifies the HMAC and current approval, deduplicates the last 64 version hashes, and persists a monotonic local sequence. Versions are not assumed to increase. Rate limits bound authenticated device and source-IP bursts. Payload contents are never stored: only a keyed version hash, installation ID and local sequence persist. Webhook access/error logging is disabled because the URL itself is a credential.

`GET /v1/events` requires `Authorization: Bearer TOKEN`; tokens are random, stored as hashes in memory, last 75 seconds and are minted only through signed configuration requests. Query-string tokens are rejected. SSE carries `id`, `event: wake` and `data: {"kind":"wake"}`; heartbeats contain no message data. `Last-Event-ID` replays bounded history. An older cursor gets a coalesced `reset` signal, so the client still fetches updates even after history was pruned. Two streams per installation are allowed. Expiry and owner revocation close streams; revocation also makes the webhook unusable.

Android integration is in `FamilyPush.java` and `FamilyPushService.java`. After each successful native/access lease renewal call `FamilyPush.configure(context, response.getJSONObject("notifications"))`; on invalidation/revocation/lease expiry call `FamilyPush.stop(context)`. The helper registers type 4 for activated accounts using the pinned Telegram request classes through reflection. Registration is considered complete only after Telegram returns `TL_boolTrue`, and is renewed within 24 hours. A wake signal calls the existing MTProto update/resume callbacks. The native Telegram notification controller then applies chat mute/read settings and displays actual messages. The SSE service shows only its ongoing connection notice; it does not invent a message notification from a generic update signal.

Manifest requirements: non-exported `org.telegram.messenger.family.FamilyPushService` with `foregroundServiceType="remoteMessaging"`; `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_REMOTE_MESSAGING` and `POST_NOTIFICATIONS` permissions. Android 13+ requires requesting notification permission from visible UI. Android may reject background foreground-service starts; opening the app retries. Tokens stay in memory; only the last signal sequence is stored locally. Java helpers compile against Android SDK 35. No Google/Firebase connection is required for this path.

Additional local validation: five relay tests pass for webhook authentication, strict payloads, deduplication, bounded history/replay, bearer expiry, stream limits, revocation and rate limits; two deployment tests pass for scoped UFW coverage and owner-argument/source-package guards. Together with access tests, the default suite has **16 passing tests and 2 optional gateway tests skipped**. Android helper compilation passed with only a compile-time FamilyAccess stub; Telegram registration, real Telegram delivery, Android Doze/OEM background restrictions and physical-device notifications remain unverified. A foreground service with a persistent connection is not a guarantee of delivery when Android suspends networking or the app is stopped.
