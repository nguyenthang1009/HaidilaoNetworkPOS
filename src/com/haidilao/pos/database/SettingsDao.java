package com.haidilao.pos.database;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cài đặt chung của nhà hàng (bảng app_settings): tên nhà hàng, tài khoản nhận chuyển khoản...
 */
public final class SettingsDao {
    public static final String RESTAURANT_NAME = "restaurant_name";
    public static final String BANK_NAME = "bank_name";
    public static final String BANK_ACCOUNT = "bank_account";
    public static final String BANK_OWNER = "bank_owner";

    private SettingsDao() {}

    public static String get(String key, String def) {
        String sql = "SELECT setting_value FROM app_settings WHERE setting_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : def;
            }
        } catch (SQLException e) {
            System.err.println("[SettingsDao] Lỗi đọc cài đặt: " + e.getMessage());
            return def;
        }
    }

    public static Map<String, String> getAll() {
        Map<String, String> map = new LinkedHashMap<>();
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT setting_key, setting_value FROM app_settings")) {
            while (rs.next()) map.put(rs.getString(1), rs.getString(2));
        } catch (SQLException e) {
            System.err.println("[SettingsDao] Lỗi đọc cài đặt: " + e.getMessage());
        }
        return map;
    }

    public static boolean set(String key, String value) {
        String sql = "INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value == null ? "" : value.trim());
            return ps.executeUpdate() >= 0;
        } catch (SQLException e) {
            System.err.println("[SettingsDao] Lỗi lưu cài đặt: " + e.getMessage());
            return false;
        }
    }
}
