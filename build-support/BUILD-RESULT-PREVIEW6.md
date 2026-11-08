# Preview6: production WSS transport update

Signed APK: `../release/FamilyGram-0.1.0-preview6.apk`.

- SHA-256: `fa5cbd820dc8d8e1c8c0e8d09558f06d6a09ba3841295fd53744ccbc60491d8c`.
- Size: 139,926,547 bytes.
- Application ID: `com.salatik.familygram`; label: `FamilyGram`.
- Version code 10006; version name `0.1.0-preview6`.
- Android min API 24 / target 36; arm64-v8a and x86_64.
- Same signing certificate as preview5; v2/v3 signature and 16 KB ZIP alignment verified.
- Nine DEX files, 54,877 unique classes, no class duplicates.
- All 23,810 public resource IDs verified.
- Fourteen Java sources compiled with javac17 and D8; ten patcher tests passed.
- Both signed APK ZIP CRC checks passed.

The new transport supports WSS over the existing API host/port `24projects.ru:8443`, in addition to legacy REALITY. FamilyAccess prefers the strictly validated WSS fallback advertised by the authenticated API. API trust, device proof, approval and lease enforcement remain the production paths. No test CA, proxy override, test profile or diagnostic instrumentation is packaged in this APK.

`verify_preview6.py` compared the final signed preview6 against the exact signed preview5 baseline (`dfab4cf49465dd819f616f5be4ebe7233e0ee33e81bde9845c02db373cd8c13f`). The APK entry inventory is identical. Only these four entries differ:

- `AndroidManifest.xml` — new application version.
- `classes9.dex` — only `FamilyAccess.java` changed among the fourteen compiled sources.
- `lib/arm64-v8a/libfamilytunnel.so` — SHA-256 `02eb4baaa66a063fb8287f66495321bc8908edb7f4a72314c5dc720648a013b8`.
- `lib/x86_64/libfamilytunnel.so` — SHA-256 `1f8dc3a34e26afba72fb4a6748378b46cfc02cb8fa4ee9c6be96cde13a98b8af`.

Every other packaged byte is unchanged, including all eight upstream DEX files, all UI resources, native tgnet/call libraries, permissions/network-security resources and notices. The existing account/data can be retained by a same-signature `adb install -r` upgrade; this build task did not install on or clear any device.

The original preview5 staging copy and signed APK are preserved. Preview6 staging and complete provenance are in `work-preview6/report.json`; the reproducible preparation is `prepare_preview6.py`.

Validation boundary: build, signature, resource, DEX and precise payload-delta checks are complete. The transport owner separately reported passing real trusted TLS/WebSocket TCP and UDP443 fixtures, including rejection of wrong CA/SNI. Production WSS server deployment and physical-phone chat synchronization must be verified by the coordinating agent; they are not claimed as passed by this packaging report.
