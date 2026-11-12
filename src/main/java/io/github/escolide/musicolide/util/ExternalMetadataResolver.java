package io.github.escolide.musicolide.util;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves public metadata only (title/creator). It never fetches media streams,
 * downloads audio, or attempts to bypass provider access controls.
 */
public final class ExternalMetadataResolver {
    private static final Pattern TITLE = Pattern.compile("\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern AUTHOR = Pattern.compile("\"author_name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public CompletableFuture<Optional<Metadata>> resolve(String rawUrl) {
        URI input;
        try { input = URI.create(rawUrl); }
        catch (IllegalArgumentException ex) { return CompletableFuture.completedFuture(Optional.empty()); }
        String host = input.getHost();
        if (host == null) return CompletableFuture.completedFuture(Optional.empty());
        host = host.toLowerCase(Locale.ROOT);
        String endpoint;
        if (host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be")) {
            endpoint = "https://www.youtube.com/oembed?format=json&url=";
        } else if (host.equals("open.spotify.com") || host.equals("spotify.com") || host.endsWith(".spotify.com")) {
            endpoint = "https://open.spotify.com/oembed?url=";
        } else if (host.equals("soundcloud.com") || host.endsWith(".soundcloud.com")) {
            endpoint = "https://soundcloud.com/oembed?format=json&url=";
        } else {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        URI uri = URI.create(endpoint + URLEncoder.encode(rawUrl, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Musicolide/0.1 metadata resolver")
                .header("Accept", "application/json").GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) return Optional.empty();
                    Matcher title = TITLE.matcher(response.body());
                    if (!title.find()) return Optional.empty();
                    Matcher author = AUTHOR.matcher(response.body());
                    return Optional.of(new Metadata(unescape(title.group(1)),
                            author.find() ? unescape(author.group(1)) : ""));
                }).exceptionally(error -> Optional.empty());
    }

    private String unescape(String value) {
        return value.replace("\\/", "/").replace("\\\"", "\"")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
    }

    public record Metadata(String title, String creator) {}
}
