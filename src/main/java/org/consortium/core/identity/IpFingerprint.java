package org.consortium.core.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Keyed fingerprint of a player's connection for the same-connection starting-capital check (specification 6):
 * the address is normalised (IPv4 as is, IPv6 reduced to its routing prefix) and run through HMAC-SHA256 with the
 * server's secret salt. The raw address never leaves this class; only the hex digest is stored.
 */
public final class IpFingerprint {
    private IpFingerprint() {
    }

    /**
     * Normalises the textual address {@code ServerPlayer.getIpAddress()} returns. Returns null when the address is
     * unknown or unparseable (the caller then grants normally and logs a warning).
     */
    public static String normalize(String address, int ipv6PrefixBits) {
        if (address == null || address.isBlank() || address.contains("<unknown>")) {
            return null;
        }
        String host = stripPort(address.trim());
        if (host.isEmpty()) {
            return null;
        }
        InetAddress inet;
        try {
            // Only literal addresses are accepted: never trigger a DNS lookup on the server thread.
            if (!looksLiteral(host)) {
                return null;
            }
            inet = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
        byte[] bytes = inet.getAddress();
        if (bytes.length == 4) {
            return "v4:" + (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "." + (bytes[3] & 0xff);
        }
        int bits = Math.max(0, Math.min(128, ipv6PrefixBits));
        int fullBytes = bits / 8;
        int remainder = bits % 8;
        byte[] prefix = new byte[16];
        System.arraycopy(bytes, 0, prefix, 0, fullBytes);
        if (remainder > 0 && fullBytes < 16) {
            int mask = 0xff << (8 - remainder);
            prefix[fullBytes] = (byte) (bytes[fullBytes] & mask);
        }
        return "v6/" + bits + ":" + HexFormat.of().formatHex(prefix);
    }

    /** HMAC-SHA256 of the normalised address with the salt as key, lower-case hex. */
    public static String hash(byte[] salt, String normalised) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    static String stripPort(String s) {
        // "/1.2.3.4:25565" (Netty toString), "[::1]:25565", "1.2.3.4", "::1"
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end > 0 ? s.substring(1, end) : s.substring(1);
        }
        int colons = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ':') {
                colons++;
            }
        }
        if (colons == 1) {
            return s.substring(0, s.indexOf(':'));
        }
        return s;
    }

    private static boolean looksLiteral(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':' || c == '%';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
