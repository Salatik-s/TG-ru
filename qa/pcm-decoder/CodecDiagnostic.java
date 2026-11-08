package com.salatik.familygram.codeccheck;

import android.app.Activity;
import android.app.Instrumentation;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.telegram.messenger.family.FamilyPcmDecoder;

/** Self-targeted codec fixture APK. It never starts or reads the Telegram application. */
public final class CodecDiagnostic extends Instrumentation {
    private String phase = "fixture_setup";
    private File folder;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        super.onStart();
        try {
            folder = new File(getTargetContext().getExternalFilesDir(null), "codec-" + UUID.randomUUID());
            require(folder.mkdir(), "fixture_directory");
            File wav = new File(folder, "synthetic.wav");
            try (InputStream in = getContext().getAssets().open("synthetic-ru.wav"); OutputStream out = new FileOutputStream(wav)) {
                byte[] bytes = new byte[8192]; int total = 0;
                for (int n; (n = in.read(bytes)) != -1;) { total += n; require(total <= 16 * 1024 * 1024, "fixture_size"); out.write(bytes, 0, n); }
            }
            byte[] reference = wavPcm(wav);
            require(reference.length > 32000 && energy(reference) > 0.000001, "fixture_non_silent");
            phase = "wav_exact_pcm";
            byte[] decoded = FamilyPcmDecoder.decode(wav, () -> false);
            require(decoded.length == reference.length && Arrays.equals(sha(decoded), sha(reference)), "wav_exact_pcm");
            pass("WAV_EXACT_PCM_SHA_AND_LENGTH_PASS", null);

            phase = "aac_encode";
            Encoded aac = encodeAudio(reference, "audio/mp4a-latm", "synthetic.m4a", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            pass("AAC_ENCODER_AND_MP4_MUX_PASS", aac.codec);
            phase = "aac_decode";
            assertDecoded(FamilyPcmDecoder.decode(aac.file, () -> false), reference, 3072);
            pass("AAC_MP4_DURATION_AND_ENERGY_PASS", null);

            phase = "opus_encode";
            Encoded opus = encodeAudio(reference, "audio/opus", "synthetic.ogg", MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
            pass("OPUS_ENCODER_AND_OGG_MUX_PASS", opus.codec);
            phase = "opus_decode";
            assertDecoded(FamilyPcmDecoder.decode(opus.file, () -> false), reference, 2048);
            pass("OPUS_OGG_DURATION_AND_ENERGY_PASS", null);

            phase = "cancel_before_decode";
            expectIOException(() -> FamilyPcmDecoder.decode(wav, () -> true), "audio_cancelled");
            pass("CANCEL_BEFORE_DECODE_PASS", null);
            phase = "cancel_mid_aac_decode";
            AtomicInteger checks = new AtomicInteger();
            expectIOException(() -> FamilyPcmDecoder.decode(aac.file, () -> checks.incrementAndGet() >= 25), "audio_cancelled");
            require(checks.get() >= 25, "mid_decode_checkpoint");
            assertDecoded(FamilyPcmDecoder.decode(aac.file, () -> false), reference, 3072);
            pass("CANCEL_MID_CODEC_THEN_REDECODE_PASS", null);
            phase = "cancel_mid_raw_decode";
            AtomicInteger rawChecks = new AtomicInteger();
            expectIOException(() -> FamilyPcmDecoder.decode(wav, () -> rawChecks.incrementAndGet() >= 25), "audio_cancelled");
            require(Arrays.equals(sha(FamilyPcmDecoder.decode(wav, () -> false)), sha(reference)), "raw_redecode");
            pass("CANCEL_MID_RAW_THEN_REDECODE_PASS", null);

            phase = "corrupted_input";
            File corrupt = new File(folder, "corrupted.bin");
            try (OutputStream out = new FileOutputStream(corrupt)) { out.write(new byte[]{1,3,5,7,11,13,17,19}); }
            expectIOException(() -> FamilyPcmDecoder.decode(corrupt, () -> false), null);
            pass("CORRUPTED_INPUT_REJECTED_PASS", null);
            phase = "video_only_fixture";
            Encoded video = encodeVideoOnly();
            pass("SYNTHETIC_AVC_ONLY_MP4_MUX_PASS", video.codec);
            expectIOException(() -> FamilyPcmDecoder.decode(video.file, () -> false), "audio_track_missing");
            pass("VALID_VIDEO_WITHOUT_AUDIO_REJECTED_PASS", null);

            phase = "round_video_fixture";
            Encoded square = encodeSquareVideo("synthetic-round-video.mp4", 48);
            File round = new File(folder, "synthetic-round.mp4");
            muxRound(square.file, aac.file, round);
            pass("SYNTHETIC_SQUARE_AVC_AAC_MP4_MUX_PASS", square.codec);
            phase = "round_video_audio_decode";
            assertDecoded(FamilyPcmDecoder.decode(round, () -> false), reference, 3072);
            pass("ROUND_VIDEO_AUDIO_DURATION_AND_ENERGY_PASS", null);

            Bundle result = new Bundle(); result.putString("result", "PASS");
            result.putString("event", "LOCAL_REAL_CODEC_FIXTURES_PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            Bundle result = new Bundle(); result.putString("result", "FAIL"); result.putString("phase", phase);
            result.putString("exception_type", error.getClass().getSimpleName());
            if(error.getMessage()!=null&&error.getMessage().matches("[a-zA-Z_0-9]+"))result.putString("error_code",error.getMessage());
            if (error instanceof CheckFailure) result.putString("check", error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Encoded encodeAudio(byte[] source, String mime, String name, int container) throws Exception {
        Choice choice = audioEncoder(mime);
        byte[] samples = choice.rate == 16000 ? source : resampleForEncoder(source, choice.rate);
        MediaFormat format = MediaFormat.createAudioFormat(mime, choice.rate, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, 2);
        if (mime.equals("audio/mp4a-latm")) format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        return encode(choice.name, format, new File(folder, name), container, samples, choice.rate, 0);
    }
    private Encoded encodeVideoOnly() throws Exception {
        return encodeSquareVideo("video-only.mp4", 3);
    }
    private Encoded encodeSquareVideo(String filename, int frameCount) throws Exception {
        require(frameCount > 0 && frameCount <= 48, "video_frame_count");
        MediaCodecInfo selected = null; int color = 0;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            if (!Arrays.asList(info.getSupportedTypes()).contains("video/avc")) continue;
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType("video/avc");
            if (!caps.getVideoCapabilities().areSizeAndRateSupported(128, 128, 5)) continue;
            int candidate = 0;
            for (int value : caps.colorFormats) if (value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible || value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar || value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) { candidate = value; break; }
            if (candidate != 0 && (selected == null || info.isSoftwareOnly())) { selected = info; color = candidate; if (info.isSoftwareOnly()) break; }
        }
        require(selected != null, "avc_encoder_unavailable");
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", 128, 128);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color); format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 5); format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        byte[] video = new byte[24576 * frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            int start = frame * 24576; Arrays.fill(video, start, start + 16384, (byte)(48 + frame % 6 * 24));
            // Equal U/V values work with both planar and semiplanar layouts.
            Arrays.fill(video, start + 16384, start + 24576, (byte)(96 + frame % 4 * 16));
        }
        return encode(selected.getName(), format, new File(folder, filename), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, video, 5, 24576);
    }

    /** Copies only our generated AVC/AAC samples, retaining each track's codec-specific data. */
    private static void muxRound(File video, File audio, File target) throws Exception {
        MediaExtractor videoExtractor = new MediaExtractor(), audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null; boolean started = false;
        long deadline = SystemClock.elapsedRealtime() + 30000;
        try (FileInputStream videoInput = new FileInputStream(video); FileInputStream audioInput = new FileInputStream(audio)) {
            videoExtractor.setDataSource(videoInput.getFD()); audioExtractor.setDataSource(audioInput.getFD());
            require(videoExtractor.getTrackCount() == 1 && audioExtractor.getTrackCount() == 1, "round_source_tracks");
            MediaFormat videoFormat = videoExtractor.getTrackFormat(0), audioFormat = audioExtractor.getTrackFormat(0);
            require("video/avc".equals(videoFormat.getString(MediaFormat.KEY_MIME)) && "audio/mp4a-latm".equals(audioFormat.getString(MediaFormat.KEY_MIME)), "round_source_formats");
            require(videoFormat.getInteger(MediaFormat.KEY_WIDTH) == 128 && videoFormat.getInteger(MediaFormat.KEY_HEIGHT) == 128, "round_square_dimensions");
            videoExtractor.selectTrack(0); audioExtractor.selectTrack(0);
            muxer = new MediaMuxer(target.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(videoFormat), audioTrack = muxer.addTrack(audioFormat);
            muxer.start(); started = true;
            MuxSample v = new MuxSample(videoExtractor, videoTrack), a = new MuxSample(audioExtractor, audioTrack);
            v.read(); a.read();
            while (!v.ended || !a.ended) {
                require(SystemClock.elapsedRealtime() < deadline, "round_mux_timeout");
                MuxSample next = a.ended || (!v.ended && v.info.presentationTimeUs <= a.info.presentationTimeUs) ? v : a;
                muxer.writeSampleData(next.track, next.buffer, next.info);
                next.written++; next.lastTimestamp = next.info.presentationTimeUs;
                next.extractor.advance(); next.read();
            }
            require(v.written == 48 && a.written > 0 && v.lastTimestamp == 9400000L, "round_track_duration");
            muxer.stop(); started = false;
            require(target.isFile() && target.length() > 0 && target.length() <= 16 * 1024 * 1024, "round_fixture_size");
        } finally {
            try { videoExtractor.release(); } finally {
                try { audioExtractor.release(); } finally {
                    if (muxer != null) {
                        try { if (started) muxer.stop(); } catch (RuntimeException ignored) { }
                        finally { muxer.release(); }
                    }
                }
            }
        }
    }
    private static final class MuxSample {
        final MediaExtractor extractor; final int track;
        final ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean ended; int written; long lastTimestamp = -1;
        MuxSample(MediaExtractor extractor, int track) { this.extractor = extractor; this.track = track; }
        void read() throws Exception {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) { ended = true; return; }
            require(size > 0 && size <= buffer.capacity(), "round_sample_size");
            // At EOS getSampleFlags() returns -1: query flags only for a real sample.
            int flags = extractor.getSampleFlags();
            require((flags & ~MediaExtractor.SAMPLE_FLAG_SYNC) == 0, "round_sample_flags");
            long timestamp = extractor.getSampleTime();
            require(timestamp >= 0 && timestamp >= lastTimestamp, "round_sample_timestamp");
            info.set(0, size, timestamp, (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            buffer.position(0); buffer.limit(size);
        }
    }
    private static Choice audioEncoder(String mime) throws CheckFailure {
        Choice fallback = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || !Arrays.asList(info.getSupportedTypes()).contains(mime)) continue;
            MediaCodecInfo.AudioCapabilities caps = info.getCapabilitiesForType(mime).getAudioCapabilities();
            int rate = mime.equals("audio/opus") && caps.isSampleRateSupported(48000) ? 48000 : caps.isSampleRateSupported(16000) ? 16000 : caps.isSampleRateSupported(48000) ? 48000 : 0;
            if (rate == 0) continue;
            Choice found = new Choice(info.getName(), rate);
            if (info.isSoftwareOnly()) return found;
            if (fallback == null) fallback = found;
        }
        require(fallback != null, "audio_encoder_unavailable"); return fallback;
    }
    private static Encoded encode(String name, MediaFormat format, File target, int container, byte[] source, int rate, int videoFrameBytes) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        MediaCodec codec = null; MediaMuxer muxer = null; boolean codecStarted = false, muxerStarted = false;
        try {
            codec = MediaCodec.createByCodecName(name);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start(); codecStarted = true;
            muxer = new MediaMuxer(target.getAbsolutePath(), container);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int sent = 0, track = -1, written = 0; boolean inputEnded = false, outputEnded = false;
            while (!outputEnded) {
                require(SystemClock.elapsedRealtime() < deadline, "encoder_timeout");
                if (!inputEnded) {
                    int index = codec.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer input = codec.getInputBuffer(index); require(input != null, "encoder_input"); input.clear();
                        long timestamp = (videoFrameBytes == 0 ? sent / 2L : sent / (long)videoFrameBytes) * 1000000L / rate;
                        if (sent == source.length) { codec.queueInputBuffer(index, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true; }
                        else {
                            int n = videoFrameBytes == 0 ? Math.min(source.length - sent, input.remaining() / 2 * 2) : videoFrameBytes;
                            require(n > 0 && n <= input.remaining(), "encoder_buffer_capacity");
                            input.put(source, sent, n); codec.queueInputBuffer(index, 0, n, timestamp, 0); sent += n;
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    require(!muxerStarted, "encoder_format_changed"); track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxerStarted = true;
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            require(muxerStarted, "muxer_missing_format"); ByteBuffer output = codec.getOutputBuffer(index);
                            require(output != null && info.offset >= 0 && (long)info.offset + info.size <= output.capacity(), "encoder_output");
                            output.position(info.offset); output.limit(info.offset + info.size); muxer.writeSampleData(track, output, info); written++;
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { codec.releaseOutputBuffer(index, false); }
                }
            }
            require(written > 0 && muxerStarted, "empty_encoder_output");
            muxer.stop(); muxerStarted = false;
            require(target.isFile() && target.length() > 0 && target.length() <= 16 * 1024 * 1024, "encoded_fixture_size");
            return new Encoded(target, name);
        } finally {
            try {
                if (codec != null) { try { if (codecStarted) codec.stop(); } catch (RuntimeException ignored) { } finally { try { codec.release(); } catch (RuntimeException ignored) { } } }
            } finally {
                if (muxer != null) { try { if (muxerStarted) muxer.stop(); } catch (RuntimeException ignored) { } finally { try { muxer.release(); } catch (RuntimeException ignored) { } } }
            }
        }
    }
    private static byte[] wavPcm(File file) throws Exception {
        byte[] bytes;
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] part = new byte[8192]; for(int n;(n=in.read(part))!=-1;)out.write(part,0,n);bytes=out.toByteArray(); }
        require(bytes.length >= 12 && tag(bytes,0,"RIFF") && tag(bytes,8,"WAVE"), "wav_header");
        boolean format = false; byte[] pcm = null;
        for (int at=12; at+8<=bytes.length;) {
            long length=le32(bytes,at+4); require(length<=bytes.length-at-8,"wav_chunk"); int start=at+8, size=(int)length;
            if(tag(bytes,at,"fmt ")) {
                require(size>=16,"wav_format"); ByteBuffer f=ByteBuffer.wrap(bytes,start,size).order(ByteOrder.LITTLE_ENDIAN);
                require(f.getShort()==1&&f.getShort()==1&&f.getInt()==16000,"wav_pcm16_mono16k"); f.getInt(); require(f.getShort()==2&&f.getShort()==16,"wav_bits"); format=true;
            } else if(tag(bytes,at,"data")) pcm=Arrays.copyOfRange(bytes,start,start+size);
            at=start+size+(size&1);
        }
        require(format&&pcm!=null&&pcm.length%2==0,"wav_data"); return pcm;
    }
    private static byte[] resampleForEncoder(byte[] source,int rate) {
        int frames=source.length/2, count=(int)((frames*(long)rate+15999)/16000); ByteBuffer out=ByteBuffer.allocate(count*2).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<count;i++){long phase=i*16000L;int index=(int)(phase/rate);double fraction=(phase%rate)/(double)rate;int left=sample(source,Math.min(index,frames-1)),right=sample(source,Math.min(index+1,frames-1));out.putShort((short)Math.round(left+(right-left)*fraction));}
        return out.array();
    }
    private static void assertDecoded(byte[] actual,byte[] reference,int toleranceFrames)throws Exception{
        require(actual.length>0&&actual.length%2==0&&Math.abs(actual.length-reference.length)<=toleranceFrames*2,"decoded_duration_padding");
        double energy=energy(actual), expected=energy(reference);
        require(energy>0.000001&&energy>expected*.1&&energy<expected*10,"decoded_nonblank_energy");
    }
    private static double energy(byte[] bytes){double sum=0;for(int i=0;i<bytes.length/2;i++){double v=sample(bytes,i)/32768.0;sum+=v*v;}return sum/Math.max(1,bytes.length/2);}
    private static int sample(byte[] b,int frame){int at=frame*2;return(short)((b[at]&255)|(b[at+1]<<8));}
    private static byte[] sha(byte[] b)throws Exception{return MessageDigest.getInstance("SHA-256").digest(b);}
    private static boolean tag(byte[] bytes,int at,String text){for(int i=0;i<4;i++)if(bytes[at+i]!=(byte)text.charAt(i))return false;return true;}
    private static long le32(byte[] b,int at){return(b[at]&255L)|((b[at+1]&255L)<<8)|((b[at+2]&255L)<<16)|((b[at+3]&255L)<<24);}
    private void pass(String event,String codec){Bundle b=new Bundle();b.putString("event",event);b.putBoolean("pass",true);if(codec!=null&&codec.matches("[a-zA-Z0-9._-]{1,150}"))b.putString("codec_name",codec);sendStatus(0,b);}
    private interface Work{void run()throws Exception;}
    private static void expectIOException(Work work,String code)throws Exception{try{work.run();throw new CheckFailure("expected_rejection_missing");}catch(IOException expected){if(code!=null)require(code.equals(expected.getMessage()),"unexpected_rejection_type");}}
    private static void require(boolean ok,String code)throws CheckFailure{if(!ok)throw new CheckFailure(code);}
    private static final class CheckFailure extends Exception{CheckFailure(String code){super(code);}}
    private static final class Choice{final String name;final int rate;Choice(String name,int rate){this.name=name;this.rate=rate;}}
    private static final class Encoded{final File file;final String codec;Encoded(File file,String codec){this.file=file;this.codec=codec;}}
}
