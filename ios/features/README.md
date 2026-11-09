# Native iOS additions — source preparation

Official source pin: `24638bc69bd083981b178dd1e7f921eebbf7fc46`.
This directory contains native Swift source, exact source transformations and an existing bundled Python runtime. It is **not an IPA**, and Windows checks do not establish that these changes compile or run on iOS.

## Integration

Run `python patches/features.py --checkout <official-pinned-checkout>` after the Personal Team preparation and before the family bootstrap patch. The script validates HEAD and all affected original file hashes before writing, validates expected output hashes, refuses partial/unrecognized edits, and accepts its own exact output when repeated. It reads the actual checkout in production; ignored `features/upstream` files are development fixtures only.

The script patches eight native-source files, adds four helpers plus native UI tests, and integrates resources into `Telegram/BUILD`. It adds `LocalAudioTranscription` to InstantVideo's BUILD and `//privacy:FamilyPrivacy` to ChatListUI's BUILD; it does not modify TelegramUI/BUILD or AppDelegate. Apply the separate privacy integration as well and copy its module into the checkout's `privacy/` directory. The app integration must:

1. Run the feature patcher **after** the Personal Team Telegram/BUILD change. `features/resourcesintegration.py` is called automatically. It accepts only the exact official BUILD or either checked Personal Team output (normal/compile-only), or its own exact result. It creates `Telegram/FamilyPython.bundle`, adds `apple_bundle_import` to `Telegram:Lib.data`, and preserves `FamilyPython.bundle/family-python/runner/` in the app. Swift explicitly locates the nested resource Bundle. Licenses and provenance are included.
2. The resource integration replaces the existing `NSSpeechRecognitionUsageDescription` with an explicit on-demand, on-device description and parses the generated plist template to verify it. It never adds a duplicate key. This feature does not request microphone access.
3. Compile/link normal Apple frameworks imported by the Swift modules: UIKit, PDFKit, Speech, AVFoundation, WebKit. Do not add a native Python interpreter or microphone pipeline.
4. Run the native build and device acceptance checks below. No new network entitlement or ATS exception is needed for these additions.

## Visible paths

- Main chat-list title becomes `S.A.A.` only for the ordinary root title. Connecting/updating states, archive/forum titles and the normal Chats tab label remain upstream. A separate native ellipsis opens `FamilyFeatureSettingsController`; compose/edit/story controls remain. The extra ellipsis is hidden while editing/reordering.
- **Дополнения** contains three feature switches plus five privacy switches, all off on a fresh installation. Current preferences are local to the app installation and shared by its Telegram accounts; they are not cloud/account preferences. The archive viewer is scoped to the current account's `account.peerId.toInt64()`.
- **Расшифровка на устройстве:** native voice and round-video transcription buttons/expanded bubble text. Premium gates and server `messages.transcribeAudio` calls are removed from both button paths. Full downloaded file required. Secret/view-once/preview messages do not gain these buttons. Existing transcripts use the normal native bubble cache.
- **Просмотр PDF:** full-screen PDFKit, pinch/native zoom, zoom controls, fit, page entry, password entry and gated sharing. Other documents retain Telegram's existing native Quick Look route. The PDF helper opens the completed MediaBox file; no document uploads or HTML conversion service.
- **Python в сообщениях:** native inline Run/Stop, input and scrollable/selectable output below a message containing an explicit `python`, `py` or `python3` preformatted entity. Up to eight blocks can be selected. Code never auto-executes. Results remain in the view; they are not sent as messages or stored in chat history.

Privacy controls call the actual `FamilyPrivacySettings.shared` policy for read receipts, typing, online, story views and archiving. Their network interception is owned by the separate privacy patch and is not established by UI toggles alone. The native archive list/detail view reads `FamilyDeletedArchive`, displays received cloud text/dates, and clears only the current account after confirmation, then reloads actual storage without an unconditional success toast. The module excludes outgoing/protected/secret/TTL/unknown-TTL content. It captures handled deletion updates, not old deletions, full history clearing or unseen offline gaps. Text only, at most500 records/4096chars each, retained up to30days. No iOS plugin engine has been implemented; a plugin settings control is deliberately absent.

## Local speech contract

The existing Telegram Opus decoder converts voice audio to temporary AAC. Native AVAssetReader selects the audio track from AAC/MP4/round video and writes PCM16 mono16k WAV. Input ≤16MiB, duration ≤300s, decoded PCM ≤9.6MB. Decode loop has a30s deadline; speech job has a90s deadline; whole bubble operation has a120s timeout, including resource lookup/conversion. Synchronous OS calls cannot be forcibly preempted by a loop deadline.

Recognition requires iOS15+, authorization, an available locale recognizer and actual `supportsOnDeviceRecognition == true`. Every request sets `requiresOnDeviceRecognition = true`; there is no cloud-recognition fallback or attempt to write the capability property. Unsupported languages/devices, missing downloads, permissions, limits and errors show an explicit local alert. Only successful final text enters `storeLocallyTranscribedAudio`. Cancellation releases the recognition task; WAV/AAC temporary files are removed. No transcript, source path, speech error object or user audio is logged.

## Python isolation and platform risk

The copied runtime is **Pyodide314.0.7**, as established by the existing assets' provenance, not an older assumed version. Five runtime hashes were checked before copying. The WASM memory maximum is256MiB. The worker has an empty frozen JS globals object, an in-memory filesystem and no auto package installation. Native code is not exposed to the worker.

The invisible WKWebView uses a nonpersistent store, an exact static-resource scheme whitelist, CSP, a network content blocker and a main-frame/run-ID-checked output-only bridge. No arbitrary app file reads, native Telegram API, navigation or remote fetch is exposed. Native input validation limits code64KiB, input16KiB, output64K characters; worker output and native UI are bounded. JavaScript startup and execution budgets are separate:30s boot,10s run. A separate native watchdog uses35s startup and10s after `running`. Stop/replacement releases the WKWebView and terminates the worker; stale worker results cannot complete a newer run.

**Not verified:** whether the selected iOS WebKit accepts module Workers and WASM loading from this custom scheme. If blocked, the UI reports a startup failure; it must not silently use a CDN, backend or app-wide file access. A real WKWebView smoke test is required before enabling this feature in a release. Memory pressure can terminate WebContent; the native failure handler remains responsive but this has not been device-tested.

## Verification

Run `python -m unittest discover -s features/tests -v`. Tests cover source pin/anchor refusal, apply/check/reapply/atomic mismatch refusal, removal of the two server transcription calls, strict recognition settings, runtime file hashes and real Node execution of the controller's timeout/cancellation/epoch logic. Snapshot-dependent integration tests run locally; CI's preparation validates the real pinned checkout even without the snapshots. Node tests do not emulate WebKit or prove Python/WASM execution on iOS.

Resource tests additionally construct an explicitly synthetic archive fixture using the pinned import rule's path semantics, check the exact Swift lookup path, and reject flattened/missing/changed runtime files or an absent Speech usage string. This fixture is not a compiled app or signed IPA. After the real build, run `python features/resourcesintegration.py --verify-ipa <actual.ipa>`: it checks the actual main-app plist and every bundled runtime hash/path (allowing binary plist encoding and a signing seal).

The resource API and hierarchy were checked against the exact [rules_apple public exports](https://github.com/bazelbuild/rules_apple/blob/485016eb5b0948cc17307064e7977a6d68e94046/apple/resources.bzl), [apple_bundle_import implementation](https://github.com/bazelbuild/rules_apple/blob/485016eb5b0948cc17307064e7977a6d68e94046/apple/internal/resource_rules/apple_bundle_import.bzl) and [bundle_relative_parent_dir](https://github.com/bazelbuild/rules_apple/blob/485016eb5b0948cc17307064e7977a6d68e94046/apple/internal/resources.bzl). A real Bazel/Apple archive has not been produced on Windows.

`features/tests/FeaturesUIContracts.swift` contains native UIKit XCTest cases for the settings route, real UISwitch persistence/policy changes, archive navigation, default-off behavior, accessibility labels and Dynamic Type. The patcher adds these to the existing `//submodules/ChatListUI:ChatListUITests` target and its FamilyPrivacy dependency. The pinned upstream runner currently requests iPhone17/iOS26.5; the Mac test environment must contain the matching simulator or explicitly use an available runner. These tests are prepared, not run on Windows.

Required Mac/device acceptance: compile all modified modules with upstream warnings-as-errors; verify S.A.A. root title/extra menu while compose/edit/archive continue working; off→on→restart settings; local voice Opus and actual round MP4 audio recognition with Telegram's transcription RPC absent; unsupported locale/permission/timeout/cancel; large/password PDF and Word Quick Look; Python stdout/input/traceback/infinite loop/stop/reuse, external-fetch denial and WebContent termination. Use only synthetic self messages and files, without reading private chats.
