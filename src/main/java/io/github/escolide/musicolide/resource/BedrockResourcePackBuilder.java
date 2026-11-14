package io.github.escolide.musicolide.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rebuilds one stable Bedrock resource pack from the immutable per-track Java
 * packs. The stable UUID plus increasing version lets Bedrock refresh its
 * cached pack after a reconnect without creating one pack per playback.
 */
public final class BedrockResourcePackBuilder {
    private static final UUID HEADER_UUID = UUID.nameUUIDFromBytes(
            "musicolide:bedrock-library".getBytes(StandardCharsets.UTF_8));
    private static final UUID MODULE_UUID = UUID.nameUUIDFromBytes(
            "musicolide:bedrock-library:resources".getBytes(StandardCharsets.UTF_8));
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_]{1,48}");
    private static final Pattern SEGMENT = Pattern.compile("[0-9]{5}");

    private final Path outputDirectory;
    private final Path outputFile;

    public BedrockResourcePackBuilder(Path dataDirectory) {
        this.outputDirectory = dataDirectory.resolve("bedrock");
        this.outputFile = outputDirectory.resolve("musicolide.mcpack");
    }

    /**
     * Rebuilds the Bedrock library. Returns null when no Java track packs exist.
     * The existing published file is replaced atomically only after the new
     * archive has been completely written.
     */
    public synchronized Path rebuild(Path javaPacksDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        List<SoundAsset> assets = collectAssets(javaPacksDirectory);
        if (assets.isEmpty()) {
            Files.deleteIfExists(outputFile);
            return null;
        }

        Path temporary = Files.createTempFile(outputDirectory, ".musicolide-bedrock-", ".tmp");
        try {
            String definitions = createSoundDefinitions(assets);
            String manifest = createManifest();
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                add(zip, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
                add(zip, "sounds/sound_definitions.json", definitions.getBytes(StandardCharsets.UTF_8));
                for (SoundAsset asset : assets) {
                    try (ZipFile sourceZip = new ZipFile(asset.archive().toFile())) {
                        ZipEntry sourceEntry = sourceZip.getEntry(asset.entryName());
                        if (sourceEntry == null) {
                            throw new IOException("A source sound asset disappeared while building the Bedrock pack.");
                        }
                        zip.putNextEntry(new ZipEntry(asset.bedrockPath()));
                        try (InputStream input = sourceZip.getInputStream(sourceEntry)) {
                            input.transferTo(zip);
                        }
                        zip.closeEntry();
                    }
                }
            }

            try {
                Files.move(temporary, outputFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, outputFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return outputFile;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path outputFile() {
        return outputFile;
    }

    private List<SoundAsset> collectAssets(Path javaPacksDirectory) throws IOException {
        if (!Files.isDirectory(javaPacksDirectory)) return List.of();
        List<SoundAsset> assets = new ArrayList<>();
        try (var files = Files.list(javaPacksDirectory)) {
            for (Path archive : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        SoundAsset asset = toAsset(archive, entry.getName());
                        if (asset != null) assets.add(asset);
                    }
                } catch (java.util.zip.ZipException ex) {
                    throw new IOException("Invalid Java resource pack archive: " + archive.getFileName(), ex);
                }
            }
        }
        assets.sort(Comparator.comparing(SoundAsset::soundId));
        return List.copyOf(assets);
    }

    private SoundAsset toAsset(Path archive, String entryName) {
        String[] parts = entryName.split("/");
        if (parts.length == 4 && parts[0].equals("assets") && parts[2].equals("sounds")
                && NAMESPACE.matcher(parts[1]).matches() && parts[3].equals("music.ogg")) {
            return new SoundAsset(archive, entryName, parts[1] + ":music",
                    "sounds/musicolide/" + parts[1] + "/music.ogg");
        }

        if (parts.length != 6 || !parts[0].equals("assets") || !parts[2].equals("sounds")
                || !parts[1].equals(parts[3]) || !parts[4].equals("stereo")
                || !NAMESPACE.matcher(parts[1]).matches() || !parts[5].endsWith(".ogg")) return null;
        String segment = parts[5].substring(0, parts[5].length() - 4);
        if (!SEGMENT.matcher(segment).matches()) return null;
        String soundId = parts[1] + ":s_" + segment;
        String bedrockPath = "sounds/musicolide/" + parts[1] + "/" + segment + ".ogg";
        return new SoundAsset(archive, entryName, soundId, bedrockPath);
    }

    private String createSoundDefinitions(List<SoundAsset> assets) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"format_version\": \"1.14.0\",\n  \"sound_definitions\": {\n");
        for (int i = 0; i < assets.size(); i++) {
            SoundAsset asset = assets.get(i);
            String name = asset.bedrockPath().substring(0, asset.bedrockPath().length() - 4);
            json.append("    \"").append(asset.soundId()).append("\": {")
                    .append("\"category\": \"music\", ")
                    .append("\"sounds\": [\"").append(name).append("\"]}");
            if (i + 1 < assets.size()) json.append(',');
            json.append('\n');
        }
        json.append("  }\n}\n");
        return json.toString();
    }

    private String createManifest() {
        int patchVersion = (int) Math.max(1L, System.currentTimeMillis() / 1000L);
        return """
                {
                  "format_version": 2,
                  "header": {
                    "name": "Musicolide Bedrock Music Library",
                    "description": "Custom sounds for Musicolide",
                    "uuid": "%s",
                    "version": [1, 0, %d],
                    "min_engine_version": [1, 20, 0]
                  },
                  "modules": [
                    {
                      "type": "resources",
                      "description": "Musicolide custom sound assets",
                      "uuid": "%s",
                      "version": [1, 0, %d]
                    }
                  ]
                }
                """.formatted(HEADER_UUID, patchVersion, MODULE_UUID, patchVersion);
    }

    private void add(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private record SoundAsset(Path archive, String entryName, String soundId, String bedrockPath) {}
}
