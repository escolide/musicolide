package io.github.escolide.musicolide.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record MusicTrack(
        String id,
        String title,
        String artist,
        String namespace,
        String packFileName,
        String sha1Hex,
        long durationMs,
        int segmentMs,
        int segmentCount,
        long packSizeBytes
) {
    public UUID packId() {
        return UUID.nameUUIDFromBytes(("musicolide:" + id).getBytes(StandardCharsets.UTF_8));
    }

    public byte[] sha1Bytes() {
        byte[] bytes = new byte[20];
        if (sha1Hex == null || sha1Hex.length() != 40) {
            return bytes;
        }
        for (int i = 0; i < 20; i++) {
            bytes[i] = (byte) Integer.parseInt(sha1Hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    public String soundKey(int segmentIndex, boolean mono) {
        return namespace + ":" + (mono ? "m_" : "s_") + String.format("%05d", segmentIndex);
    }

    public long segmentDurationMs(int segmentIndex) {
        long start = (long) segmentIndex * segmentMs;
        return Math.max(0L, Math.min(segmentMs, durationMs - start));
    }
}
