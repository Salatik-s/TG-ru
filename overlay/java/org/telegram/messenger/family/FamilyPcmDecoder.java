package org.telegram.messenger.family;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Decodes an existing local audio/video file; never records audio or accesses the network. */
public final class FamilyPcmDecoder {
    static final int MAX_INPUT = 16 * 1024 * 1024;
    static final int OUTPUT_RATE = 16000, MAX_SECONDS = 300, MAX_PCM = 9600000;
    private static final long CODEC_WAIT_US = 10000, DEADLINE_NS = 90000000000L;
    private FamilyPcmDecoder() { }

    public static byte[] decode(File file, BooleanSupplier cancelled) throws IOException {
        Guard guard = new Guard(cancelled, DEADLINE_NS); guard.check();
        if (file == null || !file.isFile()) throw new IOException("invalid_audio_file");
        MediaExtractor extractor = null; MediaCodec codec = null; boolean started = false;
        try (FileInputStream input = new FileInputStream(file)) {
            long length = input.getChannel().size();
            if (length < 1 || length > MAX_INPUT) throw new IOException("audio_input_limit");
            guard.check();
            extractor = new MediaExtractor();
            extractor.setDataSource(input.getFD(), 0, length);
            guard.check();
            int tracks = extractor.getTrackCount(), selected = -1;
            if (tracks < 1 || tracks > 64) throw new IOException("audio_track_count");
            MediaFormat format = null; String mime = null;
            for (int i = 0; i < tracks; i++) {
                guard.check(); MediaFormat candidate = extractor.getTrackFormat(i);
                String kind = candidate.getString(MediaFormat.KEY_MIME);
                if (kind != null && kind.startsWith("audio/")) { selected = i; format = candidate; mime = kind; break; }
            }
            if (selected < 0) throw new IOException("audio_track_missing");
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (rate < 8000 || rate > 192000) throw new IOException("unsupported_pcm_format");
            }
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (channels < 1 || channels > 8) throw new IOException("unsupported_pcm_format");
            }
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > MAX_SECONDS * 1000000L) throw new IOException("audio_duration_limit");
            extractor.selectTrack(selected);
            if ("audio/raw".equals(mime)) return raw(extractor, format, length, guard);
            guard.check();
            codec = MediaCodec.createDecoderByType(mime);
            // Decoders normally use signed PCM16; reported PCM_FLOAT is also handled below.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec.configure(format, null, null, 0); guard.check();
            codec.start(); started = true; guard.check();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            Pcm16 pcm = null; boolean inputEnded = false, outputEnded = false;
            long totalInput = 0, firstTimestamp = Long.MIN_VALUE;
            while (!outputEnded) {
                guard.check();
                if (!inputEnded) {
                    int index = codec.dequeueInputBuffer(CODEC_WAIT_US);
                    guard.check();
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        if (buffer == null) throw new IOException("codec_input_buffer");
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true;
                        } else {
                            checkSampleEncryption(size, extractor.getSampleFlags());
                            if (size > buffer.capacity() || (totalInput += size) > MAX_INPUT) throw new IOException("audio_input_limit");
                            long timestamp = extractor.getSampleTime();
                            if (firstTimestamp == Long.MIN_VALUE) firstTimestamp = timestamp;
                            if (timestamp >= firstTimestamp && timestamp - firstTimestamp > MAX_SECONDS * 1000000L) throw new IOException("audio_duration_limit");
                            codec.queueInputBuffer(index, 0, size, timestamp, 0);
                            extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, CODEC_WAIT_US);
                guard.check();
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    pcm = output(codec.getOutputFormat(), pcm, guard);
                } else if (index >= 0) {
                    try {
                        if (info.size < 0 || info.offset < 0) throw new IOException("codec_output_range");
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (pcm == null) pcm = output(codec.getOutputFormat(), null, guard);
                            ByteBuffer buffer = codec.getOutputBuffer(index);
                            if (buffer == null || (long)info.offset + info.size > buffer.capacity()) throw new IOException("codec_output_range");
                            ByteBuffer view = buffer.duplicate(); view.clear(); view.position(info.offset); view.limit(info.offset + info.size);
                            pcm.write(view);
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { codec.releaseOutputBuffer(index, false); }
                }
            }
            guard.check();
            if (pcm == null) throw new IOException("empty_audio");
            return pcm.finish();
        } catch (RuntimeException failure) {
            throw new IOException("audio_decode_failed", failure);
        } finally {
            try {
                if (codec != null) {
                    try { if (started) codec.stop(); } catch (RuntimeException ignored) { }
                    finally { try { codec.release(); } catch (RuntimeException ignored) { } }
                }
            } finally { if (extractor != null) try { extractor.release(); } catch (RuntimeException ignored) { } }
        }
    }

    private static byte[] raw(MediaExtractor extractor, MediaFormat format, long fileLength, Guard guard) throws IOException {
        Pcm16 pcm = output(format, null, guard);
        // API28 exposes sample size. On older Android use a bounded file-sized buffer so a large raw sample still fits.
        int initial = Build.VERSION.SDK_INT >= 28 ? (int)Math.min(256 * 1024, fileLength) : (int)fileLength;
        ByteBuffer buffer = ByteBuffer.allocate(Math.max(1, initial)); long total = 0;
        while (true) {
            guard.check();
            if (Build.VERSION.SDK_INT >= 28) {
                long next = extractor.getSampleSize();
                if (next < 0) break;
                if (next > MAX_INPUT) throw new IOException("audio_input_limit");
                if (next > buffer.capacity()) buffer = ByteBuffer.allocate((int)next);
            }
            buffer.clear(); int n = extractor.readSampleData(buffer, 0);
            if (n < 0) break;
            checkSampleEncryption(n, extractor.getSampleFlags());
            if (n > buffer.capacity() || (total += n) > MAX_INPUT) throw new IOException("audio_input_limit");
            buffer.position(0); buffer.limit(n); pcm.write(buffer);
            if (!extractor.advance()) break;
        }
        return pcm.finish();
    }

    static void checkSampleEncryption(int sampleSize, int flags) throws IOException {
        // At extractor EOS flags are -1, whose bits must not be mistaken for an encrypted sample.
        if (sampleSize >= 0 && (flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw new IOException("encrypted_audio");
    }

    private static Pcm16 output(MediaFormat format, Pcm16 previous, Guard guard) throws IOException {
        if (!format.containsKey(MediaFormat.KEY_SAMPLE_RATE) || !format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) throw new IOException("pcm_format_missing");
        int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING) ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
        if (previous != null && previous.rate == rate && previous.channels == channels && previous.encoding == encoding) return previous;
        if (previous != null && (previous.frames != 0 || previous.partial != 0)) throw new IOException("pcm_format_changed");
        return new Pcm16(rate, channels, encoding, guard);
    }

    static final class Guard {
        final BooleanSupplier cancelled; final long start = System.nanoTime(), limit;
        Guard(BooleanSupplier cancelled, long limit) { this.cancelled = cancelled; this.limit = limit; }
        void check() throws IOException {
            if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) throw new IOException("audio_cancelled");
            if (System.nanoTime() - start >= limit) throw new IOException("audio_decode_timeout");
        }
    }

    /** Streaming mono conversion and rational-phase linear resampling; package visibility is for JVM fixtures only. */
    static final class Pcm16 {
        final int rate, channels, encoding, sampleBytes, frameBytes; final Guard guard;
        final byte[] frame; byte[] bytes = new byte[65536]; int size, partial;
        long frames, samples; double last; boolean finished;
        Pcm16(int rate, int channels, int encoding, Guard guard) throws IOException {
            if (rate < 8000 || rate > 192000 || channels < 1 || channels > 8 || (encoding != 2 && encoding != 4)) throw new IOException("unsupported_pcm_format");
            this.rate = rate; this.channels = channels; this.encoding = encoding; this.guard = guard;
            sampleBytes = encoding == 2 ? 2 : 4; frameBytes = channels * sampleBytes; frame = new byte[frameBytes]; guard.check();
        }
        void write(ByteBuffer input) throws IOException {
            guard.check(); if (finished) throw new IOException("pcm_already_finished");
            while (input.hasRemaining()) {
                int n = Math.min(input.remaining(), frameBytes - partial); input.get(frame, partial, n); partial += n;
                if (partial != frameBytes) continue;
                partial = 0;
                if ((frames & 4095) == 0) guard.check();
                if (frames >= (long)rate * MAX_SECONDS) throw new IOException("audio_duration_limit");
                double mono = 0;
                for (int channel = 0; channel < channels; channel++) {
                    int at = channel * sampleBytes;
                    if (encoding == 2) mono += (short)((frame[at] & 255) | (frame[at + 1] << 8)) / 32768.0;
                    else {
                        int bits = (frame[at] & 255) | ((frame[at + 1] & 255) << 8) | ((frame[at + 2] & 255) << 16) | (frame[at + 3] << 24);
                        float value = Float.intBitsToFloat(bits);
                        mono += Float.isNaN(value) ? 0 : Math.max(-1.0, Math.min(1.0, value));
                    }
                }
                mono /= channels;
                long boundary = frames * OUTPUT_RATE;
                while (samples * rate <= boundary) {
                    double value = mono;
                    if (frames != 0) { double fraction = (samples * rate - (frames - 1) * OUTPUT_RATE) / (double)OUTPUT_RATE; value = last + (mono - last) * fraction; }
                    emit(value);
                }
                last = mono; frames++;
            }
        }
        byte[] finish() throws IOException {
            guard.check(); if (finished) throw new IOException("pcm_already_finished");
            if (partial != 0) throw new IOException("pcm_frame_alignment");
            if (frames == 0) throw new IOException("empty_audio");
            long wanted = (frames * OUTPUT_RATE + rate - 1) / rate;
            while (samples < wanted) emit(last);
            finished = true; guard.check();
            return size == bytes.length ? bytes : Arrays.copyOf(bytes, size);
        }
        private void emit(double value) throws IOException {
            if (size > MAX_PCM - 2) throw new IOException("pcm_output_limit");
            if (size + 2 > bytes.length) bytes = Arrays.copyOf(bytes, Math.min(MAX_PCM, Math.max(size + 2, bytes.length + bytes.length / 2)));
            int integer = (int)Math.max(-32768, Math.min(32767, Math.round(Math.max(-1.0, Math.min(1.0, value)) * 32768.0)));
            bytes[size++] = (byte)integer; bytes[size++] = (byte)(integer >>> 8); samples++;
        }
    }
}
