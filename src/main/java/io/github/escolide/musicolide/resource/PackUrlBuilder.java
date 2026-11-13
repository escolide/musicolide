package io.github.escolide.musicolide.resource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.regex.Pattern;

/** Builds public pack URLs while allowing the Minecraft listener port to be detected automatically. */
public final class PackUrlBuilder {
    private static final Pattern PACK_NAME = Pattern.compile("[a-zA-Z0-9_-]+\\.zip");

    private PackUrlBuilder() {
    }

    /**
     * @param publicBaseUrl public host, optionally including a currently configured port
     * @param pathPrefix resource-pack URL prefix
     * @param fileName safe ZIP file name
     * @param detectedPort bound listener/HTTP port, or -1 when auto-detection is disabled
     * @param configuredPublicPort explicit external port override, or -1 to use detectedPort
     */
    public static String build(String publicBaseUrl, String pathPrefix, String fileName,
                               int detectedPort, int configuredPublicPort) {
        Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        if (!PACK_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Invalid resource-pack file name.");
        }

        URI configured = URI.create(publicBaseUrl.trim());
        String scheme = configured.getScheme() == null ? "http" : configured.getScheme().toLowerCase();
        if (!scheme.equals("http")) {
            throw new IllegalArgumentException("Musicolide shared-port hosting currently requires an HTTP public-base-url.");
        }
        String host = configured.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("server.public-base-url must include a public IP address or hostname.");
        }

        int port = configuredPublicPort > 0 ? configuredPublicPort
                : detectedPort > 0 ? detectedPort : configured.getPort();
        if (port == 0 || port > 65535 || port < -1) {
            throw new IllegalArgumentException("The resource-pack public port is outside the valid TCP port range.");
        }

        String prefix = normalizePrefix(pathPrefix);
        try {
            URI origin = new URI(scheme, configured.getUserInfo(), host, port,
                    null, null, null);
            return origin.toASCIIString() + prefix + "/" + fileName;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Could not construct the resource-pack download URL.", ex);
        }
    }

    private static String normalizePrefix(String prefix) {
        String value = prefix == null || prefix.isBlank() ? "/packs" : prefix.trim();
        if (!value.startsWith("/")) value = "/" + value;
        while (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (!value.matches("/[A-Za-z0-9_/-]+") || value.contains("//") || value.contains("..")) {
            throw new IllegalArgumentException("server.path-prefix must contain only letters, numbers, '/', '_' or '-'.");
        }
        return value;
    }
}
