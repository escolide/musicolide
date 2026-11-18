package io.github.escolide.musicolide.storage;

import io.github.escolide.musicolide.model.MusicTrack;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class TrackRepository implements AutoCloseable {
    private final String jdbcUrl;

    public TrackRepository(Path databaseFile) throws SQLException {
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS tracks (
                      id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL,
                      namespace TEXT NOT NULL, pack_file TEXT NOT NULL, sha1_hex TEXT NOT NULL,
                      duration_ms INTEGER NOT NULL, segment_ms INTEGER NOT NULL,
                      segment_count INTEGER NOT NULL, pack_size_bytes INTEGER NOT NULL,
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ephemeral_tracks (
                      track_id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL,
                      FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS aliases (
                      alias_key TEXT PRIMARY KEY, track_id TEXT NOT NULL,
                      FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS playlists (
                      name TEXT PRIMARY KEY, created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS playlist_entries (
                      playlist_name TEXT NOT NULL, position INTEGER NOT NULL, track_id TEXT NOT NULL,
                      PRIMARY KEY(playlist_name, position),
                      FOREIGN KEY(playlist_name) REFERENCES playlists(name) ON DELETE CASCADE,
                      FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS private_playlists (
                      owner_uuid TEXT NOT NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL,
                      PRIMARY KEY(owner_uuid, name)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS private_playlist_entries (
                      owner_uuid TEXT NOT NULL, playlist_name TEXT NOT NULL,
                      position INTEGER NOT NULL, track_id TEXT NOT NULL,
                      PRIMARY KEY(owner_uuid, playlist_name, position),
                      FOREIGN KEY(owner_uuid, playlist_name)
                        REFERENCES private_playlists(owner_uuid, name) ON DELETE CASCADE,
                      FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_preferences (
                      player_uuid TEXT PRIMARY KEY, volume INTEGER NOT NULL DEFAULT 100,
                      muted INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS track_codes (
                      track_id TEXT PRIMARY KEY,
                      short_id TEXT NOT NULL UNIQUE
                    )
                    """);
            assignMissingTrackCodes(connection);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    public synchronized void saveTrack(MusicTrack track) throws SQLException {
        saveTrack(track, null);
    }

    public synchronized void saveTemporaryTrack(MusicTrack track, long expiresAtMillis) throws SQLException {
        saveTrack(track, expiresAtMillis);
    }

    private void saveTrack(MusicTrack track, Long expiresAtMillis) throws SQLException {
        String sql = """
                INSERT INTO tracks(id,title,artist,namespace,pack_file,sha1_hex,duration_ms,segment_ms,segment_count,pack_size_bytes,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET title=excluded.title, artist=excluded.artist,
                namespace=excluded.namespace, pack_file=excluded.pack_file, sha1_hex=excluded.sha1_hex,
                duration_ms=excluded.duration_ms, segment_ms=excluded.segment_ms,
                segment_count=excluded.segment_count, pack_size_bytes=excluded.pack_size_bytes
                """;
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, track.id());
                    ps.setString(2, track.title());
                    ps.setString(3, track.artist() == null ? "" : track.artist());
                    ps.setString(4, track.namespace());
                    ps.setString(5, track.packFileName());
                    ps.setString(6, track.sha1Hex());
                    ps.setLong(7, track.durationMs());
                    ps.setInt(8, track.segmentMs());
                    ps.setInt(9, track.segmentCount());
                    ps.setLong(10, track.packSizeBytes());
                    ps.setLong(11, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                ensureTrackCode(c, track.id());
                if (expiresAtMillis == null) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM ephemeral_tracks WHERE track_id=?")) {
                        ps.setString(1, track.id());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO ephemeral_tracks(track_id,expires_at) VALUES(?,?)
                            ON CONFLICT(track_id) DO UPDATE SET expires_at=excluded.expires_at
                            """)) {
                        ps.setString(1, track.id());
                        ps.setLong(2, expiresAtMillis);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException ex) {
                try {
                    c.rollback();
                } catch (SQLException rollbackError) {
                    ex.addSuppressed(rollbackError);
                }
                throw ex;
            }
        }
    }

    private static void assignMissingTrackCodes(Connection connection) throws SQLException {
        List<String> missing = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT t.id FROM tracks t
                LEFT JOIN track_codes c ON c.track_id=t.id
                WHERE c.track_id IS NULL
                ORDER BY t.created_at ASC, t.id ASC
                """); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) missing.add(rs.getString(1));
        }
        for (String id : missing) ensureTrackCode(connection, id);
    }

    private static void ensureTrackCode(Connection connection, String trackId) throws SQLException {
        try (PreparedStatement existing = connection.prepareStatement(
                "SELECT 1 FROM track_codes WHERE track_id=?")) {
            existing.setString(1, trackId);
            try (ResultSet rs = existing.executeQuery()) {
                if (rs.next()) return;
            }
        }
        String code = nextAvailableTrackCode(connection);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO track_codes(track_id,short_id) VALUES(?,?)")) {
            insert.setString(1, trackId);
            insert.setString(2, code);
            insert.executeUpdate();
        }
    }

    private static String nextAvailableTrackCode(Connection connection) throws SQLException {
        for (char letter = 'a'; letter <= 'f'; letter++) {
            for (int number = 0; number < 100; number++) {
                String candidate = letter + String.format(Locale.ROOT, "%02d", number);
                try (PreparedStatement ps = connection.prepareStatement("""
                        SELECT 1 FROM track_codes WHERE lower(short_id)=lower(?)
                        UNION ALL
                        SELECT 1 FROM tracks WHERE lower(id)=lower(?)
                        LIMIT 1
                        """)) {
                    ps.setString(1, candidate);
                    ps.setString(2, candidate);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return candidate;
                    }
                }
            }
        }
        throw new SQLException("All 600 Musicolide TrackIDs (a00-f99) are in use. Delete unused tracks before importing more.");
    }

    public synchronized Optional<String> getTrackCode(String trackId) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT short_id FROM track_codes WHERE track_id=?")) {
            ps.setString(1, trackId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public synchronized List<MusicTrack> expiredTemporaryTracks(long nowMillis) throws SQLException {
        List<MusicTrack> tracks = new ArrayList<>();
        String sql = """
                SELECT t.* FROM tracks t
                JOIN ephemeral_tracks e ON e.track_id=t.id
                WHERE e.expires_at<=?
                ORDER BY e.expires_at ASC
                """;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, nowMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tracks.add(readTrack(rs));
            }
        }
        return tracks;
    }

    /** Returns every temporary URL/imported track, including entries not yet expired. */
    public synchronized List<MusicTrack> temporaryTracks() throws SQLException {
        List<MusicTrack> tracks = new ArrayList<>();
        String sql = """
                SELECT t.* FROM tracks t
                JOIN ephemeral_tracks e ON e.track_id=t.id
                ORDER BY e.expires_at ASC
                """;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tracks.add(readTrack(rs));
            }
        }
        return tracks;
    }

    public synchronized boolean deleteTrack(String trackId) throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                String resolvedId = null;
                try (PreparedStatement lookup = c.prepareStatement("""
                        SELECT id FROM tracks WHERE lower(id)=lower(?)
                        UNION ALL
                        SELECT track_id FROM track_codes WHERE lower(short_id)=lower(?)
                        LIMIT 1
                        """)) {
                    lookup.setString(1, trackId);
                    lookup.setString(2, trackId);
                    try (ResultSet rs = lookup.executeQuery()) {
                        if (rs.next()) resolvedId = rs.getString(1);
                    }
                }
                if (resolvedId == null) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement deleteTrack = c.prepareStatement("DELETE FROM tracks WHERE id=?");
                     PreparedStatement deleteCode = c.prepareStatement("DELETE FROM track_codes WHERE track_id=?")) {
                    deleteTrack.setString(1, resolvedId);
                    deleteTrack.executeUpdate();
                    deleteCode.setString(1, resolvedId);
                    deleteCode.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException error) {
                try {
                    c.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        }
    }

    public synchronized boolean isTemporaryTrack(String trackId) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM ephemeral_tracks WHERE track_id=?")) {
            ps.setString(1, trackId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized Optional<MusicTrack> getTrack(String id) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("""
                SELECT t.* FROM tracks t
                LEFT JOIN track_codes c ON c.track_id=t.id
                WHERE lower(t.id)=lower(?) OR lower(c.short_id)=lower(?)
                ORDER BY CASE WHEN lower(c.short_id)=lower(?) THEN 0 ELSE 1 END
                LIMIT 1
                """)) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.setString(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readTrack(rs)) : Optional.empty();
            }
        }
    }

    public synchronized List<MusicTrack> search(String query, int limit) throws SQLException {
        List<MusicTrack> tracks = new ArrayList<>();
        String sql = """
                SELECT t.* FROM tracks t
                LEFT JOIN track_codes c ON c.track_id=t.id
                WHERE lower(t.id)=lower(?) OR lower(c.short_id)=lower(?) OR lower(t.title) LIKE ?
                ORDER BY t.title COLLATE NOCASE LIMIT ?
                """;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setString(2, query);
            ps.setString(3, "%" + query.toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%");
            ps.setInt(4, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tracks.add(readTrack(rs));
            }
        }
        return tracks;
    }

    /**
     * Search local playable tracks in a stable, useful order. Exact IDs and
     * titles win first, then title prefixes, then artist prefixes, with newer
     * imports taking priority among comparable matches.
     */
    public synchronized List<MusicTrack> searchForPlayback(String query, int limit, int offset) throws SQLException {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return List.of();
        String escaped = normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        String contains = "%" + escaped + "%";
        String prefix = escaped + "%";
        String sql = """
                SELECT t.* FROM tracks t
                LEFT JOIN track_codes c ON c.track_id=t.id
                WHERE lower(t.id)=lower(?)
                   OR lower(c.short_id)=lower(?)
                   OR lower(t.title) LIKE ? ESCAPE '!'
                   OR lower(t.artist) LIKE ? ESCAPE '!'
                ORDER BY CASE
                    WHEN lower(t.id)=lower(?) OR lower(c.short_id)=lower(?) THEN 0
                    WHEN lower(t.title)=lower(?) THEN 1
                    WHEN lower(t.title) LIKE ? ESCAPE '!' THEN 2
                    WHEN lower(t.artist) LIKE ? ESCAPE '!' THEN 3
                    ELSE 4 END,
                    t.created_at DESC, t.title COLLATE NOCASE
                LIMIT ? OFFSET ?
                """;
        List<MusicTrack> tracks = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, normalized);
            ps.setString(2, normalized);
            ps.setString(3, contains);
            ps.setString(4, contains);
            ps.setString(5, normalized);
            ps.setString(6, normalized);
            ps.setString(7, normalized);
            ps.setString(8, prefix);
            ps.setString(9, prefix);
            ps.setInt(10, Math.max(1, limit));
            ps.setInt(11, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tracks.add(readTrack(rs));
            }
        }
        return tracks;
    }

    public synchronized Optional<MusicTrack> findExactTitle(String title) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM tracks WHERE lower(title)=lower(?) ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, title == null ? "" : title.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readTrack(rs)) : Optional.empty();
            }
        }
    }

    /** Returns a validated-at-call-site YouTube video ID for Discord embeds. */
    public synchronized Optional<String> getYouTubeVideoId(String trackId) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("""
                SELECT substr(a.alias_key,9)
                FROM aliases a
                LEFT JOIN track_codes tc ON tc.track_id=a.track_id
                WHERE (a.track_id=? OR lower(tc.short_id)=lower(?))
                  AND lower(a.alias_key) LIKE 'youtube:%'
                ORDER BY a.alias_key LIMIT 1
                """)) {
            ps.setString(1, trackId);
            ps.setString(2, trackId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public synchronized List<MusicTrack> allTracks(int limit) throws SQLException {
        List<MusicTrack> tracks = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM tracks ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tracks.add(readTrack(rs));
            }
        }
        return tracks;
    }

    /**
     * Updates only the resource-pack checksum and size. In particular, this
     * preserves ephemeral-track expiry records during an on-disk pack migration.
     */
    public synchronized void updatePackMetadata(String trackId, String sha1Hex, long packSizeBytes)
            throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE tracks SET sha1_hex=?, pack_size_bytes=? WHERE id=?")) {
            ps.setString(1, sha1Hex);
            ps.setLong(2, packSizeBytes);
            ps.setString(3, trackId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Could not update resource-pack metadata for track " + trackId + ".");
            }
        }
    }

    /** Updates archive revision and mono segment timing without touching playlist/expiry rows. */
    public synchronized void updatePackMetadata(String trackId, String sha1Hex, long packSizeBytes,
                                                int segmentMs, int segmentCount) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE tracks SET sha1_hex=?, pack_size_bytes=?, segment_ms=?, segment_count=? WHERE id=?")) {
            ps.setString(1, sha1Hex);
            ps.setLong(2, packSizeBytes);
            ps.setInt(3, segmentMs);
            ps.setInt(4, segmentCount);
            ps.setString(5, trackId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Could not update resource-pack metadata for track " + trackId + ".");
            }
        }
    }

    public synchronized void addAlias(String aliasKey, String trackId) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO aliases(alias_key,track_id) VALUES(?,?) ON CONFLICT(alias_key) DO UPDATE SET track_id=excluded.track_id")) {
            ps.setString(1, aliasKey);
            ps.setString(2, trackId);
            ps.executeUpdate();
        }
    }

    public synchronized Optional<MusicTrack> resolveAlias(String aliasKey) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT t.* FROM tracks t JOIN aliases a ON a.track_id=t.id WHERE a.alias_key=?")) {
            ps.setString(1, aliasKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readTrack(rs)) : Optional.empty();
            }
        }
    }

    public synchronized void createPlaylist(String name) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO playlists(name,created_at) VALUES(?,?)")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public synchronized void addPlaylistTrack(String name, String trackId) throws SQLException {
        addPlaylistTrack(name, trackId, Integer.MAX_VALUE);
    }

    /**
     * Adds a catalogue track only when the playlist remains within its configured
     * maximum. The count and insert share one transaction so repeated commands
     * cannot race past the limit.
     */
    public synchronized void addPlaylistTrack(String name, String trackId, int maximumTracks) throws SQLException {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        int limit = Math.max(1, maximumTracks);
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement exists = c.prepareStatement("SELECT 1 FROM playlists WHERE name=?")) {
                    exists.setString(1, normalizedName);
                    try (ResultSet rs = exists.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Playlist does not exist.");
                    }
                }

                int count = 0;
                try (PreparedStatement countQuery = c.prepareStatement(
                        "SELECT COUNT(*) FROM playlist_entries WHERE playlist_name=?")) {
                    countQuery.setString(1, normalizedName);
                    try (ResultSet rs = countQuery.executeQuery()) {
                        if (rs.next()) count = rs.getInt(1);
                    }
                }
                if (count >= limit) {
                    throw new IllegalArgumentException("Playlist has reached its maximum of "
                            + limit + " tracks. Remove a track before adding another.");
                }

                int position = 0;
                try (PreparedStatement positionQuery = c.prepareStatement(
                        "SELECT COALESCE(MAX(position),-1)+1 FROM playlist_entries WHERE playlist_name=?")) {
                    positionQuery.setString(1, normalizedName);
                    try (ResultSet rs = positionQuery.executeQuery()) {
                        if (rs.next()) position = rs.getInt(1);
                    }
                }
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO playlist_entries(playlist_name,position,track_id) VALUES(?,?,?)")) {
                    insert.setString(1, normalizedName);
                    insert.setInt(2, position);
                    insert.setString(3, trackId);
                    insert.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public synchronized List<MusicTrack> getPlaylist(String name, int limit) throws SQLException {
        List<MusicTrack> tracks = new ArrayList<>();
        String sql = """
                SELECT t.* FROM playlist_entries p JOIN tracks t ON t.id=p.track_id
                WHERE p.playlist_name=? ORDER BY p.position LIMIT ?
                """;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tracks.add(readTrack(rs));
            }
        }
        return tracks;
    }

    public synchronized List<String> listPlaylists() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM playlists ORDER BY name"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    public synchronized boolean playlistExists(String name) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM playlists WHERE name=?")) {
            ps.setString(1, name.trim().toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized boolean deletePlaylist(String name) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM playlists WHERE name=?")) {
            ps.setString(1, name.trim().toLowerCase(Locale.ROOT));
            return ps.executeUpdate() > 0;
        }
    }

    /** Removes the one-based displayed entry number from a shared playlist. */
    public synchronized boolean removePlaylistTrack(String name, int entryNumber) throws SQLException {
        if (entryNumber < 1) throw new IllegalArgumentException("Playlist entry number must be 1 or greater.");
        String normalizedName = name.trim().toLowerCase(Locale.ROOT);
        String sql = """
                DELETE FROM playlist_entries
                WHERE playlist_name=? AND position=(
                    SELECT position FROM playlist_entries
                    WHERE playlist_name=? ORDER BY position LIMIT 1 OFFSET ?
                )
                """;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, normalizedName);
            ps.setString(2, normalizedName);
            ps.setInt(3, entryNumber - 1);
            return ps.executeUpdate() > 0;
        }
    }

    private static String normalizePrivatePlaylistName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Playlist name cannot be empty.");
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public synchronized void createPrivatePlaylist(String ownerUuid, String name) throws SQLException {
        String normalizedName = normalizePrivatePlaylistName(name);
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO private_playlists(owner_uuid,name,created_at) VALUES(?,?,?)")) {
            ps.setString(1, ownerUuid);
            ps.setString(2, normalizedName);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public synchronized boolean privatePlaylistExists(String ownerUuid, String name) throws SQLException {
        String normalizedName = normalizePrivatePlaylistName(name);
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM private_playlists WHERE owner_uuid=? AND name=?")) {
            ps.setString(1, ownerUuid);
            ps.setString(2, normalizedName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized void addPrivatePlaylistTrack(String ownerUuid, String name, String trackId,
                                                       int maximumTracks) throws SQLException {
        String normalizedName = normalizePrivatePlaylistName(name);
        int limit = Math.max(1, maximumTracks);
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement exists = c.prepareStatement(
                        "SELECT 1 FROM private_playlists WHERE owner_uuid=? AND name=?")) {
                    exists.setString(1, ownerUuid);
                    exists.setString(2, normalizedName);
                    try (ResultSet rs = exists.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Private playlist does not exist.");
                    }
                }

                int count;
                try (PreparedStatement countQuery = c.prepareStatement("""
                        SELECT COUNT(*) FROM private_playlist_entries
                        WHERE owner_uuid=? AND playlist_name=?
                        """)) {
                    countQuery.setString(1, ownerUuid);
                    countQuery.setString(2, normalizedName);
                    try (ResultSet rs = countQuery.executeQuery()) {
                        count = rs.next() ? rs.getInt(1) : 0;
                    }
                }
                if (count >= limit) {
                    throw new IllegalArgumentException("Playlist has reached its maximum of "
                            + limit + " tracks. Remove a track before adding another.");
                }

                int position;
                try (PreparedStatement next = c.prepareStatement("""
                        SELECT COALESCE(MAX(position),-1)+1 FROM private_playlist_entries
                        WHERE owner_uuid=? AND playlist_name=?
                        """)) {
                    next.setString(1, ownerUuid);
                    next.setString(2, normalizedName);
                    try (ResultSet rs = next.executeQuery()) {
                        position = rs.next() ? rs.getInt(1) : 0;
                    }
                }
                try (PreparedStatement insert = c.prepareStatement("""
                        INSERT INTO private_playlist_entries(owner_uuid,playlist_name,position,track_id)
                        VALUES(?,?,?,?)
                        """)) {
                    insert.setString(1, ownerUuid);
                    insert.setString(2, normalizedName);
                    insert.setInt(3, position);
                    insert.setString(4, trackId);
                    insert.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException ex) {
                try {
                    c.rollback();
                } catch (SQLException rollbackError) {
                    ex.addSuppressed(rollbackError);
                }
                throw ex;
            }
        }
    }

    public synchronized List<MusicTrack> getPrivatePlaylist(String ownerUuid, String name, int limit)
            throws SQLException {
        String normalizedName = normalizePrivatePlaylistName(name);
        List<MusicTrack> tracks = new ArrayList<>();
        String sql = """
                SELECT t.* FROM private_playlist_entries p
                JOIN tracks t ON t.id=p.track_id
                WHERE p.owner_uuid=? AND p.playlist_name=?
                ORDER BY p.position LIMIT ?
                """;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ownerUuid);
            ps.setString(2, normalizedName);
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tracks.add(readTrack(rs));
            }
        }
        return tracks;
    }

    public synchronized List<String> listPrivatePlaylists(String ownerUuid) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("""
                SELECT name FROM private_playlists WHERE owner_uuid=? ORDER BY name
                """)) {
            ps.setString(1, ownerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        }
        return names;
    }

    /** Removes the one-based displayed entry number from the owner's private playlist. */
    public synchronized boolean removePrivatePlaylistTrack(String ownerUuid, String name, int entryNumber)
            throws SQLException {
        if (entryNumber < 1) throw new IllegalArgumentException("Playlist entry number must be 1 or greater.");
        String normalizedName = normalizePrivatePlaylistName(name);
        String sql = """
                DELETE FROM private_playlist_entries
                WHERE owner_uuid=? AND playlist_name=? AND position=(
                    SELECT position FROM private_playlist_entries
                    WHERE owner_uuid=? AND playlist_name=? ORDER BY position LIMIT 1 OFFSET ?
                )
                """;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ownerUuid);
            ps.setString(2, normalizedName);
            ps.setString(3, ownerUuid);
            ps.setString(4, normalizedName);
            ps.setInt(5, entryNumber - 1);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized boolean deletePrivatePlaylist(String ownerUuid, String name) throws SQLException {
        String normalizedName = normalizePrivatePlaylistName(name);
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM private_playlists WHERE owner_uuid=? AND name=?")) {
            ps.setString(1, ownerUuid);
            ps.setString(2, normalizedName);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized int[] getPreference(String playerUuid) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT volume,muted FROM player_preferences WHERE player_uuid=?")) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new int[]{rs.getInt(1), rs.getInt(2)} : new int[]{100, 0};
            }
        }
    }

    public synchronized void savePreference(String playerUuid, int volume, boolean muted) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO player_preferences(player_uuid,volume,muted) VALUES(?,?,?)
                ON CONFLICT(player_uuid) DO UPDATE SET volume=excluded.volume, muted=excluded.muted
                """)) {
            ps.setString(1, playerUuid);
            ps.setInt(2, Math.max(0, Math.min(100, volume)));
            ps.setInt(3, muted ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private MusicTrack readTrack(ResultSet rs) throws SQLException {
        return new MusicTrack(rs.getString("id"), rs.getString("title"), rs.getString("artist"),
                rs.getString("namespace"), rs.getString("pack_file"), rs.getString("sha1_hex"),
                rs.getLong("duration_ms"), rs.getInt("segment_ms"), rs.getInt("segment_count"),
                rs.getLong("pack_size_bytes"));
    }

    @Override
    public void close() {
        // Connections are short-lived per operation; there is no persistent connection to close.
    }
}