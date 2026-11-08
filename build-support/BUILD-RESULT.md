# Final private preview build — 2026-11-08

Signed output: `../release/FamilyGram-0.1.0-preview5.apk`.

- SHA-256: `dfab4cf49465dd819f616f5be4ebe7233e0ee33e81bde9845c02db373cd8c13f`
- Size: 139,926,547 bytes.
- Application ID: `com.salatik.familygram`; label and runtime name: `FamilyGram`.
- Version code: 10005; version name: `0.1.0-preview5`.
- Android API minimum 24, target 36, as in the pinned upstream APK.
- Native architectures: `arm64-v8a` and `x86_64`, each with real `libfamilytunnel.so`, `libfamilytgnet.so` and `libfamilycalls.so`.
- Nine valid DEX files; 54,877 unique class descriptors; no duplicate classes.
- All 23,810 public resource IDs preserved, including 85 added family IDs.
- Full Apktool rebuild, Java/D8 compilation and 16 KB APK zip alignment verification passed.
- Ten Python integration-patching tests passed. All 28 changed upstream smali class definitions also assembled independently.
- APK v2/v3 signatures verified. Certificate SHA-256: `e89c8af3771d781f6ab3beb63a6d3030b2926c770a96770475315040115b959a`.
- Upstream license, transport notices and native call transport licenses are packaged in `assets/familygram/`.

`work-pinned-v1/report.json` records input hashes, compiled Java hashes, native library hashes, manifest Activity inventory and intercepted methods. LaunchActivity and its 227 associated definitions moved to the eighth DEX to keep the original fifth DEX below its method-reference limit. Class names did not change. The family overlay occupies the ninth DEX.

Preview4 passed the complete runtime flow on Google ATD Android 11/API 30 x86_64: invalid invitation and 300-second cooldown, pending approval, owner approval, native readiness and launcher transition, family settings, all requested reflection targets and actual DummyPushProvider, revocation, native stop and gate return. It passed in light/default text and dark/130% text configurations. Real family-screen PNGs were reviewed.

Preview5 changes only LocaleController.getAppName and package version relative to preview4. All six native hashes and all fourteen compiled Java source hashes were asserted equal. Preview5 was installed and cold-started with an approved installation: fresh native configuration became ready, the welcome name was asserted exactly `FamilyGram` and captured, then owner revocation stopped native readiness and returned to the gate. Final result: `BRANDING_AND_COLD_START_REVOCATION_PASS`.

Detailed logs and PNG paths: `../qa/instrumentation/QA-RESULT.md`. No Telegram account was authenticated. Real Telegram message delivery, real-account notification behavior, actual audio/video calls, physical ARM64 behavior and pixel identity with official Telegram are outside this no-login test. The QA-only CA/proxy harness is a separate test APK, absent from production.
