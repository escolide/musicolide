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
                    CREATE TABLE IF NOT EXISTS player_preferences (
                      player_uuid TEXT PRIMARY KEY, volume INTEGER NOT NULL DEFAULT 100,
                      muted INTEGER NOT NULL DEFAULT 0
                    )
                    """);
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

    public synchronized boolean deleteTrack(String trackId) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("DELETE FROM tracks WHERE id=?")) {
            ps.setString(1, trackId);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized Optional<MusicTrack> getTrack(String id) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("SELECT * FROM tracks WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readTrack(rs)) : Optional.empty();
            }
        }
    }

    public synchronized List<MusicTrack> search(String query, int limit) throws SQLException {
        List<MusicTrack> tracks = new ArrayList<>();
        String sql = "SELECT * FROM tracks WHERE lower(id)=lower(?) OR lower(title) LIKE ? ORDER BY title COLLATE NOCASE LIMIT ?";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setString(2, "%" + query.toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%");
            ps.setInt(3, Math.max(1, limit));
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
                SELECT * FROM tracks
                WHERE lower(id)=lower(?)
                   OR lower(title) LIKE ? ESCAPE '!'
                   OR lower(artist) LIKE ? ESCAPE '!'
                ORDER BY CASE
                    WHEN lower(id)=lower(?) THEN 0
                    WHEN lower(title)=lower(?) THEN 1
                    WHEN lower(title) LIKE ? ESCAPE '!' THEN 2
                    WHEN lower(artist) LIKE ? ESCAPE '!' THEN 3
                    ELSE 4 END,
                    created_at DESC, title COLLATE NOCASE
                LIMIT ? OFFSET ?
                """;
        List<MusicTrack> tracks = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, normalized);
            ps.setString(2, contains);
            ps.setString(3, contains);
            ps.setString(4, normalized);
            ps.setString(5, normalized);
            ps.setString(6, prefix);
            ps.setString(7, prefix);
            ps.setInt(8, Math.max(1, limit));
            ps.setInt(9, Math.max(0, offset));
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
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT substr(alias_key,9) FROM aliases WHERE track_id=? AND lower(alias_key) LIKE 'youtube:%' ORDER BY alias_key LIMIT 1")) {
            ps.setString(1, trackId);
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
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement exists = c.prepareStatement("SELECT 1 FROM playlists WHERE name=?");
                 PreparedStatement insert = c.prepareStatement(
                         "INSERT INTO playlist_entries(playlist_name,position,track_id) VALUES(?,?,?)")) {
                exists.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rs = exists.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Playlist does not exist.");
                }
                int position = 0;
                try (PreparedStatement count = c.prepareStatement(
                        "SELECT COALESCE(MAX(position),-1)+1 FROM playlist_entries WHERE playlist_name=?")) {
                    count.setString(1, name.toLowerCase(Locale.ROOT));
                    try (ResultSet rs = count.executeQuery()) { if (rs.next()) position = rs.getInt(1); }
                }
                insert.setString(1, name.toLowerCase(Locale.ROOT));
                insert.setInt(2, position);
                insert.setString(3, trackId);
                insert.executeUpdate();
                c.commit();
            } catch (SQLException ex) {
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