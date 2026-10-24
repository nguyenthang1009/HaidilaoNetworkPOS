package com.haidilao.pos.database;

import com.haidilao.pos.model.ChatMessage;
import com.haidilao.pos.model.MenuItem;
import com.haidilao.pos.model.NetworkDevice;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Quản lý kết nối và thực thi truy vấn tới MySQL trên Laragon 6.0.0
 * Sử dụng PreparedStatement 100% để phòng chống SQL Injection
 */
public class DatabaseManager {
    // Thông số kết nối, mặc định là MySQL của Laragon trên chính máy chủ (root, mật khẩu rỗng).
    // Có thể đổi khi chạy: java -Dpos.db.name=ten_db -Dpos.db.pass=matkhau -jar ...
    private static final String HOST = System.getProperty("pos.db.host", "127.0.0.1");
    private static final String PORT = System.getProperty("pos.db.port", "3306");
    private static final String DB_NAME = System.getProperty("pos.db.name", "haidilao_pos_db");
    private static final String USER = System.getProperty("pos.db.user", "root");
    private static final String PASS = System.getProperty("pos.db.pass", "");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";  // không đặt serverTimezone: driver dùng múi giờ của máy chủ (cùng máy với MySQL)

    // Đã kiểm tra / tạo cột image_data trong bảng menu_items chưa (chỉ cần làm 1 lần mỗi lần chạy chương trình)
    private static volatile boolean imageColumnReady = false;
    private static volatile boolean securityTableReady = false;

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/" + DB_NAME + OPTIONS, USER, PASS);
    }

    /** Kết nối tới MySQL mà chưa chọn database (dùng để CREATE DATABASE lần đầu) */
    static Connection getServerConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/" + OPTIONS, USER, PASS);
    }

    public static String getDatabaseName() {
        return DB_NAME;
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
     * Tìm một món theo mã (không kèm ảnh). Server dùng để lấy GIÁ THẬT khi Tablet gửi đơn.
     */
    public static MenuItem getMenuItemById(int itemId) {
        String sql = "SELECT item_id, category, item_name, price, status FROM menu_items WHERE item_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new MenuItem(rs.getInt("item_id"), rs.getString("category"), rs.getString("item_name"),
                            rs.getLong("price"), rs.getString("status"));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi tìm món ăn: " + e.getMessage());
        }
        return null;
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
     * Doanh thu hôm nay = tổng tiền khách đã thanh toán (các lượt phục vụ đã đóng trong ngày).
     * Đơn chỉ gọi mà chưa thanh toán thì chưa tính là doanh thu.
     */
    public static long getTotalRevenue() {
        String sql = "SELECT COALESCE(SUM(total_paid), 0) FROM table_sessions WHERE status <> ? AND DATE(closed_at) = CURDATE()";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, com.haidilao.pos.model.TableSession.STATUS_OPEN); // gồm cả tiền cọc giữ lại của lượt bị hủy
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi tính doanh thu: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Ghi nhật ký tin nhắn, chuông gọi, đơn hàng vào MySQL
     */
    public static void saveChatOrBellLog(String sender, String receiver, String type, String content) {
        saveChatLog(sender, null, receiver, type, content);
    }

    /**
     * Ghi một dòng nhật ký kèm tên người gửi (vd. "Phục vụ Lê Minh Phục", "Lễ tân Nguyễn Thu Ngân", "Khách Bàn 1").
     */
    public static void saveChatLog(String sender, String senderName, String receiver, String type, String content) {
        String sql = "INSERT INTO chat_logs (sender, sender_name, receiver, message_type, content) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sender);
            ps.setString(2, senderName);
            ps.setString(3, receiver);
            ps.setString(4, type);
            ps.setString(5, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi ghi log vào MySQL: " + e.getMessage());
        }
    }

    /**
     * Tạo bảng security_logs nếu chưa có. Bảng này KHÔNG có khóa ngoại tới network_devices
     * để ghi được cả sự kiện của thiết bị lạ chưa từng đăng ký (đăng nhập sai khóa, quét cổng...).
     */
    public static synchronized void ensureSecurityLogTable() {
        if (securityTableReady) return;
        String sql = "CREATE TABLE IF NOT EXISTS security_logs ("
                + " log_id INT NOT NULL AUTO_INCREMENT,"
                + " device_id VARCHAR(64) NOT NULL,"
                + " source_ip VARCHAR(45) NULL,"
                + " event_type VARCHAR(30) NOT NULL,"
                + " detail TEXT NOT NULL,"
                + " created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,"
                + " PRIMARY KEY (log_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
            securityTableReady = true;
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi tạo bảng security_logs: " + e.getMessage());
        }
    }

    /**
     * Ghi một sự kiện an ninh: AUTH_FAIL, NO_AUTH, RBAC_DENY, PORT_SHUTDOWN, PORT_RESTORE
     */
    public static void saveSecurityLog(String deviceId, String sourceIp, String eventType, String detail) {
        if (!securityTableReady) ensureSecurityLogTable();
        String id = (deviceId == null || deviceId.isEmpty()) ? "UNKNOWN" : deviceId;
        if (id.length() > 64) id = id.substring(0, 64); // client lạ có thể gửi tên rất dài
        String sql = "INSERT INTO security_logs (device_id, source_ip, event_type, detail) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, sourceIp);
            ps.setString(3, eventType);
            ps.setString(4, detail == null ? "" : detail);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi ghi log an ninh: " + e.getMessage());
        }
    }

    private static final String CHAT_COLUMNS = "log_id, sender, sender_name, receiver, message_type, content, sent_at";

    /**
     * Tin nhắn của MỘT bàn (tin bàn gửi đi và tin nhân viên gửi tới bàn đó) kể từ thời điểm since
     * (null = không giới hạn thời gian). includeRing = true: kèm cả các lần bấm chuông.
     * Trả về theo thứ tự cũ -> mới, tối đa limit dòng gần nhất.
     */
    public static List<ChatMessage> getTableChatSince(String tableId, Timestamp since, boolean includeRing, int limit) {
        String sql = "SELECT " + CHAT_COLUMNS + " FROM chat_logs "
                + "WHERE message_type IN ('CHAT'" + (includeRing ? ", 'RING'" : "") + ") AND (sender = ? OR receiver = ?) "
                + (since != null ? "AND sent_at >= ? " : "") + "ORDER BY log_id DESC LIMIT ?";
        List<ChatMessage> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, tableId);
            ps.setString(i++, tableId);
            if (since != null) ps.setTimestamp(i++, since);
            ps.setInt(i, limit);
            readChatMessages(ps, list);
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi đọc tin nhắn của bàn: " + e.getMessage());
        }
        return list;
    }

    /**
     * Lịch sử trò chuyện gần nhất của tất cả các bàn (tin nhắn và chuông gọi).
     */
    public static List<ChatMessage> getRecentChatHistory(int limit) {
        String sql = "SELECT " + CHAT_COLUMNS + " FROM chat_logs WHERE message_type IN ('CHAT', 'RING') ORDER BY log_id DESC LIMIT ?";
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

    /**
     * Danh sách cuộc trò chuyện cho Lễ tân: mỗi bàn có tin nhắn / chuông, kèm tin gần nhất và số tin trong hôm nay.
     * Mỗi phần tử: {mã bàn, thời gian tin gần nhất (dd/MM HH:mm), nội dung tin gần nhất, số tin hôm nay}
     */
    public static List<String[]> getConversations() {
        List<String[]> list = new ArrayList<>();
        String sql = "SELECT t.table_id, c.sent_at, c.message_type, c.content,"
                + " (SELECT COUNT(*) FROM chat_logs x WHERE (x.sender = t.table_id OR x.receiver = t.table_id)"
                + "   AND x.message_type IN ('CHAT', 'RING') AND x.sent_at >= CURDATE()) AS today"
                + " FROM dining_tables t JOIN chat_logs c ON c.log_id = ("
                + "   SELECT MAX(y.log_id) FROM chat_logs y WHERE (y.sender = t.table_id OR y.receiver = t.table_id)"
                + "   AND y.message_type IN ('CHAT', 'RING'))"
                + " ORDER BY c.log_id DESC";
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM HH:mm");
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Timestamp at = rs.getTimestamp(2);
                String preview = ("RING".equals(rs.getString(3)) ? "Bấm chuông: " : "") + rs.getString(4);
                list.add(new String[]{rs.getString(1), at == null ? "" : fmt.format(at), preview, String.valueOf(rs.getInt(5))});
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi đọc danh sách cuộc trò chuyện: " + e.getMessage());
        }
        return list;
    }

    /**
     * Tra cứu nhật ký chat cho quản lý (giải quyết thắc mắc / khiếu nại): theo khoảng ngày, bàn (null = mọi bàn),
     * từ khóa trong nội dung hoặc tên người gửi (null = không lọc). Thứ tự cũ -> mới, tối đa limit dòng.
     */
    public static List<ChatMessage> searchChatLogs(Date from, Date to, String tableId, String keyword, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + CHAT_COLUMNS + " FROM chat_logs WHERE message_type IN ('CHAT', 'RING')"
                + " AND DATE(sent_at) BETWEEN ? AND ?");
        if (tableId != null) sql.append(" AND (sender = ? OR receiver = ?)");
        if (keyword != null) sql.append(" AND (content LIKE ? OR sender_name LIKE ?)");
        sql.append(" ORDER BY log_id DESC LIMIT ?");
        List<ChatMessage> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setDate(i++, from);
            ps.setDate(i++, to);
            if (tableId != null) {
                ps.setString(i++, tableId);
                ps.setString(i++, tableId);
            }
            if (keyword != null) {
                String like = "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                ps.setString(i++, like);
                ps.setString(i++, like);
            }
            ps.setInt(i, limit);
            readChatMessages(ps, list);
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi tra cứu nhật ký chat: " + e.getMessage());
        }
        return list;
    }

    private static void readChatMessages(PreparedStatement ps, List<ChatMessage> list) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ChatMessage m = new ChatMessage(
                        rs.getInt("log_id"),
                        rs.getString("sender"),
                        rs.getString("receiver"),
                        rs.getString("message_type"),
                        rs.getString("content"),
                        rs.getTimestamp("sent_at")
                );
                m.setSenderName(rs.getString("sender_name"));
                list.add(m);
            }
        }
        java.util.Collections.reverse(list); // câu SQL lấy mới nhất trước (để LIMIT đúng), đảo lại để hiển thị cũ -> mới
    }

    /**
     * Lấy toàn bộ thiết bị đã từng kết nối vào Server
     */
    public static List<NetworkDevice> getAllNetworkDevices() {
        List<NetworkDevice> list = new ArrayList<>();
        String sql = "SELECT device_id, device_role, ip_address, mac_address, security_status FROM network_devices ORDER BY device_role, device_id";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(readDevice(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi đọc danh sách thiết bị: " + e.getMessage());
        }
        return list;
    }

    /**
     * Tìm thiết bị theo Device ID
     */
    public static NetworkDevice getDeviceById(String deviceId) {
        String sql = "SELECT device_id, device_role, ip_address, mac_address, security_status FROM network_devices WHERE device_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readDevice(rs);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi tìm thiết bị: " + e.getMessage());
        }
        return null;
    }

    private static NetworkDevice readDevice(ResultSet rs) throws SQLException {
        return new NetworkDevice(
                rs.getString("device_id"),
                rs.getString("device_role"),
                rs.getString("ip_address"),
                rs.getString("mac_address"),
                rs.getString("security_status")
        );
    }

    /**
     * Đổi trạng thái khóa của thiết bị (ACTIVE / LOCKED)
     */
    public static void updateDeviceSecurityStatus(String deviceId, String status) {
        String sql = "UPDATE network_devices SET security_status = ? WHERE device_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, deviceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi cập nhật trạng thái thiết bị: " + e.getMessage());
        }
    }

    /**
     * Mở khóa toàn bộ thiết bị đang bị khóa
     */
    public static void unlockAllDevices() {
        String sql = "UPDATE network_devices SET security_status = ? WHERE security_status = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, NetworkDevice.STATUS_ACTIVE);
            ps.setString(2, NetworkDevice.STATUS_LOCKED);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi mở khóa thiết bị: " + e.getMessage());
        }
    }

    /**
     * Ghi nhận thiết bị vừa đăng nhập: thêm mới hoặc cập nhật IP / MAC / vai trò.
     * KHÔNG ghi đè security_status, để thiết bị đang bị khóa vẫn bị khóa.
     */
    public static void registerOrUpdateDevice(NetworkDevice dev) {
        String sql = "INSERT INTO network_devices (device_id, device_role, ip_address, mac_address, security_status) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE device_role = VALUES(device_role), ip_address = VALUES(ip_address), mac_address = VALUES(mac_address)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dev.getDeviceId());
            ps.setString(2, dev.getDeviceRole());
            ps.setString(3, dev.getIpAddress());
            ps.setString(4, dev.getMacAddress());
            ps.setString(5, dev.getSecurityStatus() == null ? NetworkDevice.STATUS_ACTIVE : dev.getSecurityStatus());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Lỗi ghi nhận thiết bị: " + e.getMessage());
        }
    }
}
