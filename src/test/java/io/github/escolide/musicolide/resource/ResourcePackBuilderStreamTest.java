package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackBuilderStreamTest {
    @TempDir
    Path temporary;

    @Test
    void writesOneContinuousStreamedOggAndAStreamEnabledSoundDefinition() throws Exception {
        byte[] audio = new byte[]{'O', 'g', 'g', 'S', 1, 2, 3, 4};
        Path source = temporary.resolve("music.ogg");
        Files.write(source, audio);

        ResourcePackBuilder builder = new ResourcePackBuilder(temporary,
                ResourcePackFormat.forMinecraftVersion("1.21.1-R0.1-SNAPSHOT"));
        Path pack = builder.buildStreamed("ms_stream_demo", source);

        try (ZipFile zip = new ZipFile(pack.toFile())) {
            assertNotNull(zip.getEntry("pack.mcmeta"));
            ZipEntry sound = zip.getEntry("assets/ms_stream_demo/sounds/music.ogg");
            assertNotNull(sound);
            try (InputStream input = zip.getInputStream(sound)) {
                assertArrayEquals(audio, input.readAllBytes());
            }

            ZipEntry definitions = zip.getEntry("assets/ms_stream_demo/sounds.json");
            assertNotNull(definitions);
            String json;
            try (InputStream input = zip.getInputStream(definitions)) {
                json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(json.contains("\"music\""), json);
            assertTrue(json.contains("\"name\":\"ms_stream_demo:music\""), json);
            assertTrue(json.contains("\"stream\":true"), json);
            assertNull(zip.getEntry("assets/ms_stream_demo/sounds/ms_stream_demo/stereo/00000.ogg"));
        }
    }

    @Test
    void refusesAnEmptyStreamFile() throws Exception {
        Path empty = temporary.resolve("empty.ogg");
        Files.createFile(empty);
        ResourcePackBuilder builder = new ResourcePackBuilder(temporary,
                ResourcePackFormat.forMinecraftVersion("1.21.1-R0.1-SNAPSHOT"));
        assertThrows(java.io.IOException.class, () -> builder.buildStreamed("ms_empty", empty));
    }
}
