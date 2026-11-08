package org.telegram.messenger.family;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicitly selected attachment -> local decoder -> on-device system speech. No UI, upload, Premium RPC or microphone. */
public final class FamilyLocalTranscriber {
    public interface Callback { void onState(String code); void onResult(String text); void onError(String code); }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private static Job active;
    private FamilyLocalTranscriber() { }
    public static boolean eligible(Object message) {
        try {
            if(android.os.Build.VERSION.SDK_INT<33)return false;
            Object owner=field(message,"messageOwner");if(Boolean.TRUE.equals(field(owner,"noforwards"))||((Number)field(owner,"ttl")).intValue()>0)return false;
            try{if((Boolean)message.getClass().getMethod("isSecretMedia").invoke(message))return false;}catch(NoSuchMethodException ignored){}
            Object document=message.getClass().getMethod("getDocument").invoke(message);if(document==null)return false;
            long bytes=((Number)field(document,"size")).longValue();String mime=String.valueOf(field(document,"mime_type")).toLowerCase(Locale.ROOT).split(";",2)[0].trim();
            if(bytes<1||bytes>16777216||!mime.matches("audio/(ogg|opus|mpeg|mp4|wav|x-wav|flac|webm)|video/(mp4|webm)"))return false;
            for(Object attribute:(Iterable<?>)field(document,"attributes"))try{if(((Number)field(attribute,"duration")).doubleValue()>300)return false;}catch(NoSuchFieldException ignored){}
            return true;
        }catch(Exception failure){return false;}
    }
    public static Job start(Context context,Object message,int account,Callback callback) {
        Job job=new Job(context.getApplicationContext(),message,account,callback);
        synchronized(FamilyLocalTranscriber.class){
            if(active!=null&&active.isRunning()){MAIN.post(()->job.finish(null,"BUSY"));return job;}
            active=job;
        }
        job.future=WORK.submit(job::decode);return job;
    }
    public static final class Job {
        private final Context context;
        private Object message;
        private final int account;
        private Callback callback;
        private final AtomicBoolean ended=new AtomicBoolean();
        private volatile Future<?> future;
        private volatile FamilySystemSpeech.Session speech;
        private Job(Context context,Object message,int account,Callback callback){this.context=context;this.message=message;this.account=account;this.callback=callback;}
        public boolean isRunning(){return !ended.get();}
        public void cancel(){MAIN.post(()->finish(null,"CANCELLED"));}
        private void check()throws IOException{if(ended.get()||Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");if(!FamilyAccess.isAllowed())throw new IOException("NOT_ALLOWED");}
        private void state(String code){MAIN.post(()->{if(!ended.get()&&callback!=null)callback.onState(code);});}
        private void decode(){
            try{
                check();if(!eligible(message))throw new IOException("audio_input_limit");
                if(!FamilySystemSpeech.isAvailable(context))throw new IOException("ON_DEVICE_UNAVAILABLE");
                String provider=FamilySystemSpeech.providerKey(context);android.content.SharedPreferences prefs=context.getSharedPreferences("saa_system_speech_v1",0);
                boolean verified=FamilySystemSpeech.isVerifiedFileProvider(context)||(provider.equals(prefs.getString("verified_file_provider",""))&&android.os.Build.FINGERPRINT.equals(prefs.getString("verified_build","")));
                if(!verified)throw new IOException("FILE_SOURCE_UNVERIFIED");
                state("downloading");Object owner=field(message,"messageOwner"),document=message.getClass().getMethod("getDocument").invoke(message);
                Class<?> loaderType=Class.forName("org.telegram.messenger.FileLoader");Object loader=loaderType.getMethod("getInstance",int.class).invoke(null,account);
                File input=local(loader,owner);
                if(input==null){loaderType.getMethod("loadFile",Class.forName("org.telegram.tgnet.TLRPC$Document"),Object.class,int.class,int.class).invoke(loader,document,message,3,0);
                    long until=SystemClock.elapsedRealtime()+120000;while((input=local(loader,owner))==null&&SystemClock.elapsedRealtime()<until){check();Thread.sleep(500);}}
                if(input==null)throw new IOException("network_download_failed");check();state("decoding");
                byte[] pcm=FamilyPcmDecoder.decode(input,()->ended.get()||Thread.currentThread().isInterrupted());check();
                String language=prefs.getString("language","ru-RU");
                MAIN.post(()->{
                    if(ended.get()){java.util.Arrays.fill(pcm,(byte)0);return;}
                    state("recognizing");speech=FamilySystemSpeech.transcribe(context,pcm,language,verified,new FamilySystemSpeech.Callback(){
                        public void onResult(String text){finish(text,null);}public void onError(String error){finish(null,error);}public void onProgress(int sent,int total){}
                    });java.util.Arrays.fill(pcm,(byte)0);
                });
            }catch(Exception failure){String code=failure.getMessage();MAIN.post(()->finish(null,code==null?"decode_failed":code));}
        }
        private void finish(String text,String error){
            if(ended.getAndSet(true))return;
            if(speech!=null){speech.destroy();speech=null;}
            Future<?> pending=future;if(pending!=null&&!pending.isDone())pending.cancel(true);
            synchronized(FamilyLocalTranscriber.class){if(active==this)active=null;}
            Callback recipient=callback;callback=null;message=null;
            if(recipient!=null){if(error==null)recipient.onResult(text==null?"":text);else recipient.onError(error);}
        }
    }
    private static File local(Object loader,Object owner)throws Exception{
        String attached=String.valueOf(field(owner,"attachPath"));if(!attached.isEmpty()){File file=new File(attached);if(file.isFile()&&file.length()>0)return file;}
        File file=(File)loader.getClass().getMethod("getPathToMessage",Class.forName("org.telegram.tgnet.TLRPC$Message")).invoke(loader,owner);return file.isFile()&&file.length()>0?file:null;
    }
    private static Object field(Object object,String name)throws Exception{for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
}
