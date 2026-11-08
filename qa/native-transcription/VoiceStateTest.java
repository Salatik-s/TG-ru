package org.telegram.messenger.family;
import android.content.Context;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

public final class VoiceStateTest {
 static final Context CONTEXT=new Context();
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
 static MessageObject message(int id){MessageObject m=new MessageObject();m.id=id;return m;}
 static void start(MessageObject m){FamilyVoiceTranscription.start(CONTEXT,m,m.currentAccount);}
 public static final class Cell {public boolean useSeekBarWaveform=true;final MessageObject message;Cell(MessageObject m){message=m;}public MessageObject getMessageObject(){return message;}}
 public static void main(String[] args)throws Exception {
  MessageObject first=message(1);Cell cell=new Cell(first);check(FamilyVoiceTranscription.canShow(cell),"button independent of premium (UserConfig mock has no isPremium)");cell.useSeekBarWaveform=false;check(!FamilyVoiceTranscription.canShow(cell),"native waveform visibility preserved");start(first);
  check(FamilyVoiceTranscription.isRunning(first)&&first.messageOwner.voiceTranscriptionOpen&&!first.messageOwner.voiceTranscriptionFinal,"native pending bubble");
  FamilyLocalTranscriber.Callback firstCallback=FamilyLocalTranscriber.last;
  firstCallback.onState("recognizing");check(first.messageOwner.voiceTranscription.contains("Распознаём"),"state rendered");
  firstCallback.onResult("synthetic transcript");
  check(!FamilyVoiceTranscription.isRunning(first)&&first.messageOwner.voiceTranscriptionFinal&&first.messageOwner.voiceTranscriptionId<0,"native final state");
  check(NotificationCenter.last.length==5&&NotificationCenter.last[1] instanceof Long&&first.forceUpdate,"native notification and caption rebind contract");
  int starts=FamilyLocalTranscriber.starts;start(first);check(!first.messageOwner.voiceTranscriptionOpen&&FamilyLocalTranscriber.starts==starts,"collapse no rerun");
  start(first);check(first.messageOwner.voiceTranscriptionOpen&&FamilyLocalTranscriber.starts==starts,"reopen cached result");
  MessageObject rebound=message(1);FamilyVoiceTranscription.restore(rebound);
  check("synthetic transcript".equals(rebound.messageOwner.voiceTranscription)&&FamilyLocalTranscriber.starts==starts,"rebound before caption without autoexecution");
  Context.language="en-US";MessageObject newLanguage=message(1);FamilyVoiceTranscription.restore(newLanguage);check(newLanguage.messageOwner.voiceTranscription==null,"language cache isolation");start(newLanguage);check(FamilyLocalTranscriber.starts==starts+1,"language switch starts new job");FamilyLocalTranscriber.last.onResult("new language result");Context.language="ru-RU";
  MessageObject otherChat=message(1);otherChat.dialog=20;FamilyVoiceTranscription.restore(otherChat);check(otherChat.messageOwner.voiceTranscription==null,"dialog isolation");
  MessageObject otherAccount=message(1);otherAccount.currentAccount=1;FamilyVoiceTranscription.restore(otherAccount);check(otherAccount.messageOwner.voiceTranscription==null,"account isolation");
  UserConfig.owners[0]=300;MessageObject changedOwner=message(1);FamilyVoiceTranscription.restore(changedOwner);check(changedOwner.messageOwner.voiceTranscription==null,"reused account slot isolation");UserConfig.owners[0]=100;
  MessageObject cancelled=message(2);start(cancelled);FamilyLocalTranscriber.Callback late=FamilyLocalTranscriber.last;start(cancelled);String cancelText=cancelled.messageOwner.voiceTranscription;
  late.onResult("stale result");check(cancelText.equals(cancelled.messageOwner.voiceTranscription)&&!FamilyVoiceTranscription.isRunning(cancelled),"cancel rejects late completion");
  start(cancelled);check(FamilyVoiceTranscription.isRunning(cancelled),"explicit retry");FamilyLocalTranscriber.last.onError("UNTRUSTED_ERROR_WITH_PRIVATE_DATA");check(!cancelled.messageOwner.voiceTranscription.contains("PRIVATE_DATA"),"fixed generic error");
  MessageObject changedDocument=message(3);start(changedDocument);late=FamilyLocalTranscriber.last;changedDocument.document.id++;late.onResult("stale document");check(!"stale document".equals(changedDocument.messageOwner.voiceTranscription),"document change rejects callback");
  MessageObject secret=message(4);secret.dialog=999;starts=FamilyLocalTranscriber.starts;start(secret);check(FamilyLocalTranscriber.starts==starts,"secret chat excluded");
  MessageObject ttl=message(5);ttl.messageOwner.media.ttl_seconds=1;start(ttl);check(FamilyLocalTranscriber.starts==starts,"ttl excluded");
  FamilyAccess.allowed=false;start(message(6));check(FamilyLocalTranscriber.starts==starts,"access revoked");FamilyAccess.allowed=true;
  String huge="я".repeat(65536);for(int id=10;id<50;id++){MessageObject m=message(id);start(m);FamilyLocalTranscriber.last.onResult(huge);check(m.messageOwner.voiceTranscription.getBytes(StandardCharsets.UTF_8).length<=65536&&!m.messageOwner.voiceTranscription.contains("\ufffd"),"UTF8 bound");}
  Field field=FamilyVoiceTranscription.class.getDeclaredField("CACHE");field.setAccessible(true);Map<?,?> cache=(Map<?,?>)field.get(null);check(cache.size()<=32,"LRU entry bound");int bytes=0;for(Object entry:cache.values()){Field text=entry.getClass().getDeclaredField("text");text.setAccessible(true);bytes+=((String)text.get(entry)).getBytes(StandardCharsets.UTF_8).length;}check(bytes<=1048576,"LRU byte bound");
  System.out.println("PASS native fields and notifications, no autoexecution, collapse/reopen, account/dialog/document identity, cancel epoch, fixed errors, protected/access gates, UTF8/LRU bounds");
 }
}
