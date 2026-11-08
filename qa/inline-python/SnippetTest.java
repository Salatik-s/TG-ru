package org.telegram.messenger.family;
import java.util.*;
import java.nio.charset.StandardCharsets;
public final class SnippetTest {
 public static class TL_messageEntityPre {public String language;public int offset,length;TL_messageEntityPre(String language,int offset,int length){this.language=language;this.offset=offset;this.length=length;}}
 public static class Owner {public String message;public boolean noforwards;public int ttl,ttl_period;public Object media;public List<Object> entities=new ArrayList<>();}
 public static class Message {public int currentAccount=0,id=7;public long dialog=100;public Owner messageOwner=new Owner();public boolean secret;public int getId(){return id;}public long getDialogId(){return dialog;}public boolean isSecretMedia(){return secret;}}
 static void check(boolean result){if(!result)throw new AssertionError();}
 static Message message(String code,String language){Message m=new Message();m.messageOwner.message=code;m.messageOwner.entities.add(new TL_messageEntityPre(language,0,code.length()));return m;}
 static FamilyPythonSnippets.Snippet snippet(Message m){return FamilyPythonSnippets.extract(m,m.currentAccount).get(0);}
 public static void main(String[] args){
  Message m=message("python\nprint(2+2)","python");check(snippet(m).code.equals("print(2+2)"));String key=snippet(m).key;
  check(FamilyPythonSnippets.normalize("python3\r\nprint(4)").equals("print(4)"));check(FamilyPythonSnippets.normalize("python = 2\nprint(python)").startsWith("python ="));check(FamilyPythonSnippets.normalize(" python\nprint(4)").startsWith(" python"));
  for(String lang:new String[]{"","text","javascript","pythonish"})check(FamilyPythonSnippets.extract(message("print(1)",lang),0).isEmpty());
  for(String lang:new String[]{"python","py","python3","Python"})check(FamilyPythonSnippets.extract(message("print(1)",lang),0).size()==1);
  check(FamilyPythonSnippets.extract(m,1).isEmpty());m.currentAccount=1;check(!snippet(m).key.equals(key));m.currentAccount=0;
  m.dialog=101;check(!snippet(m).key.equals(key));m.dialog=100;m.id=8;check(!snippet(m).key.equals(key));m.id=7;
  check(!FamilyPythonSnippets.extract(m,0,"0:owner1").get(0).key.equals(FamilyPythonSnippets.extract(m,0,"0:owner2").get(0).key));
  m.messageOwner.noforwards=true;check(FamilyPythonSnippets.extract(m,0).isEmpty());m.messageOwner.noforwards=false;
  m.messageOwner.ttl_period=10;check(FamilyPythonSnippets.extract(m,0).isEmpty());m.messageOwner.ttl_period=0;
  m.secret=true;check(FamilyPythonSnippets.extract(m,0).isEmpty());m.secret=false;m.dialog=0x4000000000000001L;check(FamilyPythonSnippets.extract(m,0).isEmpty());m.dialog=100;
  ((TL_messageEntityPre)m.messageOwner.entities.get(0)).offset=-1;check(FamilyPythonSnippets.extract(m,0).isEmpty());
  check(FamilyPythonSnippets.extract(message("я".repeat(40000),"python"),0).isEmpty());check(FamilyPythonSnippets.extract(message(" \n\t","python"),0).isEmpty());
  m=message("print(1)\nprint(2)","python");m.messageOwner.entities.clear();m.messageOwner.entities.add(new TL_messageEntityPre("python",0,8));m.messageOwner.entities.add(new TL_messageEntityPre("python",9,8));List<FamilyPythonSnippets.Snippet> pair=FamilyPythonSnippets.extract(m,0);check(pair.size()==2&&!pair.get(0).key.equals(pair.get(1).key));
  FamilyPythonSnippets.clear();FamilyPythonSnippets.save(pair.get(0).key,"one");check(FamilyPythonSnippets.result(pair.get(1).key)==null);
  Message edited=message("print(3)","python");check(FamilyPythonSnippets.changed(snippet(edited)));check(FamilyPythonSnippets.result(snippet(edited).key)==null);
  FamilyPythonSnippets.clear();for(int i=0;i<33;i++)FamilyPythonSnippets.save("small"+i,"x");check(FamilyPythonSnippets.result("small0")==null&&FamilyPythonSnippets.result("small32").equals("x"));
  FamilyPythonSnippets.clear();for(int i=0;i<17;i++)FamilyPythonSnippets.save("large"+i,"z".repeat(65536));check(FamilyPythonSnippets.result("large0")==null&&FamilyPythonSnippets.result("large16").length()==65536);
  String bounded=FamilyPythonSnippets.limitText("😀".repeat(40000));check(bounded.getBytes(StandardCharsets.UTF_8).length==65536&&!Character.isHighSurrogate(bounded.charAt(bounded.length()-1)));
  FamilyPythonSnippets.clear();System.out.println("PASS snippet languages/normalization, bounds, protected messages, account-owner/chat/message/block/code isolation, stale-code state, LRU32/1MiB, UTF8 limits");
 }
}
