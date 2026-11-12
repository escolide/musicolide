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
