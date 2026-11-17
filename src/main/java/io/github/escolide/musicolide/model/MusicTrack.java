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

    /** New imports use the ms_ namespace and one client-streamed OGG sound. */
    public boolean isStreamed() {
        return namespace != null && (namespace.startsWith("ms_") || namespace.startsWith("mr_"));
    }

    /**
     * True when a streamed track also contains short mono-only assets for
     * listener-specific fade updates. The full mono stream remains in the pack
     * for Discord and clients that do not use segmented mono playback.
     */
    public boolean hasMonoSegments() {
        return isStreamed() && segmentCount > 1 && segmentMs > 0 && segmentMs < durationMs;
    }

    /** Whether the selected channel layout is delivered as one client stream. */
    public boolean isStreamed(boolean mono) {
        return isStreamed() && !(mono && hasMonoSegments());
    }

    public int segmentCount(boolean mono) {
        return isStreamed(mono) ? 1 : Math.max(1, segmentCount);
    }

    public int segmentMs(boolean mono) {
        if (isStreamed(mono)) {
            return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, durationMs));
        }
        return Math.max(1, segmentMs);
    }

    /** Backwards-compatible default: stereo audio. */
    public String soundKey(int segmentIndex) {
        return soundKey(segmentIndex, false);
    }

    /** Returns the selected channel layout's sound event. */
    public String soundKey(int segmentIndex, boolean mono) {
        if (isStreamed()) {
            if (mono && hasMonoSegments()) {
                return namespace + ":m_" + String.format("%05d", segmentIndex);
            }
            return namespace + (mono ? ":mono" : ":music");
        }
        return namespace + ":" + (mono ? "m_" : "s_") + String.format("%05d", segmentIndex);
    }

    public long segmentDurationMs(int segmentIndex) {
        return segmentDurationMs(segmentIndex, false);
    }

    public long segmentDurationMs(int segmentIndex, boolean mono) {
        int selectedSegmentMs = segmentMs(mono);
        long start = (long) segmentIndex * selectedSegmentMs;
        return Math.max(0L, Math.min(selectedSegmentMs, durationMs - start));
    }
}
