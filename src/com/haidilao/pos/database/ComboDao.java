package com.haidilao.pos.database;

import com.haidilao.pos.model.Combo;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Truy vấn bảng combos và combo_items
 */
public final class ComboDao {
    private ComboDao() {}

    /**
     * Danh sách combo kèm các món bên trong.
     * onlyAvailable = true: chỉ combo đang bán (gửi cho Tablet).
     */
    public static List<Combo> getAllCombos(boolean onlyAvailable) {
        Map<Integer, Combo> map = new LinkedHashMap<>();
        String sql = "SELECT combo_id, combo_name, price, description, status, image_data FROM combos"
                + (onlyAvailable ? " WHERE status = 'AVAILABLE'" : "") + " ORDER BY combo_id";
        String itemSql = "SELECT ci.combo_id, ci.item_id, m.item_name, ci.quantity FROM combo_items ci"
                + " JOIN menu_items m ON m.item_id = ci.item_id ORDER BY ci.combo_id, m.category, m.item_id";
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Combo c = read(rs);
                    map.put(c.getComboId(), c);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(itemSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Combo c = map.get(rs.getInt("combo_id"));
                    if (c != null) {
                        c.getItems().add(new Combo.ComboItem(rs.getInt("item_id"), rs.getString("item_name"), rs.getInt("quantity")));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[ComboDao] Lỗi đọc combo: " + e.getMessage());
        }
        return new ArrayList<>(map.values());
    }

    public static Combo getCombo(int comboId) {
        String sql = "SELECT combo_id, combo_name, price, description, status, image_data FROM combos WHERE combo_id = ?";
        String itemSql = "SELECT ci.item_id, m.item_name, ci.quantity FROM combo_items ci"
                + " JOIN menu_items m ON m.item_id = ci.item_id WHERE ci.combo_id = ? ORDER BY m.category, m.item_id";
        try (Connection conn = DatabaseManager.getConnection()) {
            Combo c = null;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, comboId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) c = read(rs);
                }
            }
            if (c == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(itemSql)) {
                ps.setInt(1, comboId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        c.getItems().add(new Combo.ComboItem(rs.getInt("item_id"), rs.getString("item_name"), rs.getInt("quantity")));
                    }
                }
            }
            return c;
        } catch (SQLException e) {
            System.err.println("[ComboDao] Lỗi tìm combo: " + e.getMessage());
            return null;
        }
    }

    private static Combo read(ResultSet rs) throws SQLException {
        Combo c = new Combo(
                rs.getInt("combo_id"),
                rs.getString("combo_name"),
                rs.getLong("price"),
                rs.getString("description"),
                rs.getString("status")
        );
        c.setImageData(rs.getBytes("image_data"));
        return c;
    }

    /**
     * Thêm combo mới cùng danh sách món (một transaction). Trả về mã combo, -1 nếu lỗi.
     */
    public static int addCombo(Combo combo) {
        String sql = "INSERT INTO combos (combo_name, price, description, status, image_data) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int id;
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, combo.getComboName());
                    ps.setLong(2, combo.getPrice());
                    ps.setString(3, combo.getDescription());
                    ps.setString(4, combo.getStatus());
                    ps.setBytes(5, combo.getImageData());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        rs.next();
                        id = rs.getInt(1);
                    }
                }
                insertItems(conn, id, combo.getItems());
                conn.commit();
                return id;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[ComboDao] Lỗi thêm combo: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Sửa combo: cập nhật thông tin và thay toàn bộ danh sách món (một transaction).
     */
    public static boolean updateCombo(Combo combo) {
        String sql = "UPDATE combos SET combo_name = ?, price = ?, description = ?, status = ?, image_data = ? WHERE combo_id = ?";
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int n;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, combo.getComboName());
                    ps.setLong(2, combo.getPrice());
                    ps.setString(3, combo.getDescription());
                    ps.setString(4, combo.getStatus());
                    ps.setBytes(5, combo.getImageData());
                    ps.setInt(6, combo.getComboId());
                    n = ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM combo_items WHERE combo_id = ?")) {
                    ps.setInt(1, combo.getComboId());
                    ps.executeUpdate();
                }
                insertItems(conn, combo.getComboId(), combo.getItems());
                conn.commit();
                return n > 0;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[ComboDao] Lỗi sửa combo: " + e.getMessage());
            return false;
        }
    }

    private static void insertItems(Connection conn, int comboId, List<Combo.ComboItem> items) throws SQLException {
        if (items.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO combo_items (combo_id, item_id, quantity) VALUES (?, ?, ?)")) {
            for (Combo.ComboItem ci : items) {
                ps.setInt(1, comboId);
                ps.setInt(2, ci.getItemId());
                ps.setInt(3, ci.getQuantity());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Xóa combo (các món trong combo tự xóa theo, đơn cũ vẫn giữ tên combo trong order_details) */
    public static boolean deleteCombo(int comboId) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM combos WHERE combo_id = ?")) {
            ps.setInt(1, comboId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ComboDao] Lỗi xóa combo: " + e.getMessage());
            return false;
        }
    }
}
