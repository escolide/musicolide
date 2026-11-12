package io.github.escolide.musicolide.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Removes only known processing intermediates left by an interrupted import.
 * Ordinary files in incoming/ and generated resource packs are deliberately
 * preserved. Files.walk does not follow symbolic links.
 */
public final class TemporaryStorageCleaner {
    private static final String URL_TEMP_PREFIX = ".musicolide-url-";
    private static final String PROBE_TEMP_PREFIX = ".musicolide-probe-";

    private TemporaryStorageCleaner() {}

    public record Report(int removedEntries, List<String> failures) {
        public Report {
            failures = List.copyOf(failures);
        }
    }

    public static Report clean(Path dataDirectory) {
        Path data = dataDirectory.toAbsolutePath().normalize();
        List<String> failures = new ArrayList<>();
        int removed = 0;

        // URL imports use private temporary directories inside incoming/.
        removed += cleanChildren(data.resolve("incoming"),
                path -> path.getFileName().toString().startsWith(URL_TEMP_PREFIX), failures);

        // tracks/ contains only transient OGG segment work directories. The
        // finished catalogue is stored as immutable ZIPs in packs/.
        removed += cleanChildren(data.resolve("tracks"), path -> true, failures);

        // Native version-check logs and other transient worker files.
        removed += cleanChildren(data.resolve("temp"), path -> true, failures);

        // FFprobe uses a short-lived file directly under the plugin data folder.
        removed += cleanChildren(data, path -> path.getFileName().toString().startsWith(PROBE_TEMP_PREFIX),
                failures);

        return new Report(removed, failures);
    }

    private static int cleanChildren(Path directory, Predicate<Path> shouldDelete, List<String> failures) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        int removed = 0;
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (!shouldDelete.test(child)) continue;
                try {
                    deleteTree(child);
                    removed++;
                } catch (IOException ex) {
                    failures.add(child.getFileName() + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            failures.add(directory.getFileName() + ": " + ex.getMessage());
        }
        return removed;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        // The default Files.walk options do not follow symbolic links.
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
