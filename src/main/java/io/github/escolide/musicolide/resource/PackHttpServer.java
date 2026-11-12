package io.github.escolide.musicolide.resource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PackHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final Path packDirectory;
    private final String prefix;
    private final long maxBytes;

    public PackHttpServer(String bindAddress, int port, String prefix, Path packDirectory, long maxBytes) throws IOException {
        this.packDirectory = packDirectory.toAbsolutePath().normalize();
        this.prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        this.maxBytes = Math.max(1, maxBytes);
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 32);
        this.server.createContext(this.prefix, this::handle);
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Musicolide-PackHTTP");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(executor);
        this.server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String rawPath = exchange.getRequestURI().getPath();
        String name = rawPath.substring(Math.min(prefix.length(), rawPath.length())).replaceFirst("^/", "");
        if (name.isBlank() || name.contains("/") || name.contains("..") || !name.matches("[a-zA-Z0-9_-]+\\.zip")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        Path file = packDirectory.resolve(name).normalize();
        if (!file.startsWith(packDirectory) || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        long size = Files.size(file);
        if (size > maxBytes) {
            exchange.sendResponseHeaders(413, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        exchange.getResponseHeaders().set("ETag", "\"" + Files.getLastModifiedTime(file).toMillis() + "-" + size + "\"");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, size);
            try (var out = exchange.getResponseBody(); var in = Files.newInputStream(file)) { in.transferTo(out); }
        }
        exchange.close();
    }

    @Override public void close() {
        server.stop(1);
        executor.shutdownNow();
    }
}
