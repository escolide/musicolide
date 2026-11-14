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

    private final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(BUFFERED_FRAMES);
    private final AtomicLong providedFrames = new AtomicLong();
    private volatile boolean closed;

    @Override
    public boolean canProvide() {
        return !closed && !frames.isEmpty();
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        if (closed) return null;
        byte[] frame = frames.poll();
        if (frame == null) return null;
        providedFrames.incrementAndGet();
        return ByteBuffer.wrap(frame);
    }

    public boolean offer(byte[] frame, long generation, java.util.function.LongSupplier currentGeneration)
            throws InterruptedException {
        if (frame == null || frame.length != FRAME_BYTES) {
            throw new IllegalArgumentException("Discord audio frames must contain exactly 3,840 PCM bytes.");
        }
        while (!closed && currentGeneration.getAsLong() == generation) {
            if (frames.offer(frame, 100, TimeUnit.MILLISECONDS)) return true;
        }
        return false;
    }

    /** Drops buffered audio and resets the frame clock for a new server timeline. */
    public void reset() {
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
        reset();
    }
}
