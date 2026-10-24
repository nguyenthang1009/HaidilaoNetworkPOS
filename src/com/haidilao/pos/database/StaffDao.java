package com.haidilao.pos.database;

import com.haidilao.pos.model.Staff;
import com.haidilao.pos.security.PasswordUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Truy vấn bảng staff (nhân viên) và kiểm tra đăng nhập
 */
public final class StaffDao {
    private static final String COLUMNS = "staff_id, username, full_name, role, phone, active";

    private StaffDao() {}

    /**
     * Kiểm tra tài khoản + mật khẩu. Trả về nhân viên nếu đúng và tài khoản đang hoạt động, ngược lại null.
     */
    public static Staff authenticate(String username, String password) {
        if (username == null || password == null || username.isEmpty()) return null;
        String sql = "SELECT " + COLUMNS + ", password_hash, salt FROM staff WHERE username = ? AND active = 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && PasswordUtil.verify(password, rs.getString("salt"), rs.getString("password_hash"))) {
                    return read(rs);
                }
            }
        } catch (SQLException e) {
            System.err.println("[StaffDao] Lỗi kiểm tra đăng nhập: " + e.getMessage());
        }
        return null;
    }

    public static List<Staff> getAllStaff() {
        return query("SELECT " + COLUMNS + " FROM staff ORDER BY role, staff_id", null);
    }

    /** Nhân viên phục vụ đang làm việc (để Lễ tân gán vào bàn) */
    public static List<Staff> getActiveWaiters() {
        return query("SELECT " + COLUMNS + " FROM staff WHERE active = 1 AND role = ? ORDER BY full_name", Staff.ROLE_WAITER);
    }

    public static Staff getStaff(int staffId) {
        String sql = "SELECT " + COLUMNS + " FROM staff WHERE staff_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, staffId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return read(rs);
            }
        } catch (SQLException e) {
            System.err.println("[StaffDao] Lỗi tìm nhân viên: " + e.getMessage());
        }
        return null;
    }

    private static List<Staff> query(String sql, String param) {
        List<Staff> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (param != null) ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(read(rs));
            }
        } catch (SQLException e) {
            System.err.println("[StaffDao] Lỗi đọc nhân viên: " + e.getMessage());
        }
        return list;
    }

    private static Staff read(ResultSet rs) throws SQLException {
        return new Staff(
                rs.getInt("staff_id"),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getString("role"),
                rs.getString("phone"),
                rs.getBoolean("active")
        );
    }

    public static boolean usernameExists(String username) {
        String sql = "SELECT 1 FROM staff WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static boolean addStaff(String username, String password, String fullName, String role, String phone, boolean active) {
        String salt = PasswordUtil.newSalt();
        String sql = "INSERT INTO staff (username, password_hash, salt, full_name, role, phone, active) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, PasswordUtil.hash(password, salt));
            ps.setString(3, salt);
            ps.setString(4, fullName);
            ps.setString(5, role);
            ps.setString(6, phone);
            ps.setBoolean(7, active);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[StaffDao] Lỗi thêm nhân viên: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sửa thông tin nhân viên. newPassword rỗng / null = giữ mật khẩu cũ.
     */
    public static boolean updateStaff(int staffId, String fullName, String role, String phone, boolean active, String newPassword) {
        boolean changePass = newPassword != null && !newPassword.isEmpty();
        String sql = changePass
                ? "UPDATE staff SET full_name = ?, role = ?, phone = ?, active = ?, password_hash = ?, salt = ? WHERE staff_id = ?"
                : "UPDATE staff SET full_name = ?, role = ?, phone = ?, active = ? WHERE staff_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullName);
            ps.setString(2, role);
            ps.setString(3, phone);
            ps.setBoolean(4, active);
            if (changePass) {
                String salt = PasswordUtil.newSalt();
                ps.setString(5, PasswordUtil.hash(newPassword, salt));
                ps.setString(6, salt);
                ps.setInt(7, staffId);
            } else {
                ps.setInt(5, staffId);
            }
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[StaffDao] Lỗi sửa nhân viên: " + e.getMessage());
            return false;
        }
    }

    /** Xóa nhân viên. Các bàn từng do người này phục vụ vẫn giữ, chỉ bỏ trống tên phục vụ (ON DELETE SET NULL). */
    public static boolean deleteStaff(int staffId) {
        String sql = "DELETE FROM staff WHERE staff_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, staffId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[StaffDao] Lỗi xóa nhân viên: " + e.getMessage());
            return false;
        }
    }

    /** Số quản lý đang hoạt động (không cho xóa / khóa quản lý cuối cùng, kẻo không ai vào được máy chủ) */
    public static int countActiveManagers() {
        String sql = "SELECT COUNT(*) FROM staff WHERE active = 1 AND role = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Staff.ROLE_MANAGER);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }
}
