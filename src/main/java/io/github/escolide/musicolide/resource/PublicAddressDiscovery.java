package io.github.escolide.musicolide.resource;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Discovers the public IP used by outbound connections.
 *
 * This is a best-effort fallback for directly reachable servers. It cannot
 * discover a hosting provider's inbound proxy hostname or translated port;
 * those must be configured explicitly in server.public-base-url/public-port.
 */
public final class PublicAddressDiscovery {
    private static final List<URI> ENDPOINTS = List.of(
            URI.create("https://api.ipify.org"),
            URI.create("https://checkip.amazonaws.com"),
            URI.create("https://icanhazip.com")
    );
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private PublicAddressDiscovery() {
    }

    /** Starts lookup on a worker thread and returns an HTTP origin for the discovered IP. */
    public static CompletableFuture<String> discoverBaseUrlAsync() {
        return CompletableFuture.supplyAsync(() -> {
            String address = discoverPublicAddress();
            String host = address.indexOf(':') >= 0 ? "[" + address + "]" : address;
            return "http://" + host;
        });
    }

    private static String discoverPublicAddress() {
        StringBuilder failures = new StringBuilder();
        for (URI endpoint : ENDPOINTS) {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "text/plain")
                        .GET()
                        .build();
                HttpResponse<String> response = CLIENT.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    appendFailure(failures, endpoint, "HTTP " + response.statusCode());
                    continue;
                }
                try {
                    return normalizePublicAddress(response.body());
                } catch (IllegalArgumentException invalidAddress) {
                    appendFailure(failures, endpoint, invalidAddress.getMessage());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Public IP discovery was interrupted.", interrupted);
            } catch (IOException | RuntimeException failure) {
                appendFailure(failures, endpoint, failure.getMessage());
            }
        }
        throw new IllegalStateException("All public IP discovery services failed. "
                + failures + " Set server.public-base-url to the hostname players use to join.");
    }

    private static void appendFailure(StringBuilder failures, URI endpoint, String reason) {
        if (failures.length() > 0) failures.append("; ");
        failures.append(endpoint.getHost()).append(": ")
                .append(reason == null || reason.isBlank() ? "request failed" : reason);
    }

    /**
     * Accepts only literal, globally routable IPv4 or IPv6 addresses.
     * Package-private for deterministic unit tests.
     */
    static String normalizePublicAddress(String value) {
        if (value == null) throw new IllegalArgumentException("The service returned an empty address.");
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.length() > 64) {
            throw new IllegalArgumentException("The service returned an empty or oversized address.");
        }

        try {
            InetAddress parsed;
            if (candidate.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                String[] octets = candidate.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < octets.length; i++) {
                    int octet = Integer.parseInt(octets[i]);
                    if (octet > 255) throw new IllegalArgumentException("Invalid IPv4 octet.");
                    bytes[i] = (byte) octet;
                }
                parsed = InetAddress.getByAddress(bytes);
            } else if (candidate.indexOf(':') >= 0 && candidate.matches("[0-9a-fA-F:]+")) {
                // The character check ensures getByName parses a literal and cannot perform DNS.
                parsed = InetAddress.getByName(candidate);
                if (!(parsed instanceof Inet6Address)) {
                    throw new IllegalArgumentException("The service did not return an IPv6 literal.");
                }
            } else {
                throw new IllegalArgumentException("The service did not return a valid IP literal.");
            }

            if (parsed instanceof Inet4Address && isPublicIpv4(parsed.getAddress())) {
                return parsed.getHostAddress();
            }
            if (parsed instanceof Inet6Address && isPublicIpv6(parsed.getAddress())) {
                return parsed.getHostAddress();
            }
            throw new IllegalArgumentException("The service returned a non-public or reserved IP address.");
        } catch (IOException ex) {
            throw new IllegalArgumentException("The service returned an invalid IP address.", ex);
        }
    }

    private static boolean isPublicIpv4(byte[] address) {
        int a = Byte.toUnsignedInt(address[0]);
        int b = Byte.toUnsignedInt(address[1]);
        int c = Byte.toUnsignedInt(address[2]);

        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false; // Shared address space (100.64/10).
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && ((b == 0 && (c == 0 || c == 2)) || b == 168)) return false;
        if (a == 192 && b == 88 && c == 99) return false;
        if (a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)) return false;
        if (a == 203 && b == 0 && c == 113) return false;
        return true;
    }

    private static boolean isPublicIpv6(byte[] address) {
        // Global unicast currently uses the 2000::/3 allocation.
        if ((address[0] & 0xE0) != 0x20) return false;
        // Documentation prefix 2001:db8::/32 is not globally routable.
        return !(address[0] == 0x20 && address[1] == 0x01
                && address[2] == 0x0D && address[3] == (byte) 0xB8);
    }
}
