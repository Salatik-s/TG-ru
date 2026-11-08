# Real Android codec fixtures

`CodecDiagnostic.java` and `AndroidManifest.xml` build a separate testOnly APK targeting **its own** `com.salatik.familygram.codeccheck` package. It embeds the current production `FamilyPcmDecoder` source and only the existing synthetic `qa/system-speech/build/synthetic-ru.wav` asset. There are no INTERNET, microphone or storage permissions, no Activity, no Telegram process attachment, and no messages or account reads.

Run `python build.py`. Output: `Codec-Check-unsigned.apk`; `codec-build/report.json` records source/fixture hashes. Root signs it with the existing Sign.ps1, installs only this separate package with an explicit device serial, then runs:

```powershell
adb -s <explicit-device-serial> shell am instrument -w -r com.salatik.familygram.codeccheck/com.salatik.familygram.codeccheck.CodecDiagnostic
```

Expected final marker: `LOCAL_REAL_CODEC_FIXTURES_PASS`, result `PASS`. Compilation alone does not establish runtime codec support.

The fixture sequence:

1. Parse the synthetic WAV's actual fmt/data chunks; require PCM16LE mono16k and nonzero energy. Decode through the real helper, compare exact PCM length and SHA-256 internally.
2. Encode real AAC-LC with MediaCodec, mux to M4A/MP4 with MediaMuxer, then decode with the helper. Assert duration within3072 samples (192ms at16k, covering AAC encoder padding) and mean energy within0.1–10× the source and above silence.
3. Encode Opus, preferably at48k to exercise real downsampling, mux to OGG, then decode. Duration tolerance2048 samples (128ms at16k); same nonblank/energy checks.
4. Cancel before decode and after the25th guard check during AAC and raw WAV decode. Immediately decode again and validate the result. This exercises actual cleanup paths and successful subsequent codec/extractor use; it is not a global proof of no OS resource leaks.
5. Reject corrupted input. Encode a three-frame synthetic128×128 AVC-only MP4, then require the specific no-audio-track rejection from the helper.
6. Encode 48 synthetic colour frames at 128×128 and 5fps, then copy the AVC track and the generated AAC track into `synthetic-round.mp4`. Preserve codec-specific format data, interleave samples by timestamp, reject encrypted/partial samples, and query flags only after a successful sample read. Require 48 video samples ending at9.4s (9.6s at5fps), then decode the combined MP4 audio and apply the AAC duration/energy checks. New events: `SYNTHETIC_SQUARE_AVC_AAC_MP4_MUX_PASS` and `ROUND_VIDEO_AUDIO_DURATION_AND_ENERGY_PASS`.

Each encoder loop has a30s deadline,10ms dequeue timeouts and codec/muxer stop/release in finally. Synchronous platform codec calls cannot be hard-preempted by Java; root may apply an outer instrumentation timeout. An unavailable encoder yields a bounded FAIL with a static check label rather than silently reporting a passed codec test. Output contains only test PASS events, public codec names and static failure phase/check/type; no audio content/transcription is printed.

Generated files stay in this QA app's scoped external files directory under `codec-<UUID>/`, isolated from the installed client. The combined fixture is `synthetic-round.mp4`. It contains only generated colour frames and the synthetic audio asset; no camera or microphone is used. Decoder checks do not establish Telegram playback, sending or speech recognition.

Root reported the existing WAV/AAC/Opus/cancellation/corruption/video-only checks passing on the real phone after the decoder EOF fix. The newly added combined round-video fixture is prepared for runtime verification; compilation alone is not a runtime result. This subtask performs javac, D8, AAPT2 and ZIP alignment only. Root owns signing, physical installation and runtime result capture. Production sources, UI, APK and phone are unchanged by this diagnostic preparation.
