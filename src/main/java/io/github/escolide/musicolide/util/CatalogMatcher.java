package io.github.escolide.musicolide.util;

import io.github.escolide.musicolide.model.MusicTrack;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class CatalogMatcher {
    private CatalogMatcher() {}

    public static Optional<Match> best(String title, String creator, List<MusicTrack> tracks) {
        return tracks.stream().map(track -> new Match(track, score(title, creator, track)))
                .max(Comparator.comparingDouble(Match::confidence))
                .filter(match -> match.confidence >= 0.45);
    }

    /**
     * Match metadata only when a supplied creator is compatible with the saved artist.
     * This avoids treating two differently performed songs with the same title as the
     * same recording merely because their titles are identical.
     */
    public static Optional<Match> bestCompatibleArtistMatch(
            String title, String creator, List<MusicTrack> tracks) {
        List<MusicTrack> compatible = tracks.stream()
                .filter(track -> artistsCompatible(creator, track.artist()))
                .toList();
        return best(title, creator, compatible).filter(match -> match.confidence() >= 0.96);
    }

    private static boolean artistsCompatible(String sourceArtist, String savedArtist) {
        if (sourceArtist == null || sourceArtist.isBlank()
                || savedArtist == null || savedArtist.isBlank()) return true;
        String source = normalizeArtist(sourceArtist);
        String saved = normalizeArtist(savedArtist);
        if (source.isBlank() || saved.isBlank()) return true;
        if (source.equals(saved) || source.contains(saved) || saved.contains(source)) return true;
        List<String> left = List.of(source.split(" "));
        List<String> right = List.of(saved.split(" "));
        long intersection = left.stream().distinct().filter(right::contains).count();
        long union = java.util.stream.Stream.concat(left.stream(), right.stream()).distinct().count();
        return union > 0 && intersection / (double) union >= 0.65;
    }

    private static String normalizeArtist(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s*[-|]\\s*(topic|vevo|official)$", "")
                .replaceAll("\\b(official audio|official music video|topic channel)\\b", "")
                .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static double score(String title, String creator, MusicTrack track) {
        String wanted = normalize(title);
        String candidate = normalize(track.title());
        if (wanted.isBlank() || candidate.isBlank()) return 0;
        if (wanted.equals(candidate)) return 1.0;
        if (candidate.contains(wanted) || wanted.contains(candidate)) return 0.88;
        double titleScore = jaccard(wanted, candidate);
        double artistScore = creator == null || creator.isBlank() || track.artist().isBlank()
                ? 0 : jaccard(normalize(creator), normalize(track.artist()));
        return Math.min(1.0, titleScore * 0.85 + artistScore * 0.15);
    }

    private static double jaccard(String a, String b) {
        List<String> left = List.of(a.split(" "));
        List<String> right = List.of(b.split(" "));
        long intersection = left.stream().distinct().filter(right::contains).count();
        long union = java.util.stream.Stream.concat(left.stream(), right.stream()).distinct().count();
        return union == 0 ? 0 : intersection / (double) union;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    public record Match(MusicTrack track, double confidence) {}
}
