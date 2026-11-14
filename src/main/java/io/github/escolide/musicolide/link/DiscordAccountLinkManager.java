package io.github.escolide.musicolide.link;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Stores one-to-one Discord/Minecraft account links and short-lived, one-use
 * pairing codes. Pairing codes are held only in memory and are never written
 * to disk or logged.
 */
public final class DiscordAccountLinkManager implements AutoCloseable {
    private static final long CODE_LIFETIME_NANOS = 5L * 60L * 1_000_000_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Connection connection;
    private final Logger logger;
    private final Map<String, PendingCode> pendingByHash = new HashMap<>();
    private final Map<UUID, String> pendingHashByMinecraft = new HashMap<>();

    public DiscordAccountLinkManager(Path databaseFile, Logger logger) throws SQLException, IOException {
        Objects.requireNonNull(databaseFile, "databaseFile");
        this.logger = Objects.requireNonNull(logger, "logger");
        Path parent = databaseFile.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath().normalize());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS discord_account_links (
                        discord_user_id TEXT PRIMARY KEY NOT NULL,
                        minecraft_uuid TEXT NOT NULL UNIQUE,
                        minecraft_name TEXT NOT NULL,
                        linked_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_discord_links_minecraft "
                    + "ON discord_account_links(minecraft_uuid)");
        }
    }

    /** Creates an eight-digit code that expires after five minutes. */
    public synchronized String createCode(UUID minecraftUuid, String minecraftName) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Objects.requireNonNull(minecraftName, "minecraftName");
        purgeExpiredCodes();
        if (findByMinecraft(minecraftUuid).isPresent()) {
            throw new IllegalStateException("Your Minecraft account is already linked to Discord. Use /unlink first.");
        }

        String oldHash = pendingHashByMinecraft.remove(minecraftUuid);
        if (oldHash != null) pendingByHash.remove(oldHash);

        for (int attempt = 0; attempt < 20; attempt++) {
            String code = String.format(java.util.Locale.ROOT, "%08d", RANDOM.nextInt(100_000_000));
            String hash = hash(code);
            if (pendingByHash.containsKey(hash)) continue;
            PendingCode pending = new PendingCode(minecraftUuid, minecraftName, System.nanoTime() + CODE_LIFETIME_NANOS);
            pendingByHash.put(hash, pending);
            pendingHashByMinecraft.put(minecraftUuid, hash);
            return code;
        }
        throw new IllegalStateException("Could not generate a unique pairing code. Please try again.");
    }

    /**
     * Consumes a pending code and atomically writes a unique link. No Minecraft
     * password, Microsoft credential, session token, or server authentication
     * secret is requested or stored.
     */
    public synchronized Completion completeLink(String discordUserId, String submittedCode) throws SQLException {
        if (discordUserId == null || !discordUserId.matches("[0-9]{5,25}")
                || submittedCode == null || !submittedCode.matches("[0-9]{8}")) {
            return new Completion(CompletionStatus.INVALID_CODE, null);
        }
        purgeExpiredCodes();
        String hash = hash(submittedCode);
        PendingCode pending = pendingByHash.remove(hash);
        if (pending == null) return new Completion(CompletionStatus.INVALID_CODE, null);
        pendingHashByMinecraft.remove(pending.minecraftUuid, hash);
        if (pending.expiresAtNanos <= System.nanoTime()) {
            return new Completion(CompletionStatus.EXPIRED, null);
        }
        if (findByDiscord(discordUserId).isPresent()) {
            return new Completion(CompletionStatus.DISCORD_ALREADY_LINKED, null);
        }
        if (findByMinecraft(pending.minecraftUuid).isPresent()) {
            return new Completion(CompletionStatus.MINECRAFT_ALREADY_LINKED, null);
        }

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO discord_account_links(discord_user_id, minecraft_uuid, minecraft_name, linked_at) "
                        + "VALUES(?, ?, ?, ?)")) {
            insert.setString(1, discordUserId);
            insert.setString(2, pending.minecraftUuid.toString());
            insert.setString(3, pending.minecraftName);
            insert.setLong(4, System.currentTimeMillis());
            insert.executeUpdate();
            return new Completion(CompletionStatus.LINKED, pending.minecraftName);
        } catch (SQLException error) {
            // UNIQUE constraints remain the final guard if two requests race.
            if (isConstraintViolation(error)) {
                if (findByDiscord(discordUserId).isPresent()) {
                    return new Completion(CompletionStatus.DISCORD_ALREADY_LINKED, null);
                }
                if (findByMinecraft(pending.minecraftUuid).isPresent()) {
                    return new Completion(CompletionStatus.MINECRAFT_ALREADY_LINKED, null);
                }
            }
            throw error;
        }
    }

    public synchronized Optional<LinkedAccount> findByDiscord(String discordUserId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT discord_user_id, minecraft_uuid, minecraft_name, linked_at "
                        + "FROM discord_account_links WHERE discord_user_id = ?")) {
            query.setString(1, discordUserId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new LinkedAccount(result.getString(1),
                        UUID.fromString(result.getString(2)), result.getString(3), result.getLong(4)));
            }
        }
    }

    public synchronized Optional<LinkedAccount> findByMinecraft(UUID minecraftUuid) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT discord_user_id, minecraft_uuid, minecraft_name, linked_at "
                        + "FROM discord_account_links WHERE minecraft_uuid = ?")) {
            query.setString(1, minecraftUuid.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new LinkedAccount(result.getString(1),
                        UUID.fromString(result.getString(2)), result.getString(3), result.getLong(4)));
            }
        }
    }

    public synchronized boolean unlinkMinecraft(UUID minecraftUuid) throws SQLException {
        String hash = pendingHashByMinecraft.remove(minecraftUuid);
        if (hash != null) pendingByHash.remove(hash);
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM discord_account_links WHERE minecraft_uuid = ?")) {
            delete.setString(1, minecraftUuid.toString());
            return delete.executeUpdate() > 0;
        }
    }

    private void purgeExpiredCodes() {
        long now = System.nanoTime();
        pendingByHash.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAtNanos > now) return false;
            pendingHashByMinecraft.remove(entry.getValue().minecraftUuid, entry.getKey());
            return true;
        });
    }

    private static boolean isConstraintViolation(SQLException error) {
        return error.getErrorCode() == 19 || (error.getMessage() != null
                && error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("constraint"));
    }

    private static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.US_ASCII));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    @Override
    public synchronized void close() {
        pendingByHash.clear();
        pendingHashByMinecraft.clear();
        try {
            connection.close();
        } catch (SQLException error) {
            logger.warning("Could not close the Discord account-link database cleanly: "
                    + error.getClass().getSimpleName());
        }
    }

    private record PendingCode(UUID minecraftUuid, String minecraftName, long expiresAtNanos) {}

    public record LinkedAccount(String discordUserId, UUID minecraftUuid, String minecraftName, long linkedAt) {}

    public enum CompletionStatus {
        LINKED, INVALID_CODE, EXPIRED, DISCORD_ALREADY_LINKED, MINECRAFT_ALREADY_LINKED
    }

    public record Completion(CompletionStatus status, String minecraftName) {}
}
