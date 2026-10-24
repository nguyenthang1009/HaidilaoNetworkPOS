package com.haidilao.pos.database;

import com.haidilao.pos.model.DiningTable;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Truy vấn bảng dining_tables (bàn ăn)
 */
public final class TableDao {
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");

    private TableDao() {}

    public static List<DiningTable> getAllTables() {
        List<DiningTable> list = new ArrayList<>();
        String sql = "SELECT table_id, table_name, capacity, area, status, sort_order FROM dining_tables ORDER BY sort_order, table_id";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(read(rs));
            }
        } catch (SQLException e) {
            System.err.println("[TableDao] Lỗi đọc danh sách bàn: " + e.getMessage());
        }
        return list;
    }

    public static DiningTable getTable(String tableId) {
        String sql = "SELECT table_id, table_name, capacity, area, status, sort_order FROM dining_tables WHERE table_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return read(rs);
            }
        } catch (SQLException e) {
            System.err.println("[TableDao] Lỗi tìm bàn: " + e.getMessage());
        }
        return null;
    }

    private static DiningTable read(ResultSet rs) throws SQLException {
        return new DiningTable(
                rs.getString("table_id"),
                rs.getString("table_name"),
                rs.getInt("capacity"),
                rs.getString("area"),
                rs.getString("status"),
                rs.getInt("sort_order")
        );
    }

    public static boolean addTable(DiningTable t) {
        String sql = "INSERT INTO dining_tables (table_id, table_name, capacity, area, status, sort_order)"
                + " SELECT ?, ?, ?, ?, ?, COALESCE(MAX(sort_order), 0) + 1 FROM dining_tables";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.getTableId());
            ps.setString(2, t.getTableName());
            ps.setInt(3, t.getCapacity());
            ps.setString(4, t.getArea());
            ps.setString(5, t.getStatus());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[TableDao] Lỗi thêm bàn: " + e.getMessage());
            return false;
        }
    }

    public static boolean updateTable(DiningTable t) {
        String sql = "UPDATE dining_tables SET table_name = ?, capacity = ?, area = ?, status = ? WHERE table_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.getTableName());
            ps.setInt(2, t.getCapacity());
            ps.setString(3, t.getArea());
            ps.setString(4, t.getStatus());
            ps.setString(5, t.getTableId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[TableDao] Lỗi sửa bàn: " + e.getMessage());
            return false;
        }
    }

    /**
     * Xóa bàn. Bàn đã có lịch sử phục vụ thì không xóa được (khóa ngoại RESTRICT) để giữ số liệu thống kê.
     */
    public static boolean deleteTable(String tableId) {
        String sql = "DELETE FROM dining_tables WHERE table_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[TableDao] Lỗi xóa bàn: " + e.getMessage());
            return false;
        }
    }

    public static boolean hasSessions(String tableId) {
        String sql = "SELECT 1 FROM table_sessions WHERE table_id = ? LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return true;
        }
    }

    public static boolean updateStatus(String tableId, String status) {
        String sql = "UPDATE dining_tables SET status = ? WHERE table_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, tableId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[TableDao] Lỗi đổi trạng thái bàn: " + e.getMessage());
            return false;
        }
    }

    /**
     * Tablet mới (vd. Tablet_Ban7) đăng nhập lần đầu mà chưa có trong danh sách bàn -> tự thêm "Bàn 7"
     */
    public static void ensureTableForDevice(String deviceId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            ensureTableForDevice(conn, deviceId);
        } catch (SQLException e) {
            System.err.println("[TableDao] Lỗi tự thêm bàn: " + e.getMessage());
        }
    }

    static void ensureTableForDevice(Connection conn, String deviceId) throws SQLException {
        String sql = "INSERT IGNORE INTO dining_tables (table_id, table_name, capacity, area, status, sort_order)"
                + " SELECT ?, ?, 4, 'Tầng 1', ?, COALESCE(MAX(sort_order), 0) + 1 FROM dining_tables";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setString(2, defaultTableName(deviceId));
            ps.setString(3, DiningTable.STATUS_EMPTY);
            ps.executeUpdate();
        }
    }

    /** "Tablet_Ban7" -> "Bàn 7"; không có số ở cuối thì giữ nguyên tên thiết bị */
    public static String defaultTableName(String deviceId) {
        Matcher m = TRAILING_NUMBER.matcher(deviceId);
        return m.find() ? "Bàn " + m.group(1).replaceFirst("^0+(?=\\d)", "") : deviceId;
    }
}
