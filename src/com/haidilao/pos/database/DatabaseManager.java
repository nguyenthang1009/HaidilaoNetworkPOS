package com.haidilao.pos.database;

import com.haidilao.pos.model.ChatMessage;
import com.haidilao.pos.model.MenuItem;
import com.haidilao.pos.model.NetworkDevice;
import com.haidilao.pos.model.Order;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Quản lý kết nối và thực thi truy vấn tới MySQL trên Laragon 6.0.0
 * Sử dụng PreparedStatement 100% để phòng chống SQL Injection
 */
public class DatabaseManager {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/haidilao_pos_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASS = ""; // Laragon 6.0.0 mặc định mật khẩu rỗng

    // Đã kiểm tra / tạo cột image_data trong bảng menu_items chưa (chỉ cần làm 1 lần mỗi lần chạy chương trình)
    private static volatile boolean imageColumnReady = false;

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    /**
     * Kiểm tra trạng thái kết nối tới MySQL Laragon
     */
    public static boolean testConnection() {
        try (Connection conn = getConnection()) {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi kết nối MySQL: " + e.getMessage());
            return false;
        }
    }

    public static void main(String[] args) {
        boolean ok = testConnection();
        System.out.println("TEST_CONNECTION_RESULT: " + ok);
    }

    /**
     * Lấy toàn bộ thực đơn món ăn đang mở bán
     */
    public static List<MenuItem> getAllMenuItems() {
        if (!imageColumnReady) ensureMenuImageColumn();
        List<MenuItem> list = new ArrayList<>();
        String sql = "SELECT item_id, category, item_name, price, status, image_data FROM menu_items WHERE status = 'AVAILABLE' ORDER BY category, item_id";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                MenuItem item = new MenuItem(
                        rs.getInt("item_id"),
                        rs.getString("category"),
                        rs.getString("item_name"),
                        rs.getLong("price"),
                        rs.getString("status")
                );
                item.setImageData(rs.getBytes("image_data"));
                list.add(item);
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi đọc Menu từ MySQL: " + e.getMessage());
        }
        return list;
    }

    /**
     * Lấy toàn bộ thực đơn dành cho trang Quản trị Admin (gồm cả món đang bán và tạm dừng)
     */
    public static List<MenuItem> getAllMenuItemsAdmin() {
        if (!imageColumnReady) ensureMenuImageColumn();
        List<MenuItem> list = new ArrayList<>();
        String sql = "SELECT item_id, category, item_name, price, status, image_data FROM menu_items ORDER BY category, item_id";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                MenuItem item = new MenuItem(
                        rs.getInt("item_id"),
                        rs.getString("category"),
                        rs.getString("item_name"),
                        rs.getLong("price"),
                        rs.getString("status")
                );
                item.setImageData(rs.getBytes("image_data"));
                list.add(item);
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi đọc Menu Admin từ MySQL: " + e.getMessage());
        }
        return list;
    }

    /**
     * Lấy danh sách phân loại / danh mục thức ăn hiện có
     */
    public static List<String> getAllCategories() {
        List<String> cats = new ArrayList<>();
        String sql = "SELECT DISTINCT category FROM menu_items ORDER BY category";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String c = rs.getString("category");
                if (c != null && !c.trim().isEmpty()) {
                    cats.add(c.trim());
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi lấy danh mục món từ MySQL: " + e.getMessage());
        }
        return cats;
    }

    /**
     * Thêm món ăn mới vào thực đơn trên MySQL
     */
    public static boolean addMenuItem(String category, String itemName, long price, String status) {
        return addMenuItem(category, itemName, price, status, null);
    }

    /**
     * Thêm món ăn mới kèm ảnh (imageData = null nếu không có ảnh)
     */
    public static boolean addMenuItem(String category, String itemName, long price, String status, byte[] imageData) {
        if (!imageColumnReady) ensureMenuImageColumn();
        String sql = "INSERT INTO menu_items (category, item_name, price, status, image_data) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, category);
            ps.setString(2, itemName);
            ps.setLong(3, price);
            ps.setString(4, status);
            ps.setBytes(5, imageData);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi thêm món ăn mới: " + e.getMessage());
            return false;
        }
    }

    /**
     * Chỉnh sửa thông tin món ăn trên MySQL
     */
    public static boolean updateMenuItem(int itemId, String category, String itemName, long price, String status) {
        String sql = "UPDATE menu_items SET category = ?, item_name = ?, price = ?, status = ? WHERE item_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, category);
            ps.setString(2, itemName);
            ps.setLong(3, price);
            ps.setString(4, status);
            ps.setInt(5, itemId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi cập nhật món ăn: " + e.getMessage());
            return false;
        }
    }

    /**
     * Đổi ảnh của một món (imageData = null để xóa ảnh)
     */
    public static boolean updateMenuItemImage(int itemId, byte[] imageData) {
        if (!imageColumnReady) ensureMenuImageColumn();
        String sql = "UPDATE menu_items SET image_data = ? WHERE item_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, imageData);
            ps.setInt(2, itemId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi cập nhật ảnh món ăn: " + e.getMessage());
            return false;
        }
    }

    /**
     * Tự thêm cột image_data (MEDIUMBLOB, tối đa 16MB) vào bảng menu_items nếu database cũ chưa có.
     * Được gọi tự động ở lần đầu đọc/ghi thực đơn, chạy nhiều lần cũng không sao.
     */
    public static synchronized void ensureMenuImageColumn() {
        if (imageColumnReady) return;
        try (Connection conn = getConnection()) {
            boolean exists;
            try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, "menu_items", "image_data")) {
                exists = rs.next();
            }
            if (!exists) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE menu_items ADD COLUMN image_data MEDIUMBLOB NULL");
                    System.out.println("[DatabaseManager] Đã thêm cột image_data vào bảng menu_items");
                }
            }
            imageColumnReady = true;
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi thêm cột image_data: " + e.getMessage());
        }
    }

    /**
     * Xóa món ăn khỏi thực đơn trên MySQL
     */
    public static boolean deleteMenuItem(int itemId) {
        String sql = "DELETE FROM menu_items WHERE item_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, itemId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi xóa món ăn: " + e.getMessage());
            return false;
        }
    }

    /**
     * Thêm đơn gọi món mới vào MySQL và trả về mã đơn vừa tạo
     */
    public static int insertOrder(String tableId, String itemsSummary, long totalAmount) {
        String sql = "INSERT INTO orders (table_id, items_summary, total_amount, order_status) VALUES (?, ?, ?, 'CHỜ XÁC NHẬN')";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tableId);
            ps.setString(2, itemsSummary);
            ps.setLong(3, totalAmount);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi lưu đơn hàng vào MySQL: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Cập nhật trạng thái đơn món (chuyển bếp, đã lên món, hoàn tất)
     */
    public static void updateOrderStatus(int orderId, String newStatus) {
        String sql = "UPDATE orders SET order_status = ? WHERE order_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setInt(2, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi cập nhật trạng thái đơn: " + e.getMessage());
        }
    }

    /**
     * Lấy danh sách toàn bộ đơn hàng trong ca phục vụ
     */
    public static List<Order> getAllOrders() {
        List<Order> list = new ArrayList<>();
        String sql = "SELECT order_id, table_id, items_summary, total_amount, order_status, created_at FROM orders ORDER BY order_id DESC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Order(
                        rs.getInt("order_id"),
                        rs.getString("table_id"),
                        rs.getString("items_summary"),
                        rs.getLong("total_amount"),
                        rs.getString("order_status"),
                        rs.getTimestamp("created_at")
                ));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi đọc đơn hàng từ MySQL: " + e.getMessage());
        }
        return list;
    }

    /**
     * Tính tổng doanh thu thực tế từ MySQL
     */
    public static long getTotalRevenue() {
        String sql = "SELECT COALESCE(SUM(total_amount), 0) FROM orders";
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    /**
     * Ghi nhật ký tin nhắn, chuông gọi và sự kiện an ninh vào MySQL
     */
    public static void saveChatOrBellLog(String sender, String receiver, String type, String content) {
        String sql = "INSERT INTO chat_logs (sender, receiver, message_type, content) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sender);
            ps.setString(2, receiver);
            ps.setString(3, type);
            ps.setString(4, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi ghi log vào MySQL: " + e.getMessage());
        }
    }

    /**
     * Lịch sử trò chuyện của MỘT bàn: tin bàn gửi đi, tin Lễ tân trả lời bàn đó, chuông và đơn của bàn.
     * Trả về theo thứ tự cũ -> mới, tối đa limit dòng gần nhất.
     */
    public static List<ChatMessage> getChatHistoryForTable(String tableId, int limit) {
        String sql = "SELECT log_id, sender, receiver, message_type, content, sent_at FROM chat_logs "
                + "WHERE message_type IN ('CHAT', 'RING', 'ORDER') AND (sender = ? OR receiver = ?) "
                + "ORDER BY log_id DESC LIMIT ?";
        List<ChatMessage> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableId);
            ps.setString(2, tableId);
            ps.setInt(3, limit);
            readChatMessages(ps, list);
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi đọc lịch sử chat của bàn: " + e.getMessage());
        }
        return list;
    }

    /**
     * Lịch sử trò chuyện cho quầy Lễ tân: tin nhắn và chuông gọi gần nhất của tất cả các bàn.
     */
    public static List<ChatMessage> getRecentChatHistory(int limit) {
        String sql = "SELECT log_id, sender, receiver, message_type, content, sent_at FROM chat_logs "
                + "WHERE message_type IN ('CHAT', 'RING') ORDER BY log_id DESC LIMIT ?";
        List<ChatMessage> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            readChatMessages(ps, list);
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi đọc lịch sử chat: " + e.getMessage());
        }
        return list;
    }

    private static void readChatMessages(PreparedStatement ps, List<ChatMessage> list) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new ChatMessage(
                        rs.getInt("log_id"),
                        rs.getString("sender"),
                        rs.getString("receiver"),
                        rs.getString("message_type"),
                        rs.getString("content"),
                        rs.getTimestamp("sent_at")
                ));
            }
        }
        java.util.Collections.reverse(list); // câu SQL lấy mới nhất trước (để LIMIT đúng), đảo lại để hiển thị cũ -> mới
    }

    /**
     * Lấy toàn bộ thiết bị mạng đã cấu hình
     */
    public static List<NetworkDevice> getAllNetworkDevices() {
        List<NetworkDevice> list = new ArrayList<>();
        String sql = "SELECT device_id, vlan_name, ip_address, mac_address, security_status FROM network_devices";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new NetworkDevice(
                        rs.getString("device_id"),
                        rs.getString("vlan_name"),
                        rs.getString("ip_address"),
                        rs.getString("mac_address"),
                        rs.getString("security_status")
                ));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi đọc Network Devices từ MySQL: " + e.getMessage());
        }
        return list;
    }

    /**
     * Tìm thiết bị theo Device ID
     */
    public static NetworkDevice getDeviceById(String deviceId) {
        String sql = "SELECT device_id, vlan_name, ip_address, mac_address, security_status FROM network_devices WHERE device_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new NetworkDevice(
                            rs.getString("device_id"),
                            rs.getString("vlan_name"),
                            rs.getString("ip_address"),
                            rs.getString("mac_address"),
                            rs.getString("security_status")
                    );
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi tìm Network Device: " + e.getMessage());
        }
        return null;
    }

    /**
     * Cập nhật trạng thái an ninh của thiết bị (SECURE_STICKY, ERR_DISABLE, ...)
     */
    public static void updateDeviceSecurityStatus(String deviceId, String status) {
        String sql = "UPDATE network_devices SET security_status = ? WHERE device_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, deviceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi cập nhật trạng thái an ninh thiết bị: " + e.getMessage());
        }
    }

    /**
     * Mở khóa toàn bộ cổng bị Err-Disable về trạng thái hoạt động bình thường (No Shutdown)
     */
    public static void restoreAllDevicePorts() {
        String sql = "UPDATE network_devices SET security_status = 'STICKY_ACTIVE' WHERE security_status = 'ERR_DISABLE'";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi khôi phục cổng: " + e.getMessage());
        }
    }

    /**
     * Tự động đăng ký hoặc cập nhật thiết bị mạng mới (hỗ trợ thêm Tablet mới linh hoạt)
     */
    public static void registerOrUpdateDevice(NetworkDevice dev) {
        String checkSql = "SELECT device_id FROM network_devices WHERE device_id = ?";
        String insertSql = "INSERT INTO network_devices (device_id, vlan_name, ip_address, mac_address, security_status) VALUES (?, ?, ?, ?, ?)";
        String updateSql = "UPDATE network_devices SET vlan_name = ?, ip_address = ?, mac_address = ?, security_status = ? WHERE device_id = ?";

        try (Connection conn = getConnection()) {
            boolean exists = false;
            try (PreparedStatement psCheck = conn.prepareStatement(checkSql)) {
                psCheck.setString(1, dev.getDeviceId());
                try (ResultSet rs = psCheck.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement psUpdate = conn.prepareStatement(updateSql)) {
                    psUpdate.setString(1, dev.getVlanName());
                    psUpdate.setString(2, dev.getIpAddress());
                    psUpdate.setString(3, dev.getMacAddress());
                    psUpdate.setString(4, dev.getSecurityStatus());
                    psUpdate.setString(5, dev.getDeviceId());
                    psUpdate.executeUpdate();
                }
            } else {
                try (PreparedStatement psInsert = conn.prepareStatement(insertSql)) {
                    psInsert.setString(1, dev.getDeviceId());
                    psInsert.setString(2, dev.getVlanName());
                    psInsert.setString(3, dev.getIpAddress());
                    psInsert.setString(4, dev.getMacAddress());
                    psInsert.setString(5, dev.getSecurityStatus());
                    psInsert.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi đăng ký/cập nhật thiết bị mạng: " + e.getMessage());
        }
    }
}
