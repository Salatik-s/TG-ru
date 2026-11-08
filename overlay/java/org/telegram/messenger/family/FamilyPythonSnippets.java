package org.telegram.messenger.family;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Message extraction and bounded process-only results, independent of Android UI. */
public final class FamilyPythonSnippets {
    public static final int LIMIT=65_536;
    public static final class Snippet {
        public final String key,identity,code;
        Snippet(String key,String identity,String code){this.key=key;this.identity=identity;this.code=code;}
    }
    private static final LinkedHashMap<String,String> RESULTS=new LinkedHashMap<>(32,0.75f,true);
    private static int resultBytes;
    private FamilyPythonSnippets(){}
    static Object field(Object value,String name)throws Exception{for(Class<?> c=value.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static long number(Object value,String name)throws Exception{return ((Number)field(value,name)).longValue();}
    private static long optionalNumber(Object value,String name){try{return number(value,name);}catch(Exception ignored){return 0;}}
    public static List<Snippet> extract(Object message,int account){
        return extract(message,account,String.valueOf(account));
    }
    static List<Snippet> extract(Object message,int account,String accountIdentity){
        try{
            if(message==null||account<0||number(message,"currentAccount")!=account)return Collections.emptyList();
            long dialog=((Number)message.getClass().getMethod("getDialogId").invoke(message)).longValue();
            int id=((Number)message.getClass().getMethod("getId").invoke(message)).intValue();
            if(dialog==0||id==0||((dialog&0x4000000000000000L)!=0&&(dialog&Long.MIN_VALUE)==0))return Collections.emptyList();
            Object owner=field(message,"messageOwner");
            if(Boolean.TRUE.equals(field(owner,"noforwards"))||optionalNumber(owner,"ttl")!=0||optionalNumber(owner,"ttl_period")!=0)return Collections.emptyList();
            if(Boolean.TRUE.equals(message.getClass().getMethod("isSecretMedia").invoke(message)))return Collections.emptyList();
            Object media=field(owner,"media");if(media!=null&&optionalNumber(media,"ttl_seconds")!=0)return Collections.emptyList();
            String text=(String)field(owner,"message");if(text==null||text.length()>131_072)return Collections.emptyList();
            Object entities=field(owner,"entities");if(!(entities instanceof Iterable))return Collections.emptyList();
            List<Snippet> snippets=new ArrayList<>();
            for(Object entity:(Iterable<?>)entities){
                if(entity==null||!entity.getClass().getSimpleName().equals("TL_messageEntityPre"))continue;
                String language=String.valueOf(field(entity,"language")).toLowerCase(Locale.ROOT);
                if(!language.equals("python")&&!language.equals("py")&&!language.equals("python3"))continue;
                int offset=(int)number(entity,"offset"),length=(int)number(entity,"length");
                if(offset<0||length<=0||length>LIMIT||offset>text.length()-length)continue;
                String raw=text.substring(offset,offset+length),code=normalize(raw);
                if(code.trim().isEmpty()||code.getBytes(StandardCharsets.UTF_8).length>LIMIT)continue;
                String identity=accountIdentity+":"+dialog+":"+id+":"+offset+":";
                snippets.add(new Snippet(identity+digest(raw),identity,code));if(snippets.size()==8)break;
            }
            return snippets;
        }catch(Exception ignored){return Collections.emptyList();}
    }
    // Some messages repeat the language tag as a literal first line. Do not
    // strip arbitrary identifiers, whitespace, or a line containing Python code.
    static String normalize(String code){int newline=code.indexOf('\n');if(newline<0)return code;String first=code.substring(0,newline);if(first.endsWith("\r"))first=first.substring(0,first.length()-1);return first.equals("python")||first.equals("python3")?code.substring(newline+1):code;}
    private static String digest(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder(64);for(byte b:bytes)hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}
    public static synchronized String result(String key){return RESULTS.get(key);}
    public static synchronized boolean changed(Snippet snippet){for(String key:RESULTS.keySet())if(key.startsWith(snippet.identity)&&!key.equals(snippet.key))return true;return false;}
    public static synchronized void save(String key,String output){
        String text=limitText(output);String old=RESULTS.remove(key);if(old!=null)resultBytes-=old.getBytes(StandardCharsets.UTF_8).length;
        RESULTS.put(key,text);resultBytes+=text.getBytes(StandardCharsets.UTF_8).length;
        while(RESULTS.size()>32||resultBytes>1024*1024){Map.Entry<String,String> first=RESULTS.entrySet().iterator().next();resultBytes-=first.getValue().getBytes(StandardCharsets.UTF_8).length;RESULTS.remove(first.getKey());}
    }
    public static synchronized void clear(){RESULTS.clear();resultBytes=0;}
    static String limitText(String text){if(text==null)return "";int bytes=0,end=0;while(end<text.length()){int cp=text.codePointAt(end),n=cp<=127?1:cp<=2047?2:cp<=65535?3:4;if(bytes+n>LIMIT)break;bytes+=n;end+=Character.charCount(cp);}return text.substring(0,end);}
}
