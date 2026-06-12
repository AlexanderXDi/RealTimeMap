package com.alexanderxdi.realtimemap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class MapDatabase {
    private static Connection connection;
    private static boolean available = false;

    public static synchronized void init(String dbName) {
        try {
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:rtm_data/" + dbName + ".db";
            java.io.File dir = new java.io.File("rtm_data");
            if (!dir.exists()) dir.mkdirs();

            connection = DriverManager.getConnection(url);
            available = true;

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");

                // Проверяем наличие колонки mode. Если её нет, сбрасываем таблицу.
                boolean hasMode = false;
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, "chunks", "mode")) {
                    if (rs.next()) {
                        hasMode = true;
                    }
                } catch (Exception ignored) {}

                if (!hasMode) {
                    stmt.execute("DROP TABLE IF EXISTS chunks");
                }

                stmt.execute("CREATE TABLE IF NOT EXISTS chunks (" +
                        "dim TEXT, x INTEGER, z INTEGER, mode TEXT, data BLOB, updated_at INTEGER, " +
                        "PRIMARY KEY (dim, x, z, mode))");

                stmt.execute("CREATE TABLE IF NOT EXISTS dictionary (" +
                        "id INTEGER PRIMARY KEY, block_key TEXT UNIQUE, hex_color TEXT)");

                try {
                    stmt.execute("ALTER TABLE dictionary ADD COLUMN hex_color TEXT");
                } catch (SQLException ignored) {}
            }
            RealTimeMap.LOGGER.info("RealTimeMap: Database initialized: {}.db", dbName);
        } catch (Exception e) {
            RealTimeMap.LOGGER.error("RealTimeMap: Database init error: " + e.getMessage());
            available = false;
        }
    }

    public static synchronized void saveChunk(String dim, int x, int z, String mode, String jsonData) {
        if (!available) return;
        String sql = "INSERT OR REPLACE INTO chunks (dim, x, z, mode, data, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dim);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);
            pstmt.setString(4, mode);
            pstmt.setBytes(5, compress(jsonData));
            pstmt.setLong(6, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: DB save error: " + e.getMessage());
        }
    }

    public static synchronized String getChunk(String dim, int x, int z, String mode) {
        if (!available) return null;
        String sql = "SELECT data FROM chunks WHERE dim = ? AND x = ? AND z = ? AND mode = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dim);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);
            pstmt.setString(4, mode);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return decompress(rs.getBytes("data"));
            }
        } catch (SQLException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: DB read error: " + e.getMessage());
        }
        return null;
    }

    public static synchronized void deleteChunk(String dim, int x, int z) {
        if (!available) return;
        String sql = "DELETE FROM chunks WHERE dim = ? AND x = ? AND z = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dim);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);
            pstmt.executeUpdate();
            RealTimeMap.LOGGER.info("RealTimeMap: Invalidated cached chunk {}, {} in database for dimension {}", x, z, dim);
        } catch (SQLException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: DB delete error: " + e.getMessage());
        }
    }

    public static synchronized void saveDictionary(int id, String key, String hexColor) {
        if (!available) return;
        String sql = "INSERT OR REPLACE INTO dictionary (id, block_key, hex_color) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            pstmt.setString(2, key);
            pstmt.setString(3, hexColor);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: DB dict save error: " + e.getMessage());
        }
    }

    public static synchronized void loadDictionary(java.util.function.Consumer<DictEntry> consumer) {
        if (!available) return;
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id, block_key, hex_color FROM dictionary");
            while (rs.next()) {
                consumer.accept(new DictEntry(rs.getInt("id"), rs.getString("block_key"), rs.getString("hex_color")));
            }
        } catch (SQLException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: DB dict load error: " + e.getMessage());
        }
    }

    public record DictEntry(int id, String key, String hexColor) {}

    private static byte[] compress(String str) {
        if (str == null || str.length() == 0) return null;
        try (ByteArrayOutputStream obj = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(obj)) {
            gzip.write(str.getBytes(StandardCharsets.UTF_8));
            gzip.flush();
            gzip.close();
            return obj.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static String decompress(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(gis.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static synchronized void close() {
        try {
            if (available && connection != null) connection.close();
        } catch (SQLException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: DB close error: " + e.getMessage());
        }
    }
}
