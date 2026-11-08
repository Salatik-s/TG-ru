# Local file → mono16k PCM helper

Source: `overlay/java/org/telegram/messenger/family/FamilyPcmDecoder.java` under the familygram root.

Contract: `public static byte[] decode(File file, BooleanSupplier cancelled) throws IOException`. Returns headerless signed PCM16 little-endian, mono, 16,000Hz. The caller supplies an existing local file and should run decoding on its worker thread. No AudioRecord, microphone, network or ASR service is used here.

MediaExtractor selects the first audio track. Compressed audio is decoded by the device's MediaCodec (Opus/Ogg voice, AAC in video/MP4, MP3 and other installed decoder formats); `audio/raw`, including supported raw WAV tracks, bypasses MediaCodec. Actual codec/container availability depends on the Android device. PCM16 is requested; reported PCM16 or float32 output is supported. Other PCM encodings and a format change after audio data has started are rejected explicitly.

Limits: nonempty regular file≤16MiB, known duration≤300s, decoded frames≤300s, output≤9,600,000bytes, sample rate8k–192k, channels1–8. Encrypted tracks are rejected. MediaCodec polling uses10ms timeouts and a monotonic90s deadline. Cancellation and the existing thread interrupt flag are checked around framework calls and throughout sample processing; the interrupt flag is preserved. Codec stop/release and extractor release run in finally paths; no codec is allocated for raw PCM.

The90s deadline bounds the polling/conversion loops. A pathological platform codec blocking inside synchronous configure/start/stop/release cannot be forcibly preempted by this Java helper; the owning worker/service may impose an outer watchdog if a hard process-level bound is required. No platform codec/device test is claimed by the JVM fixtures.

Conversion mixes channels by arithmetic mean, clamps float samples (NaN→0, infinities→±1), carries incomplete PCM frames between arbitrary byte chunks, and uses rational-phase linear interpolation. Final sample count is `ceil(inputFrames*16000/inputRate)`, with the final sample held for the fractional tail. This preserves duration within one output sample and produces identical bytes regardless of chunk boundaries. It is simple linear resampling, not a high-order anti-aliasing filter. A leftover partial frame at EOS is rejected.

Output storage grows only up to the PCM byte limit; no full float array is allocated. Raw samples on API28+ use reported sample sizes; API24–27 use a bounded file-sized byte buffer because getSampleSize is unavailable.

Run `./Run.ps1`:91 pure-JVM fixtures cover exact PCM16 identity, signed LE output, stereo/eight-channel mix, float clamping, upsample tail, six source rates and varied lengths, byte-level chunk invariance, a500Hz tone's amplitude/duration, invalid formats/alignment/empty input, cancellation, interruption, deadline and the exact300s/9.6MB boundary plus an additional rejected frame. Eight extractor-flag regression checks distinguish EOS (`sampleSize=-1`, flags=-1) from actual encrypted samples, including zero-length/live malformed-flag cases. Both decoder and raw paths now read a sample first, detect EOF, and only then inspect its encryption flags. Android35 javac and D8 compilation pass. No Activity, service, resource, Office renderer, microphone permission or phone is modified by this helper task.
