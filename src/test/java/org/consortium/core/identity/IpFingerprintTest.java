package org.consortium.core.identity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IpFingerprintTest {
    private static final byte[] SALT_A = "salt-a-salt-a-salt-a-salt-a-salt".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SALT_B = "salt-b-salt-b-salt-b-salt-b-salt".getBytes(StandardCharsets.UTF_8);

    @Test
    void ipv4IsKeptAsIsWithoutThePort() {
        assertEquals("v4:203.0.113.7", IpFingerprint.normalize("203.0.113.7", 64));
        assertEquals("v4:203.0.113.7", IpFingerprint.normalize("/203.0.113.7:54321", 64));
        assertEquals("v4:203.0.113.7", IpFingerprint.normalize("203.0.113.7:25565", 64));
    }

    @Test
    void ipv6CollapsesToItsPrefix() {
        String a = IpFingerprint.normalize("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd", 64);
        String b = IpFingerprint.normalize("[2001:db8:1234:5678:1:2:3:4]:25565", 64);
        String other = IpFingerprint.normalize("2001:db8:1234:5679:aaaa:bbbb:cccc:dddd", 64);
        assertEquals(a, b, "same /64, different host part");
        assertNotEquals(a, other, "different /64");
        assertEquals("v6/64:20010db8123456780000000000000000", a);
        // A non-byte-aligned prefix masks the partial byte.
        assertEquals("v6/60:20010db8123456700000000000000000", IpFingerprint.normalize("2001:db8:1234:5678::1", 60));
    }

    @Test
    void unknownAddressesGiveNull() {
        assertNull(IpFingerprint.normalize("<unknown>", 64));
        assertNull(IpFingerprint.normalize("", 64));
        assertNull(IpFingerprint.normalize(null, 64));
        assertNull(IpFingerprint.normalize("example.com", 64), "host names are never resolved");
    }

    @Test
    void hashIsDeterministicAndSaltSensitive() {
        String h1 = IpFingerprint.hash(SALT_A, "v4:203.0.113.7");
        String h2 = IpFingerprint.hash(SALT_A, "v4:203.0.113.7");
        String h3 = IpFingerprint.hash(SALT_B, "v4:203.0.113.7");
        String h4 = IpFingerprint.hash(SALT_A, "v4:203.0.113.8");
        assertEquals(h1, h2);
        assertNotEquals(h1, h3);
        assertNotEquals(h1, h4);
        assertEquals(64, h1.length(), "hex SHA-256");
    }

    @Test
    void stripPortHandlesEveryShape() {
        assertEquals("::1", IpFingerprint.stripPort("[::1]:25565"));
        assertEquals("::1", IpFingerprint.stripPort("::1"));
        assertEquals("10.0.0.1", IpFingerprint.stripPort("/10.0.0.1:1"));
    }
}
