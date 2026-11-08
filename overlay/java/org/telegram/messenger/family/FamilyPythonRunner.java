package org.telegram.messenger.family;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Offline execution surface. Receives strings only, never Telegram objects. */
public final class FamilyPythonRunner {
    public interface Listener { void changed(boolean running, String text); }
    public static final int LIMIT = 65_536;
    private static final String ORIGIN="https://saa.local/runner/";
    private static WeakReference<FamilyPythonRunner> active=new WeakReference<>(null);
    private static final Set<String> ASSETS=new HashSet<>(Arrays.asList("controller.mjs","worker.mjs","runtime/pyodide.mjs","runtime/pyodide.asm.mjs","runtime/pyodide.asm.wasm","runtime/python_stdlib.zip","runtime/pyodide-lock.json"));
    // Same sealed Pyodide worker as the editor. Job IDs belong to each worker
    // closure, so queued console events from a cancelled run cannot finish a new run.
    private static final String PAGE="<!doctype html><meta charset='utf-8'><script>globalThis.SAA_PREFIX='SAA_INLINE:';</script><script type='module' src='/runner/controller.mjs'></script>";
    private final ViewGroup host;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean loaded,running,destroyed,executing;
    private long epoch;
    private String pending;
    private Runnable watchdog;
    public FamilyPythonRunner(ViewGroup host,Listener listener){this.host=host;this.listener=listener;}
    public boolean running(){return running;}
    public void run(String code){
        if(destroyed||running)return;
        if(code==null||code.getBytes(StandardCharsets.UTF_8).length>LIMIT){listener.changed(false,label("fg_python_code_limit","Лимит кода: 64 КБ"));return;}
        FamilyPythonRunner previous=active.get();if(previous!=null&&previous!=this){previous.cancel();previous.destroy();}active=new WeakReference<>(this);
        final long job=++epoch;
        running=true;executing=false;pending=code;
        listener.changed(true,label("fg_python_loading","Загрузка Python…"));
        armWatchdog(job, 35_000);
        try{if(web==null)createWeb();if(loaded)execute(job);}
        catch(RuntimeException failure){removeWeb();finish("Не удалось запустить среду Python: "+failure.getClass().getSimpleName()+". "+String.valueOf(failure.getMessage()));}
    }
    private void armWatchdog(long job,long delay){
        if(watchdog!=null)handler.removeCallbacks(watchdog);
        watchdog=()->{if(running&&job==epoch){boolean wasExecuting=executing;removeWeb();finish(wasExecuting?"Выполнение остановлено: превышен лимит 10 секунд.":"Не удалось запустить среду Python: загрузка не завершилась.");}};
        handler.postDelayed(watchdog,delay);
    }
    private String label(String name,String fallback){Context c=host.getContext();int id=c.getResources().getIdentifier(name,"string",c.getPackageName());return id==0?fallback:c.getString(id);}
    @SuppressLint("SetJavaScriptEnabled") private void createWeb(){
        web=new WebView(host.getContext());
        web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        web.setFocusable(false);web.setAlpha(0f);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setDomStorageEnabled(false);s.setDatabaseEnabled(false);s.setGeolocationEnabled(false);s.setBlockNetworkLoads(true);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        final WebView owner=web;
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
                Uri u=request.getUrl();String path=u.getPath();
                if(!"GET".equals(request.getMethod())||!"https".equals(u.getScheme())||!"saa.local".equals(u.getHost())||u.getPort()!=-1||u.getQuery()!=null||path==null||!path.startsWith("/runner/"))return blocked();
                String file=path.substring(8);
                try{
                    WebResourceResponse response;
                    if(file.equals("inline.html"))response=new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(PAGE.getBytes(StandardCharsets.UTF_8)));
                    else {if(!ASSETS.contains(file))return blocked();String mime=file.endsWith(".wasm")?"application/wasm":file.endsWith(".mjs")?"text/javascript":file.endsWith(".json")?"application/json":"application/octet-stream";response=new WebResourceResponse(mime,"UTF-8",host.getContext().getAssets().open("familygram/runner/"+file));}
                    response.setResponseHeaders(Collections.singletonMap("Content-Security-Policy","default-src 'none'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; connect-src 'self'; worker-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"));return response;
                }catch(IOException ignored){return blocked();}
            }
            @Override public void onPageFinished(WebView v,String url){if(web==owner&&!destroyed&&url.equals(ORIGIN+"inline.html")){loaded=true;if(running)execute(epoch);}}
            @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail detail){if(web==owner){finish(label("fg_python_memory_error","Python остановлен системой."));removeWeb();}return true;}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
            @Override public boolean onConsoleMessage(ConsoleMessage message){
                String raw=message.message();if(web!=owner||destroyed||!running||raw==null||!raw.startsWith("SAA_INLINE:")||raw.length()>400_000)return true;
                try{JSONObject e=new JSONObject(raw.substring(11));if(e.getLong("id")!=epoch)return true;String type=e.optString("type");if(type.equals("result")||type.equals("error"))finish(e.optString("text"));else if(type.equals("running")){executing=true;armWatchdog(epoch,10_000);listener.changed(true,label("fg_python_running","Выполнение…"));}}catch(Exception ignored){}return true;
            }
        });
        host.addView(web,new ViewGroup.LayoutParams(1,1));web.loadUrl(ORIGIN+"inline.html");
    }
    private void execute(long job){if(web==null||!running||pending==null)return;try{String source=pending;pending=null;web.evaluateJavascript("executePython("+new JSONObject().put("id",job).put("code",source)+")",null);}catch(Exception failure){finish("Не удалось передать код в среду Python: "+failure.getClass().getSimpleName()+". "+String.valueOf(failure.getMessage()));}}
    public void cancel(){if(!running)return;finish(label("fg_python_cancelled","Выполнение остановлено."));}
    private void finish(String text){if(watchdog!=null)handler.removeCallbacks(watchdog);watchdog=null;epoch++;running=false;pending=null;if(active.get()==this)active.clear();if(web!=null&&loaded)web.evaluateJavascript("stopRun()",null);listener.changed(false,FamilyPythonSnippets.limitText(text));}
    private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    private void removeWeb(){loaded=false;if(web!=null){WebView old=web;web=null;host.removeView(old);old.stopLoading();old.destroy();}}
    public void destroy(){if(destroyed)return;destroyed=true;epoch++;running=false;pending=null;if(active.get()==this)active.clear();if(watchdog!=null)handler.removeCallbacks(watchdog);watchdog=null;removeWeb();}
}
