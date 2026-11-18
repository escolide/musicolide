package io.github.escolide.musicolide.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rebuilds one stable Bedrock resource pack from the immutable per-track Java
 * packs. The stable UUID plus a content-derived revision lets Bedrock/Geyser
 * reuse an unchanged cached pack and refresh it only when its assets change.
 */
public final class BedrockResourcePackBuilder {
    private static final UUID HEADER_UUID = UUID.nameUUIDFromBytes(
            "musicolide:bedrock-library".getBytes(StandardCharsets.UTF_8));
    private static final UUID MODULE_UUID = UUID.nameUUIDFromBytes(
            "musicolide:bedrock-library:resources".getBytes(StandardCharsets.UTF_8));
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_]{1,48}");
    private static final Pattern SEGMENT = Pattern.compile("[0-9]{5}");
    private static final int ZIP_COMPRESSION_LEVEL = Deflater.BEST_COMPRESSION;

    private final Path outputDirectory;
    private final Path outputFile;
    private final Path fingerprintFile;
    private final Path versionFile;

    public BedrockResourcePackBuilder(Path dataDirectory) {
        this.outputDirectory = dataDirectory.resolve("bedrock");
        this.outputFile = outputDirectory.resolve("musicolide.mcpack");
        this.fingerprintFile = outputDirectory.resolve(".musicolide-bedrock-fingerprint");
        this.versionFile = outputDirectory.resolve(".musicolide-bedrock-version");
    }

    /**
     * Rebuilds the Bedrock library. Returns null when no Java track packs exist.
     * Identical OGG payloads share one mcpack asset path while keeping separate
     * sound identifiers. The pack is not rewritten when its logical contents
     * are unchanged, so the manifest version and client cache remain stable.
     */
    public synchronized Path rebuild(Path javaPacksDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        List<SoundAsset> assets = deduplicateAssets(collectAssets(javaPacksDirectory));
        if (assets.isEmpty()) {
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(fingerprintFile);
            return null;
        }

        String definitions = createSoundDefinitions(assets);
        String fingerprint = fingerprint(assets, definitions);
        if (Files.isRegularFile(outputFile) && Files.isRegularFile(fingerprintFile)) {
            String previousFingerprint = Files.readString(fingerprintFile, StandardCharsets.UTF_8).trim();
            if (fingerprint.equals(previousFingerprint)) return outputFile;
        }

        int version = nextVersion();
        Path temporary = Files.createTempFile(outputDirectory, ".musicolide-bedrock-", ".tmp");
        try {
            String manifest = createManifest(version);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                zip.setLevel(ZIP_COMPRESSION_LEVEL);
                add(zip, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
                add(zip, "sounds/sound_definitions.json", definitions.getBytes(StandardCharsets.UTF_8));

                Set<String> writtenPaths = new HashSet<>();
                for (SoundAsset asset : assets) {
                    if (!writtenPaths.add(asset.bedrockPath())) continue;
                    try (ZipFile sourceZip = new ZipFile(asset.archive().toFile())) {
                        ZipEntry sourceEntry = sourceZip.getEntry(asset.entryName());
                        if (sourceEntry == null) {
                            throw new IOException("A source sound asset disappeared while building the Bedrock pack.");
                        }
                        ZipEntry targetEntry = new ZipEntry(asset.bedrockPath());
                        targetEntry.setTime(0L);
                        zip.putNextEntry(targetEntry);
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
            Files.writeString(fingerprintFile, fingerprint, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.writeString(versionFile, Integer.toString(version), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
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
                    .filter(path -> !path.getFileName().toString().equals("musicolide-base.zip"))
                    .filter(path -> !path.getFileName().toString().startsWith("musicolide-library-"))
                    .filter(path -> !path.getFileName().toString().startsWith(".musicolide-library-"))
                    // Per-session Java resume tails are never part of the stable Bedrock library.
                    .filter(path -> !path.getFileName().toString().startsWith("mr_"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        SoundAsset asset = toAsset(archive, entry.getName());
                        if (asset == null) continue;
                        String contentHash;
                        try (InputStream input = zip.getInputStream(entry)) {
                            contentHash = sha256(input);
                        }
                        assets.add(new SoundAsset(asset.archive(), asset.entryName(), asset.soundId(),
                                asset.bedrockPath(), contentHash));
                    }
                } catch (java.util.zip.ZipException ex) {
                    throw new IOException("Invalid Java resource pack archive: " + archive.getFileName(), ex);
                }
            }
        }
        assets.sort(Comparator.comparing(SoundAsset::soundId));
        return List.copyOf(assets);
    }

    private List<SoundAsset> deduplicateAssets(List<SoundAsset> assets) {
        Map<String, String> canonicalPathByHash = new LinkedHashMap<>();
        List<SoundAsset> result = new ArrayList<>(assets.size());
        for (SoundAsset asset : assets) {
            String canonicalPath = canonicalPathByHash.putIfAbsent(asset.sha256(), asset.bedrockPath());
            result.add(new SoundAsset(asset.archive(), asset.entryName(), asset.soundId(),
                    canonicalPath == null ? asset.bedrockPath() : canonicalPath, asset.sha256()));
        }
        return List.copyOf(result);
    }

    private SoundAsset toAsset(Path archive, String entryName) {
        String[] parts = entryName.split("/");
        if (parts.length == 4 && parts[0].equals("assets") && parts[2].equals("sounds")
                && NAMESPACE.matcher(parts[1]).matches()
                && (parts[3].equals("music.ogg") || parts[3].equals("mono.ogg"))) {
            boolean mono = parts[3].equals("mono.ogg");
            return new SoundAsset(archive, entryName, parts[1] + (mono ? ":mono" : ":music"),
                    "sounds/musicolide/" + parts[1] + (mono ? "/mono.ogg" : "/music.ogg"), null);
        }

        if (parts.length != 6 || !parts[0].equals("assets") || !parts[2].equals("sounds")
                || !parts[1].equals(parts[3]) || !NAMESPACE.matcher(parts[1]).matches()
                || !parts[5].endsWith(".ogg")) return null;
        String segment = parts[5].substring(0, parts[5].length() - 4);
        if (!SEGMENT.matcher(segment).matches()) return null;
        if (!parts[4].equals("stereo") && !parts[4].equals("mono")) return null;
        boolean mono = parts[4].equals("mono");
        String soundId = parts[1] + (mono ? ":m_" : ":s_") + segment;
        String bedrockPath = "sounds/musicolide/" + parts[1] + (mono ? "/mono/" : "/") + segment + ".ogg";
        return new SoundAsset(archive, entryName, soundId, bedrockPath, null);
    }

    private String fingerprint(List<SoundAsset> assets, String definitions) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(definitions.getBytes(StandardCharsets.UTF_8));
            for (SoundAsset asset : assets) {
                digest.update((byte) 0);
                digest.update(asset.soundId().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(asset.bedrockPath().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(asset.sha256().getBytes(StandardCharsets.US_ASCII));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256.", ex);
        }
    }

    private int nextVersion() throws IOException {
        long previous = 0;
        if (Files.isRegularFile(versionFile)) {
            try {
                previous = Long.parseLong(Files.readString(versionFile, StandardCharsets.UTF_8).trim());
            } catch (NumberFormatException ignored) {
                previous = 0;
            }
        }
        long now = Math.max(1L, System.currentTimeMillis() / 1000L);
        long next = Math.max(now, previous + 1L);
        if (next > Integer.MAX_VALUE) {
            throw new IOException("The Bedrock resource-pack revision counter is exhausted.");
        }
        return (int) next;
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

    private String createManifest(int patchVersion) {
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

    private String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256.", ex);
        }
    }

    private void add(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private record SoundAsset(Path archive, String entryName, String soundId,
                              String bedrockPath, String sha256) {}
}
