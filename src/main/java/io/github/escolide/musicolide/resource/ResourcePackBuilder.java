package io.github.escolide.musicolide.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ResourcePackBuilder {
    private static final String LEGACY_LIBRARY_FILE = "musicolide-base.zip";
    private static final String LIBRARY_FILE_PREFIX = "musicolide-library-";
    private static final int ZIP_COMPRESSION_LEVEL = Deflater.BEST_COMPRESSION;
    private static final UUID LIBRARY_ID = UUID.nameUUIDFromBytes(
            "musicolide:java:base-pack".getBytes(StandardCharsets.UTF_8));

    private final Path packsDirectory;
    private final ResourcePackFormat format;
    private final BedrockResourcePackBuilder bedrockBuilder;
    private volatile BasePackInfo currentBasePackInfo;

    public ResourcePackBuilder(Path dataFolder, ResourcePackFormat format) throws IOException {
        this.packsDirectory = dataFolder.resolve("packs");
        this.format = format;
        this.bedrockBuilder = new BedrockResourcePackBuilder(dataFolder);
        Files.createDirectories(packsDirectory);
    }

    /**
     * Builds one Java resource pack containing the complete immutable track
     * library. The aggregate pack is retained for the default delivery mode and
     * for explicit full-library downloads.
     *
     * Identical OGG payloads are stored once. Per-track sounds.json files are
     * rewritten to reference the first identical asset, preserving each track's
     * independent sound key and namespace.
     *
     * Every entry gets a fixed timestamp and the ZIP uses maximum DEFLATE
     * compression. This makes an unchanged library produce the same SHA-1,
     * allowing vanilla clients to reuse the exact cached revision.
     */
    public synchronized BasePackInfo buildBasePack() throws IOException {
        Path temporary = Files.createTempFile(packsDirectory, ".musicolide-library-", ".zip");
        try {
            List<Path> archives;
            try (var stream = Files.list(packsDirectory)) {
                archives = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".zip"))
                        .filter(path -> !path.getFileName().toString().equals(LEGACY_LIBRARY_FILE))
                        .filter(path -> !path.getFileName().toString().startsWith(LIBRARY_FILE_PREFIX))
                        // Exclude the archive currently being written and per-session resume tails.
                        .filter(path -> !path.getFileName().toString().startsWith(".musicolide-library-"))
                        .filter(path -> !path.getFileName().toString().startsWith("mr_"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }

            Set<String> assetNames = new HashSet<>();
            Map<String, String> firstAudioResourceByHash = new LinkedHashMap<>();
            Map<Path, Set<String>> duplicateAudioEntries = new HashMap<>();
            Map<String, String> soundReferenceAliases = new LinkedHashMap<>();

            // Scan first so sounds.json entries can be rewritten regardless of ZIP entry order.
            for (Path archive : archives) {
                try (ZipFile source = new ZipFile(archive.toFile())) {
                    Enumeration<? extends ZipEntry> entries = source.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().startsWith("assets/")) continue;
                        String name = entry.getName();
                        validateAssetPath(archive, name);
                        if (!assetNames.add(name)) {
                            throw new IOException("Duplicate resource-pack asset while building the Java library: " + name);
                        }

                        String resourceLocation = soundResourceLocation(name);
                        if (resourceLocation == null) continue;
                        String digest;
                        try (InputStream input = source.getInputStream(entry)) {
                            digest = digest(input, "SHA-256");
                        }
                        String canonical = firstAudioResourceByHash.putIfAbsent(digest, resourceLocation);
                        if (canonical != null) {
                            duplicateAudioEntries.computeIfAbsent(archive, ignored -> new HashSet<>()).add(name);
                            soundReferenceAliases.put(resourceLocation, canonical);
                        }
                    }
                } catch (java.util.zip.ZipException ex) {
                    throw new IOException("Invalid track resource pack: " + archive.getFileName(), ex);
                }
            }

            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                zip.setLevel(ZIP_COMPRESSION_LEVEL);
                add(zip, "pack.mcmeta", format.toPackMcmeta().getBytes(StandardCharsets.UTF_8));

                for (Path archive : archives) {
                    try (ZipFile source = new ZipFile(archive.toFile())) {
                        Enumeration<? extends ZipEntry> entries = source.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            if (entry.isDirectory() || !entry.getName().startsWith("assets/")) continue;
                            String name = entry.getName();
                            if (duplicateAudioEntries.getOrDefault(archive, Set.of()).contains(name)) continue;

                            if (name.endsWith("/sounds.json")) {
                                String json;
                                try (InputStream input = source.getInputStream(entry)) {
                                    json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                                }
                                for (Map.Entry<String, String> alias : soundReferenceAliases.entrySet()) {
                                    // The generated sound definitions store resource locations as JSON strings.
                                    json = json.replace("\"" + alias.getKey() + "\"",
                                            "\"" + alias.getValue() + "\"");
                                }
                                add(zip, name, json.getBytes(StandardCharsets.UTF_8));
                                continue;
                            }

                            ZipEntry copy = new ZipEntry(name);
                            copy.setTime(0L);
                            zip.putNextEntry(copy);
                            try (InputStream input = source.getInputStream(entry)) {
                                input.transferTo(zip);
                            }
                            zip.closeEntry();
                        }
                    } catch (java.util.zip.ZipException ex) {
                        throw new IOException("Invalid track resource pack: " + archive.getFileName(), ex);
                    }
                }
            }

            String hash = sha1(temporary);
            String fileName = LIBRARY_FILE_PREFIX + hash + ".zip";
            Path output = packsDirectory.resolve(fileName);
            if (Files.exists(output)) {
                Files.deleteIfExists(temporary);
            } else {
                try {
                    Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, output);
                }
            }
            BasePackInfo info = new BasePackInfo(fileName, LIBRARY_ID, hash);
            currentBasePackInfo = info;
            return info;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateAssetPath(Path archive, String name) throws IOException {
        if (name.startsWith("/") || name.contains("../") || name.contains("\\")
                || name.contains("/..")) {
            throw new IOException("Unsafe asset path in " + archive.getFileName() + ": " + name);
        }
    }

    /**
     * Converts assets/<namespace>/sounds/<path>.ogg into the corresponding
     * Minecraft resource location. Non-sound OGG assets are left untouched.
     */
    private static String soundResourceLocation(String entryName) {
        if (!entryName.endsWith(".ogg")) return null;
        String[] parts = entryName.split("/", 4);
        if (parts.length != 4 || !parts[0].equals("assets") || !parts[2].equals("sounds")
                || parts[1].isBlank() || parts[3].isBlank()) return null;
        String soundPath = parts[3].substring(0, parts[3].length() - 4);
        return soundPath.isBlank() ? null : parts[1] + ":" + soundPath;
    }

    /**
     * Deletes immutable library revisions that are neither current nor still
     * referenced by a client download. Download URLs always point at a
     * content-specific filename, so publishing a new library cannot overwrite
     * bytes being fetched for the previous revision.
     */
    public synchronized void cleanupLibraryArchives(Set<String> retainedHashes) throws IOException {
        Set<String> keep = new HashSet<>(retainedHashes == null ? Set.of() : retainedHashes);
        BasePackInfo current = currentBasePackInfo;
        if (current != null) keep.add(current.sha1());
        try (var files = Files.list(packsDirectory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.equals(LEGACY_LIBRARY_FILE)) {
                    Files.deleteIfExists(path);
                    continue;
                }
                if (!name.startsWith(LIBRARY_FILE_PREFIX) || !name.endsWith(".zip")) continue;
                String hash = name.substring(LIBRARY_FILE_PREFIX.length(), name.length() - 4);
                if (!keep.contains(hash)) Files.deleteIfExists(path);
            }
        }
    }

    public BasePackInfo currentBasePackInfo() {
        BasePackInfo info = currentBasePackInfo;
        if (info == null) throw new IllegalStateException("The Java music library pack has not been built yet.");
        return info;
    }

    public record BasePackInfo(String fileName, UUID id, String sha1) {}

    public Path build(String namespace, Path trackDirectory, int segmentCount, int segmentMs) throws IOException {
        Path output = packsDirectory.resolve(namespace + ".zip");
        Map<String, String> sounds = new LinkedHashMap<>();
        for (int i = 0; i < segmentCount; i++) {
            String id = String.format(Locale.ROOT, "%05d", i);
            sounds.put("s_" + id, namespace + "/stereo/" + id);
        }

        StringBuilder soundsJson = new StringBuilder("{\n");
        int index = 0;
        for (Map.Entry<String, String> entry : sounds.entrySet()) {
            soundsJson.append("  \"").append(entry.getKey()).append("\": {\"sounds\":[{\"name\":\"")
                    .append(namespace).append(':').append(entry.getValue()).append("\",\"stream\":false}]}");
            if (++index < sounds.size()) soundsJson.append(',');
            soundsJson.append('\n');
        }
        soundsJson.append("}\n");

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            zip.setLevel(ZIP_COMPRESSION_LEVEL);
            add(zip, "pack.mcmeta", format.toPackMcmeta().getBytes(StandardCharsets.UTF_8));
            add(zip, "assets/" + namespace + "/sounds.json", soundsJson.toString().getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < segmentCount; i++) {
                String id = String.format(Locale.ROOT, "%05d", i);
                addFile(zip, "assets/" + namespace + "/sounds/" + namespace + "/stereo/" + id + ".ogg",
                        trackDirectory.resolve("stereo").resolve(id + ".ogg"));
            }
        }
        return output;
    }

    public Path buildStreamed(String namespace, Path audioFile) throws IOException {
        if (!Files.isRegularFile(audioFile) || Files.size(audioFile) == 0) {
            throw new IOException("The streamed OGG file is missing or empty.");
        }
        Path output = packsDirectory.resolve(namespace + ".zip");
        String soundsJson = "{\n"
                + "  \"music\": {\"sounds\":[{\"name\":\"" + namespace
                + ":music\",\"stream\":true}]}\n"
                + "}\n";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            zip.setLevel(ZIP_COMPRESSION_LEVEL);
            add(zip, "pack.mcmeta", format.toPackMcmeta().getBytes(StandardCharsets.UTF_8));
            add(zip, "assets/" + namespace + "/sounds.json", soundsJson.getBytes(StandardCharsets.UTF_8));
            addFile(zip, "assets/" + namespace + "/sounds/music.ogg", audioFile);
        }
        return output;
    }

    public synchronized Path rebuildBedrockLibrary() throws IOException {
        return bedrockBuilder.rebuild(packsDirectory);
    }

    public Path bedrockPackPath() {
        return bedrockBuilder.outputFile();
    }

    private String sha1(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return digest(input, "SHA-1");
        }
    }

    private static String digest(InputStream input, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("The Java runtime does not provide " + algorithm + ".", ex);
        }
    }

    private void addFile(ZipOutputStream zip, String name, Path file) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private void add(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
