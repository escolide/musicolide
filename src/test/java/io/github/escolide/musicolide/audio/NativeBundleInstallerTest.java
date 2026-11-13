package io.github.escolide.musicolide.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NativeBundleInstallerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsBothToolsAndMarksUnixExecutables() throws Exception {
        Path archive = createBundle("linux-x86_64", false);
        Map<NativeToolchain.Tool, String> installed = NativeBundleInstaller.extract(
                archive, temporaryDirectory.resolve("native"), "linux-x86_64");

        Path ffmpeg = Path.of(installed.get(NativeToolchain.Tool.FFMPEG));
        Path ytDlp = Path.of(installed.get(NativeToolchain.Tool.YT_DLP));
        assertEquals("fake-ffmpeg", Files.readString(ffmpeg));
        assertEquals("fake-yt-dlp", Files.readString(ytDlp));
        assertEquals("fake-qjs", Files.readString(Path.of(installed.get(NativeToolchain.Tool.JS_RUNTIME))));
        assertTrue(Files.isExecutable(ffmpeg));
        assertTrue(Files.isExecutable(ytDlp));
        assertTrue(Files.isExecutable(Path.of(installed.get(NativeToolchain.Tool.JS_RUNTIME))));
    }

    @Test
    void rejectsBundleForAnotherPlatform() throws Exception {
        Path archive = createBundle("linux-x86_64", false);
        assertThrows(java.io.IOException.class, () -> NativeBundleInstaller.extract(
                archive, temporaryDirectory.resolve("native"), "windows-x86_64"));
    }

    @Test
    void rejectsPathTraversalEntries() throws Exception {
        Path archive = createBundle("linux-x86_64", true);
        assertThrows(java.io.IOException.class, () -> NativeBundleInstaller.extract(
                archive, temporaryDirectory.resolve("native"), "linux-x86_64"));
        assertFalse(Files.exists(temporaryDirectory.resolve("escape.txt")));
    }

    private Path createBundle(String platform, boolean includeTraversal) throws Exception {
        Path archive = temporaryDirectory.resolve(platform + ".zip");
        String manifest = "platform=" + platform + "\n"
                + "ffmpeg=bin/ffmpeg\n"
                + "yt-dlp=bin/yt-dlp\n"
                + "js-runtime=bin/qjs\n";
        try (OutputStream file = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(file)) {
            put(zip, "manifest.properties", manifest);
            put(zip, "bin/ffmpeg", "fake-ffmpeg");
            put(zip, "bin/yt-dlp", "fake-yt-dlp");
            put(zip, "bin/qjs", "fake-qjs");
            if (includeTraversal) put(zip, "../escape.txt", "unsafe");
        }
        return archive;
    }

    private static void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
