package io.github.escolide.musicolide.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ResourcePackBuilder {
    private final Path packsDirectory;
    private final int formatMajor;
    private final int formatMinor;

    public ResourcePackBuilder(Path dataFolder, int formatMajor, int formatMinor) throws IOException {
        if (formatMajor < 1 || formatMinor < 0) {
            throw new IllegalArgumentException("Resource pack format must have a positive major and non-negative minor version.");
        }
        this.packsDirectory = dataFolder.resolve("packs");
        this.formatMajor = formatMajor;
        this.formatMinor = formatMinor;
        Files.createDirectories(packsDirectory);
    }

    public Path build(String namespace, Path trackDirectory, int segmentCount, int segmentMs) throws IOException {
        Path output = packsDirectory.resolve(namespace + ".zip");
        Map<String, String> sounds = new LinkedHashMap<>();
        for (int i = 0; i < segmentCount; i++) {
            String id = String.format("%05d", i);
            sounds.put("s_" + id, namespace + "/stereo/" + id);
            sounds.put("m_" + id, namespace + "/mono/" + id);
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

        // 26.3 uses pack format 97.1. Since 1.21.9, min_format and max_format
        // are required and pack_format is not used for packs targeting only new formats.
        String meta = "{\"pack\":{\"min_format\":[" + formatMajor + "," + formatMinor
                + "],\"max_format\":" + formatMajor
                + ",\"description\":\"Musicolide track pack\"}}";

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            add(zip, "pack.mcmeta", meta.getBytes(StandardCharsets.UTF_8));
            add(zip, "assets/" + namespace + "/sounds.json", soundsJson.toString().getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < segmentCount; i++) {
                String id = String.format("%05d", i);
                addFile(zip, "assets/" + namespace + "/sounds/" + namespace + "/stereo/" + id + ".ogg",
                        trackDirectory.resolve("stereo").resolve(id + ".ogg"));
                addFile(zip, "assets/" + namespace + "/sounds/" + namespace + "/mono/" + id + ".ogg",
                        trackDirectory.resolve("mono").resolve(id + ".ogg"));
            }
        }
        return output;
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
