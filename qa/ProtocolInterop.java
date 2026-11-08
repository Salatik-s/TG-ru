package org.telegram.messenger.family;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Standalone QA harness; never packaged in the Android application. */
public final class ProtocolInterop {
    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String id = decode(input.readLine()), action = decode(input.readLine()), message = decode(input.readLine());
        boolean valid = FamilyProtocol.validChallenge(message, id, action);
        String encodedKey = input.readLine();
        if (!valid || encodedKey == null || encodedKey.isEmpty()) {
            System.out.print(valid ? "valid" : "invalid");
            return;
        }
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encodedKey))));
        signature.update(message.getBytes(StandardCharsets.UTF_8));
        System.out.print(Base64.getEncoder().encodeToString(signature.sign()));
    }
    private static String decode(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
}
