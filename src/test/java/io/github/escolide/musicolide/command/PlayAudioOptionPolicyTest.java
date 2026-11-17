package io.github.escolide.musicolide.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayAudioOptionPolicyTest {
    @Test
    void fadeRequiresMonoAndExplainsHowToFixTheCommand() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PlayAudioOptionPolicy.requireMonoForFade(true, "stereo"));

        assertTrue(error.getMessage().contains("mode:mono"));
    }

    @Test
    void fadeAllowsMonoModeCaseInsensitively() {
        assertDoesNotThrow(() -> PlayAudioOptionPolicy.requireMonoForFade(true, "MONO"));
    }

    @Test
    void commandsWithoutFadeKeepTheirSelectedAudioMode() {
        assertDoesNotThrow(() -> PlayAudioOptionPolicy.requireMonoForFade(false, "stereo"));
        assertDoesNotThrow(() -> PlayAudioOptionPolicy.requireMonoForFade(false, "mono"));
    }

    @Test
    void loopOptionParsesTrueAndFalseCaseInsensitively() {
        assertTrue(PlayAudioOptionPolicy.parseLoop("true"));
        assertTrue(PlayAudioOptionPolicy.parseLoop("TRUE"));
        assertFalse(PlayAudioOptionPolicy.parseLoop("false"));
        assertFalse(PlayAudioOptionPolicy.parseLoop("False"));
    }

    @Test
    void loopOptionRejectsValuesOtherThanTrueOrFalse() {
        assertThrows(IllegalArgumentException.class, () -> PlayAudioOptionPolicy.parseLoop("yes"));
        assertThrows(IllegalArgumentException.class, () -> PlayAudioOptionPolicy.parseLoop(""));
    }
}
