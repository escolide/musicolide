package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void streamedPackKeepsFullMonoStreamAndAddsMonoFadeSegments() throws Exception {
        ResourcePackBuilder builder = new ResourcePackBuilder(temporaryDirectory,
                new ResourcePackFormat(34, 0, false));
        Path stereo = audioFile("stereo.ogg");
        Path mono = audioFile("mono.ogg");
        Path segments = temporaryDirectory.resolve("mono-segments");
        Files.createDirectories(segments);
        audioFile(segments.resolve("00000.ogg"));
        audioFile(segments.resolve("00001.ogg"));

        Path pack = builder.buildStreamed("ms_fade_test", stereo, mono, segments, 2);
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            String sounds = new String(zip.getInputStream(zip.getEntry("assets/ms_fade_test/sounds.json"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sounds.contains("\"music\""));
            assertTrue(sounds.contains("\"mono\""));
            assertTrue(sounds.contains("\"m_00000\""));
            assertTrue(sounds.contains("\"m_00001\""));
            assertTrue(sounds.contains("\"stream\":false"));
            assertNotNull(zip.getEntry("assets/ms_fade_test/sounds/mono.ogg"));
            assertNotNull(zip.getEntry("assets/ms_fade_test/sounds/ms_fade_test/mono/00000.ogg"));
            assertNotNull(zip.getEntry("assets/ms_fade_test/sounds/ms_fade_test/mono/00001.ogg"));
        }
    }

    @Test
    void lazyUpgradeAddsMonoChunksWithoutDroppingFullStream() throws Exception {
        ResourcePackBuilder builder = new ResourcePackBuilder(temporaryDirectory,
                new ResourcePackFormat(34, 0, false));
        Path stereo = audioFile("upgrade-stereo.ogg");
        Path initialMono = audioFile("upgrade-mono-source.ogg");
        Path pack = builder.buildStreamed("ms_upgrade_test", stereo, initialMono);
        Path monoDirectory = temporaryDirectory.resolve("upgrade-mono");
        Files.createDirectories(monoDirectory);
        audioFile(monoDirectory.resolve("mono.ogg"));
        audioFile(monoDirectory.resolve("00000.ogg"));
        audioFile(monoDirectory.resolve("00001.ogg"));

        builder.addMonoVariant("ms_upgrade_test", pack, monoDirectory, true, 2);
        // Re-running a partial migration must replace, not duplicate, the JSON events.
        builder.addMonoVariant("ms_upgrade_test", pack, monoDirectory, true, 2);
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            String sounds = new String(zip.getInputStream(zip.getEntry("assets/ms_upgrade_test/sounds.json"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sounds.contains("\"music\""));
            assertTrue(sounds.contains("\"mono\""));
            assertTrue(sounds.contains("\"m_00000\""));
            assertTrue(sounds.contains("\"m_00001\""));
            assertEquals(1, sounds.split("\"mono\":", -1).length - 1);
            assertEquals(1, sounds.split("\"m_00000\":", -1).length - 1);
            assertEquals(1, sounds.split("\"m_00001\":", -1).length - 1);
            assertNotNull(zip.getEntry("assets/ms_upgrade_test/sounds/mono.ogg"));
            assertNotNull(zip.getEntry("assets/ms_upgrade_test/sounds/ms_upgrade_test/mono/00000.ogg"));
            assertNotNull(zip.getEntry("assets/ms_upgrade_test/sounds/ms_upgrade_test/mono/00001.ogg"));
        }
    }

    private Path audioFile(String name) throws Exception {
        return audioFile(temporaryDirectory.resolve(name));
    }

    private Path audioFile(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[]{'O', 'g', 'g', 'S', 1, 2, 3});
        return path;
    }
}
