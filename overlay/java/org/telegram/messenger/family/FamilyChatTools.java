package org.telegram.messenger.family;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import org.telegram.messenger.R;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Locale;

/** Built-in optional tools. No network/request hooks and no automatic execution. */
public final class FamilyChatTools {
    public static final int RUN_PYTHON=8_700_001;
    public static final int TRANSCRIBE=8_700_002;
    public static final int TRANSCRIPTION_LANGUAGE=8_700_003;
    private FamilyChatTools(){}
    public static void copyPhotoReference(Object destination,Object source){
        try{byte[] reference=(byte[])field(source,"file_reference");if(reference!=null&&reference.length>0)destination.getClass().getField("file_reference").set(destination,reference.clone());}catch(Exception ignored){}
    }
    public static boolean enabled(Context context,String id){return context.getSharedPreferences("saa_builtin_plugins_v1",0).getBoolean(id,false);}
    public static void setEnabled(Context context,String id,boolean enabled){if(!id.equals("python")&&!id.equals("documents"))throw new IllegalArgumentException();context.getSharedPreferences("saa_builtin_plugins_v1",0).edit().putBoolean(id,enabled).apply();}
    private static Object field(Object object,String name)throws Exception{Class<?> c=object.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException();}
    private static ArrayList<String> blocks(Object message)throws Exception{
        Object owner=field(message,"messageOwner");String text=String.valueOf(field(owner,"message"));ArrayList<String> result=new ArrayList<>();
        if(Boolean.TRUE.equals(field(owner,"noforwards")))return result;
        if(text.length()>131072)return result;
        for(Object entity:(Iterable<?>)field(owner,"entities")){
            if(!entity.getClass().getSimpleName().equals("TL_messageEntityPre"))continue;
            String language=String.valueOf(field(entity,"language")).toLowerCase(Locale.ROOT);
            if(!language.equals("python")&&!language.equals("py")&&!language.equals("python3"))continue;
            int offset=((Number)field(entity,"offset")).intValue(),length=((Number)field(entity,"length")).intValue();
            if(offset>=0&&length>0&&length<=65536&&offset<=text.length()-length)result.add(text.substring(offset,offset+length));
            if(result.size()>=8)break;
        }
        return result;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    public static void fillMenu(Object chat,Object message,ArrayList icons,ArrayList items,ArrayList options){
        try{Activity activity=(Activity)chat.getClass().getMethod("getParentActivity").invoke(chat);
            if(activity==null||!FamilyAccess.isAllowed())return;
            if(FamilyLocalTranscriber.eligible(message)&&((Boolean)message.getClass().getMethod("isVoice").invoke(message)||(Boolean)message.getClass().getMethod("isRoundVideo").invoke(message))){icons.add(android.R.drawable.ic_btn_speak_now);items.add(activity.getString(R.string.fg_transcription_menu));options.add(TRANSCRIBE);icons.add(android.R.drawable.ic_menu_manage);items.add(activity.getString(R.string.fg_transcription_language_title));options.add(TRANSCRIPTION_LANGUAGE);}
            if(enabled(activity,"python")&&!blocks(message).isEmpty()){icons.add(R.drawable.fg_ic_code);items.add(activity.getString(R.string.fg_python_menu));options.add(RUN_PYTHON);}
        }catch(Exception ignored){}
    }
    public static boolean select(Object chat,int option){
        if(option==TRANSCRIBE){try{FamilyVoiceTranscription.runFromChat(chat,field(chat,"selectedObject"));}catch(Exception ignored){}return true;}
        if(option==TRANSCRIPTION_LANGUAGE){try{Activity activity=(Activity)chat.getClass().getMethod("getParentActivity").invoke(chat);if(activity!=null)new AlertDialog.Builder(activity).setTitle(R.string.fg_transcription_language_title).setItems(new String[]{"Русский","English","Язык устройства"},(dialog,which)->{String language=which==0?"ru-RU":which==1?"en-US":Locale.getDefault().toLanguageTag();activity.getSharedPreferences("saa_system_speech_v1",0).edit().putString("language",language).apply();}).setNegativeButton(android.R.string.cancel,null).show();}catch(Exception ignored){}return true;}
        if(option!=RUN_PYTHON)return false;
        try{Activity activity=(Activity)chat.getClass().getMethod("getParentActivity").invoke(chat);
            if(activity==null||!enabled(activity,"python")||!FamilyAccess.isAllowed())return true;
            Object message=field(chat,"selectedObject");
            if(!FamilyInlinePython.runFromChat(chat,message))Toast.makeText(activity,"Нажмите «Запустить» под блоком Python в сообщении.",Toast.LENGTH_SHORT).show();
        }catch(Exception ignored){}return true;
    }
    public static void openPython(Activity activity,String source){if(enabled(activity,"python"))activity.startActivity(new Intent(activity,FamilyPythonActivity.class).putExtra("code",source));}
    public static boolean openDocument(File file,String name,Activity activity){
        if(activity==null||file==null||name==null||!enabled(activity,"documents")||!FamilyAccess.isAllowed())return false;
        String lower=name.toLowerCase(Locale.ROOT);int dot=lower.lastIndexOf('.');if(dot<0)return false;String ext=lower.substring(dot+1);
        if(!ext.matches("pdf|docx|doc|odt|rtf|txt|csv|json|xml|md|log|py|js|java|html|htm"))return false;
        if(!file.isFile()||file.length()>32L*1024*1024){Toast.makeText(activity,R.string.fg_documents_size_limit,Toast.LENGTH_LONG).show();return true;}
        try{String path=file.getCanonicalPath();
            // Caller is a pinned internal file-open path, never an exported intent.
            activity.startActivity(new Intent(activity,FamilyDocumentActivity.class).putExtra("path",path).putExtra("name",name).putExtra("extension",ext));return true;
        }catch(Exception failure){Toast.makeText(activity,R.string.fg_documents_error,Toast.LENGTH_LONG).show();return true;}
    }
}
