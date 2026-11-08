package org.telegram.messenger.family;
import java.util.*;

/** Context-menu identity/code guard regressions, without an Android device. */
public final class ContextEntryTest {
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 public static void main(String[] args){
  SnippetTest.Message selected=SnippetTest.message("print(1)","python");
  check(FamilyInlinePython.messageMatches(selected,0,100,7),"same selected message");
  check(!FamilyInlinePython.messageMatches(selected,1,100,7),"other account rejected");
  check(!FamilyInlinePython.messageMatches(selected,0,101,7),"other dialog rejected");
  check(!FamilyInlinePython.messageMatches(selected,0,100,8),"other message rejected");
  check(!FamilyInlinePython.messageMatches(null,0,100,7),"missing cell message rejected");
  List<FamilyPythonSnippets.Snippet> snapshot=FamilyPythonSnippets.extract(selected,0,"0:owner1");
  check(FamilyInlinePython.sameKeys(snapshot,FamilyPythonSnippets.extract(selected,0,"0:owner1")),"rebound same identity and code accepted");
  check(!FamilyInlinePython.sameKeys(snapshot,FamilyPythonSnippets.extract(selected,0,"0:owner2")),"reused account slot rejected");
  selected.messageOwner.message="print(2)";
  check(!FamilyInlinePython.sameKeys(snapshot,FamilyPythonSnippets.extract(selected,0,"0:owner1")),"edited code after selection rejected");
  selected.messageOwner.noforwards=true;
  check(!FamilyInlinePython.sameKeys(snapshot,FamilyPythonSnippets.extract(selected,0,"0:owner1")),"new protection after selection rejected");
  selected=SnippetTest.message("print(1)\nprint(2)","python");selected.messageOwner.entities.clear();
  selected.messageOwner.entities.add(new SnippetTest.TL_messageEntityPre("python",0,8));selected.messageOwner.entities.add(new SnippetTest.TL_messageEntityPre("python",9,8));
  List<FamilyPythonSnippets.Snippet> pair=FamilyPythonSnippets.extract(selected,0,"0:owner1");
  List<FamilyPythonSnippets.Snippet> reversed=new ArrayList<>(pair);Collections.reverse(reversed);
  check(!FamilyInlinePython.sameKeys(pair,reversed),"reordered block choice rejected");
  check(!FamilyInlinePython.sameKeys(pair,pair.subList(0,1)),"removed block rejected");
  check(!FamilyInlinePython.runFromChat(null,null),"missing fragment has no execution fallback");
  System.out.println("PASS context identity, owner/account/chat/message isolation, edited/protected/reordered/removed blocks, missing-view failure without editor fallback");
 }
}
