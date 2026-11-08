package org.telegram.messenger.family;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.File;
import java.util.Locale;
import org.telegram.messenger.R;

/** Full-height local viewer. Parsing stays in the separate isolated-UID service. */
public class FamilyDocumentViewer extends FamilyUi {
    private Messenger service;
    private TextView status, text;
    private FamilyPdfView image;
    private WebView document;
    private Button previous, next, counter, zoomLabel;
    private File file;
    private String extension;
    private boolean bound, busy, htmlLoaded;
    private int page, count, renderedPage = -1, renderedWidth;
    private float requestedZoom = 1;
    private float officeInitialScale = 1;
    private Bitmap shown;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable rerender = () -> { if (!busy && image != null && requestedWidth() > renderedWidth * 1.2f) load(); };
    private final Messenger reply = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (isFinishing() || isDestroyed()) return;
            busy = false;
            if (message.what != 1) { status.setText(R.string.fg_documents_error); status.setTextColor(error); return; }
            Bundle data = message.getData(); data.setClassLoader(Bitmap.class.getClassLoader());
            if (extension.equals("pdf")) {
                Bitmap value = data.getParcelable("bitmap"); if (value == null) { status.setText(R.string.fg_documents_error); return; }
                boolean reset = renderedPage != data.getInt("page"); renderedPage = data.getInt("page"); renderedWidth = data.getInt("requested_width",value.getWidth());
                image.setPage(value, data.getInt("page_width",value.getWidth()), data.getInt("page_height",value.getHeight()), reset);
                if (shown != null) shown.recycle(); shown = value;
                page = data.getInt("page"); count = data.getInt("page_count");
                counter.setText((page + 1) + " / " + count); enabled(previous,page > 0); enabled(next,page + 1 < count);
                status.setText(R.string.fg_document_zoom_hint);
                if (requestedWidth() > renderedWidth * 1.2f) main.postDelayed(rerender,250);
            } else {
                String html = data.getString("html", ""); String value = data.getString("text", "");
                text.setText(value.isEmpty() ? android.text.Html.fromHtml(html,android.text.Html.FROM_HTML_MODE_LEGACY) : value);
                if (html.isEmpty()) html = "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'\"><style>body{background:white;color:#222;padding:20px;font:18px/1.6 sans-serif;white-space:pre-wrap;overflow-wrap:anywhere}</style>" + android.text.TextUtils.htmlEncode(value);
                document.loadDataWithBaseURL("https://saa.local/document/",html,"text/html","UTF-8",null);
                status.setText(data.getBoolean("truncated") ? R.string.fg_documents_truncated : R.string.fg_document_format_notice);
            }
        }
    });
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { service = new Messenger(binder); load(); }
        public void onServiceDisconnected(ComponentName name) { service = null; busy = false; status.setText(R.string.fg_documents_error); }
        public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
    };
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); setTitle(R.string.fg_documents_title);
        if (!FamilyChatTools.enabled(this,"documents") || !FamilyAccess.isAllowed()) { finish(); return; }
        String path = getIntent().getStringExtra("path"); extension = getIntent().getStringExtra("extension");
        if (path == null || extension == null || !extension.matches("pdf|docx|doc|odt|rtf|txt|csv|json|xml|md|log|py|js|java|html|htm")) { finish(); return; }
        file = new File(path); if (!file.isFile() || file.length() > 32L*1024*1024) { finish(); return; }
        if (saved != null) page = saved.getInt("document_page",0);
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setBackgroundColor(surface); root.setFitsSystemWindows(true);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this); back.setImageResource(R.drawable.fg_ic_back); back.setBackgroundColor(surface); back.setContentDescription(getString(R.string.fg_back)); back.setOnClickListener(v->finish()); toolbar.addView(back,new LinearLayout.LayoutParams(dp(56),dp(56)));
        TextView name = text(R.string.fg_documents_title,16,ink); String label = getIntent().getStringExtra("name"); name.setText(label == null ? file.getName() : label); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); toolbar.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        Button fit = small(R.string.fg_document_zoom); fit.setOnClickListener(v->zoomMenu()); toolbar.addView(fit); root.addView(toolbar);
        status = text(R.string.fg_documents_loading,12,muted); status.setPadding(dp(16),dp(3),dp(16),dp(5)); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(status);
        if (extension.equals("pdf")) {
            image = new FamilyPdfView(this); image.setContentDescription(getString(R.string.fg_documents_title));
            image.setListener(new FamilyPdfView.Listener() { public void page(int direction) { changePage(direction); }
                public void zoom(float value,boolean settled) { requestedZoom=value; if(zoomLabel!=null)zoomLabel.setText(Math.round(value*100)+"%"); if(settled){main.removeCallbacks(rerender);main.postDelayed(rerender,250);} }
            }); root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout navigation = new LinearLayout(this); navigation.setGravity(Gravity.CENTER_VERTICAL);
            previous=small(R.string.fg_documents_previous); next=small(R.string.fg_documents_next); counter=small(R.string.fg_document_jump); zoomLabel=small(R.string.fg_document_zoom);
            previous.setText("‹"); previous.setContentDescription(getString(R.string.fg_documents_previous)); previous.setTextSize(28);
            next.setText("›"); next.setContentDescription(getString(R.string.fg_documents_next)); next.setTextSize(28);
            previous.setOnClickListener(v->changePage(-1)); next.setOnClickListener(v->changePage(1)); counter.setOnClickListener(v->jump()); zoomLabel.setText("100%"); zoomLabel.setOnClickListener(v->zoomMenu());
            enabled(previous,false); enabled(next,false); navigation.addView(previous,new LinearLayout.LayoutParams(dp(56),dp(52))); navigation.addView(counter,new LinearLayout.LayoutParams(0,dp(52),1)); navigation.addView(zoomLabel,new LinearLayout.LayoutParams(dp(88),dp(52))); navigation.addView(next,new LinearLayout.LayoutParams(dp(56),dp(52))); root.addView(navigation);
        } else {
            text=text(R.string.fg_documents_loading,17,ink); text.setVisibility(View.GONE); // Accessible source used for explicit copy; never displayed instead of the formatted document.
            document = new WebView(this); WebSettings settings=document.getSettings(); settings.setJavaScriptEnabled(false); settings.setDomStorageEnabled(false); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setBlockNetworkLoads(true); settings.setSupportZoom(true); settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false); settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true); settings.setGeolocationEnabled(false);
            document.setBackgroundColor(android.graphics.Color.WHITE); document.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request){return true;}
                @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return true;}
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){if("data".equals(request.getUrl().getScheme()))return null;return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));}
                @Override public void onPageFinished(WebView view,String url){htmlLoaded=true;officeInitialScale=view.getScale();}
                @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){status.setText(R.string.fg_documents_error);view.destroy();document=null;return true;}
            }); document.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
            root.addView(document,new LinearLayout.LayoutParams(-1,0,1));
        }
        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 30) { root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;}); root.requestApplyInsets(); }
        bound=bindService(new Intent(this,FamilyDocumentService.class),connection,BIND_AUTO_CREATE); if(!bound)status.setText(R.string.fg_documents_error);
    }
    private Button small(int title) { Button button=new Button(this); button.setText(title); button.setAllCaps(false); button.setTextColor(primary); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setTextSize(14); button.setBackgroundColor(surface); return button; }
    private void changePage(int direction) { if(!busy&&page+direction>=0&&page+direction<count){page+=direction;requestedZoom=1;load();} }
    private void zoomMenu() {
        String[] choices=image!=null?new String[]{getString(R.string.fg_document_zoom_in),getString(R.string.fg_document_zoom_out),getString(R.string.fg_document_fit),getString(R.string.fg_document_fit_width)}:new String[]{getString(R.string.fg_document_zoom_in),getString(R.string.fg_document_zoom_out),getString(R.string.fg_document_fit),getString(R.string.fg_document_fit_width),getString(R.string.fg_document_copy)};
        new AlertDialog.Builder(this).setTitle(R.string.fg_document_zoom).setItems(choices,(dialog,which)->{
            if(image!=null){if(which==0)image.zoomIn();if(which==1)image.zoomOut();if(which==2)image.fitPage();if(which==3)image.fitWidth();}
            else if(document!=null){if(which==0)document.zoomIn();if(which==1)document.zoomOut();if(which==2||which==3)document.zoomBy(officeInitialScale/document.getScale());}
            if(which==4&&text!=null)((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("document",text.getText()));
        }).show();
    }
    private void jump() { if(count<1||busy)return; EditText value=new EditText(this);value.setInputType(InputType.TYPE_CLASS_NUMBER);value.setHint(getString(R.string.fg_document_page_hint,count));new AlertDialog.Builder(this).setTitle(R.string.fg_document_jump).setView(value).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(dialog,which)->{try{int target=Integer.parseInt(value.getText().toString())-1;if(target>=0&&target<count&&!busy){page=target;requestedZoom=1;load();}}catch(NumberFormatException ignored){}}).show(); }
    private int requestedWidth(){return Math.min(4096,Math.max(512,Math.round(getResources().getDisplayMetrics().widthPixels*requestedZoom)));}
    private void load(){
        if(service==null||busy)return;busy=true;status.setText(R.string.fg_documents_loading);if(previous!=null){enabled(previous,false);enabled(next,false);}
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)){
            Message message=Message.obtain(null,extension.equals("pdf")?1:2);message.replyTo=reply;Bundle data=new Bundle();data.putParcelable("fd",descriptor);data.putString("extension",extension);data.putInt("page",page);data.putInt("width",requestedWidth());message.setData(data);service.send(message);
        }catch(Exception failure){busy=false;status.setText(R.string.fg_documents_error);}
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("document_page",page);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){main.removeCallbacks(rerender);if(bound)unbindService(connection);if(image!=null)image.setPage(null,1,1,true);if(shown!=null)shown.recycle();if(document!=null){document.stopLoading();document.destroy();}super.onDestroy();}
}
