package org.telegram.messenger.family;

import java.io.IOException;
import java.nio.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PcmDecoderTest {
    private static int passed;
    private static FamilyPcmDecoder.Guard guard() { return new FamilyPcmDecoder.Guard(() -> false, 90000000000L); }
    public static void main(String[] arguments) throws Exception {
        short[] edge = {-32768, -12345, -1, 0, 1, 12345, 32767};
        byte[] source = shorts(edge);
        check(Arrays.equals(source, convert(source, 16000, 1, 2, 3)), "pcm16_identity_split_samples");
        byte[] stereo = shorts(new short[]{10000,-10000,32767,32767,-32768,-32768,10000,20000});
        check(Arrays.equals(shorts(new short[]{0,32767,-32768,15000}),convert(stereo,16000,2,2,1)),"stereo_mix_partial_frame_boundaries");
        byte[] floats=ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(Float.NEGATIVE_INFINITY).putFloat(3).putFloat(-3).putFloat(.5f).putFloat(-.5f).array();
        check(Arrays.equals(shorts(new short[]{0,32767,-32768,32767,-32768,16384,-16384}),convert(floats,16000,1,4,3)),"float_clamp_nan_and_little_endian");
        byte[] stereoFloat=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(.75f).putFloat(-.25f).putFloat(-.5f).putFloat(-.5f).array();
        check(Arrays.equals(shorts(new short[]{8192,-16384}),convert(stereoFloat,16000,2,4,5)),"float_stereo_chunked_mix");
        byte[] eight=shorts(new short[]{1000,1000,1000,1000,1000,1000,1000,1000});
        check(Arrays.equals(shorts(new short[]{1000}),convert(eight,16000,8,2,7)),"eight_channel_mix");
        check(Arrays.equals(shorts(new short[]{0,16384,32767,32767}),convert(shorts(new short[]{0,32767}),8000,1,2,1)),"linear_upsample_and_tail");
        for(int rate:new int[]{8000,16000,22050,44100,48000,192000}) {
            for(int frames:new int[]{1,2,13,1024,rate/10}) {
                ByteBuffer samples=ByteBuffer.allocate(frames*4).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<frames;i++){short value=(short)(Math.sin(i*.13)*28000);samples.putShort(value).putShort(value);}
                byte[] whole=convert(samples.array(),rate,2,2,Integer.MAX_VALUE);
                byte[] chunks=convert(samples.array(),rate,2,2,37);
                check(Arrays.equals(whole,chunks),"chunk_invariance_"+rate+"_"+frames);
                check(whole.length==2*((frames*16000L+rate-1)/rate),"rational_duration_"+rate+"_"+frames);
            }
        }
        int frames=44100; ByteBuffer tone=ByteBuffer.allocate(frames*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<frames;i++){float value=(float)(.5*Math.sin(2*Math.PI*500*i/44100));tone.putFloat(value);}
        byte[] result=convert(tone.array(),44100,1,4,127); ByteBuffer decoded=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        double error=0;for(int i=0;i<16000;i++){double expected=.5*Math.sin(2*Math.PI*500*i/16000);double difference=decoded.getShort()/32768.0-expected;error+=difference*difference;}
        check(result.length==32000&&Math.sqrt(error/16000)<.001,"tone_frequency_duration_and_amplitude");
        rejects(()->convert(new byte[3],16000,1,2,1),"pcm_frame_alignment");
        rejects(()->convert(new byte[7],16000,2,4,3),"pcm_frame_alignment");
        rejects(()->convert(new byte[0],16000,1,2,4),"empty_audio");
        for(int[] format:new int[][]{{7999,1,2},{192001,1,2},{16000,0,2},{16000,9,2},{16000,1,3},{16000,1,22}})
            rejects(()->new FamilyPcmDecoder.Pcm16(format[0],format[1],format[2],guard()),"unsupported_pcm_format");
        AtomicBoolean cancel=new AtomicBoolean(); FamilyPcmDecoder.Pcm16 pending=new FamilyPcmDecoder.Pcm16(16000,1,2,new FamilyPcmDecoder.Guard(cancel::get,90000000000L));
        pending.write(ByteBuffer.wrap(new byte[16])); cancel.set(true); rejects(()->pending.write(ByteBuffer.wrap(new byte[16])),"audio_cancelled");
        Thread.currentThread().interrupt();try{rejects(()->new FamilyPcmDecoder.Pcm16(16000,1,2,guard()),"audio_cancelled");check(Thread.currentThread().isInterrupted(),"interrupt_flag_preserved");}finally{Thread.interrupted();}
        rejects(()->new FamilyPcmDecoder.Pcm16(16000,1,2,new FamilyPcmDecoder.Guard(()->false,0)),"audio_decode_timeout");
        FamilyPcmDecoder.Pcm16 maximum=new FamilyPcmDecoder.Pcm16(8000,1,2,guard());
        ByteBuffer silence=ByteBuffer.allocate(8192);int remaining=8000*300;
        while(remaining>0){int n=Math.min(remaining,4096);silence.clear();silence.limit(n*2);maximum.write(silence);remaining-=n;}
        rejects(()->maximum.write(ByteBuffer.wrap(new byte[2])),"audio_duration_limit");
        check(maximum.finish().length==9600000,"exact_300_seconds_and_pcm_byte_limit");
        rejects(()->maximum.write(ByteBuffer.wrap(new byte[2])),"pcm_already_finished");
        FamilyPcmDecoder.checkSampleEncryption(-1,-1); check(true,"extractor_eof_negative_flags_are_not_drm");
        FamilyPcmDecoder.checkSampleEncryption(-1,2); check(true,"extractor_no_sample_ignores_flag_bits");
        FamilyPcmDecoder.checkSampleEncryption(1024,0); check(true,"clear_live_sample");
        FamilyPcmDecoder.checkSampleEncryption(1024,1); check(true,"clear_sync_sample");
        rejects(()->FamilyPcmDecoder.checkSampleEncryption(1024,2),"encrypted_audio");
        rejects(()->FamilyPcmDecoder.checkSampleEncryption(1024,3),"encrypted_audio");
        rejects(()->FamilyPcmDecoder.checkSampleEncryption(0,2),"encrypted_audio");
        rejects(()->FamilyPcmDecoder.checkSampleEncryption(1024,-1),"encrypted_audio");
        System.out.println("PASS "+passed+" streaming PCM fixtures");
    }
    private static byte[] convert(byte[] source,int rate,int channels,int encoding,int chunk)throws Exception{
        FamilyPcmDecoder.Pcm16 pcm=new FamilyPcmDecoder.Pcm16(rate,channels,encoding,guard());
        for(int at=0;at<source.length;){int n=Math.min(chunk,source.length-at);pcm.write(ByteBuffer.wrap(source,at,n));at+=n;}
        return pcm.finish();
    }
    private static byte[] shorts(short[] source){ByteBuffer bytes=ByteBuffer.allocate(source.length*2).order(ByteOrder.LITTLE_ENDIAN);for(short value:source)bytes.putShort(value);return bytes.array();}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;}
    private interface Work {void run()throws Exception;}
    private static void rejects(Work work,String code)throws Exception{try{work.run();throw new AssertionError("missing rejection "+code);}catch(IOException expected){check(code.equals(expected.getMessage()),"rejection "+code);}}
}
