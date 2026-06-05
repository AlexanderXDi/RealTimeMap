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
                // Включаем WAL для конкурентного доступа и ускорения
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");

                // Таблица для чанков с составным индексом
                stmt.execute("CREATE TABLE IF NOT EXISTS chunks (" +
                        "dim TEXT, x INTEGER, z INTEGER, data BLOB, updated_at INTEGER, " +
                        "PRIMARY KEY (dim, x, z))");

                // Таблица для словаря блоков
                stmt.execute("CREATE TABLE IF NOT EXISTS dictionary (" +
                        "id INTEGER PRIMARY KEY, block_key TEXT UNIQUE)");
            }
            RealTimeMap.LOGGER.info("RealTimeMap: Database initialized: {}.db", dbName);
        } catch (Exception e) {
            RealTimeMap.LOGGER.error("RealTimeMap: Database init error: " + e.getMessage());
            available = false;
        }
    }

    public static synchronized void saveChunk(String dim, int x, int z, String jsonData) {
        if (!available) return;
        String sql = "INSERT OR REPLACE INTO chunks (dim, x, z, data, updated_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dim);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);
            pstmt.setBytes(4, compress(jsonData));
            pstmt.setLong(5, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: DB save error: " + e.getMessage());
        }
    }

    public static synchronized String getChunk(String dim, int x, int z) {
        if (!available) return null;
        String sql = "SELECT data FROM chunks WHERE dim = ? AND x = ? AND z = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dim);
            pstmt.setInt(2, x);
            pstmt.setInt(3, z);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return decompress(rs.getBytes("data"));
            }
        } catch (SQLException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: DB read error: " + e.getMessage());
        }
        return null;
    }

    public static synchronized void saveDictionary(int id, String key) {
        if (!available) return;
        String sql = "INSERT OR IGNORE INTO dictionary (id, block_key) VALUES (?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            pstmt.setString(2, key);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: DB dict save error: " + e.getMessage());
        }
    }

    public static synchronized void loadDictionary(java.util.function.BiConsumer<Integer, String> consumer) {
        if (!available) return;
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id, block_key FROM dictionary");
            while (rs.next()) {
                consumer.accept(rs.getInt("id"), rs.getString("block_key"));
            }
        } catch (SQLException e) {
            RealTimeMap.LOGGER.error("RealTimeMap: DB dict load error: " + e.getMessage());
        }
    }

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
