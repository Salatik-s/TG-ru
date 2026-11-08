package com.salatik.familygram.savedmedia;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Owner-authorized synthetic media test. Recipient cannot be supplied by a caller. */
public final class SavedMediaDiagnostic extends Instrumentation {
    private ClassLoader loader;
    private int account;
    private long self;
    private Object instance, config, center, observer;
    private Object selfChat;
    private volatile Activity launch;
    private volatile Activity current;
    private volatile String expectedCaption;
    private final AtomicReference<Object> acknowledged = new AtomicReference<>();
    private final AtomicReference<Object> acknowledgedMessage = new AtomicReference<>();
    private final AtomicReference<String> observerError = new AtomicReference<>();
    private final AtomicInteger acknowledgedId = new AtomicInteger();
    private String mode="all";
    private File folder;
    private int notificationId;
    private long began;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        String requested=arguments.getString("mode","all");if(requested.matches("photo|features|owner|tools|owner-cycle|finalize"))mode=requested;
        ((Application)getTargetContext().getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) {current=a; if(a.getClass().getName().equals("org.telegram.ui.LaunchActivity")) launch=a; }
            public void onActivityCreated(Activity a,Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a,Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        });
        start();
    }
    @Override public void onStart() {
        Bundle report=new Bundle(); began=SystemClock.elapsedRealtime();
        try {
            loader=getTargetContext().getClassLoader();
            getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName("com.salatik.familygram","org.telegram.messenger.ExteralessIcon"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long until=SystemClock.elapsedRealtime()+60000;
            Class<?> uc=cls("org.telegram.messenger.UserConfig");
            while(SystemClock.elapsedRealtime()<until) {
                if(launch!=null && (Boolean)cls("org.telegram.messenger.family.FamilyAccess").getMethod("isAllowed").invoke(null)) {
                    account=uc.getField("selectedAccount").getInt(null);
                    config=uc.getMethod("getInstance",int.class).invoke(null,account);
                    if((Boolean)config.getClass().getMethod("isClientActivated").invoke(config)) break;
                }
                SystemClock.sleep(100);
            }
            if(config==null || launch==null) throw new Check("NORMAL_LAUNCH_NOT_READY");
            self=(Long)config.getClass().getMethod("getClientUserId").invoke(config);
            instance=cls("org.telegram.messenger.AccountInstance").getMethod("getInstance",int.class).invoke(null,account);
            guard();
            folder=new File(getTargetContext().getExternalFilesDir(null),"familygram-synthetic-"+UUID.randomUUID());
            if(!folder.mkdir()) throw new Check("QA_DIRECTORY_FAILED");
            center=instance.getClass().getMethod("getNotificationCenter").invoke(instance);
            Class<?> nc=cls("org.telegram.messenger.NotificationCenter");
            notificationId=nc.getField("messageReceivedByServer").getInt(null);
            Class<?> delegate=cls("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate");
            observer=Proxy.newProxyInstance(loader,new Class<?>[]{delegate},(proxy,method,args)->{
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return proxy==args[0];
                if(method.getName().equals("toString"))return "SelfOnlySyntheticObserver";
                if(method.getName().equals("didReceivedNotification")) try {
                    int id=(Integer)args[0],slot=(Integer)args[1]; Object[] values=(Object[])args[2];
                    // Filter destination before touching any message payload.
                    if(id!=notificationId || slot!=account || values.length<4 || !(values[3] instanceof Long) || ((Long)values[3])!=self) return null;
                    Object message=values[2];
                    if(message==null || !cls("org.telegram.tgnet.TLRPC$Message").isInstance(message)) return null;
                    if(expectedCaption!=null && expectedCaption.equals(field(message,"message")) && ((Number)field(message,"id")).intValue()>0) {
                        acknowledgedId.set(((Number)field(message,"id")).intValue());
                        acknowledgedMessage.set(message);
                        acknowledged.compareAndSet(null,field(message,"media"));
                    }
                } catch(Throwable failure) { observerError.set("SYNTHETIC_ACK_OBSERVER_FAILED_"+failure.getClass().getSimpleName()); }
                return null;
            });
            ui(()->{
                    guard(); center.getClass().getMethod("addObserver",delegate,int.class).invoke(center,observer,notificationId);
                    Bundle args=new Bundle();args.putLong("user_id",self);
                    Object chat=cls("org.telegram.ui.ChatActivity").getConstructor(Bundle.class).newInstance(args);
                    selfChat=chat;
                    launch.getClass().getMethod("presentFragment",cls("org.telegram.ui.ActionBar.BaseFragment")).invoke(launch,chat);
            });
            status("SELF_ONLY_READY",0,0);
            if(mode.equals("features")){ghost();deleted();report.putString("result","GHOST_AND_SELF_DELETION_HISTORY_PASSED");}
            else if(mode.equals("owner")){owner();report.putString("result","BOUND_PHONE_OWNER_LIST_PASSED");}
            else if(mode.equals("tools")){tools();report.putString("result","LOCAL_PYTHON_AND_DOCUMENT_VIEWERS_PASSED");}
            else if(mode.equals("owner-cycle")){ownerCycle();report.putString("result","OWNER_INVITE_APPROVE_REVOKE_AND_NONOWNER_DENIAL_PASSED");}
            else if(mode.equals("finalize")){finalizeSettings();report.putString("result","SAA_OWNER_UI_AND_BOTH_TOOLS_DISABLED_VERIFIED");}
            else{if(mode.equals("all")){document("small",128*1024);document("large",12*1024*1024+197);}photo();report.putString("result","SAVED_MESSAGES_MEDIA_ROUNDTRIP_PASSED");}
            report.putInt("tests_passed",mode.equals("all")?3:mode.equals("features")?2:1);
            report.putBoolean("recipient_was_self_only",true);
            finish(Activity.RESULT_OK,report);
        } catch(Throwable failure) {
            report.putString("result",failure instanceof Check?failure.getMessage():"SYNTHETIC_TEST_FAILED");
            report.putString("exception_type",failure.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED,report);
        } finally {
            if(center!=null&&observer!=null) try {
                runOnMainSync(()->{try {center.getClass().getMethod("removeObserver",cls("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate"),int.class).invoke(center,observer,notificationId);}catch(Exception ignored){}});
            } catch(Throwable ignored) {}
            // Keep only these synthetic local inputs on a failed test: an upload may still be queued.
        }
    }
    private void guard() throws Exception {
        if(self<=0 || account!=cls("org.telegram.messenger.UserConfig").getField("selectedAccount").getInt(null)
            || !(Boolean)config.getClass().getMethod("isClientActivated").invoke(config)
            || self!=(Long)config.getClass().getMethod("getClientUserId").invoke(config)) throw new Check("SELF_RECIPIENT_GUARD_FAILED");
        if(!(Boolean)cls("org.telegram.messenger.family.FamilyAccess").getMethod("isAllowed").invoke(null)) throw new Check("FAMILY_LEASE_NOT_READY");
        Object plugins=cls("app.exteraless.plugins.PluginsController").getMethod("getInstance").invoke(null);
        if((Boolean)plugins.getClass().getMethod("hasAnyRequestHooks").invoke(plugins)
            ||(Boolean)plugins.getClass().getMethod("hasSendMessageHooks").invoke(plugins)) throw new Check("ACTIVE_PLUGIN_HOOKS_TEST_ABORTED");
    }
    private void document(String label,int size) throws Exception {
        guard(); File file=new File(folder,"familygram-"+label+".bin");
        MessageDigest source=MessageDigest.getInstance("SHA-256"); SecureRandom random=new SecureRandom();byte[] block=new byte[32768];
        try(OutputStream out=new FileOutputStream(file)) { for(int n=0;n<size;) {random.nextBytes(block);int take=Math.min(block.length,size-n);out.write(block,0,take);source.update(block,0,take);n+=take;} }
        expectedCaption="FamilyGram synthetic "+folder.getName()+" "+label; acknowledged.set(null);
        ArrayList<String> paths=new ArrayList<>();paths.add(file.getAbsolutePath());
        Method send=null;
        for(Method m:cls("org.telegram.messenger.SendMessagesHelper").getMethods())
            if(m.getName().equals("prepareSendingDocuments")&&m.getParameterCount()==19&&m.getParameterTypes()[4]==String.class)send=m;
        if(send==null)throw new Check("UPSTREAM_DOCUMENT_API_MISMATCH"); final Method invoke=send;
        ui(()->{guard();invoke.invoke(null,instance,paths,paths,null,expectedCaption,"application/octet-stream",self,null,null,null,null,null,false,0,null,null,0L,false,0L);});
        Object media=waitAck(label,240000); Object doc=field(media,"document");
        if(doc==null||((Number)field(doc,"size")).longValue()!=size)throw new Check("SYNTHETIC_DOCUMENT_METADATA_MISMATCH");
        Object location=cls("org.telegram.tgnet.TLRPC$TL_inputDocumentFileLocation").getConstructor().newInstance();
        copy(doc,location,"id","access_hash","file_reference");set(location,"thumb_size","");
        byte[] actual=download(location,((Number)field(doc,"dc_id")).intValue(),size,null);
        if(!MessageDigest.isEqual(source.digest(),actual))throw new Check("SYNTHETIC_DOCUMENT_HASH_MISMATCH");
        status(label.toUpperCase()+"_UPLOAD_DOWNLOAD_HASH_PASSED",size,0);
    }
    private void photo() throws Exception {
        guard(); File file=new File(folder,"familygram-photo.png");
        Bitmap image=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.rgb(20,60,110));
        Paint paint=new Paint();paint.setColor(Color.rgb(35,175,235));canvas.drawRect(32,32,480,400,paint);paint.setColor(Color.WHITE);paint.setTextSize(32);canvas.drawText("FamilyGram test",40,460,paint);
        try(OutputStream out=new FileOutputStream(file)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new Check("SYNTHETIC_IMAGE_FAILED");} image.recycle();
        expectedCaption="FamilyGram synthetic "+folder.getName()+" photo";acknowledged.set(null);
        Method send=null;for(Method m:cls("org.telegram.messenger.SendMessagesHelper").getMethods())if(m.getName().equals("prepareSendingPhoto")&&m.getParameterCount()==17)send=m;
        if(send==null)throw new Check("UPSTREAM_PHOTO_API_MISMATCH");final Method invoke=send;
        ui(()->{guard();invoke.invoke(null,instance,file.getAbsolutePath(),null,self,null,null,null,expectedCaption,null,null,null,0,null,false,0,0,null);});
        Object photo=field(waitAck("photo",120000),"photo");if(photo==null)throw new Check("SYNTHETIC_PHOTO_MISSING");
        if(((byte[])field(photo,"file_reference")).length==0)throw new Check("SENT_PHOTO_REFERENCE_WAS_NOT_UPDATED");
        Object largest=null;int best=0;for(Object size:(Iterable<?>)field(photo,"sizes")){String type=String.valueOf(field(size,"type"));if(type.equals("i")||type.equals("j"))continue;int bytes=((Number)field(size,"size")).intValue();if(bytes>best){best=bytes;largest=size;}}
        if(largest==null||best<1||best>4*1024*1024)throw new Check("SYNTHETIC_PHOTO_SIZE_INVALID");
        Object location=cls("org.telegram.tgnet.TLRPC$TL_inputPhotoFileLocation").getConstructor().newInstance();copy(photo,location,"id","access_hash","file_reference");set(location,"thumb_size",field(largest,"type"));
        ByteArrayOutputStream downloaded=new ByteArrayOutputStream();download(location,((Number)field(photo,"dc_id")).intValue(),best,downloaded);
        byte[] bytes=downloaded.toByteArray();Bitmap result=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
        if(result==null||result.getWidth()!=512||result.getHeight()!=512)throw new Check("SYNTHETIC_PHOTO_DECODE_MISMATCH");result.recycle();
        status("PHOTO_UPLOAD_FRESH_DOWNLOAD_DECODE_PASSED",best,0);
    }
    private Object waitAck(String label,long timeout) throws Exception {
        long until=SystemClock.elapsedRealtime()+timeout,last=0;
        while(acknowledged.get()==null&&SystemClock.elapsedRealtime()<until){
            guard();if(observerError.get()!=null)throw new Check(observerError.get());
            if(SystemClock.elapsedRealtime()-last>15000){status("WAITING_"+label.toUpperCase()+"_SERVER_ACK",0,0);last=SystemClock.elapsedRealtime();}
            SystemClock.sleep(200);
        }
        Object result=acknowledged.get();if(result==null)throw new Check("SYNTHETIC_"+label.toUpperCase()+"_ACK_TIMEOUT");return result;
    }
    private Object refreshSyntheticMedia() throws Exception {
        guard();int ownMessage=acknowledgedId.get();if(ownMessage<=0)throw new Check("SYNTHETIC_MESSAGE_ID_MISSING");
        Object request=cls("org.telegram.tgnet.TLRPC$TL_messages_getMessages").getConstructor().newInstance();
        @SuppressWarnings("unchecked") ArrayList<Integer> ids=(ArrayList<Integer>)field(request,"id");ids.add(ownMessage);
        Object manager=instance.getClass().getMethod("getConnectionsManager").invoke(instance);Class<?> delegate=cls("org.telegram.tgnet.RequestDelegate");
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Object> media=new AtomicReference<>();
        Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{delegate},(proxy,method,args)->{if(method.getName().equals("run"))try{
            if(args[1]==null)for(Object message:(Iterable<?>)field(args[0],"messages")){
                if(((Number)field(message,"id")).intValue()!=ownMessage)continue;
                Object peer=field(message,"peer_id");
                if(((Number)field(peer,"user_id")).longValue()==self && expectedCaption.equals(field(message,"message")))media.set(field(message,"media"));
            }
        }finally{done.countDown();}return null;});
        int token=(Integer)manager.getClass().getMethod("sendRequest",cls("org.telegram.tgnet.TLObject"),delegate).invoke(manager,request,callback);
        if(!done.await(30,TimeUnit.SECONDS)){manager.getClass().getMethod("cancelRequest",int.class,boolean.class).invoke(manager,token,true);throw new Check("SYNTHETIC_REFRESH_TIMEOUT");}
        if(media.get()==null)throw new Check("SYNTHETIC_REFRESH_FAILED");return media.get();
    }
    private void owner() throws Exception {
        guard();Class<?> access=cls("org.telegram.messenger.family.FamilyAccess");
        Method proof=access.getDeclaredMethod("proofRequest",String.class,String.class,org.json.JSONObject.class);proof.setAccessible(true);
        org.json.JSONObject result=(org.json.JSONObject)proof.invoke(null,"https://24projects.ru:8443","manage",new org.json.JSONObject().put("command","list"));
        if(!result.getBoolean("phone_owner"))throw new Check("PHONE_NOT_OWNER");
        org.json.JSONArray devices=result.getJSONObject("management").getJSONArray("devices");int owners=0;
        for(int n=0;n<devices.length();n++)if(devices.getJSONObject(n).optBoolean("phone_owner"))owners++;
        if(owners!=1)throw new Check("OWNER_COUNT_INVALID");status("SIGNED_OWNER_LIST_SELF_ONLY_PASSED",0,0);
    }
    private void ghost()throws Exception{
        guard();Class<?> config=cls("tw.nekomimi.nekogram.NekoConfig"),utils=cls("com.radolyn.ayugram.utils.AyuGhostUtils"),state=cls("com.radolyn.ayugram.utils.AyuState");
        String[] keys={"sendReadMessagePackets","sendUploadProgress","sendOnlinePackets"};Object[] items=new Object[3];boolean[] original=new boolean[3];
        Field gate=state.getDeclaredField("allowReadPacket");gate.setAccessible(true);Object variable=gate.get(null);boolean oldGate=(Boolean)field(variable,"val");int oldReset=(Integer)field(variable,"resetAfter");
        try{
            for(int n=0;n<3;n++){items[n]=config.getField(keys[n]).get(null);original[n]=(Boolean)items[n].getClass().getMethod("Bool").invoke(items[n]);items[n].getClass().getMethod("setConfigBool",boolean.class).invoke(items[n],false);}
            state.getMethod("setAllowReadPacket",boolean.class,int.class).invoke(null,false,-1);
            Object peer=cls("org.telegram.tgnet.TLRPC$TL_inputPeerSelf").getConstructor().newInstance();
            Method intercept=utils.getMethod("interceptRequest",cls("org.telegram.tgnet.TLObject"),cls("org.telegram.tgnet.RequestDelegate"));
            Object read=cls("org.telegram.tgnet.TLRPC$TL_messages_readHistory").getConstructor().newInstance();set(read,"peer",peer);set(read,"max_id",1);
            Object typing=cls("org.telegram.tgnet.TLRPC$TL_messages_setTyping").getConstructor().newInstance();set(typing,"peer",peer);set(typing,"action",cls("org.telegram.tgnet.TLRPC$TL_sendMessageTypingAction").getConstructor().newInstance());
            Object readResult=intercept.invoke(null,read,null),typingResult=intercept.invoke(null,typing,null);
            if(!(Boolean)readResult.getClass().getMethod("blockRequest").invoke(readResult)||!(Boolean)typingResult.getClass().getMethod("blockRequest").invoke(typingResult))throw new Check("GHOST_READ_OR_TYPING_NOT_BLOCKED");
            Object online=cls("org.telegram.tgnet.tl.TL_account$updateStatus").getConstructor().newInstance();set(online,"offline",false);intercept.invoke(null,online,null);if(!(Boolean)field(online,"offline"))throw new Check("GHOST_ONLINE_NOT_FORCED_OFFLINE");
            for(Object item:items)item.getClass().getMethod("setConfigBool",boolean.class).invoke(item,true);
            Object allowed=intercept.invoke(null,typing,null);if((Boolean)allowed.getClass().getMethod("blockRequest").invoke(allowed))throw new Check("GHOST_DISABLED_STILL_BLOCKS_TYPING");
            status("GHOST_DRY_RUN_READ_TYPING_OFFLINE_AND_DISABLE_PASSED",0,0);
        }finally{
            for(int n=0;n<3;n++)if(items[n]!=null)items[n].getClass().getMethod("setConfigBool",boolean.class).invoke(items[n],original[n]);set(variable,"val",oldGate);set(variable,"resetAfter",oldReset);
        }
    }
    private Object rpc(Object request)throws Exception{
        guard();Object manager=instance.getClass().getMethod("getConnectionsManager").invoke(instance);Class<?> delegate=cls("org.telegram.tgnet.RequestDelegate");CountDownLatch done=new CountDownLatch(1);AtomicReference<Object> response=new AtomicReference<>();
        Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{delegate},(p,m,args)->{if(m.getName().equals("run")){if(args[1]==null)response.set(args[0]);done.countDown();}return null;});
        int token=(Integer)manager.getClass().getMethod("sendRequest",cls("org.telegram.tgnet.TLObject"),delegate).invoke(manager,request,callback);
        if(!done.await(30,TimeUnit.SECONDS)){manager.getClass().getMethod("cancelRequest",int.class,boolean.class).invoke(manager,token,true);throw new Check("SELF_TEST_RPC_TIMEOUT");}
        if(response.get()==null)throw new Check("SELF_TEST_RPC_FAILED");return response.get();
    }
    private static Object hidden(Object object,String name)throws Exception{Class<?> type=object.getClass();while(type!=null){try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(NoSuchFieldException ignored){type=type.getSuperclass();}}throw new NoSuchFieldException();}
    private static void setHidden(Object object,String name,Object value)throws Exception{Class<?> type=object.getClass();while(type!=null){try{Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(object,value);return;}catch(NoSuchFieldException ignored){type=type.getSuperclass();}}throw new NoSuchFieldException();}
    private Activity waitActivity(String suffix)throws Exception{long deadline=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<deadline){Activity a=current;if(a!=null&&a.getClass().getName().equals("org.telegram.messenger.family."+suffix)&&!a.isFinishing())return a;SystemClock.sleep(100);}throw new Check("TOOL_ACTIVITY_NOT_READY_"+suffix);}
    private void screenshot(Activity activity,String name)throws Exception{
        ui(()->{android.view.View view=activity.getWindow().getDecorView();if(view.getWidth()<1||view.getHeight()<1)throw new Check("TOOL_LAYOUT_EMPTY");Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(OutputStream out=new FileOutputStream(new File(folder,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();});
    }
    private String runPython(Activity activity,String source,String input)throws Exception{
        ui(()->{((android.widget.EditText)hidden(activity,"code")).setText(source);((android.widget.EditText)hidden(activity,"input")).setText(input);((android.widget.Button)hidden(activity,"run")).performClick();});
        long deadline=SystemClock.elapsedRealtime()+35000;while(SystemClock.elapsedRealtime()<deadline){if(!(Boolean)hidden(activity,"running")){String value=((android.widget.TextView)hidden(activity,"output")).getText().toString();if(!value.isEmpty())return value;}SystemClock.sleep(100);}throw new Check("PYTHON_RESULT_TIMEOUT");
    }
    private void tools()throws Exception{
        guard();Class<?> tools=cls("org.telegram.messenger.family.FamilyChatTools");Method enabled=tools.getMethod("enabled",android.content.Context.class,String.class),toggle=tools.getMethod("setEnabled",android.content.Context.class,String.class,boolean.class);
        boolean python=(Boolean)enabled.invoke(null,getTargetContext(),"python"),documents=(Boolean)enabled.invoke(null,getTargetContext(),"documents");
        status(python||documents?"TOOLS_EXISTING_USER_CHOICE_PRESERVED":"BOTH_BUNDLED_TOOLS_DISABLED_BY_DEFAULT",0,0);
        try{
            toggle.invoke(null,getTargetContext(),"python",true);toggle.invoke(null,getTargetContext(),"documents",true);
            String snippet="print('SAA_QA_CODE_42')";expectedCaption=snippet;acknowledgedMessage.set(null);acknowledgedId.set(0);
            Class<?> paramsType=cls("org.telegram.messenger.SendMessagesHelper$SendMessageParams");Object params=paramsType.getMethod("of",String.class,long.class).invoke(null,snippet,self);set(params,"notify",false);
            Object entity=cls("org.telegram.tgnet.TLRPC$TL_messageEntityPre").getConstructor().newInstance();set(entity,"offset",0);set(entity,"length",snippet.length());set(entity,"language","python");ArrayList<Object> entities=new ArrayList<>();entities.add(entity);set(params,"entities",entities);
            Object sender=instance.getClass().getMethod("getSendMessagesHelper").invoke(instance);ui(()->{guard();sender.getClass().getMethod("sendMessage",paramsType).invoke(sender,params);});
            long ackDeadline=SystemClock.elapsedRealtime()+30000;while(acknowledgedMessage.get()==null&&SystemClock.elapsedRealtime()<ackDeadline)SystemClock.sleep(100);if(acknowledgedMessage.get()==null)throw new Check("PYTHON_SELF_CODE_MESSAGE_ACK_TIMEOUT");
            Object message=cls("org.telegram.messenger.MessageObject").getConstructor(int.class,cls("org.telegram.tgnet.TLRPC$Message"),boolean.class,boolean.class).newInstance(account,acknowledgedMessage.get(),false,false);
            ui(()->{Object previous=hidden(selfChat,"selectedObject");try{setHidden(selfChat,"selectedObject",message);ArrayList<Integer> icons=new ArrayList<>(),options=new ArrayList<>();ArrayList<CharSequence> labels=new ArrayList<>();selfChat.getClass().getMethod("fillMessageMenu",cls("org.telegram.messenger.MessageObject"),ArrayList.class,ArrayList.class,ArrayList.class).invoke(selfChat,message,icons,labels,options);int option=tools.getField("RUN_PYTHON").getInt(null);if(!options.contains(option))throw new Check("PYTHON_CONTEXT_MENU_ACTION_MISSING");selfChat.getClass().getMethod("processSelectedOption",int.class).invoke(selfChat,option);}finally{setHidden(selfChat,"selectedObject",previous);}});
            status("REAL_SAVED_CODE_MESSAGE_CONTEXT_MENU_ACTION_PASSED",0,0);
            Activity activity=waitActivity("FamilyPythonActivity");long until=SystemClock.elapsedRealtime()+20000;while(!(Boolean)hidden(activity,"ready")&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
            if(!(Boolean)hidden(activity,"ready"))throw new Check("PYTHON_WEBVIEW_NOT_READY");
            if(!((android.widget.EditText)hidden(activity,"code")).getText().toString().equals(snippet))throw new Check("PYTHON_CODE_BLOCK_EXTRACTION_MISMATCH");
            String result=runPython(activity,"print(sum(range(10)))\nprint(input()[::-1])","SAA");if(!result.trim().equals("45\nAAS"))throw new Check("PYTHON_CALCULATION_OR_STDIN_FAILED");status("PYTHON_LOCAL_COMPUTATION_STDIN_PASSED",0,0);screenshot(activity,"python-result.png");
            result=runPython(activity,"import js, os\nprint(hasattr(js, 'fetch'))\nprint(os.path.exists('/data/user/0/com.salatik.familygram/files'))","");if(!result.trim().equals("False\nFalse"))throw new Check("PYTHON_VIRTUAL_FILES_OR_JS_BOUNDARY_FAILED");status("PYTHON_NO_JS_FETCH_OR_APP_PRIVATE_FILES_PASSED",0,0);
            ui(()->((android.webkit.WebView)hidden(activity,"web")).evaluateJavascript("fetch('https://24projects.ru:8443/health').then(()=>console.log('SAA_RUNNER:'+JSON.stringify({type:'result',text:'NETWORK_ALLOWED'}))).catch(()=>console.log('SAA_RUNNER:'+JSON.stringify({type:'result',text:'NETWORK_BLOCKED'})))",null));
            until=SystemClock.elapsedRealtime()+5000;while(!((android.widget.TextView)hidden(activity,"output")).getText().toString().equals("NETWORK_BLOCKED")&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);if(!((android.widget.TextView)hidden(activity,"output")).getText().toString().equals("NETWORK_BLOCKED"))throw new Check("PYTHON_BROWSER_NETWORK_BOUNDARY_FAILED");status("PYTHON_BROWSER_NETWORK_REQUEST_BLOCKED",0,0);
            result=runPython(activity,"print(","");if(!result.contains("SyntaxError"))throw new Check("PYTHON_SYNTAX_ERROR_NOT_REPORTED");
            result=runPython(activity,"while True: pass","");if(!result.contains("лимит")&&!result.contains("превышен"))throw new Check("PYTHON_TIME_LIMIT_FAILED");status("PYTHON_SYNTAX_AND_INFINITE_LOOP_TIMEOUT_PASSED",0,0);
            ui(activity::finish);SystemClock.sleep(300);
            for(String extension:new String[]{"pdf","docx","doc","odt","rtf","txt"}){
                File file=new File(folder,"fixture."+extension);try(InputStream in=getContext().getAssets().open(file.getName());OutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
                ui(()->{boolean opened=(Boolean)cls("org.telegram.messenger.AndroidUtilities").getMethod("openForView",File.class,String.class,String.class,Activity.class,cls("org.telegram.ui.ActionBar.Theme$ResourcesProvider"),boolean.class).invoke(null,file,file.getName(),"application/octet-stream",launch,null,false);if(!opened)throw new Check("UPSTREAM_DOCUMENT_HOOK_FAILED");});
                Activity viewer=waitActivity("FamilyDocumentActivity");until=SystemClock.elapsedRealtime()+15000;
                if(extension.equals("pdf")){
                    while(((Integer)hidden(viewer,"count"))<1&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
                    if((Integer)hidden(viewer,"count")!=2||hidden(viewer,"shown")==null)throw new Check("PDF_ISOLATED_RENDER_FAILED");
                    screenshot(viewer,"pdf-page1.png");ui(()->((android.widget.Button)hidden(viewer,"next")).performClick());SystemClock.sleep(200);until=SystemClock.elapsedRealtime()+10000;while((Boolean)hidden(viewer,"busy")&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);if((Integer)hidden(viewer,"page")!=1)throw new Check("PDF_PAGINATION_FAILED");SystemClock.sleep(2000);
                }else{
                    String marker=extension.equals("doc")?"SAA_QA_DOC_BINARY":extension.equals("txt")?"SAA_QA_TEXT":"SAA_QA_"+extension.toUpperCase();String value="";
                    while(SystemClock.elapsedRealtime()<until){value=((android.widget.TextView)hidden(viewer,"text")).getText().toString();if(value.contains(marker))break;SystemClock.sleep(100);}
                    if(!value.contains(marker))throw new Check("LOCAL_DOCUMENT_PARSE_FAILED_"+extension.toUpperCase());if(extension.equals("docx")&&!value.contains("& < >"))throw new Check("DOCX_XML_ENTITY_TEXT_FAILED");screenshot(viewer,"document-"+extension+".png");
                }
                status("INTERNAL_"+extension.toUpperCase()+"_VIEWER_PASSED",0,0);ui(viewer::finish);SystemClock.sleep(200);
            }
        }finally{toggle.invoke(null,getTargetContext(),"python",python);toggle.invoke(null,getTargetContext(),"documents",documents);if(!getTargetContext().getSharedPreferences("saa_builtin_plugins_v1",0).edit().putBoolean("python",python).putBoolean("documents",documents).commit())throw new Check("QA_PREFERENCE_RESTORE_FAILED");status("TOOLS_ORIGINAL_ENABLED_STATES_RESTORED",0,0);}
    }
    private org.json.JSONObject ownerAction(String command,String id)throws Exception{
        Class<?> access=cls("org.telegram.messenger.family.FamilyAccess");Method proof=access.getDeclaredMethod("proofRequest",String.class,String.class,org.json.JSONObject.class);proof.setAccessible(true);
        org.json.JSONObject payload=new org.json.JSONObject().put("command",command);if(command.equals("invite"))payload.put("hours",1);else if(!command.equals("list"))payload.put("device_id",id);
        return ((org.json.JSONObject)proof.invoke(null,"https://24projects.ru:8443","manage",payload)).getJSONObject("management");
    }
    private org.json.JSONObject temporaryProof(java.security.KeyPair keys,String action,org.json.JSONObject payload)throws Exception{
        Class<?> access=cls("org.telegram.messenger.family.FamilyAccess");Method post=access.getDeclaredMethod("post",String.class,String.class,org.json.JSONObject.class);post.setAccessible(true);
        byte[] publicKey=keys.getPublic().getEncoded();String publicText=android.util.Base64.encodeToString(publicKey,android.util.Base64.NO_WRAP);
        org.json.JSONObject challenge=(org.json.JSONObject)post.invoke(null,"https://24projects.ru:8443","/v1/challenge",new org.json.JSONObject().put("public_key",publicText).put("action",action).put("payload",payload));
        java.security.Signature signer=java.security.Signature.getInstance("SHA256withECDSA");signer.initSign(keys.getPrivate());signer.update(challenge.getString("message").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (org.json.JSONObject)post.invoke(null,"https://24projects.ru:8443","/v1/complete",new org.json.JSONObject().put("challenge_id",challenge.getString("challenge_id")).put("signature",android.util.Base64.encodeToString(signer.sign(),android.util.Base64.NO_WRAP)));
    }
    private void ownerDenied(java.security.KeyPair keys)throws Exception{
        try{temporaryProof(keys,"manage",new org.json.JSONObject().put("command","list"));throw new Check("OTHER_INSTALLATION_GOT_OWNER_ACCESS");}
        catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause==null||!"owner_required".equals(hidden(cause,"code")))throw failure;}
    }
    private void ownerCycle()throws Exception{
        guard();java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));java.security.KeyPair keys=generator.generateKeyPair();
        org.json.JSONObject invite=ownerAction("invite",null);String id=null;boolean revoked=false;
        try{
            org.json.JSONObject enrolled=temporaryProof(keys,"invite",new org.json.JSONObject().put("code",invite.getString("code")).put("name","QA temporary device"));id=enrolled.getString("device_id");
            if(!id.matches("[a-f0-9]{64}")||!enrolled.getString("status").equals("pending"))throw new Check("TEMP_DEVICE_NOT_PENDING");ownerDenied(keys);status("OWNER_INVITATION_AND_PENDING_NONOWNER_DENIAL_PASSED",0,0);
            org.json.JSONObject approved=ownerAction("approve",id);if(!approved.getString("status").equals("approved"))throw new Check("OWNER_APPROVAL_FAILED");
            org.json.JSONObject config=temporaryProof(keys,"config",new org.json.JSONObject());if(config.optBoolean("phone_owner")||!config.has("gateway_fallbacks"))throw new Check("APPROVED_OTHER_DEVICE_OWNER_OR_ROUTE_INVALID");ownerDenied(keys);status("OWNER_APPROVAL_AND_APPROVED_NONOWNER_DENIAL_PASSED",0,0);
            org.json.JSONObject removed=ownerAction("revoke",id);if(!removed.getString("status").equals("revoked"))throw new Check("OWNER_REVOCATION_FAILED");revoked=true;
            try{temporaryProof(keys,"config",new org.json.JSONObject());throw new Check("REVOKED_DEVICE_CAN_GET_CONFIG");}catch(InvocationTargetException failure){if(!"revoked".equals(hidden(failure.getCause(),"code")))throw failure;}
            status("OWNER_REVOCATION_AND_REVOKED_CONFIG_DENIAL_PASSED",0,0);
        }finally{if(id!=null&&!revoked)ownerAction("revoke",id);}
    }
    private void finalizeSettings()throws Exception{
        guard();if(!getTargetContext().getPackageManager().getApplicationLabel(getTargetContext().getApplicationInfo()).toString().equals("TG-ru"))throw new Check("APPLICATION_LABEL_MISMATCH");
        SystemClock.sleep(1200); // Let the preceding Saved Messages navigation animation finish.
        AtomicReference<Object> dialogs=new AtomicReference<>();
        ui(()->{Object fragment=cls("org.telegram.ui.DialogsActivity").getConstructor(Bundle.class).newInstance(new Bundle());dialogs.set(fragment);launch.getClass().getMethod("presentFragment",cls("org.telegram.ui.ActionBar.BaseFragment")).invoke(launch,fragment);});
        SystemClock.sleep(1000);
        ui(()->{Object bar=dialogs.get().getClass().getMethod("getActionBar").invoke(dialogs.get());
            if(!"S.A.A.".equals(bar.getClass().getMethod("getTitle").invoke(bar)))throw new Check("ACTUAL_MAIN_CHAT_HEADER_MISMATCH");
            android.view.View title=(android.view.View)bar.getClass().getMethod("getTitleTextView").invoke(bar);
            if(!title.isShown())throw new Check("ACTUAL_MAIN_CHAT_HEADER_HIDDEN");
            // Only the actual header; no chat list, names or message previews are captured.
            saveView((android.view.View)bar,"chat-header-only.png");
            Method menu=dialogs.get().getClass().getDeclaredMethod("showItemOptions");menu.setAccessible(true);menu.invoke(dialogs.get());
        });
        status("TG_RU_LABEL_AND_VISIBLE_SAA_HEADER_PASSED",0,0);
        boolean clicked=false;long menuDeadline=SystemClock.elapsedRealtime()+5000;
        while(!clicked&&SystemClock.elapsedRealtime()<menuDeadline){
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("Настройки призрака")){
                if(node.getText()==null||!node.getText().toString().equals("Настройки призрака"))continue;
                for(int n=0;n<4&&node!=null;n++,node=node.getParent())if(node.isClickable()){clicked=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);break;}
                if(clicked)break;
            }
            if(!clicked)SystemClock.sleep(100);
        }
        if(!clicked)throw new Check("ACTUAL_OVERFLOW_GHOST_SETTINGS_NOT_CLICKABLE");
        Activity privacy=waitActivity("FamilyPrivacyActivity");SystemClock.sleep(300);
        int[] privacySwitches=new int[2];ui(()->countSwitches(privacy.getWindow().getDecorView(),privacySwitches));
        if(privacySwitches[0]!=7)throw new Check("UNIFIED_PRIVACY_CONTROLS_INCOMPLETE");
        Class<?> privacyType=cls("org.telegram.messenger.family.FamilyPrivacyActivity");
        Map<?,?> controls=(Map<?,?>)hidden(privacy,"controls");
        String[] flags={"sendReadMessagePackets","sendUploadProgress"};
        for(String flag:flags){Boolean before=(Boolean)privacyType.getMethod("flag",String.class).invoke(null,flag);
            try{ui(()->((android.widget.Switch)controls.get(flag)).performClick());Boolean after=(Boolean)privacyType.getMethod("flag",String.class).invoke(null,flag);
                if(before==null||before.equals(after))throw new Check("PRIVACY_SWITCH_NOT_BOUND_TO_PACKET_FILTER");}
            finally{ui(()->{privacyType.getMethod("setFlag",String.class,boolean.class).invoke(null,flag,before);Method update=privacyType.getDeclaredMethod("update");update.setAccessible(true);update.invoke(privacy);});}
        }
        screenshot(privacy,"ghost-settings.png");ui(privacy::finish);
        status("ACTUAL_MAIN_OVERFLOW_PRIVACY_AND_LIVE_FILTER_SWITCHES_PASSED",0,0);
        AtomicReference<Object> settingsFragment=new AtomicReference<>();
        ui(()->{Object fragment=cls("org.telegram.ui.SettingsActivity").getConstructor().newInstance();settingsFragment.set(fragment);launch.getClass().getMethod("presentFragment",cls("org.telegram.ui.ActionBar.BaseFragment")).invoke(launch,fragment);});
        SystemClock.sleep(700);
        ui(()->{
            Object list=hidden(settingsFragment.get(),"listView");Object adapter=field(list,"adapter");ArrayList<?> items=(ArrayList<?>)hidden(adapter,"items");
            HashSet<Integer> required=new HashSet<>(Arrays.asList(-44001,-44002,-44003,-44004));int lastOwn=-1,lastStock=-1;Object privacyRow=null;
            for(int n=0;n<items.size();n++){Object item=items.get(n);int id=((Number)field(item,"id")).intValue();
                required.remove(id);if(id==-1||id==100)throw new Check("OPTIONAL_ADVANCED_SETTINGS_STILL_AT_TOP");
                if(id==-44001)lastOwn=n;if(id==-44002)privacyRow=item;if(id>0)lastStock=n;
            }
            if(!required.isEmpty()||lastOwn<=lastStock)throw new Check("MAIN_SETTINGS_OWNER_ROWS_OR_SYSTEM_POSITION_INVALID");
            if(!Boolean.TRUE.equals(cls("org.telegram.messenger.family.FamilyBinarySettings").getMethod("onClick",Object.class,Object.class).invoke(null,settingsFragment.get(),privacyRow)))throw new Check("MAIN_PRIVACY_SETTINGS_ACTION_FAILED");
        });
        Activity fromSettings=waitActivity("FamilyPrivacyActivity");ui(fromSettings::finish);
        status("STANDARD_SETTINGS_GHOST_PLUGINS_OWNER_AND_SYSTEM_LAST_PASSED",0,0);
        if(!getTargetContext().getSharedPreferences("saa_builtin_plugins_v1",0).edit().putBoolean("python",false).putBoolean("documents",false).commit())throw new Check("QA_DEFAULT_STATE_COMMIT_FAILED");
        ui(()->getTargetContext().startActivity(new Intent().setClassName("com.salatik.familygram","org.telegram.messenger.family.FamilyPluginsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        Activity plugins=waitActivity("FamilyPluginsActivity");SystemClock.sleep(300);screenshot(plugins,"plugins-disabled.png");
        int[] switches=new int[2];ui(()->countSwitches(plugins.getWindow().getDecorView(),switches));if(switches[0]!=2||switches[1]!=0)throw new Check("BUNDLED_TOOLS_NOT_BOTH_DISABLED_IN_UI");ui(plugins::finish);
        ui(()->getTargetContext().startActivity(new Intent().setClassName("com.salatik.familygram","org.telegram.messenger.family.FamilySettingsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        Activity settings=waitActivity("FamilySettingsActivity");SystemClock.sleep(300);screenshot(settings,"system-settings.png");ui(settings::finish);
        ui(()->getTargetContext().startActivity(new Intent().setClassName("com.salatik.familygram","org.telegram.messenger.family.FamilyDevicesActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        Activity devices=waitActivity("FamilyDevicesActivity");long deadline=SystemClock.elapsedRealtime()+15000;while((Boolean)hidden(devices,"busy")&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
        if((Boolean)hidden(devices,"busy")||((android.widget.LinearLayout)hidden(devices,"list")).getChildCount()<2)throw new Check("OWNER_DEVICES_UI_NOT_LOADED");
        status("BOUND_OWNER_UI_LOADED_AND_BOTH_PLUGINS_DISABLED_PASSED",0,0);
    }
    private void saveView(android.view.View view,String name)throws Exception{
        if(view.getWidth()<1||view.getHeight()<1)throw new Check("SYNTHETIC_VIEW_LAYOUT_EMPTY");
        Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.rgb(24,24,24));view.draw(new Canvas(image));
        try(OutputStream out=new FileOutputStream(new File(folder,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
    }
    private void countSwitches(android.view.View view,int[] result){if(view instanceof android.widget.Switch){result[0]++;if(((android.widget.Switch)view).isChecked())result[1]++;}if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)countSwitches(group.getChildAt(i),result);}}
    @SuppressWarnings("unchecked")
    private void deleted()throws Exception{
        guard();if(!(Boolean)cls("org.telegram.messenger.family.FamilyFeatures").getMethod("saveDeletedEnabled").invoke(null))throw new Check("DELETED_HISTORY_DISABLED");
        expectedCaption="FamilyGram synthetic deletion "+folder.getName();acknowledgedMessage.set(null);acknowledgedId.set(0);
        Class<?> paramsType=cls("org.telegram.messenger.SendMessagesHelper$SendMessageParams");Object params=paramsType.getMethod("of",String.class,long.class).invoke(null,expectedCaption,self);set(params,"notify",false);
        Object sender=instance.getClass().getMethod("getSendMessagesHelper").invoke(instance);
        ui(()->{guard();sender.getClass().getMethod("sendMessage",paramsType).invoke(sender,params);});
        long deadline=SystemClock.elapsedRealtime()+30000;while(acknowledgedMessage.get()==null&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
        int id=acknowledgedId.get();if(acknowledgedMessage.get()==null||id<=0)throw new Check("SYNTHETIC_TEXT_ACK_TIMEOUT");
        Object helper=cls("tw.nekomimi.nekogram.helpers.MessageHelper").getMethod("getInstance",int.class).invoke(null,account);Object local=null;
        for(int n=0;n<30&&local==null;n++){local=helper.getClass().getMethod("getMessage",long.class,long.class).invoke(helper,self,(long)id);if(local==null)SystemClock.sleep(100);}
        if(local==null||!expectedCaption.equals(field(local,"message")))throw new Check("SYNTHETIC_LOCAL_MESSAGE_MISSING");
        Object request=cls("org.telegram.tgnet.TLRPC$TL_messages_deleteMessages").getConstructor().newInstance();((ArrayList<Integer>)field(request,"id")).add(id);set(request,"revoke",true);
        // The only id submitted was just acknowledged for this installation's self dialog.
        Object response=rpc(request);
        Object update=cls("org.telegram.tgnet.tl.TL_update$TL_updateDeleteMessages").getConstructor().newInstance();((ArrayList<Integer>)field(update,"messages")).add(id);set(update,"pts",field(response,"pts"));set(update,"pts_count",field(response,"pts_count"));
        ArrayList<Object> updates=new ArrayList<>();updates.add(update);Object controller=instance.getClass().getMethod("getMessagesController").invoke(instance),queue=cls("org.telegram.messenger.Utilities").getField("stageQueue").get(null);
        CountDownLatch processed=new CountDownLatch(1);AtomicBoolean failed=new AtomicBoolean();
        queue.getClass().getMethod("postRunnable",Runnable.class).invoke(queue,(Runnable)()->{try{controller.getClass().getMethod("processUpdateArray",ArrayList.class,ArrayList.class,ArrayList.class,boolean.class,int.class).invoke(controller,updates,new ArrayList<>(),new ArrayList<>(),false,(int)(System.currentTimeMillis()/1000));controller.getClass().getMethod("processNewDifferenceParams",int.class,int.class,int.class,int.class).invoke(controller,-1,field(response,"pts"),-1,field(response,"pts_count"));}catch(Exception failure){failed.set(true);}finally{processed.countDown();}});
        if(!processed.await(10,TimeUnit.SECONDS)||failed.get())throw new Check("SYNTHETIC_DELETE_UPDATE_FAILED");
        Object history=cls("com.radolyn.ayugram.messages.AyuMessagesController").getMethod("getInstance").invoke(null);Object stored=null;
        deadline=SystemClock.elapsedRealtime()+15000;while(stored==null&&SystemClock.elapsedRealtime()<deadline){stored=history.getClass().getMethod("getMessage",long.class,long.class,int.class).invoke(history,self,self,id);if(stored==null)SystemClock.sleep(100);}
        if(stored==null||!expectedCaption.equals(field(field(stored,"message"),"text")))throw new Check("SYNTHETIC_DELETED_HISTORY_MISSING");
        status("REAL_SELF_DELETE_AND_INBOUND_UPDATE_LOCAL_HISTORY_PASSED",0,0);
        Object historyScreen=cls("com.radolyn.ayugram.ui.AyuViewDeleted").getConstructor(long.class).newInstance(self);
        ui(()->launch.getClass().getMethod("presentFragment",cls("org.telegram.ui.ActionBar.BaseFragment")).invoke(launch,historyScreen));
        AtomicBoolean visible=new AtomicBoolean();deadline=SystemClock.elapsedRealtime()+10000;
        while(!visible.get()&&SystemClock.elapsedRealtime()<deadline){ui(()->{for(Object row:(Iterable<?>)hidden(historyScreen,"deletedMessages"))if(((Number)field(field(row,"message"),"messageId")).intValue()==id)visible.set(true);});if(!visible.get())SystemClock.sleep(100);}
        if(!visible.get())throw new Check("SELF_DELETED_MESSAGE_NOT_IN_HISTORY_UI");status("SELF_DELETED_MESSAGE_VISIBLE_IN_ACTUAL_HISTORY_UI",0,0);
    }
    private byte[] download(Object location,int dc,int length,OutputStream output) throws Exception {
        guard();if(dc<1||dc>10)throw new Check("SYNTHETIC_DC_INVALID");
        Object manager=instance.getClass().getMethod("getConnectionsManager").invoke(instance);
        Class<?> delegate=cls("org.telegram.tgnet.RequestDelegate");
        Method send=manager.getClass().getMethod("sendRequest",cls("org.telegram.tgnet.TLObject"),delegate,cls("org.telegram.tgnet.QuickAckDelegate"),cls("org.telegram.tgnet.WriteToSocketDelegate"),int.class,int.class,int.class,boolean.class);
        MessageDigest hash=MessageDigest.getInstance("SHA-256");int offset=0;
        while(offset<length){guard();Object request=cls("org.telegram.tgnet.TLRPC$TL_upload_getFile").getConstructor().newInstance();set(request,"location",location);set(request,"offset",(long)offset);set(request,"limit",512*1024);set(request,"precise",true);set(request,"cdn_supported",false);
            CountDownLatch done=new CountDownLatch(1);AtomicReference<byte[]> payload=new AtomicReference<>();AtomicReference<String> failure=new AtomicReference<>();
            Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{delegate},(proxy,method,args)->{if(method.getName().equals("run"))try{if(args[1]!=null){failure.set("SYNTHETIC_DOWNLOAD_RPC_ERROR");}else if(!args[0].getClass().getSimpleName().equals("TL_upload_file")){failure.set("SYNTHETIC_DOWNLOAD_UNEXPECTED_RESPONSE");}else{Object buffer=field(args[0],"bytes");int n=(Integer)buffer.getClass().getMethod("remaining").invoke(buffer);if(n<0||n>512*1024)throw new Check("SYNTHETIC_CHUNK_INVALID");byte[] bytes=new byte[n];buffer.getClass().getMethod("readBytes",byte[].class,boolean.class).invoke(buffer,bytes,true);payload.set(bytes);}}catch(Throwable e){failure.set("SYNTHETIC_DOWNLOAD_CALLBACK_FAILED");}finally{done.countDown();}return null;});
            int token=(Integer)send.invoke(manager,request,callback,null,null,0,dc,2,true);
            if(!done.await(45,TimeUnit.SECONDS)){manager.getClass().getMethod("cancelRequest",int.class,boolean.class).invoke(manager,token,true);throw new Check("SYNTHETIC_DOWNLOAD_TIMEOUT");}
            if(failure.get()!=null)throw new Check(failure.get());byte[] bytes=payload.get();if(bytes==null||bytes.length==0||offset+bytes.length>length)throw new Check("SYNTHETIC_DOWNLOAD_LENGTH_MISMATCH");hash.update(bytes);if(output!=null)output.write(bytes);offset+=bytes.length;
        }
        status("FRESH_NETWORK_DOWNLOAD_COMPLETE",length,dc);return hash.digest();
    }
    private void status(String state,int bytes,int dc){Bundle event=new Bundle();event.putString("state",state);event.putLong("elapsed_seconds",(SystemClock.elapsedRealtime()-began)/1000);if(bytes>0)event.putInt("synthetic_bytes",bytes);if(dc>0)event.putInt("datacenter",dc);sendStatus(0,event);}
    private interface MainAction {void run() throws Exception;}
    private void ui(MainAction action) throws Exception {
        AtomicReference<Throwable> error=new AtomicReference<>();
        runOnMainSync(()->{try{action.run();}catch(Throwable failure){error.set(failure);}});
        if(error.get()!=null){Throwable cause=error.get();while(cause instanceof InvocationTargetException&&cause.getCause()!=null)cause=cause.getCause();if(cause instanceof Check)throw (Check)cause;throw new Check("SYNTHETIC_UI_ACTION_FAILED_"+cause.getClass().getSimpleName()+"_"+(cause.getStackTrace().length==0?"unknown":cause.getStackTrace()[0].getMethodName()));}
    }
    private Class<?> cls(String name)throws Exception{return Class.forName(name,true,loader);}
    private static Object field(Object owner,String name)throws Exception{return owner.getClass().getField(name).get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{owner.getClass().getField(name).set(owner,value);}
    private static void copy(Object from,Object to,String...names)throws Exception{for(String name:names)set(to,name,field(from,name));}
    private static final class Check extends Exception{Check(String message){super(message);}}
}
