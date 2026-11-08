package org.telegram.messenger.family;

import android.content.*;
import android.os.*;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.*;
import java.io.File;
import org.telegram.messenger.R;

public final class FamilyDocumentActivity extends FamilyUi {
    private Messenger service;
    private TextView status,text;
    private ImageView image;
    private Button previous,next;
    private File file;
    private String extension;
    private boolean bound,busy;
    private int page,count;
    private Bitmap shown;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Messenger reply=new Messenger(new Handler(Looper.getMainLooper()){
        @Override public void handleMessage(Message message){
            if(isFinishing()||isDestroyed())return;busy=false;
            if(message.what!=1){status.setText(R.string.fg_documents_error);status.setTextColor(error);return;}
            Bundle data=message.getData();data.setClassLoader(Bitmap.class.getClassLoader());
            if(extension.equals("pdf")){
                Bitmap result=data.getParcelable("bitmap");if(result==null){status.setText(R.string.fg_documents_error);return;}
                image.setImageBitmap(result);if(shown!=null)shown.recycle();shown=result;
                page=data.getInt("page");count=data.getInt("page_count");status.setText(getString(R.string.fg_documents_page,page+1,count));enabled(previous,page>0);enabled(next,page+1<count);
            }else{String value=data.getString("text","");text.setText(value);status.setText(data.getBoolean("truncated")?R.string.fg_documents_truncated:R.string.fg_documents_text_notice);}
        }
    });
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){service=new Messenger(binder);load();}
        public void onServiceDisconnected(ComponentName name){service=null;busy=false;status.setText(R.string.fg_documents_error);}
        public void onBindingDied(ComponentName name){onServiceDisconnected(name);}
    };
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);setTitle(R.string.fg_documents_title);settingsNavigation(R.string.fg_documents_title);
        if(!FamilyChatTools.enabled(this,"documents")){finish();return;}
        String path=getIntent().getStringExtra("path");extension=getIntent().getStringExtra("extension");if(path==null||extension==null||!extension.matches("pdf|docx|doc|odt|rtf|txt|csv|json|xml|md|log|py|js|java|html|htm")){finish();return;}
        file=new File(path);if(!file.isFile()||file.length()>32L*1024*1024){finish();return;}
        TextView name=text(R.string.fg_documents_title,17,ink);String label=getIntent().getStringExtra("name");name.setText(label==null?file.getName():label);name.setTextIsSelectable(true);add(name,12);
        status=text(R.string.fg_documents_loading,14,muted);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(status,12);
        if(extension.equals("pdf")){
            image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(getString(R.string.fg_documents_title));add(image,12);
            previous=button(R.string.fg_documents_previous,false);next=button(R.string.fg_documents_next,false);enabled(previous,false);enabled(next,false);
            previous.setOnClickListener(v->{if(!busy&&page>0){page--;load();}});next.setOnClickListener(v->{if(!busy&&page+1<count){page++;load();}});
        }else{text=text(R.string.fg_documents_loading,17,ink);text.setTextIsSelectable(true);text.setPadding(0,dp(8),0,dp(24));add(text,12);}
        bound=bindService(new Intent(this,FamilyDocumentService.class),connection,BIND_AUTO_CREATE);
        if(!bound)status.setText(R.string.fg_documents_error);
    }
    private void load(){
        if(service==null||busy)return;busy=true;status.setText(R.string.fg_documents_loading);if(previous!=null){enabled(previous,false);enabled(next,false);}
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)){
            Message message=Message.obtain(null,extension.equals("pdf")?1:2);message.replyTo=reply;Bundle data=new Bundle();data.putParcelable("fd",descriptor);data.putString("extension",extension);data.putInt("page",page);data.putInt("width",getResources().getDisplayMetrics().widthPixels-dp(48));message.setData(data);service.send(message);
        }catch(Exception failure){busy=false;status.setText(R.string.fg_documents_error);}
    }
    @Override protected void onDestroy(){if(bound)unbindService(connection);if(image!=null)image.setImageDrawable(null);if(shown!=null)shown.recycle();super.onDestroy();}
}
