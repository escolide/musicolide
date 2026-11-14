package io.github.escolide.musicolide.audio;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NativeToolDownloaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hasPinnedDownloadDefinitionsForAllSixPlatformProfiles() {
        List<String> platforms = List.of(
                "linux-x86_64", "linux-aarch64",
                "windows-x86_64", "windows-aarch64",
                "macos-x86_64", "macos-aarch64");
        for (String platform : platforms) {
            assertTrue(NativeToolDownloader.supportsPlatform(platform), platform);
        }
        assertFalse(NativeToolDownloader.supportsPlatform("freebsd-x86_64"));
    }

    @Test
    void extractsOnlyFfmpegFromWindowsZip() throws Exception {
        Path archive = temporaryDirectory.resolve("ffmpeg.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "ffmpeg-build/bin/ffmpeg.exe", "windows-ffmpeg");
            put(zip, "ffmpeg-build/bin/ffprobe.exe", "must-not-be-extracted");
        }

        Path destination = temporaryDirectory.resolve("native/bin/ffmpeg.exe");
        NativeToolDownloader.extractFfmpegArchive(
                archive, destination, NativeToolDownloader.ArchiveFormat.ZIP);

        assertEquals("windows-ffmpeg", Files.readString(destination));
        assertFalse(Files.exists(temporaryDirectory.resolve("native/bin/ffprobe.exe")));
    }

    @Test
    void extractsOnlyFfmpegFromLinuxTarXz() throws Exception {
        Path archive = temporaryDirectory.resolve("ffmpeg.tar.xz");
        createTarXz(archive, "ffmpeg-build/bin/ffmpeg", "linux-ffmpeg");

        Path destination = temporaryDirectory.resolve("native/bin/ffmpeg");
        NativeToolDownloader.extractFfmpegArchive(
                archive, destination, NativeToolDownloader.ArchiveFormat.TAR_XZ);

        assertEquals("linux-ffmpeg", Files.readString(destination));
    }

    @Test
    void extractsOnlyFfmpegFromMacOsTarGz() throws Exception {
        Path archive = temporaryDirectory.resolve("ffmpeg.tar.gz");
        createTarGz(archive, "ffmpeg", "macos-ffmpeg");

        Path destination = temporaryDirectory.resolve("native/bin/ffmpeg");
        NativeToolDownloader.extractFfmpegArchive(
                archive, destination, NativeToolDownloader.ArchiveFormat.TAR_GZ);

        assertEquals("macos-ffmpeg", Files.readString(destination));
    }

    @Test
    void rejectsArchiveWithoutFfmpegExecutable() throws Exception {
        Path archive = temporaryDirectory.resolve("missing.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "bin/ffprobe", "not-ffmpeg");
        }

        Path destination = temporaryDirectory.resolve("native/bin/ffmpeg");
        assertThrows(java.io.IOException.class, () -> NativeToolDownloader.extractFfmpegArchive(
                archive, destination, NativeToolDownloader.ArchiveFormat.ZIP));
        assertFalse(Files.exists(destination));
    }

    private static void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void createTarXz(Path archive, String entryName, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try (OutputStream file = Files.newOutputStream(archive);
             XZCompressorOutputStream xz = new XZCompressorOutputStream(file);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(xz)) {
            addTarEntry(tar, entryName, bytes);
        }
    }

    private static void createTarGz(Path archive, String entryName, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try (OutputStream file = Files.newOutputStream(archive);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(file);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            addTarEntry(tar, entryName, bytes);
        }
    }

    private static void addTarEntry(TarArchiveOutputStream tar, String name, byte[] bytes) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }
}
