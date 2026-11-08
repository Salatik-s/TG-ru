package com.salatik.familygram.tgnetqa;
import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Isolated signed instrumentation, never included in the release APK. */
public final class TgnetInstrumentation extends Instrumentation {
  private String phase="bootstrap";
  private String mode="fixture";
  public void onCreate(Bundle args){super.onCreate(args);if(args!=null)mode=args.getString("mode","fixture");start();}
  private Class<?> cls(String name)throws Exception{return Class.forName(name,true,getTargetContext().getClassLoader());}
  private static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
  private void event(String name,String detail){Bundle b=new Bundle();b.putString("stage",name);b.putString("detail",detail);sendStatus(0,b);}
  private String readProfile(boolean publicGateway)throws Exception{
    java.io.File temporary=null;
    java.io.ByteArrayOutputStream content=new java.io.ByteArrayOutputStream();
    try{
      java.io.InputStream stream;
      if(publicGateway){
        temporary=new java.io.File(getTargetContext().getExternalFilesDir(null),"network-debug/profile.json");
        if(temporary.length()>16384)throw new IllegalStateException("profile_size");
        stream=new java.io.FileInputStream(temporary);
      }else stream=getContext().getAssets().open("test-profile.json");
      try(java.io.InputStream input=stream){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){content.write(buffer,0,count);if(content.size()>16384)throw new IllegalStateException("profile_size");}java.util.Arrays.fill(buffer,(byte)0);}
    }finally{if(temporary!=null&&temporary.exists()&&!temporary.delete())throw new IllegalStateException("profile_cleanup");}
    byte[] bytes=content.toByteArray();
    try{
      org.json.JSONObject profile=new org.json.JSONObject(new String(bytes,StandardCharsets.UTF_8));
      if(profile.has("gateway"))profile=profile.getJSONObject("gateway");
      if(publicGateway&&profile.has("publicKey")){
        // Owner's six-field diagnostic export; normalize only in this test.
        profile=new org.json.JSONObject().put("host",profile.getString("host")).put("port",profile.getInt("port"))
          .put("uuid",profile.getString("uuid")).put("public_key",profile.getString("publicKey"))
          .put("short_id",profile.getString("shortId")).put("server_name",profile.getString("serverName"))
          .put("protocol","vless").put("security","reality").put("flow","xtls-rprx-vision").put("fingerprint","chrome");
      }
      profile.put("host",publicGateway?(mode.equals("public-ip")?"157.228.191.231":"24projects.ru"):"localhost");
      return profile.toString();
    }finally{java.util.Arrays.fill(bytes,(byte)0);content.reset();}
  }
  public void onStart(){
    Class<?> nativeBridge=null,access=null;
    try{
      if(!mode.equals("fixture")&&!mode.equals("public-fqdn")&&!mode.equals("public-ip"))throw new IllegalArgumentException("mode");
      final boolean publicGateway=!mode.equals("fixture");
      nativeBridge=cls("org.telegram.messenger.family.FamilyNative");access=cls("org.telegram.messenger.family.FamilyAccess");
      Class<?> app=cls("org.telegram.messenger.ApplicationLoader");
      event("BEFORE_GATE","applicationInited="+field(app,"applicationInited").getBoolean(null));
      long readyDeadline=SystemClock.elapsedRealtime()+10_000;
      while(!field(nativeBridge,"initialized").getBoolean(null)&&SystemClock.elapsedRealtime()<readyDeadline)SystemClock.sleep(50);
      event("BOOTSTRAP_NATIVE","initialized="+field(nativeBridge,"initialized").getBoolean(null));
      phase="read_profile_"+mode;
      String profile=readProfile(publicGateway);
      event("PROFILE_FILE",publicGateway?"read_and_deleted":"local_fixture_asset");
      int result=(Integer)nativeBridge.getMethod("start",String.class).invoke(null,profile);
      profile=null;
      event("PROFILE_RESULT","code="+result+",error="+nativeBridge.getMethod("lastError").invoke(null));
      if(result!=0)throw new IllegalStateException("local_fixture_start_failed");
      // Test process only: bounded lease for the explicitly owner-supplied gateway
      // profile. No device approval, phone number, login or enrollment request.
      field(access,"allowedUntil").setLong(null,SystemClock.elapsedRealtime()+75_000);
      event("NATIVE_READY",String.valueOf(nativeBridge.getMethod("isReady").invoke(null)));
      field(cls("org.telegram.messenger.BuildVars"),"LOGS_ENABLED").setBoolean(null,!publicGateway);
      phase="actual_launch_post_init";
      Intent intent=new Intent().setComponent(new ComponentName("com.salatik.familygram","org.telegram.ui.LaunchActivity"));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      runOnMainSync(()->getTargetContext().startActivity(intent));
      long launchDeadline=SystemClock.elapsedRealtime()+15_000;
      while(!field(app,"applicationInited").getBoolean(null)&&SystemClock.elapsedRealtime()<launchDeadline)SystemClock.sleep(50);
      SystemClock.sleep(1500);
      event("AFTER_LAUNCH","applicationInited="+field(app,"applicationInited").getBoolean(null));
      phase="actual_connections_manager";
      Class<?> cm=cls("org.telegram.tgnet.ConnectionsManager");Object manager=cm.getMethod("getInstance",int.class).invoke(null,0);
      if(publicGateway&&field(cm,"appPaused").getBoolean(manager)){
        runOnMainSync(()->{try{cm.getMethod("setAppPaused",boolean.class,boolean.class).invoke(manager,false,false);}catch(Exception e){throw new RuntimeException(e);}});
        event("QA_LIFECYCLE","foreground_resumed");
      }
      if(publicGateway){nativeBridge.getMethod("renewLease",int.class).invoke(null,75);field(access,"allowedUntil").setLong(null,SystemClock.elapsedRealtime()+75_000);}
      event("AUTH_BEFORE","present="+(((Long)cm.getMethod("native_getCurrentAuthKeyId",int.class).invoke(null,0))!=0));
      event("MANAGER","dc="+cm.getMethod("native_getCurrentDatacenterId",int.class).invoke(null,0)+",state="+cm.getMethod("native_getConnectionState",int.class).invoke(null,0)+",paused="+field(cm,"appPaused").get(manager)+",resume="+field(cm,"appResumeCount").get(manager));
      phase="account_free_java_request";
      Class<?> tl=cls("org.telegram.tgnet.TLObject"),delegate=cls("org.telegram.tgnet.RequestDelegate");
      Object request=cls("org.telegram.tgnet.TLRPC$TL_help_getNearestDc").getConstructor().newInstance();
      AtomicBoolean callback=new AtomicBoolean(false);
      AtomicBoolean accepted=new AtomicBoolean(false),errorSeen=new AtomicBoolean(false);
      AtomicInteger writes=new AtomicInteger();CountDownLatch completed=new CountDownLatch(1);
      Object listener=Proxy.newProxyInstance(getTargetContext().getClassLoader(),new Class<?>[]{delegate},(p,m,a)->{
        if(m.getName().equals("run")){
          callback.set(true);Object response=a[0],error=a[1];errorSeen.set(error!=null);
          String type=response==null?"null":response.getClass().getSimpleName();
          accepted.set(error==null&&type.equals("TL_nearestDc"));
          int errorCode=error==null?0:error.getClass().getField("code").getInt(error);
          event("JAVA_CALLBACK","type="+type+",error_code="+errorCode);completed.countDown();
        }return null;
      });
      Class<?> quick=cls("org.telegram.tgnet.QuickAckDelegate"),write=cls("org.telegram.tgnet.WriteToSocketDelegate");
      Object written=Proxy.newProxyInstance(getTargetContext().getClassLoader(),new Class<?>[]{write},(p,m,a)->{if(m.getName().equals("run")){writes.incrementAndGet();event("NATIVE_REQUEST_WRITE","count="+writes.get());}return null;});
      int dc=cm.getField("DEFAULT_DATACENTER_ID").getInt(null),connectionType=cm.getField("ConnectionTypeGeneric").getInt(null);
      int token=(Integer)cm.getMethod("sendRequest",tl,delegate,quick,write,int.class,int.class,int.class,boolean.class).invoke(manager,request,listener,null,written,0x1b,dc,connectionType,true);
      event("REQUEST_QUEUED","token="+token);
      completed.await(publicGateway?60:15,TimeUnit.SECONDS);
      event("AUTH_AFTER","present="+(((Long)cm.getMethod("native_getCurrentAuthKeyId",int.class).invoke(null,0))!=0));
      java.util.Map<?,?> pending=(java.util.Map<?,?>)field(cm,"requestCallbacks").get(manager);
      event("AFTER_WAIT","dc="+cm.getMethod("native_getCurrentDatacenterId",int.class).invoke(null,0)+",state="+cm.getMethod("native_getConnectionState",int.class).invoke(null,0)+",pending="+pending.containsKey(token)+",callback="+callback.get()+",writes="+writes.get()+",engine_ready="+nativeBridge.getMethod("isReady").invoke(null));
      cm.getMethod("cancelRequest",int.class,boolean.class).invoke(manager,token,false);
      Bundle done=new Bundle();done.putString("mode",mode);done.putString("result",publicGateway?(accepted.get()?"TELEGRAM_CALLBACK_OK":errorSeen.get()?"TELEGRAM_CALLBACK_ERROR":callback.get()?"TELEGRAM_CALLBACK_UNEXPECTED":"TELEGRAM_TIMEOUT"):"HARNESS_COMPLETED_CHECK_HOST_HANDSHAKE_COUNTERS");finish(Activity.RESULT_OK,done);
    }catch(Throwable e){Bundle b=new Bundle();b.putString("result","FAIL");b.putString("phase",phase);b.putString("exception",e.getClass().getName());if(e.getCause()!=null)b.putString("cause",e.getCause().getClass().getName());finish(Activity.RESULT_CANCELED,b);}
    finally{try{if(nativeBridge!=null)nativeBridge.getMethod("stop").invoke(null);if(access!=null)field(access,"allowedUntil").setLong(null,0);}catch(Throwable ignored){}}
  }
}
