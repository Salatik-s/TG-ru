# Android QA results — final preview5

Environment: Google ATD Android 11/API 30, x86_64, emulator-5582. Production package `com.salatik.familygram`; separate same-signature test package `com.salatik.familygram.qa`. No Telegram account was authenticated, no phone number entered, and no chat data fabricated.

Final signed APK: `../../release/FamilyGram-0.1.0-preview5.apk`, version code 10005. SHA-256: `dfab4cf49465dd819f616f5be4ebe7233e0ee33e81bde9845c02db373cd8c13f`.

## Complete functional and visual run: preview4

Preview4 SHA-256: `0a70e84c8c85b60f4a0d1c8857f894316a38f0f8a86aa533e2041a284d03f7ed`.

| Check | Observed result |
|---|---|
| Initial launcher alias enters family gate | PASS |
| QA TLS / unchanged hostname verification / narrow CONNECT proxy | Authenticated challenge/complete succeeds |
| Real Android Keystore P256 status and invitation proofs | PASS |
| Wrong invitation | `invalid_invite`, retry 300 seconds, disabled submit, access/native readiness false |
| One-use invitation | Pending; code input cleared; public installation ID emitted |
| Owner approval of exact test ID | Native ready, access allowed, automatic LaunchActivity welcome transition |
| Family settings | Three actual navigation rows present/enabled; top and scrolled PNGs captured |
| Feature reflection | Public constructors/BaseFragment inheritance for all three destinations and LaunchActivity.presentFragment present |
| Push reflection | UserConfig fields/methods, ConnectionsManager request/wake methods, registerDevice fields, RequestDelegate.run and actual dynamic-proxy call, TL_boolTrue runtime name present |
| Calls reflection | VoIPService.getSharedInstance/hangUp present |
| Upstream provider | Actual getProvider result is DummyPushProvider |
| Owner revocation | Access false, native readiness false, gate shown |
| Light/default text | Full flow PASS; actual PNGs reviewed |
| Dark/130% text | Full flow PASS; wrapping and scrolling work without overlapping controls |

Raw logs: `results/preview4-cooldown.txt`, `results/preview4-light.txt`, `results/preview4-dark-font130.txt`.

Useful screenshots:

- `screenshots/preview4-light-02-cooldown.png`
- `screenshots/preview4-light-03-pending.png`
- `screenshots/preview4-light-05-family-settings.png`
- `screenshots/preview4-dark-font130-01-invitation.png`
- `screenshots/preview4-dark-font130-05-family-settings.png`
- `screenshots/preview4-dark-font130-05b-family-settings-bottom.png`
- `screenshots/preview4-dark-font130-06-revoked.png`

## Targeted final-artifact run: preview5

Only LocaleController.getAppName and package version changed after preview4; OpenExtera credits were retained. All six native library hashes and all fourteen compiled Java source hashes match preview4. Public IDs and DEX uniqueness were checked again.

A fresh test installation was enrolled/approved using the authentic QA API, then upgraded to preview5 retaining its Keystore identity. A new instrumentation process cold-started the approved app, fetched a fresh configuration, asserted native readiness and absence of a Telegram account, asserted `getAppName()` equals `FamilyGram`, and captured the visible welcome title. Owner revocation of that exact ID stopped native readiness and returned the gate.

Final result: `BRANDING_AND_COLD_START_REVOCATION_PASS`.

- Log: `results/preview5-final.txt`; setup log: `results/preview5-approved-setup.txt`.
- Visible FamilyGram title reviewed: `screenshots/preview5-final-07-final-welcome.png`.
- Revocation: `screenshots/preview5-final-08-final-revoked.png`.

The emulator was returned to light mode and default 1.0 font scale. Final test-device access is revoked. No root/remount/reboot was used in these runs.

## Scope and limits

The tests found and fixed the invitation commitment mismatch, explicit Crashlytics calls after its registrar was disabled, the preference-selected Google push provider, and the welcome name helper bypassing AppName resources. Earlier preview failures are not counted as passes for those artifacts.

The QA backend uses authentic P256/invitation/approval code with a configuration-only gateway and no live Telegram gateway listener. Results prove the tested local engine/access transitions, not remote Telegram reachability. Real message delivery, per-chat notification/mute behavior, actual calls, physical ARM64 behavior and the authenticated chat UI remain outside this no-login test.

PNGs render actual Activity decor views. SurfaceView/GL animation content is not included: the black animation rectangle in welcome PNGs is a capture limitation, not evidence of a rendering defect. Native family gate/settings views were reviewed in both configurations. The production APK contains neither QA CA nor instrumentation code. Test trust/proxy overrides exist only inside the separate test process. These tests do not establish total absence of third-party traffic.
