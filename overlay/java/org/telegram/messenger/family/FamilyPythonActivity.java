package org.telegram.messenger.family;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.*;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.graphics.Typeface;
import android.net.Uri;
import org.json.JSONObject;
import org.telegram.messenger.R;
import java.io.*;
import java.util.*;

/** Offline WebAssembly Python. No JavaScript bridge, network access or Telegram objects. */
public final class FamilyPythonActivity extends FamilyUi {
    private EditText code,input;
    private TextView output;
    private Button run,stop;
    private WebView web;
    private boolean ready,running;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable watchdog=()->{cancelWorker();finishRun(getString(R.string.fg_python_timeout));};
    private static final Set<String> ASSETS=new HashSet<>(Arrays.asList("index.html","worker.mjs","runtime/pyodide.mjs","runtime/pyodide.asm.mjs","runtime/pyodide.asm.wasm","runtime/python_stdlib.zip","runtime/pyodide-lock.json"));
    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setTitle(R.string.fg_python_runner);settingsNavigation(R.string.fg_python_runner);
        if(!FamilyChatTools.enabled(this,"python")){finish();return;}
        paragraph(R.string.fg_python_summary);
        code=new EditText(this);code.setTextColor(ink);code.setTextSize(15);code.setTypeface(Typeface.MONOSPACE);code.setGravity(android.view.Gravity.TOP);code.setMinLines(5);code.setHorizontallyScrolling(true);code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);code.setContentDescription(getString(R.string.fg_python_code));
        String initial=state==null?getIntent().getStringExtra("code"):state.getString("code");
        code.setText(initial==null?"print(2 + 2)":initial.substring(0,Math.min(initial.length(),65536)));add(code,12);
        input=new EditText(this);input.setTextColor(ink);input.setHint(R.string.fg_python_input);input.setTextSize(15);input.setMinLines(2);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);add(input,12);if(state!=null)input.setText(state.getString("input",""));
        run=button(R.string.fg_python_run,true);stop=button(R.string.fg_python_stop,false);enabled(run,false);enabled(stop,false);
        output=text(R.string.fg_python_loading,15,ink);output.setTypeface(Typeface.MONOSPACE);output.setTextIsSelectable(true);output.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(output,16);
        web=new WebView(this);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false);settings.setDatabaseEnabled(false);settings.setGeolocationEnabled(false);settings.setBlockNetworkLoads(true);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
                Uri uri=request.getUrl();String path=uri.getPath();
                if(!"https".equals(uri.getScheme())||!"saa.local".equals(uri.getHost())||uri.getPort()!=-1||uri.getQuery()!=null||path==null||!path.startsWith("/runner/"))return blocked();
                String file=path.substring(8);if(!ASSETS.contains(file))return blocked();
                try{String mime=file.endsWith(".wasm")?"application/wasm":file.endsWith(".mjs")?"text/javascript":file.endsWith(".html")?"text/html":file.endsWith(".json")?"application/json":"application/octet-stream";
                    WebResourceResponse response=new WebResourceResponse(mime,"UTF-8",getAssets().open("familygram/runner/"+file));
                    response.setResponseHeaders(Collections.singletonMap("Content-Security-Policy","default-src 'none'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; connect-src 'self'; worker-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"));return response;
                }catch(IOException failure){return blocked();}
            }
            @Override public void onPageFinished(WebView v,String url){if(url.equals("https://saa.local/runner/index.html")){ready=true;enabled(run,true);output.setText(R.string.fg_python_ready);}}
            @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail detail){finishRun(getString(R.string.fg_python_memory_error));ready=false;enabled(run,false);v.destroy();web=null;return true;}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
            @Override public boolean onConsoleMessage(ConsoleMessage message){String value=message.message();if(value!=null&&value.startsWith("SAA_RUNNER:")&&value.length()<140000)try{JSONObject event=new JSONObject(value.substring(11));String type=event.optString("type");if(type.equals("result"))finishRun(event.optString("text"));else if(type.equals("running"))output.setText(R.string.fg_python_running);else if(type.equals("loading"))output.setText(R.string.fg_python_loading);}catch(Exception ignored){}return true;}
        });
        run.setOnClickListener(v->{if(!ready||running||web==null)return;try{String source=code.getText().toString();if(source.length()>65536){output.setText(R.string.fg_python_code_limit);return;}JSONObject payload=new JSONObject().put("code",source).put("input",input.getText().toString());running=true;enabled(run,false);enabled(stop,true);web.evaluateJavascript("executePython("+payload.toString()+")",null);handler.postDelayed(watchdog,26000);}catch(Exception failure){finishRun(getString(R.string.fg_python_failed));}});
        stop.setOnClickListener(v->{cancelWorker();finishRun(getString(R.string.fg_python_cancelled));});
        // WebView is an execution surface only. All visible controls/output are native.
        content.addView(web,new android.widget.LinearLayout.LayoutParams(1,1));web.loadUrl("https://saa.local/runner/index.html");
    }
    private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    private void cancelWorker(){if(web!=null)web.evaluateJavascript("stopRun()",null);}
    private void finishRun(String text){handler.removeCallbacks(watchdog);running=false;if(run!=null)enabled(run,ready);if(stop!=null)enabled(stop,false);if(output!=null)output.setText(text.length()>65536?text.substring(0,65536):text);}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(code!=null)state.putString("code",code.getText().toString());if(input!=null)state.putString("input",input.getText().toString());}
    @Override protected void onDestroy(){handler.removeCallbacks(watchdog);if(web!=null){cancelWorker();web.stopLoading();web.destroy();web=null;}super.onDestroy();}
}
