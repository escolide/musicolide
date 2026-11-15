package io.github.escolide.musicolide.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ResourcePackBuilder {
    private static final String LEGACY_LIBRARY_FILE = "musicolide-base.zip";
    private static final String LIBRARY_FILE_PREFIX = "musicolide-library-";
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
     * library. This replaces the old empty consent pack plus one pack per song,
     * so a normal player receives one library pack instead of sequential pack
     * applications for the first track.
     *
     * New imports rebuild this archive. Its stable UUID and content SHA-1 allow
     * clients to reuse an unchanged cached copy after reconnecting.
     */
    public synchronized BasePackInfo buildBasePack() throws IOException {
        Path temporary = Files.createTempFile(packsDirectory, ".musicolide-library-", ".zip");
        try {
            Set<String> writtenEntries = new HashSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                add(zip, "pack.mcmeta", format.toPackMcmeta().getBytes(StandardCharsets.UTF_8));
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

                for (Path archive : archives) {
                    try (ZipFile source = new ZipFile(archive.toFile())) {
                        Enumeration<? extends ZipEntry> entries = source.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            if (entry.isDirectory() || !entry.getName().startsWith("assets/")) continue;
                            String name = entry.getName();
                            if (name.startsWith("/") || name.contains("../") || name.contains("\")
                                    || name.contains("/..")) {
                                throw new IOException("Unsafe asset path in " + archive.getFileName() + ": " + name);
                            }
                            if (!writtenEntries.add(name)) {
                                throw new IOException("Duplicate resource-pack asset while building the Java library: " + name);
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
        boolean hasMono = Files.isDirectory(trackDirectory.resolve("mono"));
        StringBuilder soundsJson = new StringBuilder("{\n");
        int total = segmentCount * (hasMono ? 2 : 1);
        int index = 0;
        for (int i = 0; i < segmentCount; i++) {
            String id = String.format("%05d", i);
            appendSegmentSound(soundsJson, namespace, "s_" + id, namespace + "/stereo/" + id,
                    ++index < total);
            if (hasMono) {
                appendSegmentSound(soundsJson, namespace, "m_" + id, namespace + "/mono/" + id,
                        ++index < total);
            }
        }
        soundsJson.append("}\n");

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            add(zip, "pack.mcmeta", format.toPackMcmeta().getBytes(StandardCharsets.UTF_8));
            add(zip, "assets/" + namespace + "/sounds.json", soundsJson.toString().getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < segmentCount; i++) {
                String id = String.format("%05d", i);
                addFile(zip, "assets/" + namespace + "/sounds/" + namespace + "/stereo/" + id + ".ogg",
                        trackDirectory.resolve("stereo").resolve(id + ".ogg"));
                if (hasMono) {
                    addFile(zip, "assets/" + namespace + "/sounds/" + namespace + "/mono/" + id + ".ogg",
                            trackDirectory.resolve("mono").resolve(id + ".ogg"));
                }
            }
        }
        return output;
    }

    private void appendSegmentSound(StringBuilder json, String namespace, String key,
                                    String path, boolean comma) {
        json.append("  \"").append(key).append("\": {\"sounds\":[{\"name\":\"")
                .append(namespace).append(':').append(path).append("\",\"stream\":false}]}");
        if (comma) json.append(',');
        json.append('\n');
    }

    public Path buildStreamed(String namespace, Path audioFile) throws IOException {
        return buildStreamed(namespace, audioFile, null);
    }

    public Path buildStreamed(String namespace, Path audioFile, Path monoAudioFile) throws IOException {
        requireAudioFile(audioFile, "The streamed stereo OGG file is missing or empty.");
        if (monoAudioFile != null) requireAudioFile(monoAudioFile, "The streamed mono OGG file is missing or empty.");
        Path output = packsDirectory.resolve(namespace + ".zip");
        String soundsJson = "{\n"
                + "  \"music\": {\"sounds\":[{\"name\":\"" + namespace
                + ":music\",\"stream\":true}]}"
                + (monoAudioFile == null ? "" : ",\n  \"mono\": {\"sounds\":[{\"name\":\""
                + namespace + ":mono\",\"stream\":true}]}")
                + "\n}\n";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            add(zip, "pack.mcmeta", format.toPackMcmeta().getBytes(StandardCharsets.UTF_8));
            add(zip, "assets/" + namespace + "/sounds.json", soundsJson.getBytes(StandardCharsets.UTF_8));
            addFile(zip, "assets/" + namespace + "/sounds/music.ogg", audioFile);
            if (monoAudioFile != null) {
                addFile(zip, "assets/" + namespace + "/sounds/mono.ogg", monoAudioFile);
            }
        }
        return output;
    }

    private void requireAudioFile(Path file, String message) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) throw new IOException(message);
    }

    /**
     * Adds a one-channel variant to an older per-track archive without changing
     * its stereo assets or required pack metadata. The replacement is atomic.
     */
    public synchronized void addMonoVariant(String namespace, Path sourcePack, Path monoDirectory,
                                            boolean streamed, int segmentCount) throws IOException {
        if (!Files.isRegularFile(sourcePack)) throw new IOException("The source track pack is missing.");
        Path temporary = Files.createTempFile(sourcePack.getParent(), ".musicolide-mono-", ".zip");
        String soundsEntry = "assets/" + namespace + "/sounds.json";
        String monoPrefix = streamed
                ? "assets/" + namespace + "/sounds/mono.ogg"
                : "assets/" + namespace + "/sounds/" + namespace + "/mono/";
        try {
            // Finish reading and writing the archive before replacing the source.
            // This also works on Windows, where an open ZipFile can lock the file.
            try (ZipFile source = new ZipFile(sourcePack.toFile())) {
                ZipEntry sounds = source.getEntry(soundsEntry);
                if (sounds == null || sounds.isDirectory()) {
                    throw new IOException("The source track pack has no sounds.json.");
                }
                String oldSounds;
                try (InputStream input = source.getInputStream(sounds)) {
                    oldSounds = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }

                List<String> definitions = new ArrayList<>();
                if (streamed) {
                    requireAudioFile(monoDirectory.resolve("mono.ogg"), "The generated mono OGG is missing.");
                    definitions.add("  \"mono\": {\"sounds\":[{\"name\":\"" + namespace
                            + ":mono\",\"stream\":true}]}");
                } else {
                    for (int i = 0; i < segmentCount; i++) {
                        String id = String.format("%05d", i);
                        requireAudioFile(monoDirectory.resolve(id + ".ogg"),
                                "A generated mono segment is missing: " + id);
                        definitions.add("  \"m_" + id + "\": {\"sounds\":[{\"name\":\""
                                + namespace + ":" + namespace + "/mono/" + id
                                + "\",\"stream\":false}]}");
                    }
                }

                int finalBrace = oldSounds.lastIndexOf('}');
                if (finalBrace < 0) throw new IOException("The source sounds.json is invalid.");
                String prefix = oldSounds.substring(0, finalBrace).stripTrailing();
                String additions = (prefix.endsWith("}") ? ",\n" : "")
                        + String.join(",\n", definitions) + "\n";
                String updatedSounds = prefix + additions + "}\n";

                try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                    Set<String> written = new HashSet<>();
                    Enumeration<? extends ZipEntry> entries = source.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.equals(soundsEntry) || name.equals(monoPrefix)
                                || (!streamed && name.startsWith(monoPrefix))) continue;
                        if (!written.add(name)) {
                            throw new IOException("Duplicate entry in source track pack: " + name);
                        }
                        ZipEntry copy = new ZipEntry(name);
                        if (entry.getTime() >= 0) copy.setTime(entry.getTime());
                        output.putNextEntry(copy);
                        if (!entry.isDirectory()) {
                            try (InputStream input = source.getInputStream(entry)) {
                                input.transferTo(output);
                            }
                        }
                        output.closeEntry();
                    }
                    if (!written.add(soundsEntry)) {
                        throw new IOException("Could not replace the sounds metadata.");
                    }
                    add(output, soundsEntry, updatedSounds.getBytes(StandardCharsets.UTF_8));
                    if (streamed) {
                        addFile(output, monoPrefix, monoDirectory.resolve("mono.ogg"));
                    } else {
                        for (int i = 0; i < segmentCount; i++) {
                            String id = String.format("%05d", i);
                            String name = monoPrefix + id + ".ogg";
                            if (!written.add(name)) throw new IOException("Duplicate mono asset: " + name);
                            addFile(output, name, monoDirectory.resolve(id + ".ogg"));
                        }
                    }
                }
            }

            try {
                Files.move(temporary, sourcePack,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, sourcePack, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized Path rebuildBedrockLibrary() throws IOException {
        return bedrockBuilder.rebuild(packsDirectory);
    }

    public Path bedrockPackPath() {
        return bedrockBuilder.outputFile();
    }

    private String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("The Java runtime does not provide SHA-1.", ex);
        }
    }

    private void addFile(ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private void add(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
