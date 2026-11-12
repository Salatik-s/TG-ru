# TG-ru security review — 2026-11-12

## Conclusion and coverage

This review supports a limited conclusion: the inspected custom network code transports Telegram MTProto ciphertext through the host and encrypted VPN egress. I found no Telegram session-key ingestion or message-decryption implementation in the inspected access/push/gateway server. This is not a certification that the entire APK is equivalent in security to the official Telegram client, nor a claim that every source line, dependency or binary instruction was reviewed.

Reviewed current access/enrollment/management, gateway, HTTP, push, deployment isolation, transport configuration and lease boundary, native build provenance and socket replacement, updater validation, inline Python, document process isolation, speech-provider boundary, and selected inherited plugin-engine bytecode. The current working tree is being edited concurrently. Findings describe source observed during this review, not an immutable final APK. No production host, connected phone, traffic interception, real calls or final-release binary were tested by this audit.

Full upstream Java/Kotlin/Smali, media/WebRTC native code, Chaquopy dependencies and WebView were not exhaustively audited. No claim of having checked every character is warranted. A complete source-built, reproducible release, dependency inventory and independent mobile pentest remain necessary before a public security claim.

## Confirmed boundaries and risks

### High impact, conditional — external plugins can become trusted application code

The inherited plugin system remains reachable from “Other plugins”. `build-support/work-upstream-1321/decoded/smali/app/exteraless/plugins/PythonPluginsEngine.smali:327-448` starts Chaquopy using the application Context and calls the Python loader in the app process. `PluginPermissions.smali:72-78,715-721` includes message-read, message-send, network and native permissions; native is explicitly described as full control. `PluginsController.smali:6678,6821,7101,7265` exposes message/update/request hooks.

This is a confirmed trust boundary, not a demonstrated permission-bypass exploit. Granting untrusted plugin message/network/native capabilities can expose plaintext available at the endpoint or allow account actions. Hook-based grants are not an Android isolated UID. An adversarial plugin with native permission must be treated like application code. Do not describe arbitrary plugins as sandboxed to the same degree as our inline Python runner. For a public build, prefer audited bundled tools and remove or separately redesign third-party executable plugins.

### Medium — gateway credentials are reusable bearer credentials

`server/gateway.mjs:10-19,84-89` accepts approved UUIDs in VLESS and returns that UUID in the signed-proof-authorized configuration. The UUID remains until revocation (`server/access.mjs`, owner approve/revoke). Android Keystore proves installation identity to enrollment/management; it does not sign each Xray session. An approved client, rooted device or malicious app-process code can copy a UUID/profile and use it elsewhere until revoked. The 75-second lease in `tunnel/engine.go:45-84` is enforced by our client, not by a server-issued expiration on the UUID.

Copying an unopened APK alone does not provide an approved UUID or owner signing key. Conversely, “only this physical phone can ever use the connection” is not guaranteed. Adding expiry/rotation or a proof-bound server transport requires a compatibility design for deployed clients. Revocation currently restarts the gateway and removes old credentials, including both REALITY and WSS.

### Medium — approved route is not limited to Telegram destinations

`server/gateway.mjs:22-26` blocks private/local ranges, then forwards arbitrary TCP/UDP destinations through the selected outbound. This makes a leaked approved UUID useful as a general public-internet tunnel, with bandwidth and abuse consequences. It does not grant root or the local owner Unix socket. Destination restrictions need careful Telegram DC/media/call coverage; do not add a brittle address list without measuring the full feature set.

### Medium — signing workstation is part of the security boundary

`build-support/Sign.ps1:9-22` keeps JKS and password JSON together under a private Windows directory with restricted ACLs. This is not an APK secret leak, and the actual secret files were not opened. Malware running as the same Windows user could read both and sign a malicious update. Use a separately protected signing credential or hardware/managed signing and offline recovery copy for production. Antivirus must remain enabled.

### Privacy — deleted-message retention deliberately changes expectations

`FamilyFeatures.java:23-26` initializes saved deleted messages to true. Retained local message copies remain readable to the application and anyone who compromises its process/device storage. Transport encryption cannot erase retained plaintext. Make this explicit to users, consider opt-in default and a clear purge action; remove retention of disappearing content and ghost interference for a compliant store edition.

## Positive evidence

- Enrollment validates P256 public keys, hashes canonical public key to installation identity, uses random expiring one-use challenges, verifies signatures, binds action/payload, and rechecks owner status. Owner binding is not offered in public API. Ten failed invitation attempts block that identity; IP limits mitigate but do not make a reinstall/new key physically impossible. See `server/access.mjs:45-137`, `server/main.mjs:28-63`.
- Public pending/new devices cannot obtain gateway configuration. Host management is a permission-restricted Unix socket; Compose publishes only TLS8443 and REALITY18443, drops capabilities, uses a non-root access process and read-only source/runtime mounts. See `server/http.mjs`, `deploy/compose.yaml`.
- Production gateway has no direct freedom fallback. Missing VPN route selects blackhole. Private ranges are blocked before the general route. This preserves confidentiality but can affect availability when route probes withdraw a node.
- Pinned 13.2.1 native input verification passed now for 144 files per ABI, arm64-v8a and x86_64. `build-support/native-13.2.1/CMakeLists.txt` builds original pinned Handshake, Datacenter, ConnectionsManager and crypto sources and replaces the ConnectionSocket transport. `native/prepare.mjs:25-52` rejects surviving direct socket/connect/DNS calls in that patched file. This substantiates that transport replacement itself does not substitute a plaintext messaging protocol. It does not audit every crypto implementation or inherited library.
- Inner MTProto is encrypted on the client before bytes reach the transport. Own host terminates outer TLS/REALITY and can see timing, destinations and traffic volume, not thereby obtain MTProto plaintext. Telegram cloud chats are server-client encrypted; Telegram servers remain a trusted endpoint. Secret chats add end-to-end encryption. See [Telegram MTProto description](https://core.telegram.org/mtproto/description) and [Telegram FAQ](https://telegram.org/faq#q-how-secure-is-telegram).
- Push accepts bounded numeric wake versions, stores HMAC versions/sequence rather than message text, requires approved identities and expiring bearer SSE access, and closes revoked streams. Webhook secret URL logs are disabled in nginx. See `server/push.mjs`, `deploy/nginx.conf`.
- Inline Python is a separate bounded Pyodide worker. `FamilyPythonRunner.java` disables file/content/network access, permissions and external navigation, uses an asset allowlist/CSP and no JavaScript-to-Java interface. Worker `jsglobals` is empty; output/code and run duration are bounded. This is stronger isolation than inherited plugins but still relies on WebView/OS security and has memory-exhaustion residual risk.
- Document parsing runs in non-exported `isolatedProcess=true` service, receives a descriptor, bounds sizes/time and disables script/network/file access in its viewer. See `FamilyDocumentService.java`, manifest, `FamilyDocumentViewer.java`. Complex parsers still need patched dependencies and fuzzing.
- Updater limits response size, disallows HTTP redirects, verifies APK length/hash/package/version and equality of signer set before Android installer. See `FamilyAppUpdate.java:214,276-313`. Host compromise alone cannot make a different signer pass this check. Compromise of the signing key remains decisive.
- APK manifest disables Android backup and cleartext traffic. Network security configuration trusts system CAs only. This does not globally tunnel every Java/WebView network operation; access API and updater intentionally use separate HTTPS requests.

## Remaining release work

Calls adapter changes and real call confidentiality/availability need the dedicated call audit and actual device verification. Native GOT hooks apply to a particular original ELF; they do not establish an app-wide VPN boundary. Final packaged libraries, manifest and DEX must be verified after build. Test network failover and revocation separately: preserving offline chat UI must not silently convert a cached approval flag into active transport authorization.

System speech remains device/provider-dependent; installing a language model cannot add unsupported file-source recognition. Existing code uses a verified file-provider boundary and no intentional cloud/microphone fallback. Telegram Premium transcription is a separate server-side path; it should not be described as local. The speech UI agent is changing this area during the review.

Future store publication needs its own release track. [Telegram API Terms](https://core.telegram.org/api/terms) require an application's own api_id and disallow ghost interference; the inherited API identity must be replaced. Do not represent TG-ru as an official Telegram product. Preserve required upstream license/attribution and provide corresponding source as required by the actual licenses. A private repository alone is not evidence all distribution obligations are satisfied.

The current website APK self-updater must not ship unchanged in a Google Play build: [Google Play Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/9888379) requires Play-distributed apps to update through Play and constrains downloaded executable code. Review plugin execution, privacy disclosures and foreground service declarations. Apple publication requires a separate review of [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/), including executable-code and privacy requirements. Removing ghost and deleted retention alone does not establish store readiness.

## Verification run

`node --test server/test/access.test.mjs server/test/phone-owner.test.mjs server/test/push.test.mjs server/test/egress.test.mjs` — 28 passed, 0 failed, 0 skipped.

Imported current 13.2.1 `prepare.mjs` and called `verifySource` for both ABIs — 144 pinned inputs verified each. These are focused static/unit checks, not a production penetration test or proof of absence of vulnerabilities.
