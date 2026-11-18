package io.github.escolide.musicolide.discord;

import moe.kyokobot.libdave.NativeDaveFactory;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DiscordDaveRuntimeTest {
    @Test
    void loadsBundledDaveNativeOnLinuxX64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        assumeTrue(os.contains("linux")
                        && (architecture.equals("amd64") || architecture.equals("x86_64")),
                "The production DAVE native bundled by this build targets Linux x86_64.");

        assertDoesNotThrow(NativeDaveFactory::ensureAvailable);
    }
}
