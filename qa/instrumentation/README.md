# Separate Android QA instrumentation

This test APK targets `com.salatik.familygram` and must use the same signing key. It does not modify or repackage the production APK. Its process-local SSLContext trusts only the supplied public QA CA, leaves the default hostname verifier unchanged, and sends HTTPS requests for exactly `24projects.ru:8443` through the host loopback CONNECT bridge at `10.0.2.2:19090`. It contains no invitation, owner token, CA private key or release signing material.

Build with `build.py`, then use the existing `build-support/Sign.ps1` with input/output overrides to sign `build/FamilyGram-QA-unsigned.apk`. Install with `adb -s emulator-5582 install -t -r FamilyGram-QA.apk`.

Run one fresh-install test at a time:

```text
adb -s emulator-5582 shell am instrument -w -r -e mode cooldown -e variant light com.salatik.familygram.qa/.FamilyInstrumentation
adb -s emulator-5582 shell am instrument -w -r -e mode pending -e variant light -e invite_file /sdcard/Android/data/com.salatik.familygram/files/qa-invite.txt com.salatik.familygram.qa/.FamilyInstrumentation
adb -s emulator-5582 shell am instrument -w -r -e mode inspect -e variant dark com.salatik.familygram.qa/.FamilyInstrumentation
```

For `pending`, copy only the QA invitation code into that scoped external file before the run. The harness reads and deletes it, submits through the actual UI button, and emits `PENDING_PUBLIC_DEVICE_ID` with the public device ID. It waits up to 15 minutes for approval via the host QA owner control, then checks the native lease, Telegram welcome activity and family settings. On `READY_TO_REVOKE`, revoke the emitted ID through the same QA owner control. It checks that access and native readiness become false and the gate returns. It never enters a Telegram phone number, authenticates an account, or creates chat data.

`cooldown` uses a known-invalid synthetic code and checks the real 300-second backoff and disabled action. Clear the target app before the later fresh-install invitation test; this intentionally generates a new Android Keystore installation identity. `inspect` captures a denied gate without submitting an invitation, useful for system dark mode and font-scale checks.

Screenshots are actual Activity decor views rendered inside the test process to `/sdcard/Android/data/com.salatik.familygram/files/qa/*.png`; they do not claim to include SurfaceView content. The test only uses empty test installations. The invitation screenshot is taken before a valid code is entered, and pending capture requires that its input was cleared. Result bundles expose only event names, public device IDs, allowlisted state/error codes, durations and screenshot paths.

No runtime pass is asserted merely by building this harness. Capture and retain the actual instrumentation result and inspect the resulting PNGs.
