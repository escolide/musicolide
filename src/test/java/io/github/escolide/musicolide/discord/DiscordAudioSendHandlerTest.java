package io.github.escolide.musicolide.discord;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DiscordAudioSendHandlerTest {
    @Test
    void providesExactlyOneJdaPcmFrameAndCountsIt() throws InterruptedException {
        DiscordAudioSendHandler handler = new DiscordAudioSendHandler();
        AtomicLong generation = new AtomicLong(4);
        byte[] frame = new byte[DiscordAudioSendHandler.FRAME_BYTES];

        assertFalse(handler.canProvide());
        assertTrue(handler.offer(frame, 4, generation::get));
        assertTrue(handler.canProvide());

        ByteBuffer provided = handler.provide20MsAudio();
        assertNotNull(provided);
        assertEquals(DiscordAudioSendHandler.FRAME_BYTES, provided.remaining());
        assertEquals(1L, handler.providedFrames());
        assertFalse(handler.canProvide());
    }

    @Test
    void discardsFramesFromAnInvalidatedDecoderGeneration() throws InterruptedException {
        DiscordAudioSendHandler handler = new DiscordAudioSendHandler();
        AtomicLong generation = new AtomicLong(9);
        byte[] frame = new byte[DiscordAudioSendHandler.FRAME_BYTES];

        assertFalse(handler.offer(frame, 8, generation::get));
        assertFalse(handler.canProvide());
        assertEquals(0L, handler.providedFrames());
    }

    @Test
    void resetDropsBufferedFramesAndResetsSynchronizationCounter() throws InterruptedException {
        DiscordAudioSendHandler handler = new DiscordAudioSendHandler();
        AtomicLong generation = new AtomicLong(2);
        byte[] frame = new byte[DiscordAudioSendHandler.FRAME_BYTES];

        assertTrue(handler.offer(frame, 2, generation::get));
        assertNotNull(handler.provide20MsAudio());
        assertEquals(1L, handler.providedFrames());

        handler.offer(frame, 2, generation::get);
        handler.reset();

        assertFalse(handler.canProvide());
        assertEquals(0L, handler.providedFrames());
    }

    @Test
    void closedHandlerNeverAcceptsOrProvidesAudio() throws InterruptedException {
        DiscordAudioSendHandler handler = new DiscordAudioSendHandler();
        AtomicLong generation = new AtomicLong(1);
        byte[] frame = new byte[DiscordAudioSendHandler.FRAME_BYTES];

        handler.close();

        assertFalse(handler.offer(frame, 1, generation::get));
        assertFalse(handler.canProvide());
        assertNull(handler.provide20MsAudio());
    }
}
