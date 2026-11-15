package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackBuilderTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsEverySupportedReleaseFamilyToItsResourcePackFormat() {
        assertEquals("34", ResourcePackFormat.forMinecraftVersion("1.21.1-R0.1-SNAPSHOT").displayName());
        assertEquals("42", ResourcePackFormat.forMinecraftVersion("1.21.2-R0.1-SNAPSHOT").displayName());
        assertEquals("42", ResourcePackFormat.forMinecraftVersion("1.21.3-R0.1-SNAPSHOT").displayName());
        assertEquals("46", ResourcePackFormat.forMinecraftVersion("1.21.4-R0.1-SNAPSHOT").displayName());
        assertEquals("55", ResourcePackFormat.forMinecraftVersion("1.21.5-R0.1-SNAPSHOT").displayName());
        assertEquals("63", ResourcePackFormat.forMinecraftVersion("1.21.6-R0.1-SNAPSHOT").displayName());
        assertEquals("64", ResourcePackFormat.forMinecraftVersion("1.21.8-R0.1-SNAPSHOT").displayName());
        assertEquals("69.0", ResourcePackFormat.forMinecraftVersion("1.21.10-R0.1-SNAPSHOT").displayName());
        assertEquals("75.0", ResourcePackFormat.forMinecraftVersion("1.21.11-R0.1-SNAPSHOT").displayName());
        assertEquals("84.0", ResourcePackFormat.forMinecraftVersion("26.1.2.build.1-stable").displayName());
        assertEquals("88.0", ResourcePackFormat.forMinecraftVersion("26.2.build.1-stable").displayName());
        assertEquals("97.1", ResourcePackFormat.forMinecraftVersion("26.3.build.242-stable").displayName());
    }

    @Test
    void rejectsVersionsOutsideTheSupportedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourcePackFormat.forMinecraftVersion("1.20.6-R0.1-SNAPSHOT"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourcePackFormat.forMinecraftVersion("26.4.build.1-stable"));
    }

    @Test
    void writesLegacyMetadataForMinecraft1211() throws Exception {
        Path pack = buildPack("1.21.1-R0.1-SNAPSHOT");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            String json = readMetadata(zip);
            assertTrue(json.contains("\"pack_format\":34"), json);
            assertFalse(json.contains("\"min_format\""), json);
            assertSoundEntries(zip);
        }
    }

    @Test
    void writesModernMetadataForMinecraft263() throws Exception {
        Path pack = buildPack("26.3.build.242-stable");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            String json = readMetadata(zip);
            assertTrue(json.contains("\"min_format\":[97,1]"), json);
            assertTrue(json.contains("\"max_format\":97"), json);
            assertFalse(json.contains("\"pack_format\""), json);
            assertSoundEntries(zip);
        }
    }

    @Test
    void createsStreamedStereoAndMonoAssetsWithSeparateSoundEvents() throws Exception {
        Path stereo = tempDir.resolve("streamed-stereo.ogg");
        Path mono = tempDir.resolve("streamed-mono.ogg");
        Files.write(stereo, new byte[]{1, 2, 3});
        Files.write(mono, new byte[]{4, 5});
        ResourcePackBuilder builder = new ResourcePackBuilder(tempDir,
                ResourcePackFormat.forMinecraftVersion("1.21.1"));
        Path pack = builder.buildStreamed("ms_demo", stereo, mono);

        try (ZipFile zip = new ZipFile(pack.toFile())) {
            assertNotNull(zip.getEntry("assets/ms_demo/sounds/music.ogg"));
            assertNotNull(zip.getEntry("assets/ms_demo/sounds/mono.ogg"));
            String sounds = readSoundDefinitions(zip, "ms_demo");
            assertTrue(sounds.contains("music"), sounds);
            assertTrue(sounds.contains("mono"), sounds);
            assertTrue(sounds.contains("ms_demo:music"), sounds);
            assertTrue(sounds.contains("ms_demo:mono"), sounds);
        }
    }

    @Test
    void createsSegmentedStereoAndMonoAssetsWithDistinctSoundKeys() throws Exception {
        Path track = tempDir.resolve("dual-segmented");
        Files.createDirectories(track.resolve("stereo"));
        Files.createDirectories(track.resolve("mono"));
        Files.write(track.resolve("stereo/00000.ogg"), new byte[]{1, 2, 3});
        Files.write(track.resolve("mono/00000.ogg"), new byte[]{4, 5});
        ResourcePackBuilder builder = new ResourcePackBuilder(tempDir,
                ResourcePackFormat.forMinecraftVersion("1.21.1"));
        Path pack = builder.build("mc_dual", track, 1, 1000);

        try (ZipFile zip = new ZipFile(pack.toFile())) {
            assertNotNull(zip.getEntry("assets/mc_dual/sounds/mc_dual/stereo/00000.ogg"));
            assertNotNull(zip.getEntry("assets/mc_dual/sounds/mc_dual/mono/00000.ogg"));
            String sounds = readSoundDefinitions(zip, "mc_dual");
            assertTrue(sounds.contains("s_00000"), sounds);
            assertTrue(sounds.contains("m_00000"), sounds);
            assertTrue(sounds.contains("mc_dual:mc_dual/mono/00000"), sounds);
        }
    }

    @Test
    void canAddMonoAssetsToAnOlderStereoOnlyArchiveWithoutDroppingExistingFiles() throws Exception {
        Path track = tempDir.resolve("legacy-segmented");
        Files.createDirectories(track.resolve("stereo"));
        Files.write(track.resolve("stereo/00000.ogg"), new byte[]{1, 2, 3});
        ResourcePackBuilder builder = new ResourcePackBuilder(tempDir,
                ResourcePackFormat.forMinecraftVersion("1.21.1"));
        Path pack = builder.build("mc_legacy", track, 1, 1000);
        Path mono = tempDir.resolve("legacy-mono");
        Files.createDirectories(mono);
        Files.write(mono.resolve("00000.ogg"), new byte[]{7, 8, 9});

        builder.addMonoVariant("mc_legacy", pack, mono, false, 1);

        try (ZipFile zip = new ZipFile(pack.toFile())) {
            assertNotNull(zip.getEntry("pack.mcmeta"));
            assertNotNull(zip.getEntry("assets/mc_legacy/sounds/mc_legacy/stereo/00000.ogg"));
            assertNotNull(zip.getEntry("assets/mc_legacy/sounds/mc_legacy/mono/00000.ogg"));
            String sounds = readSoundDefinitions(zip, "mc_legacy");
            assertTrue(sounds.contains("s_00000"), sounds);
            assertTrue(sounds.contains("m_00000"), sounds);
        }
    }

    private String readSoundDefinitions(ZipFile zip, String namespace) throws Exception {
        ZipEntry entry = zip.getEntry("assets/" + namespace + "/sounds.json");
        assertNotNull(entry);
        try (InputStream input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path buildPack(String serverVersion) throws Exception {
        Path track = tempDir.resolve("track-" + serverVersion.replaceAll("[^A-Za-z0-9]", "_"));
        Files.createDirectories(track.resolve("stereo"));
        Files.write(track.resolve("stereo/00000.ogg"), new byte[]{1, 2, 3});
        ResourcePackBuilder builder = new ResourcePackBuilder(tempDir,
                ResourcePackFormat.forMinecraftVersion(serverVersion));
        return builder.build("mc_demo", track, 1, 1000);
    }

    private String readMetadata(ZipFile zip) throws Exception {
        ZipEntry metadata = zip.getEntry("pack.mcmeta");
        assertNotNull(metadata);
        try (InputStream input = zip.getInputStream(metadata)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertSoundEntries(ZipFile zip) throws Exception {
        assertNotNull(zip.getEntry("assets/mc_demo/sounds.json"));
        assertNotNull(zip.getEntry("assets/mc_demo/sounds/mc_demo/stereo/00000.ogg"));
        assertNull(zip.getEntry("assets/mc_demo/sounds/mc_demo/mono/00000.ogg"));
        String sounds = readSoundDefinitions(zip);
        assertTrue(sounds.contains("\"s_00000\""), sounds);
        assertFalse(sounds.contains("\"m_00000\""), sounds);
    }

    private String readSoundDefinitions(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry("assets/mc_demo/sounds.json");
        assertNotNull(entry);
        try (InputStream input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
