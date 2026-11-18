package io.github.escolide.musicolide.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

public final class LinkNormalizer {
    private LinkNormalizer() {}

    public static Optional<String> normalize(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        try {
            URI uri = URI.create(input.trim());
            String host = uri.getHost();
            if (host == null) return Optional.empty();
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            if (host.equals("youtu.be")) {
                String id = path.replaceFirst("^/", "");
                return id.isBlank() ? Optional.empty() : Optional.of("youtube:" + id);
            }
            if (host.equals("youtube.com") || host.equals("music.youtube.com")) {
                if (path.equals("/watch")) {
                    String id = queryValue(uri.getRawQuery(), "v");
                    return id == null ? Optional.empty() : Optional.of("youtube:" + id);
                }
                if (path.startsWith("/shorts/")) return Optional.of("youtube:" + path.substring(8));
            }
            if (host.equals("open.spotify.com") || host.equals("spotify.com")) {
                String[] parts = path.split("/");
                if (parts.length >= 3 && (parts[1].equals("track") || parts[1].equals("album"))) {
                    return Optional.of("spotify:" + parts[1] + ":" + parts[2]);
                }
            }
            if (host.equals("soundcloud.com") || host.endsWith(".soundcloud.com")) {
                return Optional.of("soundcloud:" + host + path);
            }
            return Optional.of(host + path);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String queryValue(String raw, String key) {
        if (raw == null) return null;
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals(key)) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
