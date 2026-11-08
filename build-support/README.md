# Binary contingency build

The delivered preview5 uses this pinned APK rebuild with compiled Java/native overlays; a full upstream Gradle build was not completed. The signed artifact, exact hashes and completed runtime checks are recorded in [BUILD-RESULT.md](BUILD-RESULT.md). The source archive includes modifications, native inputs and upstream source pins. The sections below also preserve the original integration checklist; they are not evidence that every checklist item passed.

## Pinned input

Upstream release supplied by the parent task: `exteraless/exteraless`, `v13.1.1-beta15`.

Expected APK SHA-256:

```text
3855d366b9fa7b49e9219ac26a698c4307e0d4c205dae9b7188ef351d0a3a2c5
```

`prepare_repack.py` rejects any other input. It does not download the APK. The existing download is owned by the parent task at `familygram/tools/upstream.apk`; partial chunks are not accepted as an APK.

Tool researched from the [official Apktool release](https://github.com/iBotPeaches/Apktool/releases/tag/v3.0.3): `apktool_3.0.3.jar`, 15,478,013 bytes, SHA-256 `dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423`. It is downloaded separately with four workers into this directory; the scripts require its local path. Follow the [official Apktool documentation](https://apktool.org/docs/cli-parameters/) and [D8 documentation](https://developer.android.com/tools/d8) for tool behavior.

Existing local tools found: Android SDK Build Tools 36.1.0 (AAPT2, D8, zipalign, apksigner), Android 35 `android.jar`, IntelliJ JBR `javac/java`. The prior APK work had dexlib2 2.5.2, but no cached apktool was found in the targeted tool/cache locations. No files from that project are changed or packaged.

## Stages

1. **Prepare** — `prepare_repack.py --apk ... --apktool ... --java ... --work <new child of build-support>` verifies the upstream hash, decodes into that child, assigns new IDs after the last entry of each existing resource type, copies the family resources, adds both unexported family Activities, and applies the guard wrappers. It creates `report.json` and compile-time `generated/R.java`.
2. **Build unsigned** — `build_unsigned.py --work ... --apktool ... --java-bin ... --android-jar ... --build-tools ... --native-dir ... --abi arm64-v8a` requires the real `FamilyNative.java` and the real `libfamilytunnel.so` plus `libfamilytgnet.so`. It rejects missing or wrong-architecture native artifacts, compiles Java/D8, builds resources/smali with Apktool, verifies allocated resource IDs, appends an additional DEX, and aligns the unsigned APK.
3. **Validate and sign separately** — the parent task supplies an owned signing key and must perform the checks below. The unsigned filename is deliberately `FamilyGram-contingency-UNSIGNED-UNVERIFIED.apk`.

The work directory must be a fresh child of this directory. Existing source and input APK files are never changed. ABI pruning only touches explicitly selected staging directories. `R.java` is used for constants while compiling; `R.class` and nested R classes are not inserted into the APK, avoiding a duplicate upstream R definition. Existing upstream resource IDs remain pinned in `public.xml`.

The pinned fifth DEX is already near its 65,536 method-reference limit. Preparation moves LaunchActivity and its 227 associated definitions into `smali_classes8` without changing their class names. D8's family overlay is appended after the rebuilt eight upstream class sets. The complete build verifies unique class descriptors across every DEX.

On Windows, AAPT2 may reject a work path containing Cyrillic characters. Use `--ascii-work <existing ASCII junction to the exact work directory>`; the builder checks that both paths refer to the same directory before passing the alias to native tools. `--retry` replaces only the checked staging compiler-output directories. Apktool always uses `-f` to avoid trusting an incomplete DEX cache after a failed parallel build. The build packages the upstream license and transport notices under `assets/familygram/`.

The application ID becomes `com.salatik.familygram` and the label becomes `FamilyGram`. Manifest authorities, app-ID literals and matching resource values are rewritten; upstream Java package names stay unchanged. This permits a separate installation alongside Exteraless and does not migrate its data. A private signing key remains required. Licensing and source publication remain the parent's release responsibilities.

While native libraries are building, `build_unsigned.py` accepts `--compile-only` with the work/tool arguments and without `--native-dir` or `--abi`. This compiles all current overlay Java, the three binary integration helpers and D8 into separate check directories. It never creates an APK and does not substitute a native implementation. The later complete build compiles the sources again.

## Native bootstrap contract

The wrapper must provide:

```java
FamilyNative.initialize(android.content.Context);
```

It loads `familytunnel`, then `familytgnet`, then initializes `FamilyAccess`. Native load failure must abort initialization. The preparation step inserts the appearance preset, this call and the lifecycle guard immediately after both expected `NativeLoader.initNativeLibs(Context)` calls in `ApplicationLoader`, before `ConnectionsManager.native_setJava(false)` and before normal account connection creation. The idempotent preset also runs immediately after the first `applicationContext` assignment, before `AndroidUtilities` can cache appearance settings.

The companion native library must actually take ownership of the Telegram JNI connection entrypoints and enforce denial before any tgnet socket connects. Java UI wrappers cannot substitute for that. `FGReady`/`FGDial` need a native monotonic lease, and all active FDs must close on stop/revoke/expiry. A Java scheduler alone is insufficient to make the native lease expire reliably.

## Why the wrappers are necessary

In the inspected source snapshot, `LaunchActivity.onCreate()` invokes `postInitApplication()` before `super.onCreate()`. A UI redirect placed after the usual superclass call would allow account/network initialization first.

Simply returning from `onCreate()` also leaves later lifecycle methods with uninitialized fields. The script renames each original intercepted method and wraps it with a small independent register frame. The original register count/instruction sequence is retained. A per-Activity `familyBinaryInitialized` flag routes denied lifecycle calls only to the superclass. `onResume()` rechecks access; `onNewIntent()` refuses denied entries. `LaunchActivity.handleIntent()` is guarded before the upstream plugin dispatch wrapper.

All manifest Activity definitions are processed, including non-exported bubble/PendingIntent surfaces. If a class cannot be found exactly once, an expected method signature changed, or either native bootstrap call is absent, preparation stops. A separate `FamilyBinaryGuard` follows resumed Activities and redirects on later denial. `ApplicationLoader.postInitApplication()` returns before its body while access is denied. A row with ID `-44001` uses the existing SettingsActivity cell factory to open FamilySettingsActivity; its ghost/history/plugins links are handled by the allowlisted FamilyFeatures helper before plugin dispatch. Deferred links go through Telegram's passcode flow.

## Remaining network and entrypoint risks

These are findings requiring integration proof, not claims that the patch covers every subsystem:

- The patch removes the three ApplicationLoader bootstrap calls for proxy rotation, proxy ping and link-cleaner preload. `initPushServices()` is a no-op because the family service owns push registration. Firebase auto-init and default data collection are disabled; FirebaseInitProvider remains present. Other Java networking still requires independent review.
- Push and notification receivers, account sync, widget services, media streaming providers and exportable services can start the process without the launcher. Their network paths require native enforcement and separate review.
- Voice calls/WebRTC UDP and plugin-created HTTP/socket requests are separate paths. A companion tgnet library is not a device VPN and cannot automatically cover these.
- Providers may be created before `Application.onCreate()`. Audit provider paths that initialize networking or access caches; do not treat the launcher gate as a process-wide data boundary.
- Denied deep links/share intents are intentionally not replayed automatically after approval. The user must repeat them; untrusted external extras are not persisted as part of family credentials.
- Resource rebuild, multidex loading, signature-dependent behavior, JNI registration order, R8 name changes, case collisions in Windows paths, and native 16 KB page alignment still require validation on the real input.

## Checks completed and required

Seven Python tests cover method/register preservation, inherited `onCreate`, both native bootstrap calls, the early appearance preset, public passcode-handler dispatch, resource IDs, SettingsActivity callbacks and separate app identity. On the actual pinned APK, all 23 modified smali definitions (21 Activities, ApplicationLoader and SettingsActivity) assembled into DEX, and all 13 current Java sources compiled with Javac and D8 against Android 35. These checks are not Android ART/runtime verification. The staging report records preparation/build stages and, after a complete build, the compiled Java/native hashes, preserved public resource IDs and DEX class inventory.

Required before considering any output installable:

- Independently match the upstream APK SHA-256 (also enforced by preparation).
- Run preparation on the actual pinned APK; inspect `report.json`, manifest Activity inventory and generated smali wrappers.
- Build with real native artifacts; confirm original and added DEX sets contain no duplicate classes; verify JNI symbols, dependencies and load order.
- Compare every original public resource ID with the original APK, not only the new family IDs.
- Sign with the intended private-build key; run apksigner verify and zipalign verification. Do not call an unsigned artifact a finished APK.
- On a real device or emulator: cold launch without approval; launcher alias; deep link; share; plugin intent; bubble; notification reply; widget; background service startup; resume after revocation; process death; expiry while suspended.
- Capture connection attempts before approval and after revocation. Existing socket traffic must stop; reconnect must require a fresh validated config. Check call and plugin paths independently.
- Run approved access end to end with a live family service. Confirm native UI light/dark and font scaling; do not substitute a browser screenshot for Android rendering.

The recorded build is an unsigned APK, not a signed release. No installation, Android runtime result or successful live connection is claimed by these build checks.
