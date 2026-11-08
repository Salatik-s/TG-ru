# TG-ru preview11 — Android device verification

Date: 2026-11-09. Physical NX721J, Android 15, arm64-v8a. An update installation preserved the existing account, app data, Android Keystore and signing identity.

Artifact: **TG-ru.apk**, versionCode **10011**, versionName **0.1.0-preview11**, **150075024 bytes**.

SHA-256: `788ae2174ac88ffb433b4ca13e2eafba60f9b2dd303d6edcf66745bbb7bd4659`.

Signer SHA-256: `e89c8af3771d781f6ab3beb63a6d3030b2926c770a96770475315040115b959a`.

## Physical checks

| Path | Evidence |
| --- | --- |
| Inline Python | Seven own Saved Messages code fixtures. `print(1+1)` returned 2 in the same message. NameError, ZeroDivisionError and SyntaxError retained their real errors. Output occupied at least 75% screen width; thirty lines expanded into a native selectable dialog. Infinite-loop timeout explicitly said ten seconds; Stop terminated another loop. No editor Activity opened. |
| Context Run, final APK | Actual fillMessageMenu/processSelectedOption displayed exactly SAA_QA_CODE_42 in its own message cell. ActivityMonitor recorded no editor opening. Advanced editor was then opened explicitly for its separate tests. |
| Python boundaries | Computation and stdin passed. Python had no JS fetch or app-private files; WebView network fetch was blocked. Syntax errors and infinite-loop termination passed. Runtime bootstrap deadline is separate from execution time. |
| PDF, final APK | Real Telegram internal-document hook and isolated service. Two-page fixture; actual synthetic pinch above 150% increased bitmap render width. Fit-page and pagination passed. Owner also confirmed zoom manually. |
| Office, final APK | DOCX formatted WebView loaded and native zoom increased scale. DOCX, binary DOC, ODT, RTF and TXT fixtures passed. Complex Word layout is approximate; DOC/RTF use simplified text. |
| Native local speech | Actual native TranscribeButton on a synthetic Opus voice in Saved Messages produced Telegram's normal expandable transcription bubble. An in-memory non-Premium flag test showed the button without a lock, then immediately restored the account flag. No Premium transcription RPC was used. |
| Personal recordings | Owner explicitly supplied his voice and round video in Saved Messages. Native local voice test passed; owner confirmed round transcription. No personal transcript, audio/video, face or screenshot was exported. |
| System file recognition | Separate test package recognized paced Russian PCM with runtime RECORD_AUDIO revoked and microphone UID AppOp ignored. Russian offline model was downloaded through the Android API. No Whisper or host transcription was deployed. |
| Real codecs | WAV exact reference PCM; AAC/MP4 and Opus/Ogg duration/energy; cancel and re-decode; corrupt input and video-without-audio rejection. Synthetic square AVC+AAC MP4 audio also passed duration/energy checks. |
| Navigation, final APK | App label TG-ru; visible S.A.A. header; actual main overflow opened privacy settings and live filters. Standard settings retained ghost/plugins/owner with system settings last. Bound-owner UI loaded. Existing plugin choices were preserved. |

Full final Python/context/document suite passed in 29 seconds; final navigation suite in five seconds. The seven inline/error/expansion cases and voice tests were on the preview11 candidate; final APK changes only context-menu delegation to that same inline runner. Decoder, speech and runtime assets were unchanged. Owner also confirmed Python manually.

## Supporting checks and unchanged baseline

91 PCM JVM conversion/boundary/EOF fixtures and 25 bounded Office renderer fixtures passed. Pinned real Pyodide worker checks covered original errors, line reporting, output limits, startup/run clocks, stale epochs, Stop and infinite-loop termination.

No host deployment was needed. WSS/VPN/device approvals/owner binding were preserved. Unused remote-ASR additions were reverted without reverting owner or WSS. Whisper downloads/models are excluded from runtime and APK.

Earlier preview9 checks are preserved in PREVIEW9-RESULT.md: five Telegram DCs through public WSS/VPN; fresh synthetic 128-KiB and 12-MiB+197-byte file and photo round trips; owner invite/approve/revoke/non-owner denial; ghost interceptor; actual self-message deletion/history. Server regression was 57 passed, three optional real-Xray tests skipped. Those unchanged paths were not all repeated for preview11.

Build checks: same-key v2/v3 signatures, 16-KiB ZIP alignment, 23934 public resource IDs preserved, ten DEX files and 63660 unique classes. Existing verified native transport libraries were preserved.

## Limits

All test sends are structurally constrained to the activated account's Saved Messages. Only generated fixtures appear in screenshots. Existing addon choices were restored; fresh installs default to addons off.

Local ready-file recognition needs a suitable API, offline language model and verified provider. This Android 15 phone's Google Android System Intelligence service was verified; arbitrary providers/all Android versions are not claimed. Input limits: 16 MiB/five minutes. Text stays in bounded process memory and may need rerunning after restart.

Calls with another person, every external VPN, prolonged background delivery and other devices were not established here. Ghost affects this client and existing exceptions; other sessions remain independent. Deleted history cannot recover unseen messages. iOS was separately authorized after Android completion; this report makes no IPA claim.
