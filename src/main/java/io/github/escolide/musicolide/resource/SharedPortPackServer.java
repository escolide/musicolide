package io.github.escolide.musicolide.resource;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Serves Musicolide resource-pack ZIPs through the existing Minecraft TCP
 * listener. The first bytes of each accepted connection are inspected before
 * Paper's Minecraft packet decoders are installed. HTTP GET/HEAD connections
 * are handled here; every other connection is passed through untouched.
 *
 * This intentionally uses a small reflective bridge to Paper's Netty listener.
 * The listener and child-acceptor are server internals, so every discovery step
 * is validated and a clear startup error is reported when the layout changes.
 */
public final class SharedPortPackServer implements AutoCloseable {
    private static final String SNIFFER_NAME = "musicolide-shared-port-sniffer";
    private static final String HTTP_CODEC_NAME = "musicolide-http-codec";
    private static final String HTTP_AGGREGATOR_NAME = "musicolide-http-aggregator";
    private static final String HTTP_HANDLER_NAME = "musicolide-http-pack-handler";
    private static final Pattern PACK_NAME = Pattern.compile("[a-zA-Z0-9_-]+\\.zip");

    private final JavaPlugin plugin;
    private final Path packDirectory;
    private final String pathPrefix;
    private final long maxBytes;
    private final List<ParentHook> parentHooks = new CopyOnWriteArrayList<>();
    private volatile int listeningPort = -1;

    private SharedPortPackServer(JavaPlugin plugin, Path packDirectory, String pathPrefix, long maxBytes) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.packDirectory = packDirectory.toAbsolutePath().normalize();
        this.pathPrefix = normalizePrefix(pathPrefix);
        this.maxBytes = Math.max(1L, maxBytes);
    }

    /**
     * Installs an early child-channel hook on every discovered Minecraft TCP
     * listener and returns the actual bound listener port.
     */
    public static SharedPortPackServer install(JavaPlugin plugin, Path packDirectory,
                                               String pathPrefix, long maxBytes) throws Exception {
        Files.createDirectories(packDirectory);
        SharedPortPackServer server = new SharedPortPackServer(plugin, packDirectory, pathPrefix, maxBytes);
        try {
            server.installOnMinecraftListeners();
            return server;
        } catch (Exception ex) {
            server.close();
            throw ex;
        }
    }

    public int listeningPort() {
        return listeningPort;
    }

    private void installOnMinecraftListeners() throws Exception {
        Object minecraftServer = findMinecraftServer();
        Object connectionListener = findConnectionListener(minecraftServer);
        List<ChannelFuture> listeners = findListeningChannels(connectionListener);
        if (listeners.isEmpty()) {
            throw new IllegalStateException("Paper's Minecraft TCP listener channel list was empty.");
        }

        int installed = 0;
        for (ChannelFuture future : listeners) {
            if (future == null || !future.channel().isOpen()) continue;
            Channel listeningChannel = future.channel();
            if (!(listeningChannel.localAddress() instanceof InetSocketAddress address)
                    || address.getPort() <= 0) {
                continue;
            }

            String acceptorName = findChildAcceptorName(listeningChannel.pipeline());
            if (acceptorName == null) {
                throw new IllegalStateException("Could not locate Netty's child-channel acceptor in "
                        + listeningChannel.pipeline().names() + ".");
            }

            String hookName = "musicolide-shared-port-acceptor-" + installed;
            listeningChannel.pipeline().addBefore(acceptorName, hookName,
                    new AcceptedChildChannelHandler(packDirectory, pathPrefix, maxBytes, plugin));
            parentHooks.add(new ParentHook(listeningChannel, hookName));
            if (listeningPort < 0) listeningPort = address.getPort();
            if (listeningPort != address.getPort()) {
                throw new IllegalStateException("Minecraft exposes multiple TCP listener ports ("
                        + listeningPort + " and " + address.getPort()
                        + "); set server.public-port explicitly and report this server layout.");
            }
            installed++;
        }

        if (installed == 0 || listeningPort < 1) {
            throw new IllegalStateException("Could not discover an active Minecraft TCP listener port.");
        }
        plugin.getLogger().info("Shared-port resource-pack hosting is active on the Minecraft TCP listener "
                + "port " + listeningPort + ". No additional public port is required.");
    }

    private static Object findMinecraftServer() throws Exception {
        Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
        for (Method method : allMethods(serverClass)) {
            if (!method.getName().equals("getServer") || method.getParameterCount() != 0
                    || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!method.trySetAccessible()) continue;
            Object instance = method.invoke(null);
            if (instance != null) return instance;
        }
        throw new IllegalStateException("Could not access the running net.minecraft.server.MinecraftServer instance.");
    }

    private static Object findConnectionListener(Object minecraftServer) throws Exception {
        for (Method method : allMethods(minecraftServer.getClass())) {
            if (method.getParameterCount() != 0) continue;
            String typeName = method.getReturnType().getSimpleName();
            if (!typeName.equals("ServerConnectionListener") && !typeName.equals("ServerConnection")) continue;
            if (!method.trySetAccessible()) continue;
            Object result = method.invoke(minecraftServer);
            if (result != null) return result;
        }

        for (Field field : allFields(minecraftServer.getClass())) {
            String typeName = field.getType().getSimpleName();
            if (!typeName.equals("ServerConnectionListener") && !typeName.equals("ServerConnection")) continue;
            if (!field.trySetAccessible()) continue;
            Object result = field.get(minecraftServer);
            if (result != null) return result;
        }
        throw new IllegalStateException("Could not locate Paper's ServerConnectionListener.");
    }

    private static List<ChannelFuture> findListeningChannels(Object connectionListener) throws Exception {
        Map<ChannelFuture, Boolean> found = new LinkedHashMap<>();
        for (Field field : allFields(connectionListener.getClass())) {
            if (!Collection.class.isAssignableFrom(field.getType()) || !field.trySetAccessible()) continue;
            Object value = field.get(connectionListener);
            if (!(value instanceof Collection<?> collection)) continue;
            for (Object entry : collection) {
                if (entry instanceof ChannelFuture future) found.put(future, Boolean.TRUE);
            }
        }
        return new ArrayList<>(found.keySet());
    }

    private static String findChildAcceptorName(ChannelPipeline pipeline) throws IllegalAccessException {
        for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
            for (Field field : allFields(entry.getValue().getClass())) {
                if (!field.getName().equals("childHandler") || !field.trySetAccessible()) continue;
                Object value = field.get(entry.getValue());
                if (value instanceof ChannelHandler) return entry.getKey();
            }
        }
        return null;
    }

    private static List<Method> allMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            methods.addAll(List.of(current.getDeclaredMethods()));
        }
        return methods;
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            fields.addAll(List.of(current.getDeclaredFields()));
        }
        return fields;
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

    @Override
    public void close() {
        for (ParentHook hook : parentHooks) {
            try {
                if (hook.channel().pipeline().get(hook.handlerName()) != null) {
                    hook.channel().pipeline().remove(hook.handlerName());
                }
            } catch (Exception ex) {
                plugin.getLogger().fine("Could not remove shared-port listener hook: " + ex.getMessage());
            }
        }
        parentHooks.clear();
    }

    private record ParentHook(Channel channel, String handlerName) {}

    private static final class AcceptedChildChannelHandler extends ChannelInboundHandlerAdapter {
        private final Path packDirectory;
        private final String pathPrefix;
        private final long maxBytes;
        private final JavaPlugin plugin;

        private AcceptedChildChannelHandler(Path packDirectory, String pathPrefix, long maxBytes, JavaPlugin plugin) {
            this.packDirectory = packDirectory;
            this.pathPrefix = pathPrefix;
            this.maxBytes = maxBytes;
            this.plugin = plugin;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            if (message instanceof Channel child && child.pipeline().get(SNIFFER_NAME) == null) {
                child.pipeline().addFirst(SNIFFER_NAME,
                        new HttpMinecraftSniffer(packDirectory, pathPrefix, maxBytes, plugin));
            }
            context.fireChannelRead(message);
        }
    }

    /**
     * Buffers at most five initial bytes. Minecraft's initial handshake is
     * normally distinguishable immediately; GET and HEAD are recognized even
     * when the client sends their method across multiple TCP reads.
     */
    static final class HttpMinecraftSniffer extends ChannelInboundHandlerAdapter {
        private final Path packDirectory;
        private final String pathPrefix;
        private final long maxBytes;
        private final JavaPlugin plugin;
        private ByteBuf prefix;

        HttpMinecraftSniffer(Path packDirectory, String pathPrefix, long maxBytes, JavaPlugin plugin) {
            this.packDirectory = packDirectory;
            this.pathPrefix = pathPrefix;
            this.maxBytes = maxBytes;
            this.plugin = plugin;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (!(message instanceof ByteBuf incoming)) {
                context.fireChannelRead(message);
                return;
            }

            if (prefix == null) prefix = context.alloc().buffer(5, 5);
            int needed = Math.max(0, 5 - prefix.readableBytes());
            int copied = Math.min(needed, incoming.readableBytes());
            if (copied > 0) prefix.writeBytes(incoming, copied);

            ProtocolDecision decision = classifyPrefix(prefix);
            if (decision == ProtocolDecision.NEED_MORE) {
                if (!incoming.isReadable()) ReferenceCountUtil.release(incoming);
                else {
                    // A five-byte prefix is always decisive for GET/HEAD or Minecraft.
                    context.close();
                    ReferenceCountUtil.release(incoming);
                }
                return;
            }

            ByteBuf firstBytes = prefix;
            prefix = null;
            ChannelPipeline pipeline = context.pipeline();
            String ownName = context.name();
            if (decision == ProtocolDecision.HTTP) {
                pipeline.addAfter(ownName, HTTP_CODEC_NAME, new HttpServerCodec());
                pipeline.addAfter(HTTP_CODEC_NAME, HTTP_AGGREGATOR_NAME, new HttpObjectAggregator(8192));
                pipeline.addAfter(HTTP_AGGREGATOR_NAME, HTTP_HANDLER_NAME,
                        new PackRequestHandler(packDirectory, pathPrefix, maxBytes));
            }
            pipeline.remove(this);

            // Restart dispatch at the head so the new HTTP handlers (or the
            // vanilla Minecraft decoder) receive the complete original stream.
            pipeline.fireChannelRead(firstBytes);
            if (incoming.isReadable()) pipeline.fireChannelRead(incoming);
            else ReferenceCountUtil.release(incoming);
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            if (prefix != null) {
                ReferenceCountUtil.release(prefix);
                prefix = null;
            }
            super.channelInactive(context);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            plugin.getLogger().fine("Shared-port connection sniffing failed: " + cause.getMessage());
            if (prefix != null) {
                ReferenceCountUtil.release(prefix);
                prefix = null;
            }
            context.close();
        }

        static ProtocolDecision classifyPrefix(ByteBuf buffer) {
            int length = buffer.readableBytes();
            if (length == 0) return ProtocolDecision.NEED_MORE;
            byte[] bytes = new byte[length];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return classifyPrefix(bytes, length);
        }

        static ProtocolDecision classifyPrefix(byte[] bytes, int length) {
            if (length <= 0) return ProtocolDecision.NEED_MORE;
            if (isPrefixOrMatch(bytes, length, "GET ")) {
                return length >= 4 ? ProtocolDecision.HTTP : ProtocolDecision.NEED_MORE;
            }
            if (isPrefixOrMatch(bytes, length, "HEAD ")) {
                return length >= 5 ? ProtocolDecision.HTTP : ProtocolDecision.NEED_MORE;
            }
            return ProtocolDecision.MINECRAFT;
        }

        private static boolean isPrefixOrMatch(byte[] bytes, int length, String expected) {
            int compare = Math.min(length, expected.length());
            for (int i = 0; i < compare; i++) {
                if ((bytes[i] & 0xff) != expected.charAt(i)) return false;
            }
            return true;
        }
    }

    enum ProtocolDecision {
        NEED_MORE,
        MINECRAFT,
        HTTP
    }

    private static final class PackRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final Path packDirectory;
        private final String pathPrefix;
        private final long maxBytes;

        private PackRequestHandler(Path packDirectory, String pathPrefix, long maxBytes) {
            this.packDirectory = packDirectory;
            this.pathPrefix = pathPrefix;
            this.maxBytes = maxBytes;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
            if (!request.decoderResult().isSuccess()) {
                respond(context, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            boolean head = request.method().name().equals("HEAD");
            if (!head && !request.method().name().equals("GET")) {
                respond(context, HttpResponseStatus.METHOD_NOT_ALLOWED);
                return;
            }

            String requestPath;
            try {
                requestPath = new QueryStringDecoder(request.uri()).path();
            } catch (IllegalArgumentException ex) {
                respond(context, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            if (!(requestPath.equals(pathPrefix) || requestPath.startsWith(pathPrefix + "/"))) {
                respond(context, HttpResponseStatus.NOT_FOUND);
                return;
            }
            String name = requestPath.substring(pathPrefix.length());
            if (name.startsWith("/")) name = name.substring(1);
            if (!PACK_NAME.matcher(name).matches()) {
                respond(context, HttpResponseStatus.NOT_FOUND);
                return;
            }

            Path file = packDirectory.resolve(name).normalize();
            if (!file.startsWith(packDirectory) || !Files.isRegularFile(file)) {
                respond(context, HttpResponseStatus.NOT_FOUND);
                return;
            }

            long size = Files.size(file);
            if (size > maxBytes) {
                respond(context, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
                return;
            }

            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/zip");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, size);
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "public, max-age=31536000, immutable");
            response.headers().set(HttpHeaderNames.ETAG, "\"" + Files.getLastModifiedTime(file).toMillis()
                    + "-" + size + "\"");
            response.headers().set(HttpHeaderNames.CONNECTION, "close");

            if (head) {
                DefaultFullHttpResponse headResponse = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                headResponse.headers().set(response.headers());
                context.writeAndFlush(headResponse).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
            DefaultFileRegion region = new DefaultFileRegion(channel, 0L, size);
            context.write(response);
            ChannelFuture transfer = context.write(region);
            transfer.addListener(future -> {
                if (region.refCnt() > 0) region.release();
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            });
            context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
        }

        private static void respond(ChannelHandlerContext context, HttpResponseStatus status) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status);
            HttpUtil.setContentLength(response, 0);
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            context.close();
        }
    }
}
