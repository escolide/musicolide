package io.github.escolide.musicolide.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscordAudioSendHandlerTest {
    @Test
    void providesExactlyOneJdaPcmFrameAndCountsIt() throws InterruptedException {
        DiscordAudioSendHandler handler = new DiscordAudioSendHandler();
        handler.reset(4);
        byte[] frame = new byte[DiscordAudioSendHandler.FRAME_BYTES];

        assertFalse(handler.canProvide());
        assertTrue(handler.offer(frame, 4));
        assertTrue(handler.canProvide());

        var provided = handler.provide20MsAudio();
        assertNotNull(provided);
        assertEquals(DiscordAudioSendHandler.FRAME_BYTES, provided.remaining());
        assertEquals(1L, handler.providedFrames());
        assertFalse(handler.canProvide());
    }

    @Test
    void rejectsFramesFromAnInvalidatedDecoderGeneration() throws InterruptedException {
        DiscordAudioSendHandler handler = new DiscordAudioSendHandler();
        handler.reset(9);
        byte[] frame = new byte[DiscordAudioSendHandler.FRAME_BYTES];

        assertFalse(handler.offer(frame, 8));
        assertFalse(handler.canProvide());
        assertEquals(0L, handler.providedFrames());
    }

    @Test
    void staleFrameCannotBeTransmittedAfterGenerationReset() throws InterruptedException {
        DiscordAudioSendHandler handler = new DiscordAudioSendHandler();
        handler.reset(2);
        byte[] frame = new byte[DiscordAudioSendHandler.FRAME_BYTES];

        assertTrue(handler.offer(frame, 2));
        handler.reset(3);
        assertFalse(handler.canProvide());
        assertNull(handler.provide20MsAudio());
        assertEquals(0L, handler.providedFrames());
    }

    @Test
    void closedHandlerNeverAcceptsOrProvidesAudio() throws InterruptedException {
        DiscordAudioSendHandler handler = new DiscordAudioSendHandler();
        handler.reset(1);
        byte[] frame = new byte[DiscordAudioSendHandler.FRAME_BYTES];

        handler.close();

        assertFalse(handler.offer(frame, 1));
        assertFalse(handler.canProvide());
        assertNull(handler.provide20MsAudio());
    }
}
