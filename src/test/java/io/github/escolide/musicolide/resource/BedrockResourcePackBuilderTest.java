package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BedrockResourcePackBuilderTest {
    @TempDir
    Path temporary;

    @Test
    void buildsOneBedrockLibraryPackFromJavaTrackArchives() throws Exception {
        Path packs = Files.createDirectories(temporary.resolve("packs"));
        Path sourceArchive = packs.resolve("mc_sample_track.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(sourceArchive))) {
            zip.putNextEntry(new ZipEntry("assets/mc_sample_track/sounds/mc_sample_track/stereo/00000.ogg"));
            zip.write(new byte[]{'O', 'g', 'g', 'S', 1, 2, 3});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/mc_sample_track/sounds/mc_sample_track/stereo/00001.ogg"));
            zip.write(new byte[]{'O', 'g', 'g', 'S', 4, 5, 6});
            zip.closeEntry();
        }

        BedrockResourcePackBuilder builder = new BedrockResourcePackBuilder(temporary);
        Path output = builder.rebuild(packs);
        assertNotNull(output);
        assertTrue(Files.isRegularFile(output));

        try (ZipFile bedrock = new ZipFile(output.toFile())) {
            assertNotNull(bedrock.getEntry("manifest.json"));
            assertNotNull(bedrock.getEntry("sounds/sound_definitions.json"));
            assertNotNull(bedrock.getEntry("sounds/musicolide/mc_sample_track/00000.ogg"));
            assertNotNull(bedrock.getEntry("sounds/musicolide/mc_sample_track/00001.ogg"));

            String definitions;
            try (var input = bedrock.getInputStream(bedrock.getEntry("sounds/sound_definitions.json"))) {
                definitions = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(definitions.contains("\"mc_sample_track:s_00000\""));
            assertTrue(definitions.contains("\"mc_sample_track:s_00001\""));
            assertTrue(definitions.contains("\"category\": \"music\""));

            String manifest;
            try (var input = bedrock.getInputStream(bedrock.getEntry("manifest.json"))) {
                manifest = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(manifest.contains("\"type\": \"resources\""));
            assertTrue(manifest.contains("\"format_version\": 2"));
        }
    }

    @Test
    void includesSingleFileStreamAssetsInBedrockLibrary() throws Exception {
        Path packs = Files.createDirectories(temporary.resolve("stream-packs"));
        Path sourceArchive = packs.resolve("ms_stream_track.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(sourceArchive))) {
            zip.putNextEntry(new ZipEntry("assets/ms_stream_track/sounds.json"));
            zip.write("{\"music\":{\"sounds\":[{\"name\":\"ms_stream_track:music\",\"stream\":true}]}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/ms_stream_track/sounds/music.ogg"));
            zip.write(new byte[]{'O', 'g', 'g', 'S', 9, 8, 7});
            zip.closeEntry();
        }

        BedrockResourcePackBuilder builder = new BedrockResourcePackBuilder(temporary);
        Path output = builder.rebuild(packs);
        assertNotNull(output);
        try (ZipFile bedrock = new ZipFile(output.toFile())) {
            assertNotNull(bedrock.getEntry("sounds/musicolide/ms_stream_track/music.ogg"));
            String definitions;
            try (var input = bedrock.getInputStream(bedrock.getEntry("sounds/sound_definitions.json"))) {
                definitions = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(definitions.contains("\"ms_stream_track:music\""));
        }
    }

    @Test
    void removesOldPackWhenThereAreNoTrackArchives() throws Exception {
        Path packs = Files.createDirectories(temporary.resolve("empty-packs"));
        BedrockResourcePackBuilder builder = new BedrockResourcePackBuilder(temporary);
        Files.createDirectories(builder.outputFile().getParent());
        Files.writeString(builder.outputFile(), "previous pack");
        Path newPack = builder.rebuild(packs);
        assertNull(newPack);
        assertFalse(Files.exists(builder.outputFile()));
    }
    @Test
    void deduplicatesIdenticalAudioAndKeepsUnchangedBedrockPackStable() throws Exception {
        Path packs = Files.createDirectories(temporary.resolve("dedupe-packs"));
        byte[] sharedAudio = new byte[]{'O', 'g', 'g', 'S', 11, 22, 33, 44};
        writeStreamArchive(packs.resolve("ms_alpha.zip"), "ms_alpha", sharedAudio);
        writeStreamArchive(packs.resolve("ms_beta.zip"), "ms_beta", sharedAudio);

        BedrockResourcePackBuilder builder = new BedrockResourcePackBuilder(temporary);
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
            assertTrue(definitions.contains("\\\"ms_alpha:music\\\""));
            assertTrue(definitions.contains("\\\"ms_beta:music\\\""));
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
            zip.putNextEntry(new ZipEntry("assets/" + namespace + "/sounds.json"));
            String definitions = "{\\\"music\\\":{\\\"sounds\\\":[{\\\"name\\\":\\\""
                    + namespace + ":music\\\",\\\"stream\\\":true}]}}";
            zip.write(definitions.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/" + namespace + "/sounds/music.ogg"));
            zip.write(audio);
            zip.closeEntry();
        }
    }

}
