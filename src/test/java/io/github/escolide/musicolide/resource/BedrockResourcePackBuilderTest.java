package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BedrockResourcePackBuilderTest {
    @TempDir
    Path tempDir;

    @Test
    void includesStreamedStereoAndMonoAssets() throws Exception {
        Path packs = tempDir.resolve("packs");
        Files.createDirectories(packs);
        Path archive = packs.resolve("ms_demo.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            add(zip, "assets/ms_demo/sounds/music.ogg", new byte[]{1, 2, 3});
            add(zip, "assets/ms_demo/sounds/mono.ogg", new byte[]{4, 5});
        }

        Path output = new BedrockResourcePackBuilder(tempDir).rebuild(packs);

        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertNotNull(zip.getEntry("sounds/musicolide/ms_demo/music.ogg"));
            assertNotNull(zip.getEntry("sounds/musicolide/ms_demo/mono.ogg"));
            String definitions = read(zip, "sounds/sound_definitions.json");
            assertTrue(definitions.contains("ms_demo:music"), definitions);
            assertTrue(definitions.contains("ms_demo:mono"), definitions);
        }
    }

    @Test
    void includesSegmentedStereoAndMonoAssets() throws Exception {
        Path packs = tempDir.resolve("packs");
        Files.createDirectories(packs);
        Path archive = packs.resolve("mc_demo.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            add(zip, "assets/mc_demo/sounds/mc_demo/stereo/00000.ogg", new byte[]{1, 2, 3});
            add(zip, "assets/mc_demo/sounds/mc_demo/mono/00000.ogg", new byte[]{4, 5});
        }

        Path output = new BedrockResourcePackBuilder(tempDir).rebuild(packs);

        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertNotNull(zip.getEntry("sounds/musicolide/mc_demo/00000.ogg"));
            assertNotNull(zip.getEntry("sounds/musicolide/mc_demo/mono/00000.ogg"));
            String definitions = read(zip, "sounds/sound_definitions.json");
            assertTrue(definitions.contains("mc_demo:s_00000"), definitions);
            assertTrue(definitions.contains("mc_demo:m_00000"), definitions);
        }
    }

    private void add(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private String read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull(entry);
        try (var input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
