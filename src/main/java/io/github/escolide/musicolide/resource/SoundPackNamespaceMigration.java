package io.github.escolide.musicolide.resource;

import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.storage.TrackRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * One-time migration for Java resource packs generated before sound-file names
 * were explicitly qualified with each track's namespace.
 *
 * The OGG data is copied without decoding or re-encoding. The new SHA-1 and
 * file size are written back to SQLite so Minecraft does not reuse a cached
 * copy of the old, silent pack.
 */
public final class SoundPackNamespaceMigration {
    private static final String BACKUP_SUFFIX = ".musicolide-namespace-backup";
    private static final String TEMP_PREFIX = ".musicolide-namespace-";
    private static final String TEMP_SUFFIX = ".tmp";

    private SoundPackNamespaceMigration() {
    }

    public static int migrate(Path packsDirectory, TrackRepository repository, Logger logger) {
        Path root = packsDirectory.toAbsolutePath().normalize();
        final List<MusicTrack> tracks;
        try {
            tracks = repository.allTracks(Integer.MAX_VALUE);
        } catch (SQLException ex) {
            logger.warning("Could not list tracks for the resource-pack sound-path migration: " + ex.getMessage());
            return 0;
        }

        int migrated = 0;
        for (MusicTrack track : tracks) {
            try {
                if (migrateTrack(root, repository, track, logger)) migrated++;
            } catch (Exception ex) {
                logger.warning("Could not migrate the sound paths for track '" + track.title()
                        + "' (" + track.id() + "): " + ex.getMessage());
            }
        }

        if (migrated > 0) {
            logger.info("Migrated " + migrated
                    + " existing Java resource pack(s) to namespace-qualified sound paths.");
        }
        return migrated;
    }

    private static boolean migrateTrack(Path root, TrackRepository repository, MusicTrack track, Logger logger)
            throws IOException, SQLException {
        Path archive = root.resolve(track.packFileName()).toAbsolutePath().normalize();
        if (!archive.startsWith(root) || !root.equals(archive.getParent())) {
            throw new IOException("The track pack path is outside the configured packs directory.");
        }

        Path backup = archive.resolveSibling(archive.getFileName() + BACKUP_SUFFIX);
        recoverInterruptedMigration(archive, backup, track.sha1Hex());

        if (!Files.isRegularFile(archive)) return false;

        Path temporary = Files.createTempFile(root, TEMP_PREFIX, TEMP_SUFFIX);
        try {
            if (!rewriteSoundDefinitions(archive, temporary, track.namespace())) return false;

            String migratedSha1 = sha1(temporary);
            long migratedSize = Files.size(temporary);

            // Keep the old archive until both the replacement and its catalogue
            // checksum have been committed. A backup is recoverable on startup.
            move(archive, backup);
            try {
                move(temporary, archive);
                repository.updatePackMetadata(track.id(), migratedSha1, migratedSize);
            } catch (Exception ex) {
                try {
                    Files.deleteIfExists(archive);
                    if (Files.isRegularFile(backup)) move(backup, archive);
                } catch (Exception rollbackError) {
                    ex.addSuppressed(rollbackError);
                }
                throw ex;
            }

            try {
                Files.deleteIfExists(backup);
            } catch (IOException ex) {
                // The new archive and database metadata agree. A leftover backup
                // is safely removed by recoverInterruptedMigration next startup.
                logger.warning("Migrated track '" + track.title()
                        + "' but could not remove its temporary backup: " + ex.getMessage());
            }
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void recoverInterruptedMigration(Path archive, Path backup, String expectedSha1)
            throws IOException {
        if (!Files.exists(backup)) return;

        boolean archiveMatches = Files.isRegularFile(archive)
                && sha1(archive).equalsIgnoreCase(expectedSha1 == null ? "" : expectedSha1);
        if (archiveMatches) {
            // The database points at the current archive: the previous migration
            // completed and only its backup cleanup was interrupted.
            Files.deleteIfExists(backup);
            return;
        }

        boolean backupMatches = Files.isRegularFile(backup)
                && sha1(backup).equalsIgnoreCase(expectedSha1 == null ? "" : expectedSha1);
        if (backupMatches) {
            // The database still points at the old archive: restore it and retry
            // the migration cleanly on this startup.
            Files.deleteIfExists(archive);
            move(backup, archive);
            return;
        }

        throw new IOException("A previous sound-pack migration left a backup whose checksum "
                + "does not match the catalogue; no file was overwritten.");
    }

    private static boolean rewriteSoundDefinitions(Path source, Path destination, String namespace)
            throws IOException {
        String definitionsPath = "assets/" + namespace + "/sounds.json";
        String oldPrefix = "\"name\":\"" + namespace + "/stereo/";
        String newPrefix = "\"name\":\"" + namespace + ":" + namespace + "/stereo/";
        boolean[] changed = {false};

        try (ZipFile inputZip = new ZipFile(source.toFile());
             ZipOutputStream outputZip = new ZipOutputStream(Files.newOutputStream(destination))) {
            Enumeration<? extends ZipEntry> entries = inputZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry inputEntry = entries.nextElement();
                ZipEntry outputEntry = new ZipEntry(inputEntry.getName());
                if (inputEntry.getTime() >= 0) outputEntry.setTime(inputEntry.getTime());
                outputZip.putNextEntry(outputEntry);

                if (!inputEntry.isDirectory() && inputEntry.getName().equals(definitionsPath)) {
                    byte[] original;
                    try (InputStream stream = inputZip.getInputStream(inputEntry)) {
                        original = stream.readAllBytes();
                    }
                    String json = new String(original, StandardCharsets.UTF_8);
                    String qualified = json.replace(oldPrefix, newPrefix);
                    if (!qualified.equals(json)) changed[0] = true;
                    outputZip.write(qualified.getBytes(StandardCharsets.UTF_8));
                } else if (!inputEntry.isDirectory()) {
                    try (InputStream stream = inputZip.getInputStream(inputEntry)) {
                        stream.transferTo(outputZip);
                    }
                }
                outputZip.closeEntry();
            }
        }

        if (!changed[0]) {
            Files.deleteIfExists(destination);
            return false;
        }
        return true;
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("The Java runtime does not provide SHA-1.", ex);
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
