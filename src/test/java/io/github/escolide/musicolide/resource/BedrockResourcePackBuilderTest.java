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
    void removesOldPackWhenThereAreNoTrackArchives() throws Exception {
        Path packs = Files.createDirectories(temporary.resolve("empty-packs"));
        BedrockResourcePackBuilder builder = new BedrockResourcePackBuilder(temporary);
        Files.createDirectories(builder.outputFile().getParent());
        Files.writeString(builder.outputFile(), "previous pack");
        Path newPack = builder.rebuild(packs);
        assertNull(newPack);
        assertFalse(Files.exists(builder.outputFile()));
    }
}
