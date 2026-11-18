package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackBuilderSoundPathTest {
    @Test
    void generatedSoundDefinitionsReferenceFilesInTheirOwnNamespace(@TempDir Path temp) throws Exception {
        String namespace = "mc_test_track";
        Path stereo = temp.resolve("stereo");
        Files.createDirectories(stereo);
        Files.write(stereo.resolve("00000.ogg"), new byte[] { 'O', 'g', 'g', 'S' });

        ResourcePackBuilder builder = new ResourcePackBuilder(
                temp, new ResourcePackFormat(88, 0, true));
        Path archive = builder.build(namespace, temp, 1, 1000);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry definitions = zip.getEntry("assets/" + namespace + "/sounds.json");
            assertNotNull(definitions, "sounds.json must be at the namespace root");

            String json;
            try (var input = zip.getInputStream(definitions)) {
                json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }

            assertTrue(json.contains(
                    "\"name\":\"" + namespace + ":" + namespace + "/stereo/00000\""),
                    "Sound-file names must explicitly resolve against the track namespace");
            assertNotNull(zip.getEntry("assets/" + namespace + "/sounds/"
                    + namespace + "/stereo/00000.ogg"),
                    "The referenced OGG file must exist at the corresponding ZIP path");
        }
    }
}
