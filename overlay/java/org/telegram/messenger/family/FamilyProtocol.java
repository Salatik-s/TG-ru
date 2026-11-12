package org.telegram.messenger.family;

import java.net.URI;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure protocol rules shared by the client and host-side validation tests. */
final class FamilyProtocol {
    private FamilyProtocol() { }

    static boolean validChallenge(String message, String challengeId, String action) {
        if (message == null || challengeId == null || action == null
                || !action.matches("invite|status|config")) return false;
        String[] parts = message.split("\n", -1);
        if (parts.length != 5 || !parts[0].equals("familygram-v1")
                || !parts[1].equals(challengeId) || !parts[2].matches("[A-Za-z0-9_-]{43}")
                || !parts[3].equals(action) || !parts[4].matches("[a-f0-9]{64}")) return false;
        // Invitation commitments cover the host's salted, non-plaintext representation.
        // The client signs the exact TLS-authenticated message, not a reconstructed one.
        return action.equals("invite") || parts[4].equals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
    }
    static String canonicalManagement(String command,String deviceId,int hours) {
        if("set_developer".equals(command)&&deviceId!=null&&deviceId.matches("[A-Za-z][A-Za-z0-9_]{3,31}"))
            return "{\"command\":\"set_developer\",\"username\":\""+deviceId+"\"}";
        if("list".equals(command))return "{\"command\":\"list\"}";
        if("invite".equals(command)&&hours>=1&&hours<=720)return "{\"command\":\"invite\",\"hours\":"+hours+"}";
        if(("approve".equals(command)||"revoke".equals(command))&&deviceId!=null&&deviceId.matches("[a-f0-9]{64}"))
            return "{\"command\":\""+command+"\",\"device_id\":\""+deviceId+"\"}";
        throw new IllegalArgumentException("Invalid management action");
    }
    static boolean validManagementChallenge(String message,String challengeId,String canonicalPayload) {
        if(message==null||challengeId==null||canonicalPayload==null)return false;
        String[] parts=message.split("\n",-1);
        if(parts.length!=5||!parts[0].equals("familygram-v1")||!parts[1].equals(challengeId)||!parts[2].matches("[A-Za-z0-9_-]{43}")||!parts[3].equals("manage")||!parts[4].matches("[a-f0-9]{64}"))return false;
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b&255));return parts[4].equals(hex.toString());}
        catch(Exception failure){return false;}
    }

    static String validateServer(String value) throws Exception {
        if (value == null || value.length() > 512) throw new IllegalArgumentException();
        URI uri = new URI(value.trim());
        String path = uri.getRawPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (path != null && !path.isEmpty() && !path.equals("/"))
                || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException();
        }
        return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null).toASCIIString();
    }

    static String trimName(String value) {
        if (value == null) return "";
        int start = 0, end = value.length();
        while (start < end && trimCharacter(value.charAt(start))) start++;
        while (end > start && trimCharacter(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }
    private static boolean trimCharacter(char c) {
        return c == '\uFEFF' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f' || Character.isSpaceChar(c);
    }

    static String canonicalInvite(String code, String name) {
        return "{\"code\":" + canonicalString(code) + ",\"name\":" + canonicalString(name) + "}";
    }
    private static String canonicalString(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 32 || (Character.isSurrogate(c)
                            && !(Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(value.charAt(i - 1))))) {
                        result.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    } else result.append(c);
            }
        }
        return result.append('"').toString();
    }
}
