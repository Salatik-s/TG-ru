# Normal production enrollment diagnostic

Separate testOnly APK signed with the production key, targeting the unchanged installed FamilyGram. This runner never modifies TLS trust, hostname verification, ProxySelector, access leases or gateway configuration. It creates no Telegram account and enters no phone number.

Build with `python build.py`; sign `build/Production-Enrollment-unsigned.apk` to `Production-Enrollment.apk` with `../../build-support/Sign.ps1` (paths relative to this directory: the script itself is at `../../build-support/Sign.ps1`). Run `./Run.ps1` after the owner command writes the private JSON invitation at the default path. The runner transfers it to app-scoped external storage; instrumentation reads and deletes it before clicking the real invitation form. The device name is `FamilyGram QA Android`.

Output includes only the public device ID/fingerprint, allowlisted diagnostic state, callback class and numeric error code. The owner must approve the exact emitted device; the runner waits at most 15 minutes. After the normal automatic LaunchActivity transition it sends read-only `help.getNearestDc`, with the same unauthenticated request flags as the phone form, and waits at most 60 seconds. It reports callback receipt, write callback count and whether the Java request callback remains registered. It neither prints nor reads Telegram credentials, gateway profile secrets, or error text.

Use `./Run.ps1 -Resume` for the same pending/approved installation without a new invitation. No app data is cleared by the runner. Only task-owned emulator serials are accepted. The production approval persists after the diagnostic; the owner can revoke that exact test device separately.

The final `INSTRUMENTATION_RESULT: result=PASS` is authoritative; adb's exit code alone does not imply a passing diagnostic.
