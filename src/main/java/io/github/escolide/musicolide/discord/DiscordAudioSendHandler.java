package io.github.escolide.musicolide.discord;

import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded PCM handoff between FFmpeg's decoder thread and JDA's audio thread.
 * JDA requires 20 ms of 48 kHz, 16-bit, stereo, signed, big-endian PCM per call.
 */
public final class DiscordAudioSendHandler implements AudioSendHandler {
    public static final int FRAME_BYTES = 3_840;
    private static final int BUFFERED_FRAMES = 12;

    private record QueuedFrame(byte[] pcm, long generation) {}

    private final ArrayBlockingQueue<QueuedFrame> frames = new ArrayBlockingQueue<>(BUFFERED_FRAMES);
    private final AtomicLong providedFrames = new AtomicLong();
    private final AtomicLong activeGeneration = new AtomicLong();
    private volatile boolean closed;

    @Override
    public boolean canProvide() {
        return !closed && !frames.isEmpty();
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        if (closed) return null;
        QueuedFrame queued;
        while ((queued = frames.poll()) != null) {
            // A decoder can finish an offer at the same instant that a track is
            // changed. Never transmit an old session's frame after a reset.
            if (queued.generation() != activeGeneration.get()) continue;
            providedFrames.incrementAndGet();
            return ByteBuffer.wrap(queued.pcm());
        }
        return null;
    }

    public boolean offer(byte[] frame, long generation) throws InterruptedException {
        if (frame == null || frame.length != FRAME_BYTES) {
            throw new IllegalArgumentException("Discord audio frames must contain exactly 3,840 PCM bytes.");
        }
        QueuedFrame queued = new QueuedFrame(frame, generation);
        while (!closed && activeGeneration.get() == generation) {
            if (frames.offer(queued, 100, TimeUnit.MILLISECONDS)) {
                return activeGeneration.get() == generation;
            }
        }
        return false;
    }

    /**
     * Invalidates every queued frame from an earlier decoder before clearing the
     * buffer. The generation tag also rejects a stale offer that races this clear.
     */
    public void reset(long generation) {
        activeGeneration.set(generation);
        frames.clear();
        providedFrames.set(0L);
    }

    public long providedFrames() {
        return providedFrames.get();
    }

    public int queuedFrames() {
        return frames.size();
    }

    public void close() {
        closed = true;
        reset(activeGeneration.incrementAndGet());
    }
}
