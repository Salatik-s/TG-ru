package org.telegram.messenger.family;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Isolated UID, no app permissions, only a caller-supplied read-only descriptor. */
public final class FamilyDocumentService extends Service {
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy=new AtomicBoolean();
    private final Messenger messenger=new Messenger(new Handler(Looper.getMainLooper()){
        @Override public void handleMessage(Message message){
            Messenger reply=message.replyTo;if(reply==null)return;
            if(busy.getAndSet(true)){send(reply,null,"busy");return;}
            Bundle request=message.getData();request.setClassLoader(ParcelFileDescriptor.class.getClassLoader());
            ParcelFileDescriptor descriptor=request.getParcelable("fd");int kind=message.what;
            Runnable timeout=()->{send(reply,null,"timeout");android.os.Process.killProcess(android.os.Process.myPid());};
            main.postDelayed(timeout,8000);
            work.execute(()->{
                try{if(descriptor==null||descriptor.getStatSize()<0||descriptor.getStatSize()>32L*1024*1024)throw new IOException();
                    Bundle result=kind==1?pdf(descriptor,request):text(descriptor,request.getString("extension",""));
                    send(reply,result,null);
                }catch(Throwable failure){send(reply,null,"invalid_document");}
                finally{try{if(descriptor!=null)descriptor.close();}catch(IOException ignored){}main.removeCallbacks(timeout);busy.set(false);}
            });
        }
    });
    public static boolean isIsolatedDocumentProcess(){return android.os.Process.myUid()%100000>=90000;}
    @Override public IBinder onBind(Intent intent){return messenger.getBinder();}
    @Override public void onDestroy(){work.shutdownNow();super.onDestroy();}
    private static void send(Messenger reply,Bundle value,String error){try{Message response=Message.obtain(null,error==null?1:2);Bundle payload=value==null?new Bundle():value;if(error!=null)payload.putString("error",error);response.setData(payload);reply.send(response);}catch(RemoteException ignored){}}
    private static Bundle pdf(ParcelFileDescriptor fd,Bundle input)throws Exception{
        int index=input.getInt("page",0),width=Math.max(256,Math.min(4096,input.getInt("width",1080)));
        try(PdfRenderer renderer=new PdfRenderer(fd)){
            int count=renderer.getPageCount();if(index<0||index>=count||count>5000)throw new IOException();
            try(PdfRenderer.Page page=renderer.openPage(index)){
                if(page.getWidth()<1||page.getHeight()<1||page.getWidth()>100000||page.getHeight()>100000)throw new IOException();
                double target=Math.min((double)width/page.getWidth(),4096.0/page.getHeight());
                target=Math.min(target,Math.sqrt(8000000.0/((double)page.getWidth()*page.getHeight())));
                width=Math.max(1,(int)Math.ceil(page.getWidth()*target));int height=Math.max(1,(int)Math.ceil(page.getHeight()*target));
                Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
                Matrix scale=new Matrix();float ratio=Math.min((float)width/page.getWidth(),(float)height/page.getHeight());scale.setScale(ratio,ratio);
                page.render(image,null,scale,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                Bundle result=new Bundle();result.putParcelable("bitmap",image);result.putInt("page_count",count);result.putInt("page",index);result.putInt("page_width",page.getWidth());result.putInt("page_height",page.getHeight());result.putInt("requested_width",input.getInt("width",1080));return result;
            }
        }
    }
    private static byte[] read(InputStream input,int maximum)throws IOException{
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){if(output.size()+n>maximum)throw new IOException();output.write(buffer,0,n);}return output.toByteArray();
    }
    private static Bundle text(ParcelFileDescriptor fd,String extension)throws Exception{
        String value;
        try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){
            byte[] data=read(input,32*1024*1024);
            try {String html=FamilyOfficeRenderer.render(data,extension);Bundle result=new Bundle();result.putString("html",html);return result;}
            catch(UnsupportedOperationException fallback) { /* RTF keeps its bounded local text parser. */ }
            if(extension.equals("docx")||extension.equals("odt"))value=zippedXml(data,extension.equals("docx")?"word/document.xml":"content.xml",extension.equals("docx"));
            else if(extension.equals("doc"))value=legacyWord(data);
            else if(extension.equals("rtf"))value=rtf(data);
            else{value=decode(data);if(extension.equals("html")||extension.equals("htm"))value=android.text.Html.fromHtml(value,android.text.Html.FROM_HTML_MODE_LEGACY).toString();}
        }
        boolean truncated=value.length()>200000;if(truncated)value=value.substring(0,200000);
        Bundle result=new Bundle();result.putString("text",value);result.putBoolean("truncated",truncated);return result;
    }
    private static String zippedXml(byte[] bytes,String wanted,boolean word)throws Exception{
        byte[] xml=null;int entries=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){if(++entries>4096)throw new IOException();String name=entry.getName();if(name.startsWith("/")||name.contains("../")||name.length()>512)throw new IOException();
                if(name.equals(wanted)){xml=read(zip,8*1024*1024);break;}}
        }
        if(xml==null)throw new IOException();
        XmlPullParser parser=Xml.newPullParser();parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);parser.setInput(new ByteArrayInputStream(xml),null);
        StringBuilder text=new StringBuilder();int depth=0,paragraphDepth=0;
        for(int token=parser.nextToken();token!=XmlPullParser.END_DOCUMENT;token=parser.nextToken()){
            if(token==XmlPullParser.DOCDECL||(token==XmlPullParser.ENTITY_REF&&parser.getText()==null))throw new IOException();
            if(token==XmlPullParser.START_TAG){depth++;String name=parser.getName();if(word&&name.equals("t"))paragraphDepth=depth;if(!word&&(name.equals("p")||name.equals("h")))paragraphDepth=depth;if(name.equals("tab"))text.append('\t');if(name.equals("br")||name.equals("line-break"))text.append('\n');}
            else if((token==XmlPullParser.TEXT||token==XmlPullParser.ENTITY_REF)&&paragraphDepth>0)text.append(parser.getText());
            else if(token==XmlPullParser.END_TAG){String name=parser.getName();if(name.equals("p")||name.equals("h")||name.equals("tr"))text.append('\n');else if(name.equals("tc"))text.append('\t');if(depth==paragraphDepth)paragraphDepth=0;depth--;}
            if(text.length()>200000)break;
        }
        return text.toString();
    }
    private static String legacyWord(byte[] bytes)throws Exception{
        Class<?> limits=Class.forName("org.apache.poi.util.IOUtils");limits.getMethod("setByteArrayMaxOverride",int.class).invoke(null,32*1024*1024);
        Object extractor;
        try{extractor=Class.forName("org.apache.poi.hwpf.extractor.WordExtractor").getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(bytes));}
        catch(java.lang.reflect.InvocationTargetException oldFormat){extractor=Class.forName("org.apache.poi.hwpf.extractor.Word6Extractor").getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(bytes));}
        try{return (String)extractor.getClass().getMethod("getText").invoke(extractor);}finally{((Closeable)extractor).close();}
    }
    private static String decode(byte[] data)throws Exception{
        if(data.length>=2&&((data[0]&255)==255&&(data[1]&255)==254))return new String(data,2,data.length-2,StandardCharsets.UTF_16LE);
        if(data.length>=2&&((data[0]&255)==254&&(data[1]&255)==255))return new String(data,2,data.length-2,StandardCharsets.UTF_16BE);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString();}
        catch(CharacterCodingException bad){return new String(data,Charset.forName("windows-1251"));}
    }
    private static String rtf(byte[] bytes)throws Exception{
        String source=new String(bytes,StandardCharsets.ISO_8859_1);StringBuilder out=new StringBuilder();int depth=0,skip=-1,uc=1;
        for(int at=0;at<source.length()&&out.length()<200001;){char c=source.charAt(at++);
            if(c=='{'){depth++;continue;}if(c=='}'){if(skip==depth)skip=-1;depth--;continue;}
            if(c!='\\'){if(skip<0&&c!='\r'&&c!='\n')out.append(c);continue;}
            if(at>=source.length())break;c=source.charAt(at++);
            if(c=='\\'||c=='{'||c=='}'){if(skip<0)out.append(c);continue;}
            if(c=='*'){skip=depth;continue;}
            if(c=='\''&&at+2<=source.length()){try{if(skip<0)out.append(new String(new byte[]{(byte)Integer.parseInt(source.substring(at,at+2),16)},Charset.forName("windows-1251")));}catch(NumberFormatException ignored){}at+=2;continue;}
            if(!Character.isLetter(c)){if(c=='~'&&skip<0)out.append(' ');continue;}
            int start=at-1;while(at<source.length()&&Character.isLetter(source.charAt(at)))at++;String word=source.substring(start,at);
            int sign=1;if(at<source.length()&&source.charAt(at)=='-'){sign=-1;at++;}int number=0;boolean numeric=false;while(at<source.length()&&Character.isDigit(source.charAt(at))){numeric=true;number=Math.min(1000000,number*10+(source.charAt(at++)-'0'));}number*=sign;if(at<source.length()&&source.charAt(at)==' ')at++;
            if(word.equals("fonttbl")||word.equals("colortbl")||word.equals("stylesheet")||word.equals("pict")||word.equals("object")||word.equals("info")){skip=depth;continue;}if(skip>=0)continue;
            if(word.equals("par")||word.equals("line"))out.append('\n');else if(word.equals("tab"))out.append('\t');else if(word.equals("uc")&&numeric)uc=Math.max(0,Math.min(8,number));else if(word.equals("u")&&numeric){out.append((char)number);for(int n=0;n<uc&&at<source.length()&&source.charAt(at)!='\\'&&source.charAt(at)!='{'&&source.charAt(at)!='}';n++)at++;}
        }return out.toString();
    }
}
