package com.haidilao.pos.database;

import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.model.Order;
import com.haidilao.pos.model.Staff;
import com.haidilao.pos.model.TableSession;
import com.haidilao.pos.security.PasswordUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tự tạo / nâng cấp cấu trúc database khi Server khởi động.
 * - Database mới tinh: tạo đủ bảng và dữ liệu mẫu (bàn, nhân viên, thực đơn, combo).
 * - Database của bản cũ: thêm bảng, thêm cột, đổi tên cột, chuyển trạng thái cũ sang trạng thái mới.
 * Mọi bước đều kiểm tra trước nên chạy lại nhiều lần cũng không sao.
 */
public final class SchemaMigrator {
    private static volatile boolean done = false;

    private SchemaMigrator() {}

    public static synchronized boolean migrate(Consumer<String> log) {
        if (done) return true;
        Consumer<String> out = (log != null) ? log : System.out::println;
        try {
            createDatabaseIfMissing();
            try (Connection conn = DatabaseManager.getConnection()) {
                createTables(conn);
                upgradeNetworkDevices(conn, out);
                upgradeMenuItems(conn, out);
                upgradeChatLogs(conn, out);
                upgradeOrders(conn, out);
                upgradeOrderDetails(conn, out);
                upgradeSessions(conn, out);
                seedTables(conn, out);
                seedStaff(conn, out);
                seedMenu(conn, out);
                seedCombos(conn, out);
                seedSettings(conn);
                migrateOldOrders(conn, out);
            }
            DatabaseManager.ensureMenuImageColumn();
            DatabaseManager.ensureSecurityLogTable();
            done = true;
            return true;
        } catch (SQLException e) {
            out.accept("[DATABASE] Lỗi nâng cấp cấu trúc database: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ tạo mới

    private static void createDatabaseIfMissing() throws SQLException {
        String name = DatabaseManager.getDatabaseName();
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new SQLException("Tên database không hợp lệ: " + name);
        }
        try (Connection conn = DatabaseManager.getServerConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static final String TABLE_OPTIONS = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static void createTables(Connection conn) throws SQLException {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS network_devices ("
                + " device_id VARCHAR(30) NOT NULL,"
                + " device_role VARCHAR(30) NOT NULL DEFAULT 'TABLET',"
                + " ip_address VARCHAR(45) NOT NULL DEFAULT '',"
                + " mac_address VARCHAR(30) NOT NULL DEFAULT '',"
                + " security_status VARCHAR(20) DEFAULT 'ACTIVE',"
                + " PRIMARY KEY (device_id))" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS menu_items ("
                + " item_id INT NOT NULL AUTO_INCREMENT,"
                + " category VARCHAR(50) NOT NULL,"
                + " item_name VARCHAR(120) NOT NULL,"
                + " price DECIMAL(10,0) NOT NULL,"
                + " status VARCHAR(20) DEFAULT 'AVAILABLE',"
                + " image_data MEDIUMBLOB NULL,"
                + " PRIMARY KEY (item_id))" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS dining_tables ("
                + " table_id VARCHAR(30) NOT NULL,"
                + " table_name VARCHAR(50) NOT NULL,"
                + " capacity INT NOT NULL DEFAULT 4,"
                + " area VARCHAR(50) NOT NULL DEFAULT 'Tầng 1',"
                + " status VARCHAR(20) NOT NULL DEFAULT 'TRỐNG',"
                + " sort_order INT NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (table_id))" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS staff ("
                + " staff_id INT NOT NULL AUTO_INCREMENT,"
                + " username VARCHAR(30) NOT NULL,"
                + " password_hash CHAR(64) NOT NULL,"
                + " salt CHAR(32) NOT NULL,"
                + " full_name VARCHAR(80) NOT NULL,"
                + " role VARCHAR(20) NOT NULL,"
                + " phone VARCHAR(20) NULL,"
                + " active TINYINT(1) NOT NULL DEFAULT 1,"
                + " created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,"
                + " PRIMARY KEY (staff_id),"
                + " UNIQUE KEY uq_staff_username (username))" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS table_sessions ("
                + " session_id INT NOT NULL AUTO_INCREMENT,"
                + " table_id VARCHAR(30) NOT NULL,"
                + " customer_name VARCHAR(80) NULL,"
                + " customer_phone VARCHAR(20) NULL,"
                + " guest_count INT NOT NULL DEFAULT 0,"
                + " waiter_id INT NULL,"
                + " cashier_id INT NULL,"
                + " note TEXT NULL,"
                + " status VARCHAR(20) NOT NULL DEFAULT 'ĐANG PHỤC VỤ',"
                + " opened_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,"
                + " closed_at TIMESTAMP NULL DEFAULT NULL,"
                + " subtotal DECIMAL(12,0) NOT NULL DEFAULT 0,"
                + " discount DECIMAL(12,0) NOT NULL DEFAULT 0,"
                + " total_paid DECIMAL(12,0) NOT NULL DEFAULT 0,"
                + " payment_method VARCHAR(30) NULL,"
                + " deposit DECIMAL(12,0) NOT NULL DEFAULT 0,"
                + " reservation_id INT NULL,"
                + " pay_request VARCHAR(30) NULL,"
                + " pay_requested_at TIMESTAMP NULL DEFAULT NULL,"
                + " PRIMARY KEY (session_id),"
                + " KEY idx_session_table_status (table_id, status),"
                + " KEY idx_session_closed (closed_at),"
                + " CONSTRAINT fk_session_table FOREIGN KEY (table_id) REFERENCES dining_tables (table_id) ON DELETE RESTRICT ON UPDATE CASCADE,"
                + " CONSTRAINT fk_session_waiter FOREIGN KEY (waiter_id) REFERENCES staff (staff_id) ON DELETE SET NULL ON UPDATE CASCADE,"
                + " CONSTRAINT fk_session_cashier FOREIGN KEY (cashier_id) REFERENCES staff (staff_id) ON DELETE SET NULL ON UPDATE CASCADE)" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS reservations ("
                + " reservation_id INT NOT NULL AUTO_INCREMENT,"
                + " customer_name VARCHAR(80) NOT NULL,"
                + " customer_phone VARCHAR(20) NOT NULL,"
                + " guest_count INT NOT NULL DEFAULT 1,"
                + " reserved_at DATETIME NOT NULL,"
                + " duration_minutes INT NOT NULL DEFAULT 120,"
                + " table_id VARCHAR(30) NULL,"
                + " area_pref VARCHAR(50) NULL,"
                + " deposit DECIMAL(12,0) NOT NULL DEFAULT 0,"
                + " note TEXT NULL,"
                + " status VARCHAR(20) NOT NULL DEFAULT 'ĐÃ ĐẶT',"
                + " session_id INT NULL,"
                + " created_by INT NULL,"
                + " created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,"
                + " PRIMARY KEY (reservation_id),"
                + " KEY idx_reservation_time (reserved_at),"
                + " KEY idx_reservation_table (table_id),"
                + " CONSTRAINT fk_reservation_table FOREIGN KEY (table_id) REFERENCES dining_tables (table_id) ON DELETE SET NULL ON UPDATE CASCADE,"
                + " CONSTRAINT fk_reservation_staff FOREIGN KEY (created_by) REFERENCES staff (staff_id) ON DELETE SET NULL ON UPDATE CASCADE)" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS app_settings ("
                + " setting_key VARCHAR(50) NOT NULL,"
                + " setting_value VARCHAR(255) NOT NULL DEFAULT '',"
                + " PRIMARY KEY (setting_key))" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS combos ("
                + " combo_id INT NOT NULL AUTO_INCREMENT,"
                + " combo_name VARCHAR(120) NOT NULL,"
                + " price DECIMAL(10,0) NOT NULL,"
                + " description TEXT NULL,"
                + " status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',"
                + " image_data MEDIUMBLOB NULL,"
                + " PRIMARY KEY (combo_id))" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS combo_items ("
                + " combo_id INT NOT NULL,"
                + " item_id INT NOT NULL,"
                + " quantity INT NOT NULL DEFAULT 1,"
                + " PRIMARY KEY (combo_id, item_id),"
                + " KEY fk_combo_item_menu (item_id),"
                + " CONSTRAINT fk_combo_item_combo FOREIGN KEY (combo_id) REFERENCES combos (combo_id) ON DELETE CASCADE ON UPDATE CASCADE,"
                + " CONSTRAINT fk_combo_item_menu FOREIGN KEY (item_id) REFERENCES menu_items (item_id) ON DELETE CASCADE ON UPDATE CASCADE)" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS orders ("
                + " order_id INT NOT NULL AUTO_INCREMENT,"
                + " order_no INT NOT NULL DEFAULT 0,"
                + " session_id INT NULL,"
                + " table_id VARCHAR(30) NOT NULL,"
                + " items_summary TEXT NOT NULL,"
                + " total_amount DECIMAL(12,0) NOT NULL,"
                + " order_status VARCHAR(30) DEFAULT 'CHỜ BẾP',"
                + " note TEXT NULL,"
                + " created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,"
                + " updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,"
                + " PRIMARY KEY (order_id),"
                + " KEY idx_orders_table (table_id),"
                + " KEY idx_orders_created (created_at),"
                + " KEY fk_orders_session (session_id),"
                + " CONSTRAINT fk_orders_session FOREIGN KEY (session_id) REFERENCES table_sessions (session_id) ON DELETE SET NULL ON UPDATE CASCADE)" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS order_details ("
                + " detail_id INT NOT NULL AUTO_INCREMENT,"
                + " order_id INT NOT NULL,"
                + " item_id INT NULL,"
                + " combo_id INT NULL,"
                + " item_name VARCHAR(120) NOT NULL DEFAULT '',"
                + " quantity INT NOT NULL DEFAULT 1,"
                + " unit_price DECIMAL(10,0) NOT NULL,"
                + " note VARCHAR(255) NULL,"
                + " PRIMARY KEY (detail_id),"
                + " KEY fk_detail_order (order_id),"
                + " KEY fk_detail_menu (item_id),"
                + " KEY fk_detail_combo (combo_id),"
                + " CONSTRAINT fk_detail_order FOREIGN KEY (order_id) REFERENCES orders (order_id) ON DELETE CASCADE ON UPDATE CASCADE,"
                + " CONSTRAINT fk_detail_menu FOREIGN KEY (item_id) REFERENCES menu_items (item_id) ON DELETE SET NULL ON UPDATE CASCADE,"
                + " CONSTRAINT fk_detail_combo FOREIGN KEY (combo_id) REFERENCES combos (combo_id) ON DELETE SET NULL ON UPDATE CASCADE)" + TABLE_OPTIONS,

            "CREATE TABLE IF NOT EXISTS chat_logs ("
                + " log_id INT NOT NULL AUTO_INCREMENT,"
                + " sender VARCHAR(30) NOT NULL,"
                + " receiver VARCHAR(30) NOT NULL,"
                + " message_type VARCHAR(20) NOT NULL,"
                + " content TEXT NOT NULL,"
                + " sent_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,"
                + " sender_name VARCHAR(80) NULL,"
                + " PRIMARY KEY (log_id),"
                + " KEY idx_chat_sender (sender),"
                + " KEY idx_chat_receiver (receiver))" + TABLE_OPTIONS
        };
        try (Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.executeUpdate(sql);
            }
        }
    }

    // ------------------------------------------------------------------ nâng cấp database cũ

    /** Bỏ VLAN: cột vlan_name đổi thành device_role, trạng thái Cisco đổi thành ACTIVE / LOCKED */
    private static void upgradeNetworkDevices(Connection conn, Consumer<String> log) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (hasColumn(conn, "network_devices", "vlan_name") && !hasColumn(conn, "network_devices", "device_role")) {
                st.executeUpdate("ALTER TABLE network_devices CHANGE vlan_name device_role VARCHAR(30) NOT NULL DEFAULT 'TABLET'");
                st.executeUpdate("UPDATE network_devices SET device_role = CASE"
                        + " WHEN device_id IN ('POS_Server', 'PC_Admin') THEN 'ADMIN'"
                        + " WHEN device_id LIKE 'Bep%' OR device_id LIKE 'Kitchen%' THEN 'KITCHEN'"
                        + " WHEN device_id LIKE 'Tablet%' THEN 'TABLET'"
                        + " WHEN device_role LIKE 'VLAN 10%' THEN 'RECEPTION'"
                        + " ELSE 'TABLET' END");
                log.accept("[DATABASE] network_devices: đổi cột vlan_name -> device_role");
            }
            st.executeUpdate("ALTER TABLE network_devices MODIFY ip_address VARCHAR(45) NOT NULL DEFAULT ''");
            int n = st.executeUpdate("UPDATE network_devices SET security_status = CASE WHEN security_status = 'ERR_DISABLE' THEN 'LOCKED' ELSE 'ACTIVE' END"
                    + " WHERE security_status IS NULL OR security_status NOT IN ('ACTIVE', 'LOCKED')");
            if (n > 0) log.accept("[DATABASE] network_devices: chuyển " + n + " trạng thái cũ sang ACTIVE / LOCKED");
        }
    }

    private static void upgradeMenuItems(Connection conn, Consumer<String> log) throws SQLException {
        if (!hasColumn(conn, "menu_items", "image_data")) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE menu_items ADD COLUMN image_data MEDIUMBLOB NULL");
            }
            log.accept("[DATABASE] menu_items: thêm cột image_data");
        }
        if (!hasColumn(conn, "combos", "image_data")) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE combos ADD COLUMN image_data MEDIUMBLOB NULL");
            }
            log.accept("[DATABASE] combos: thêm cột image_data (ảnh combo)");
        }
    }

    /**
     * chat_logs cũ có khóa ngoại tới network_devices: bỏ đi để ghi được tin của mọi thiết bị.
     * Thêm cột sender_name (tên nhân viên / khách gửi) phục vụ tra cứu khi có khiếu nại.
     */
    private static void upgradeChatLogs(Connection conn, Consumer<String> log) throws SQLException {
        if (!hasColumn(conn, "chat_logs", "sender_name")) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE chat_logs ADD COLUMN sender_name VARCHAR(80) NULL");
            }
            log.accept("[DATABASE] chat_logs: thêm cột sender_name (tên người gửi)");
        }
        if (!hasIndex(conn, "chat_logs", "idx_chat_time")) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE chat_logs ADD INDEX idx_chat_time (sent_at)");
            }
        }
        for (String fk : foreignKeys(conn, "chat_logs", null)) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE chat_logs DROP FOREIGN KEY `" + fk + "`");
            }
            log.accept("[DATABASE] chat_logs: bỏ khóa ngoại " + fk);
        }
    }

    private static void upgradeOrders(Connection conn, Consumer<String> log) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String fk : foreignKeys(conn, "orders", "network_devices")) {
                st.executeUpdate("ALTER TABLE orders DROP FOREIGN KEY `" + fk + "`");
                log.accept("[DATABASE] orders: bỏ khóa ngoại " + fk + " (bàn giờ nằm trong dining_tables)");
            }
            if (!hasColumn(conn, "orders", "order_no")) {
                st.executeUpdate("ALTER TABLE orders ADD COLUMN order_no INT NOT NULL DEFAULT 0 AFTER order_id");
                log.accept("[DATABASE] orders: thêm cột order_no (số thứ tự trong ngày)");
            }
            if (!hasColumn(conn, "orders", "session_id")) {
                st.executeUpdate("ALTER TABLE orders ADD COLUMN session_id INT NULL AFTER order_no");
                log.accept("[DATABASE] orders: thêm cột session_id");
            }
            if (!hasColumn(conn, "orders", "note")) {
                st.executeUpdate("ALTER TABLE orders ADD COLUMN note TEXT NULL AFTER order_status");
                log.accept("[DATABASE] orders: thêm cột note (ghi chú cho bếp)");
            }
            if (!hasColumn(conn, "orders", "updated_at")) {
                st.executeUpdate("ALTER TABLE orders ADD COLUMN updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP AFTER created_at");
            }
            st.executeUpdate("ALTER TABLE orders ALTER COLUMN order_status SET DEFAULT 'CHỜ BẾP'");
            if (!hasIndex(conn, "orders", "idx_orders_created")) {
                st.executeUpdate("ALTER TABLE orders ADD INDEX idx_orders_created (created_at)");
            }
            if (foreignKeys(conn, "orders", "table_sessions").isEmpty()) {
                st.executeUpdate("ALTER TABLE orders ADD CONSTRAINT fk_orders_session FOREIGN KEY (session_id)"
                        + " REFERENCES table_sessions (session_id) ON DELETE SET NULL ON UPDATE CASCADE");
            }

            // Trạng thái đơn của bản cũ -> trạng thái mới
            int n = 0;
            n += updateStatus(conn, "CHỜ XÁC NHẬN", Order.STATUS_WAITING);
            n += updateStatus(conn, "BẾP ĐANG CHẾ BIẾN", Order.STATUS_COOKING);
            n += updateStatus(conn, "ĐÃ PHỤC VỤ HOÀN TẤT", Order.STATUS_SERVED);
            if (n > 0) log.accept("[DATABASE] orders: chuyển " + n + " đơn sang trạng thái mới");
        }
    }

    private static int updateStatus(Connection conn, String oldStatus, String newStatus) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE orders SET order_status = ? WHERE order_status = ?")) {
            ps.setString(1, newStatus);
            ps.setString(2, oldStatus);
            return ps.executeUpdate();
        }
    }

    /** order_details: cho phép item_id NULL (dòng combo, món đã bị xóa), thêm combo_id, item_name, note */
    private static void upgradeOrderDetails(Connection conn, Consumer<String> log) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (!isNullable(conn, "order_details", "item_id")) {
                for (String fk : foreignKeys(conn, "order_details", "menu_items")) {
                    st.executeUpdate("ALTER TABLE order_details DROP FOREIGN KEY `" + fk + "`");
                }
                st.executeUpdate("ALTER TABLE order_details MODIFY item_id INT NULL");
                log.accept("[DATABASE] order_details: item_id cho phép NULL");
            }
            if (foreignKeys(conn, "order_details", "menu_items").isEmpty()) {
                st.executeUpdate("ALTER TABLE order_details ADD CONSTRAINT fk_detail_menu FOREIGN KEY (item_id)"
                        + " REFERENCES menu_items (item_id) ON DELETE SET NULL ON UPDATE CASCADE");
            }
            if (!hasColumn(conn, "order_details", "combo_id")) {
                st.executeUpdate("ALTER TABLE order_details ADD COLUMN combo_id INT NULL AFTER item_id");
                log.accept("[DATABASE] order_details: thêm cột combo_id");
            }
            if (!hasColumn(conn, "order_details", "item_name")) {
                st.executeUpdate("ALTER TABLE order_details ADD COLUMN item_name VARCHAR(120) NOT NULL DEFAULT '' AFTER combo_id");
            }
            if (!hasColumn(conn, "order_details", "note")) {
                st.executeUpdate("ALTER TABLE order_details ADD COLUMN note VARCHAR(255) NULL");
                log.accept("[DATABASE] order_details: thêm cột note (ghi chú từng món)");
            }
            if (foreignKeys(conn, "order_details", "combos").isEmpty()) {
                st.executeUpdate("ALTER TABLE order_details ADD CONSTRAINT fk_detail_combo FOREIGN KEY (combo_id)"
                        + " REFERENCES combos (combo_id) ON DELETE SET NULL ON UPDATE CASCADE");
            }
        }
    }

    /** table_sessions của bản v2: thêm tiền cọc, lượt đặt bàn, yêu cầu thanh toán từ Tablet */
    private static void upgradeSessions(Connection conn, Consumer<String> log) throws SQLException {
        String[][] cols = {
            {"deposit", "DECIMAL(12,0) NOT NULL DEFAULT 0"},
            {"reservation_id", "INT NULL"},
            {"pay_request", "VARCHAR(30) NULL"},
            {"pay_requested_at", "TIMESTAMP NULL DEFAULT NULL"}
        };
        try (Statement st = conn.createStatement()) {
            for (String[] c : cols) {
                if (!hasColumn(conn, "table_sessions", c[0])) {
                    st.executeUpdate("ALTER TABLE table_sessions ADD COLUMN " + c[0] + " " + c[1]);
                    log.accept("[DATABASE] table_sessions: thêm cột " + c[0]);
                }
            }
        }
    }

    // ------------------------------------------------------------------ dữ liệu mẫu

    private static void seedSettings(Connection conn) throws SQLException {
        String[][] defaults = {
            {SettingsDao.RESTAURANT_NAME, "Nhà hàng Lẩu Haidilao"},
            {SettingsDao.BANK_NAME, "Vietcombank"},
            {SettingsDao.BANK_ACCOUNT, "0123456789"},
            {SettingsDao.BANK_OWNER, "NHA HANG LAU HAIDILAO"}
        };
        try (PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO app_settings (setting_key, setting_value) VALUES (?, ?)")) {
            for (String[] d : defaults) {
                ps.setString(1, d[0]);
                ps.setString(2, d[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void seedTables(Connection conn, Consumer<String> log) throws SQLException {
        if (countRows(conn, "dining_tables") == 0) {
            Object[][] tables = {
                {"Tablet_Ban1", "Bàn 1", 4, "Tầng 1"},
                {"Tablet_Ban2", "Bàn 2", 4, "Tầng 1"},
                {"Tablet_Ban3", "Bàn 3", 6, "Tầng 1"},
                {"Tablet_Ban4", "Bàn 4", 6, "Tầng 1"},
                {"Tablet_Ban5", "Bàn 5", 8, "Phòng VIP"},
                {"Tablet_Ban6", "Bàn 6", 2, "Phòng VIP"}
            };
            String sql = "INSERT INTO dining_tables (table_id, table_name, capacity, area, status, sort_order) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int order = 1;
                for (Object[] t : tables) {
                    ps.setString(1, (String) t[0]);
                    ps.setString(2, (String) t[1]);
                    ps.setInt(3, (Integer) t[2]);
                    ps.setString(4, (String) t[3]);
                    ps.setString(5, DiningTable.STATUS_EMPTY);
                    ps.setInt(6, order++);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.accept("[DATABASE] Tạo 6 bàn mẫu (Bàn 1 - Bàn 6)");
        }
        // Tablet đã từng kết nối hoặc đã có đơn ở bản cũ nhưng chưa có trong danh sách bàn -> thêm vào
        List<String> missing = new ArrayList<>();
        String sql = "SELECT device_id FROM network_devices WHERE device_role = 'TABLET'"
                + " AND device_id NOT IN (SELECT table_id FROM dining_tables)"
                + " UNION SELECT DISTINCT table_id FROM orders WHERE table_id NOT IN (SELECT table_id FROM dining_tables)";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) missing.add(rs.getString(1));
        }
        for (String id : missing) {
            TableDao.ensureTableForDevice(conn, id);
            log.accept("[DATABASE] Thêm bàn " + id + " (thiết bị đã có từ bản cũ)");
        }
    }

    private static void seedStaff(Connection conn, Consumer<String> log) throws SQLException {
        if (countRows(conn, "staff") > 0) return;
        Object[][] staff = {
            {"admin", "admin123", "Quản lý cửa hàng", Staff.ROLE_MANAGER},
            {"thungan", "123456", "Nguyễn Thu Ngân", Staff.ROLE_CASHIER},
            {"bep", "123456", "Trần Văn Bếp", Staff.ROLE_KITCHEN},
            {"phucvu1", "123456", "Lê Minh Phục", Staff.ROLE_WAITER},
            {"phucvu2", "123456", "Phạm Thị Vụ", Staff.ROLE_WAITER}
        };
        String sql = "INSERT INTO staff (username, password_hash, salt, full_name, role, active) VALUES (?, ?, ?, ?, ?, 1)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] s : staff) {
                String salt = PasswordUtil.newSalt();
                ps.setString(1, (String) s[0]);
                ps.setString(2, PasswordUtil.hash((String) s[1], salt));
                ps.setString(3, salt);
                ps.setString(4, (String) s[2]);
                ps.setString(5, (String) s[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.accept("[DATABASE] Tạo 5 tài khoản nhân viên mẫu (admin / thungan / bep / phucvu1 / phucvu2)");
    }

    private static void seedMenu(Connection conn, Consumer<String> log) throws SQLException {
        if (countRows(conn, "menu_items") > 0) return;
        Object[][] menu = {
            {"1. Nước Lẩu Haidilao", "Lẩu Dầu Cay Tứ Xuyên Thượng Hạng", 139000},
            {"1. Nước Lẩu Haidilao", "Lẩu Cà Chua Tân Cương Đậm Đà", 129000},
            {"1. Nước Lẩu Haidilao", "Lẩu Nấm Tùng Nhung Thiên Nhiên", 139000},
            {"1. Nước Lẩu Haidilao", "Lẩu Thái Tomyum Cốt Dừa", 129000},
            {"2. Thịt Thượng Hạng", "Bò Wagyu A5 Nhật Bản (150g)", 489000},
            {"2. Thịt Thượng Hạng", "Thăn Ngoại Bò Mỹ Black Angus", 219000},
            {"2. Thịt Thượng Hạng", "Thịt Cừu Úc Thảo Nguyên Cuộn", 189000},
            {"2. Thịt Thượng Hạng", "Lá Sách Bò Tươi Giòn Haidilao", 159000},
            {"3. Hải Sản & Đặc Sắc", "Múa Mì Haidilao Biểu Diễn Tại Bàn", 45000},
            {"3. Hải Sản & Đặc Sắc", "Tôm Sú Bằm Thủ Công", 149000},
            {"3. Hải Sản & Đặc Sắc", "Bào Ngư tươi sống (Phần)", 299000},
            {"3. Hải Sản & Đặc Sắc", "Đậu Phụ Phô Mai Tan Chảy", 89000},
            {"4. Món Quốc Tế & Đồ Uống", "Gan Ngỗng Pháp Áp Chảo Sốt Táo", 350000},
            {"4. Món Quốc Tế & Đồ Uống", "Sashimi Cá Hồi Na Uy Tươi", 220000},
            {"4. Món Quốc Tế & Đồ Uống", "Trà Đào Cam Sả Nhiệt Đới", 55000},
            {"4. Món Quốc Tế & Đồ Uống", "Kem Häagen-Dazs Vani Pháp", 95000}
        };
        String sql = "INSERT INTO menu_items (category, item_name, price, status) VALUES (?, ?, ?, 'AVAILABLE')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] m : menu) {
                ps.setString(1, (String) m[0]);
                ps.setString(2, (String) m[1]);
                ps.setLong(3, (Integer) m[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.accept("[DATABASE] Tạo thực đơn mẫu (" + menu.length + " món)");
    }

    private static void seedCombos(Connection conn, Consumer<String> log) throws SQLException {
        if (countRows(conn, "combos") > 0) return;
        Object[][] combos = {
            {"Combo Lẩu Đôi", 499000, "Cho 2 người: 1 nồi lẩu cà chua, bò Mỹ, đậu phụ phô mai, 2 trà đào",
                new Object[][]{{"Lẩu Cà Chua Tân Cương Đậm Đà", 1}, {"Thăn Ngoại Bò Mỹ Black Angus", 1},
                               {"Đậu Phụ Phô Mai Tan Chảy", 1}, {"Trà Đào Cam Sả Nhiệt Đới", 2}}},
            {"Combo Gia Đình 4 Người", 999000, "Cho 4 người: lẩu cay Tứ Xuyên, 2 phần bò Mỹ, cừu Úc, tôm sú, múa mì, 4 trà đào",
                new Object[][]{{"Lẩu Dầu Cay Tứ Xuyên Thượng Hạng", 1}, {"Thăn Ngoại Bò Mỹ Black Angus", 2},
                               {"Thịt Cừu Úc Thảo Nguyên Cuộn", 1}, {"Tôm Sú Bằm Thủ Công", 1},
                               {"Múa Mì Haidilao Biểu Diễn Tại Bàn", 1}, {"Trà Đào Cam Sả Nhiệt Đới", 4}}},
            {"Combo Hải Sản", 599000, "Lẩu Thái Tomyum, tôm sú bằm, bào ngư, 2 trà đào",
                new Object[][]{{"Lẩu Thái Tomyum Cốt Dừa", 1}, {"Tôm Sú Bằm Thủ Công", 1},
                               {"Bào Ngư tươi sống (Phần)", 1}, {"Trà Đào Cam Sả Nhiệt Đới", 2}}}
        };
        int created = 0;
        for (Object[] c : combos) {
            List<int[]> parts = new ArrayList<>();
            for (Object[] it : (Object[][]) c[3]) {
                int id = findMenuItemId(conn, (String) it[0]);
                if (id > 0) parts.add(new int[]{id, (Integer) it[1]});
            }
            if (parts.isEmpty()) continue; // thực đơn đã bị đổi hết, bỏ qua combo mẫu này
            int comboId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO combos (combo_name, price, description, status) VALUES (?, ?, ?, 'AVAILABLE')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, (String) c[0]);
                ps.setLong(2, (Integer) c[1]);
                ps.setString(3, (String) c[2]);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    comboId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO combo_items (combo_id, item_id, quantity) VALUES (?, ?, ?)")) {
                for (int[] p : parts) {
                    ps.setInt(1, comboId);
                    ps.setInt(2, p[0]);
                    ps.setInt(3, p[1]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            created++;
        }
        if (created > 0) log.accept("[DATABASE] Tạo " + created + " combo mẫu");
    }

    private static int findMenuItemId(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT item_id FROM menu_items WHERE item_name = ? ORDER BY item_id LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Đơn của bản cũ chưa có số thứ tự và chưa thuộc phiên phục vụ nào.
     * - Đánh số thứ tự theo từng ngày.
     * - Gom các đơn cùng bàn cùng ngày thành 1 phiên đã thanh toán, để thống kê doanh thu vẫn có số liệu cũ.
     */
    private static void migrateOldOrders(Connection conn, Consumer<String> log) throws SQLException {
        // 1. Số thứ tự trong ngày
        List<int[]> numbering = new ArrayList<>();
        String sql = "SELECT order_id, DATE(created_at) AS d FROM orders WHERE order_no = 0 ORDER BY created_at, order_id";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            java.sql.Date lastDay = null;
            int counter = 0;
            while (rs.next()) {
                java.sql.Date d = rs.getDate("d");
                if (lastDay == null || !lastDay.equals(d)) {
                    lastDay = d;
                    counter = maxOrderNo(conn, d);
                }
                numbering.add(new int[]{rs.getInt("order_id"), ++counter});
            }
        }
        if (!numbering.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE orders SET order_no = ?, updated_at = updated_at WHERE order_id = ?")) {
                for (int[] n : numbering) {
                    ps.setInt(1, n[1]);
                    ps.setInt(2, n[0]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.accept("[DATABASE] Đánh số thứ tự cho " + numbering.size() + " đơn cũ");
        }

        // 2. Gom đơn cũ thành phiên phục vụ đã thanh toán
        List<Object[]> groups = new ArrayList<>();
        sql = "SELECT table_id, DATE(created_at) AS d, MIN(created_at) AS t1, MAX(created_at) AS t2,"
                + " SUM(CASE WHEN order_status = ? THEN 0 ELSE total_amount END) AS total"
                + " FROM orders WHERE session_id IS NULL GROUP BY table_id, DATE(created_at)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Order.STATUS_CANCELLED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    groups.add(new Object[]{rs.getString("table_id"), rs.getDate("d"), rs.getTimestamp("t1"), rs.getTimestamp("t2"), rs.getLong("total")});
                }
            }
        }
        if (groups.isEmpty()) return;
        String insert = "INSERT INTO table_sessions (table_id, note, status, opened_at, closed_at, subtotal, discount, total_paid, payment_method)"
                + " VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)";
        String link = "UPDATE orders SET session_id = ?, updated_at = updated_at WHERE session_id IS NULL AND table_id = ? AND DATE(created_at) = ?";
        try (PreparedStatement ins = conn.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement upd = conn.prepareStatement(link)) {
            for (Object[] g : groups) {
                TableDao.ensureTableForDevice(conn, (String) g[0]);
                ins.setString(1, (String) g[0]);
                ins.setString(2, "Dữ liệu từ phiên bản cũ");
                ins.setString(3, TableSession.STATUS_PAID);
                ins.setTimestamp(4, (Timestamp) g[2]);
                ins.setTimestamp(5, (Timestamp) g[3]);
                ins.setLong(6, (Long) g[4]);
                ins.setLong(7, (Long) g[4]);
                ins.setString(8, "Tiền mặt");
                ins.executeUpdate();
                int sessionId;
                try (ResultSet rs = ins.getGeneratedKeys()) {
                    rs.next();
                    sessionId = rs.getInt(1);
                }
                upd.setInt(1, sessionId);
                upd.setString(2, (String) g[0]);
                upd.setDate(3, (java.sql.Date) g[1]);
                upd.executeUpdate();
            }
        }
        log.accept("[DATABASE] Gom đơn cũ thành " + groups.size() + " lượt phục vụ đã thanh toán (để thống kê)");
    }

    private static int maxOrderNo(Connection conn, java.sql.Date day) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(order_no), 0) FROM orders WHERE DATE(created_at) = ?")) {
            ps.setDate(1, day);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------ tra cứu cấu trúc

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean isNullable(Connection conn, String table, String column) throws SQLException {
        String sql = "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "YES".equals(rs.getString(1));
            }
        }
    }

    private static boolean hasIndex(Connection conn, String table, String index) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Tên các khóa ngoại của bảng (refTable = null: mọi khóa ngoại) */
    private static List<String> foreignKeys(Connection conn, String table, String refTable) throws SQLException {
        String sql = "SELECT DISTINCT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL"
                + (refTable != null ? " AND REFERENCED_TABLE_NAME = ?" : "");
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            if (refTable != null) ps.setString(2, refTable);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString(1));
            }
        }
        return list;
    }

    private static int countRows(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
