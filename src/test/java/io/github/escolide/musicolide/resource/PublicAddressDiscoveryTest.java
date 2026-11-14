package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicAddressDiscoveryTest {
    @Test
    void acceptsPublicIpv4AndTrimsServiceWhitespace() {
        assertEquals("8.8.8.8", PublicAddressDiscovery.normalizePublicAddress("  8.8.8.8\n"));
    }

    @Test
    void rejectsPrivateReservedAndMalformedIpv4() {
        for (String address : new String[]{
                "127.0.0.1", "10.0.0.1", "192.168.1.1", "100.64.0.1",
                "169.254.1.1", "192.0.2.10", "198.51.100.12",
                "203.0.113.4", "224.0.0.1", "256.1.1.1", "localhost"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> PublicAddressDiscovery.normalizePublicAddress(address), address);
        }
    }

    @Test
    void acceptsPublicIpv6AndRejectsNonGlobalIpv6() {
        String normalized = PublicAddressDiscovery.normalizePublicAddress("2001:4860:4860::8888");
        org.junit.jupiter.api.Assertions.assertTrue(normalized.startsWith("2001:4860:4860:"));

        for (String address : new String[]{"::1", "fc00::1", "fe80::1", "2001:db8::1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PublicAddressDiscovery.normalizePublicAddress(address), address);
        }
    }
}
