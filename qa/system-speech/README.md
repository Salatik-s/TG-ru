# On-device file speech QA

This separate test-only package never targets the Telegram process. Its manifest has no
Internet permission. The helper uses only `createOnDeviceSpeechRecognizer`, checks support
with the same file-descriptor intent, and has no microphone or cloud fallback.

Modes: `capabilities` checks support without starting recognition; `download` requests an
on-device language model; `file-paced` feeds a bundled public/synthetic PCM sample in real
time; `file` is the deliberately unpaced comparison. File modes require the test UID's
RECORD_AUDIO AppOp to be ignored or denied. They do not require a runtime microphone grant
in application code. A provider permission error remains an error, not a fallback.

Samples are `jfk`, `synthetic-ru`, and `synthetic-en`. The builder strips WAV headers using
the wave parser and verifies mono, 16 kHz, PCM16. Synthetic WAV fixtures supplied locally
are included only when present. Output contains only known sample text and diagnostics.

## Physical-device evidence, 2026-11-08

Provider: `com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService`
version code `12872254`, Android API 35. The production verification policy matches this
exact service, package version, and SDK; other versions remain unverified.

With the test UID microphone AppOp ignored, the downloaded Russian language recognized
the complete 307,200-byte / 9.6-second synthetic Russian sample in 9,758 ms with paced input:
two segment callbacks, one end-session callback, and no final-result callback. Sending the
same input in a burst completed in 1,821 ms but omitted the second phrase. Production
therefore defaults to 1,280-byte / 40-ms blocks (32,000 bytes per second). Burst mode is
retained solely for comparison. Completion still requires input EOF and segmented/final
result completion; no speech-end callback alone finishes a session.

The initial successful file tests had a runtime microphone grant but an ignored UID AppOp.
A subsequent ready-file Russian run succeeded with runtime RECORD_AUDIO revoked for
**only the test package**, microphone UID AppOp ignored (mode 1), and caller permission
reported false. The complete 9.6-second synthetic sample was recognized. This confirms
file routing on the tested Android 15 / AiAi version, not arbitrary providers or every
Android device. Do not change the production application's permissions or any global
microphone privacy setting during these tests.

Build with `qa/system-speech/build.py`; it only produces an unsigned diagnostic APK.
Installation, test-only permissions/AppOps, and device execution are coordinated separately.
These tests establish file routing and sample behavior on this provider, not universal
recognition accuracy, support for every device, or language availability before download.
