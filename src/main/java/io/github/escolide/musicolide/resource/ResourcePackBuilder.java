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
    private static final String LIBRARY_FILE = "musicolide-base.zip";
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
        Path output = packsDirectory.resolve(LIBRARY_FILE);
        Path temporary = Files.createTempFile(packsDirectory, ".musicolide-library-", ".zip");
        try {
            Set<String> writtenEntries = new HashSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                add(zip, "pack.mcmeta", format.toPackMcmeta().getBytes(StandardCharsets.UTF_8));
                List<Path> archives;
                try (var stream = Files.list(packsDirectory)) {
                    archives = stream.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".zip"))
                            .filter(path -> !path.getFileName().toString().equals(LIBRARY_FILE))
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
                            if (name.startsWith("/") || name.contains("../") || name.contains("\\")
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

            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }

        BasePackInfo info = new BasePackInfo(LIBRARY_FILE, LIBRARY_ID, sha1(output));
        currentBasePackInfo = info;
        return info;
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
            String id = String.format("%05d", i);
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

        String meta = format.toPackMcmeta();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            add(zip, "pack.mcmeta", meta.getBytes(StandardCharsets.UTF_8));
            add(zip, "assets/" + namespace + "/sounds.json", soundsJson.toString().getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < segmentCount; i++) {
                String id = String.format("%05d", i);
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
