package org.telegram.messenger.family;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
public final class FamilyOwnerProtocolTest {
  static String challenge(String payload)throws Exception {StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)))hex.append(String.format(Locale.ROOT,"%02x",b&255));return "familygram-v1\nid\n"+"A".repeat(43)+"\nmanage\n"+hex;}
  static void check(boolean value){if(!value)throw new AssertionError();}
  public static void main(String[] args)throws Exception {
    String target="a".repeat(64),other="b".repeat(64);
    for(String command:new String[]{"list","invite","approve","revoke"}){String canonical=FamilyProtocol.canonicalManagement(command,target,24);check(FamilyProtocol.validManagementChallenge(challenge(canonical),"id",canonical));check(!FamilyProtocol.validChallenge(challenge(canonical),"id","manage"));}
    String approve=FamilyProtocol.canonicalManagement("approve",target,24),revoke=FamilyProtocol.canonicalManagement("revoke",target,24);
    check(!FamilyProtocol.validManagementChallenge(challenge(approve),"id",revoke));check(!FamilyProtocol.validManagementChallenge(challenge(approve),"id",FamilyProtocol.canonicalManagement("approve",other,24)));check(!FamilyProtocol.validManagementChallenge(challenge(approve),"wrong",approve));
    String invite=FamilyProtocol.canonicalManagement("invite",target,24);check(!FamilyProtocol.validManagementChallenge(challenge(invite),"id",FamilyProtocol.canonicalManagement("invite",target,1)));
    try{FamilyProtocol.canonicalManagement("set-owner",target,24);throw new AssertionError();}catch(IllegalArgumentException expected){}
    try{FamilyProtocol.canonicalManagement("approve","short",24);throw new AssertionError();}catch(IllegalArgumentException expected){}
    System.out.println("PASS management challenge binds command, target, TTL, nonce shape and challenge id; legacy methods cannot sign management");
  }
}
