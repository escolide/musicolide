package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedPortProtocolSnifferTest {
    @Test
    void recognizesGetRequestEvenWhenMethodArrivesInFragments() {
        assertEquals(SharedPortPackServer.ProtocolDecision.NEED_MORE,
                classify("G"));
        assertEquals(SharedPortPackServer.ProtocolDecision.NEED_MORE,
                classify("GE"));
        assertEquals(SharedPortPackServer.ProtocolDecision.NEED_MORE,
                classify("GET"));
        assertEquals(SharedPortPackServer.ProtocolDecision.HTTP,
                classify("GET "));
        assertEquals(SharedPortPackServer.ProtocolDecision.HTTP,
                classify("GET /packs/musicolide-base.zip"));
    }

    @Test
    void recognizesHeadRequests() {
        assertEquals(SharedPortPackServer.ProtocolDecision.NEED_MORE,
                classify("HEAD"));
        assertEquals(SharedPortPackServer.ProtocolDecision.HTTP,
                classify("HEAD "));
    }

    @Test
    void leavesMinecraftHandshakesUntouched() {
        assertEquals(SharedPortPackServer.ProtocolDecision.MINECRAFT,
                SharedPortPackServer.HttpMinecraftSniffer.classifyPrefix(
                        new byte[]{0x10, 0x00, 0x00}, 3));
        assertEquals(SharedPortPackServer.ProtocolDecision.MINECRAFT,
                SharedPortPackServer.HttpMinecraftSniffer.classifyPrefix(
                        new byte[]{0x47, 0x00}, 2));
    }

    private static SharedPortPackServer.ProtocolDecision classify(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        return SharedPortPackServer.HttpMinecraftSniffer.classifyPrefix(bytes, bytes.length);
    }
}
