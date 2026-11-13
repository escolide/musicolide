package io.github.escolide.musicolide.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TemporaryStorageCleanerTest {
    @TempDir
    Path dataDirectory;

    @Test
    void removesOnlyKnownAbandonedProcessingData() throws Exception {
        Path incoming = Files.createDirectories(dataDirectory.resolve("incoming"));
        Path urlImport = Files.createDirectories(incoming.resolve(".musicolide-url-abandoned"));
        Files.writeString(urlImport.resolve("source.webm"), "temporary");
        Path userUpload = Files.writeString(incoming.resolve("keep-this.mp3"), "user upload");

        Path tracks = Files.createDirectories(dataDirectory.resolve("tracks"));
        Path segments = Files.createDirectories(tracks.resolve("unfinished-track/stereo"));
        Files.writeString(segments.resolve("00000.ogg"), "temporary");

        Path temp = Files.createDirectories(dataDirectory.resolve("temp"));
        Files.writeString(temp.resolve(".tool-check-123.log"), "temporary");

        Path probe = Files.writeString(dataDirectory.resolve(".musicolide-probe-abandoned.txt"), "temporary");
        Path packs = Files.createDirectories(dataDirectory.resolve("packs"));
        Path retainedPack = Files.writeString(packs.resolve("persistent-track.zip"), "keep");

        TemporaryStorageCleaner.Report report = TemporaryStorageCleaner.clean(dataDirectory);

        assertEquals(4, report.removedEntries());
        assertTrue(report.failures().isEmpty());
        assertTrue(Files.exists(userUpload));
        assertTrue(Files.exists(retainedPack));
        assertFalse(Files.exists(urlImport));
        assertFalse(Files.exists(tracks.resolve("unfinished-track")));
        assertFalse(Files.exists(temp.resolve(".tool-check-123.log")));
        assertFalse(Files.exists(probe));
    }

    @Test
    void toleratesMissingTemporaryDirectories() {
        TemporaryStorageCleaner.Report report = TemporaryStorageCleaner.clean(dataDirectory);

        assertEquals(0, report.removedEntries());
        assertTrue(report.failures().isEmpty());
    }
}
