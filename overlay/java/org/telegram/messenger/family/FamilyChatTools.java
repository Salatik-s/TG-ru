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
            if(!language.isEmpty()&&!language.equals("python")&&!language.equals("py")&&!language.equals("python3"))continue;
            int offset=((Number)field(entity,"offset")).intValue(),length=((Number)field(entity,"length")).intValue();
            if(offset>=0&&length>0&&length<=65536&&offset<=text.length()-length)result.add(text.substring(offset,offset+length));
            if(result.size()>=8)break;
        }
        return result;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    public static void fillMenu(Object chat,Object message,ArrayList icons,ArrayList items,ArrayList options){
        try{Activity activity=(Activity)chat.getClass().getMethod("getParentActivity").invoke(chat);
            if(activity==null||!FamilyAccess.isAllowed()||!enabled(activity,"python")||blocks(message).isEmpty())return;
            icons.add(R.drawable.fg_ic_code);items.add(activity.getString(R.string.fg_python_menu));options.add(RUN_PYTHON);
        }catch(Exception ignored){}
    }
    public static boolean select(Object chat,int option){
        if(option!=RUN_PYTHON)return false;
        try{Activity activity=(Activity)chat.getClass().getMethod("getParentActivity").invoke(chat);
            if(activity==null||!enabled(activity,"python")||!FamilyAccess.isAllowed())return true;
            Object message=field(chat,"selectedObject");ArrayList<String> snippets=blocks(message);if(snippets.isEmpty())return true;
            if(snippets.size()==1)openPython(activity,snippets.get(0));
            else{String[] labels=new String[snippets.size()];for(int n=0;n<labels.length;n++)labels[n]=activity.getString(R.string.fg_python_block,n+1);new AlertDialog.Builder(activity).setTitle(R.string.fg_python_choose).setItems(labels,(dialog,which)->openPython(activity,snippets.get(which))).setNegativeButton(android.R.string.cancel,null).show();}
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
