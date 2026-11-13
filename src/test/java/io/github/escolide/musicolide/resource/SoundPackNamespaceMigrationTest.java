package io.github.escolide.musicolide.resource;

import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.storage.TrackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundPackNamespaceMigrationTest {
    @Test
    void migratesExistingPackAndUpdatesChecksumWithoutClearingTemporaryRetention(@TempDir Path temp)
            throws Exception {
        Path packs = temp.resolve("packs");
        Files.createDirectories(packs);

        String namespace = "mc_test_track";
        String fileName = namespace + ".zip";
        Path archive = packs.resolve(fileName);
        byte[] audioBytes = new byte[] {79, 103, 103, 83, 1, 2, 3, 4};
        String oldDefinitions = "{\n"
                + "  \"s_00000\": {\"sounds\":[{\"name\":\""
                + namespace + "/stereo/00000\",\"stream\":false}]}\n"
                + "}\n";

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(zip, "pack.mcmeta", "{\"pack\":{\"pack_format\":88}}".getBytes(StandardCharsets.UTF_8));
            write(zip, "assets/" + namespace + "/sounds.json", oldDefinitions.getBytes(StandardCharsets.UTF_8));
            write(zip, "assets/" + namespace + "/sounds/" + namespace + "/stereo/00000.ogg", audioBytes);
        }

        String oldSha1 = sha1(archive);
        MusicTrack track = new MusicTrack(
                "test-track-id", "Test track", "", namespace, fileName,
                oldSha1, 1000L, 1000, 1, Files.size(archive));

        try (TrackRepository repository = new TrackRepository(temp.resolve("musicolide.db"))) {
            repository.saveTemporaryTrack(track, System.currentTimeMillis() - 1000L);

            int migrated = SoundPackNamespaceMigration.migrate(
                    packs, repository, Logger.getLogger("MusicolideMigrationTest"));

            assertEquals(1, migrated);
            MusicTrack updated = repository.getTrack(track.id()).orElseThrow();
            assertNotEquals(oldSha1, updated.sha1Hex());
            assertEquals(sha1(archive), updated.sha1Hex());
            assertEquals(Files.size(archive), updated.packSizeBytes());

            // Updating the pack checksum must not turn a temporary import into
            // a permanent track by deleting its expiry row.
            assertEquals(1, repository.expiredTemporaryTracks(System.currentTimeMillis()).size());

            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry definitions = zip.getEntry("assets/" + namespace + "/sounds.json");
                assertNotNull(definitions);
                String json;
                try (InputStream input = zip.getInputStream(definitions)) {
                    json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                assertTrue(json.contains(
                        "\"name\":\"" + namespace + ":" + namespace + "/stereo/00000\""));
                assertFalse(json.contains("\"name\":\"" + namespace + "/stereo/00000\""));

                ZipEntry sound = zip.getEntry(
                        "assets/" + namespace + "/sounds/" + namespace + "/stereo/00000.ogg");
                assertNotNull(sound);
                try (InputStream input = zip.getInputStream(sound)) {
                    assertArrayEquals(audioBytes, input.readAllBytes());
                }
            }

            // The migration is idempotent and does not rewrite already-fixed packs.
            long modifiedBefore = Files.getLastModifiedTime(archive).toMillis();
            assertEquals(0, SoundPackNamespaceMigration.migrate(
                    packs, repository, Logger.getLogger("MusicolideMigrationTest")));
            assertEquals(modifiedBefore, Files.getLastModifiedTime(archive).toMillis());
        }
    }

    private static void write(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
