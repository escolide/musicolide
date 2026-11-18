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
    @Test
    void deduplicatesIdenticalAudioAndKeepsUnchangedBedrockPackStable() throws Exception {
        Path packs = Files.createDirectories(tempDir.resolve("dedupe-packs"));
        byte[] sharedAudio = new byte[]{'O', 'g', 'g', 'S', 11, 22, 33, 44};
        writeStreamArchive(packs.resolve("ms_alpha.zip"), "ms_alpha", sharedAudio);
        writeStreamArchive(packs.resolve("ms_beta.zip"), "ms_beta", sharedAudio);

        BedrockResourcePackBuilder builder = new BedrockResourcePackBuilder(tempDir);
        Path output = builder.rebuild(packs);
        assertNotNull(output);

        byte[] firstBuild = Files.readAllBytes(output);
        try (ZipFile bedrock = new ZipFile(output.toFile())) {
            assertNotNull(bedrock.getEntry("sounds/musicolide/ms_alpha/music.ogg"));
            assertNull(bedrock.getEntry("sounds/musicolide/ms_beta/music.ogg"),
                    "the second sound should reuse the first identical OGG asset");

            int oggCount = 0;
            var entries = bedrock.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().endsWith(".ogg")) oggCount++;
            }
            assertEquals(1, oggCount);

            String definitions;
            try (var input = bedrock.getInputStream(bedrock.getEntry("sounds/sound_definitions.json"))) {
                definitions = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(definitions.contains("\"ms_alpha:music\""));
            assertTrue(definitions.contains("\"ms_beta:music\""));
            assertTrue(definitions.contains("sounds/musicolide/ms_alpha/music"));
        }

        Path unchanged = builder.rebuild(packs);
        assertEquals(output, unchanged);
        assertArrayEquals(firstBuild, Files.readAllBytes(unchanged),
                "an unchanged Bedrock library should not receive a new manifest version or ZIP");

        writeStreamArchive(packs.resolve("ms_gamma.zip"), "ms_gamma",
                new byte[]{'O', 'g', 'g', 'S', 55, 66, 77, 88});
        Path changed = builder.rebuild(packs);
        assertNotNull(changed);
        assertFalse(java.util.Arrays.equals(firstBuild, Files.readAllBytes(changed)),
                "adding a new track must publish a changed Bedrock pack");
    }

    private void writeStreamArchive(Path archive, String namespace, byte[] audio) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("assets/" + namespace + "/sounds/music.ogg"));
            zip.write(audio);
            zip.closeEntry();
        }
    }

}
