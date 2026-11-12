package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackBuilderTest {
    @TempDir
    Path tempDir;

    @Test
    void writesMinecraft263ResourcePackMetadata() throws Exception {
        Path track = tempDir.resolve("track");
        Files.createDirectories(track.resolve("stereo"));
        Files.createDirectories(track.resolve("mono"));
        Files.write(track.resolve("stereo/00000.ogg"), new byte[]{1, 2, 3});
        Files.write(track.resolve("mono/00000.ogg"), new byte[]{1, 2, 3});

        ResourcePackBuilder builder = new ResourcePackBuilder(tempDir, 97, 1);
        Path pack = builder.build("mc_demo", track, 1, 1000);

        try (ZipFile zip = new ZipFile(pack.toFile())) {
            ZipEntry metadata = zip.getEntry("pack.mcmeta");
            assertNotNull(metadata);
            try (InputStream input = zip.getInputStream(metadata)) {
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(json.contains("\"min_format\":[97,1]"), json);
                assertTrue(json.contains("\"max_format\":97"), json);
                assertFalse(json.contains("\"pack_format\""), json);
            }
            assertNotNull(zip.getEntry("assets/mc_demo/sounds.json"));
            assertNotNull(zip.getEntry("assets/mc_demo/sounds/mc_demo/stereo/00000.ogg"));
            assertNotNull(zip.getEntry("assets/mc_demo/sounds/mc_demo/mono/00000.ogg"));
        }
    }
}
