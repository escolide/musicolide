package io.github.escolide.musicolide.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ResourcePackBuilder {
    private final Path packsDirectory;
    private final ResourcePackFormat format;
    private final BedrockResourcePackBuilder bedrockBuilder;

    public ResourcePackBuilder(Path dataFolder, ResourcePackFormat format) throws IOException {
        this.packsDirectory = dataFolder.resolve("packs");
        this.format = format;
        this.bedrockBuilder = new BedrockResourcePackBuilder(dataFolder);
        Files.createDirectories(packsDirectory);
    }

    public BasePackInfo buildBasePack() throws IOException {
        String fileName = "musicolide-base.zip";
        Path output = packsDirectory.resolve(fileName);
        String metadata = format.toPackMcmeta();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            ZipEntry entry = new ZipEntry("pack.mcmeta");
            entry.setTime(0L);
            zip.putNextEntry(entry);
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(output)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            UUID id = UUID.nameUUIDFromBytes("musicolide:java:base-pack".getBytes(StandardCharsets.UTF_8));
            return new BasePackInfo(fileName, id, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("The Java runtime does not provide SHA-1.", ex);
        }
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
                    .append(entry.getValue()).append("\",\"stream\":false}]}");
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

    public synchronized Path rebuildBedrockLibrary() throws IOException {
        return bedrockBuilder.rebuild(packsDirectory);
    }

    public Path bedrockPackPath() {
        return bedrockBuilder.outputFile();
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
