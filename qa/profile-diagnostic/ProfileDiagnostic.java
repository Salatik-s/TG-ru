package com.salatik.familygram.profilediag;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Exports only the authenticated FamilyGram route, encrypted to the owner's ephemeral desktop key. */
public final class ProfileDiagnostic extends Instrumentation {
    private String recipient;
    private boolean identityOnly;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        recipient=args.getString("recipient_key", "");
        identityOnly="true".equals(args.getString("identity_only","false"));
        start();
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        byte[] secret=null,plain=null;
        try {
            if(identityOnly){
                Class<?> access=Class.forName("org.telegram.messenger.family.FamilyAccess",false,getTargetContext().getClassLoader());
                Field preferences=access.getDeclaredField("preferences");preferences.setAccessible(true);
                long deadline=SystemClock.elapsedRealtime()+15000;
                while(preferences.get(null)==null&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
                if(preferences.get(null)==null)throw new IllegalStateException();
                Method proof=access.getDeclaredMethod("proofRequest",String.class,String.class,JSONObject.class);proof.setAccessible(true);
                JSONObject response=(JSONObject)proof.invoke(null,"https://24projects.ru:8443","status",new JSONObject());
                String id=response.getString("device_id");
                if(!"approved".equals(response.getString("status"))||!id.matches("[a-f0-9]{64}"))throw new IllegalStateException();
                result.putString("device_id",id);result.putString("result","APPROVED_INSTALLATION_IDENTITY");finish(Activity.RESULT_OK,result);return;
            }
            if(recipient.length()<400||recipient.length()>1024)throw new IllegalArgumentException();
            PublicKey recipientKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(recipient,Base64.NO_WRAP)));
            Class<?> access=Class.forName("org.telegram.messenger.family.FamilyAccess",false,getTargetContext().getClassLoader());
            Field preferences=access.getDeclaredField("preferences");preferences.setAccessible(true);
            long deadline=SystemClock.elapsedRealtime()+15000;
            while(preferences.get(null)==null&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
            if(preferences.get(null)==null)throw new IllegalStateException();
            Method proof=access.getDeclaredMethod("proofRequest",String.class,String.class,JSONObject.class);proof.setAccessible(true);
            JSONObject response=(JSONObject)proof.invoke(null,"https://24projects.ru:8443","config",new JSONObject());
            if(!"approved".equals(response.getString("status")))throw new IllegalStateException();
            JSONObject gateway=response.getJSONObject("gateway");
            JSONObject route=new JSONObject().put("host",gateway.getString("host")).put("port",gateway.getInt("port"))
                .put("uuid",gateway.getString("uuid")).put("publicKey",gateway.getString("public_key"))
                .put("shortId",gateway.getString("short_id")).put("serverName",gateway.getString("server_name"));
            plain=route.toString().getBytes(StandardCharsets.UTF_8);
            SecureRandom random=new SecureRandom();secret=new byte[32];byte[] iv=new byte[12];random.nextBytes(secret);random.nextBytes(iv);
            Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,iv));
            aes.updateAAD("FamilyGram outside profile v1".getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=aes.doFinal(plain),tag=Arrays.copyOfRange(encrypted,encrypted.length-16,encrypted.length);
            Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsa.init(Cipher.ENCRYPT_MODE,recipientKey,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
            JSONObject envelope=new JSONObject().put("version",1).put("wrappedKey",encode(rsa.doFinal(secret)))
                .put("iv",encode(iv)).put("tag",encode(tag)).put("ciphertext",encode(Arrays.copyOf(encrypted,encrypted.length-16)));
            result.putString("envelope",encode(envelope.toString().getBytes(StandardCharsets.UTF_8)));
            result.putString("result","ENCRYPTED_ROUTE_READY");finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){
            result.putString("result","PRIVATE_ROUTE_DIAGNOSTIC_FAILED");
            result.putString("exception_type",failure.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,result);
        }finally{if(secret!=null)Arrays.fill(secret,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);recipient="";}
    }
    private static String encode(byte[] value){return Base64.encodeToString(value,Base64.NO_WRAP);}
}
